/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.helix.core.rebalance;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.helix.AccessOption;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.helix.zookeeper.zkclient.exception.ZkBadVersionException;
import org.apache.pinot.common.assignment.InstanceAssignmentConfigUtils;
import org.apache.pinot.common.assignment.InstancePartitions;
import org.apache.pinot.common.assignment.InstancePartitionsUtils;
import org.apache.pinot.common.exception.InvalidConfigException;
import org.apache.pinot.common.metadata.ZKMetadataProvider;
import org.apache.pinot.common.metadata.segment.SegmentZKMetadata;
import org.apache.pinot.common.metrics.ControllerGauge;
import org.apache.pinot.common.metrics.ControllerMetrics;
import org.apache.pinot.common.metrics.ControllerTimer;
import org.apache.pinot.common.tier.PinotServerTierStorage;
import org.apache.pinot.common.tier.Tier;
import org.apache.pinot.common.tier.TierFactory;
import org.apache.pinot.common.utils.SegmentUtils;
import org.apache.pinot.common.utils.config.TierConfigUtils;
import org.apache.pinot.controller.api.resources.ForceCommitBatchConfig;
import org.apache.pinot.controller.helix.core.assignment.instance.InstanceAssignmentDriver;
import org.apache.pinot.controller.helix.core.assignment.segment.SegmentAssignment;
import org.apache.pinot.controller.helix.core.assignment.segment.SegmentAssignmentFactory;
import org.apache.pinot.controller.helix.core.assignment.segment.SegmentAssignmentUtils;
import org.apache.pinot.controller.helix.core.assignment.segment.StrictRealtimeSegmentAssignment;
import org.apache.pinot.controller.helix.core.realtime.PinotLLCRealtimeSegmentManager;
import org.apache.pinot.controller.util.TableSizeReader;
import org.apache.pinot.segment.local.utils.TableConfigUtils;
import org.apache.pinot.spi.config.table.RoutingConfig;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.config.table.TierConfig;
import org.apache.pinot.spi.config.table.assignment.InstanceAssignmentConfig;
import org.apache.pinot.spi.config.table.assignment.InstancePartitionsType;
import org.apache.pinot.spi.stream.LongMsgOffset;
import org.apache.pinot.spi.stream.OffsetCriteria;
import org.apache.pinot.spi.stream.StreamConfig;
import org.apache.pinot.spi.stream.StreamConsumerFactory;
import org.apache.pinot.spi.stream.StreamConsumerFactoryProvider;
import org.apache.pinot.spi.stream.StreamMetadataProvider;
import org.apache.pinot.spi.stream.StreamPartitionMsgOffset;
import org.apache.pinot.spi.utils.CommonConstants.Helix.StateModel.SegmentStateModel;
import org.apache.pinot.spi.utils.Enablement;
import org.apache.pinot.spi.utils.IngestionConfigUtils;
import org.apache.pinot.spi.utils.StringUtil;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code TableRebalancer} class can be used to rebalance a table (reassign instances and segments for a table).
 *
 * <p>Running the rebalancer in {@code dry-run} mode will only return the target instance and segment assignment without
 * applying any change to the cluster. This mode returns immediately.
 *
 * <p>If instance reassignment is enabled, the rebalancer will reassign the instances based on the instance assignment
 * config from the table config, persist the instance partitions if not in {@code dry-run} mode, and reassign segments
 * based on the new instance assignment. Otherwise, the rebalancer will skip the instance reassignment and reassign
 * segments based on the existing instance assignment.
 *
 * <p>For segment reassignment, 2 modes are offered:
 * <ul>
 *   <li>
 *     With-downtime rebalance: the IdealState is replaced with the target segment assignment in one go and there are no
 *     guarantees around replica availability. This mode returns immediately without waiting for ExternalView to reach
 *     the target segment assignment. Disabled tables will always be rebalanced with downtime.
 *   </li>
 *   <li>
 *     No-downtime rebalance: care is taken to ensure that the configured number of replicas of any segment are
 *     available (ONLINE or CONSUMING) at all times. The rebalancer tracks the number of segments to be offloaded from
 *     each instance and offload the segments from the most loaded instances first to ensure segments are not moved to
 *     the already over-loaded instances. This mode returns after ExternalView reaching the target segment assignment.
 *     <p>In the following edge case scenarios, if {@code best-efforts} is disabled, rebalancer will fail the rebalance
 *     because the no-downtime contract cannot be achieved, and table might end up in a middle stage. User needs to
 *     check the rebalance result, solve the issue, and run the rebalance again if necessary. If {@code best-efforts} is
 *     enabled, rebalancer will log a warning and continue the rebalance, but the no-downtime contract will not be
 *     guaranteed.
 *     <ul>
 *       <li>
 *         Segment falls into ERROR state in ExternalView -> with best-efforts, count ERROR state as good state
 *       </li>
 *       <li>
 *         ExternalView has not converged within the maximum wait time -> with best-efforts, continue to the next stage
 *       </li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>NOTE: If the controller that handles the rebalance goes down/restarted, the rebalance isn't automatically resumed
 * by other controllers.
 */
public class TableRebalancer {
  private static final Logger LOGGER = LoggerFactory.getLogger(TableRebalancer.class);
  private static final int TOP_N_IN_CONSUMING_SEGMENT_SUMMARY = 10;
  // TODO: Consider making the timeoutMs below table rebalancer configurable
  private static final int TABLE_SIZE_READER_TIMEOUT_MS = 30_000;
  private static final int STREAM_PARTITION_OFFSET_READ_TIMEOUT_MS = 10_000;
  private static final AtomicInteger REBALANCE_JOB_COUNTER = new AtomicInteger(0);
  private final HelixManager _helixManager;
  private final HelixDataAccessor _helixDataAccessor;
  private final TableRebalanceObserver _tableRebalanceObserver;
  private final ControllerMetrics _controllerMetrics;
  private final RebalancePreChecker _rebalancePreChecker;
  private final TableSizeReader _tableSizeReader;
  private final PinotLLCRealtimeSegmentManager _pinotLLCRealtimeSegmentManager;

  public TableRebalancer(HelixManager helixManager, @Nullable TableRebalanceObserver tableRebalanceObserver,
      @Nullable ControllerMetrics controllerMetrics, @Nullable RebalancePreChecker rebalancePreChecker,
      @Nullable TableSizeReader tableSizeReader,
      @Nullable PinotLLCRealtimeSegmentManager pinotLLCRealtimeSegmentManager) {
    _helixManager = helixManager;
    _tableRebalanceObserver = Objects.requireNonNullElseGet(tableRebalanceObserver, NoOpTableRebalanceObserver::new);
    _helixDataAccessor = helixManager.getHelixDataAccessor();
    _controllerMetrics = controllerMetrics;
    _rebalancePreChecker = rebalancePreChecker;
    _tableSizeReader = tableSizeReader;
    _pinotLLCRealtimeSegmentManager = pinotLLCRealtimeSegmentManager;
  }

  public TableRebalancer(HelixManager helixManager) {
    this(helixManager, null, null, null, null, null);
  }

  public static String createUniqueRebalanceJobIdentifier() {
    return UUID.randomUUID().toString();
  }

  public RebalanceResult rebalance(TableConfig tableConfig, RebalanceConfig rebalanceConfig,
      @Nullable String rebalanceJobId) {
    return rebalance(tableConfig, rebalanceConfig, rebalanceJobId, null);
  }

  public RebalanceResult rebalance(TableConfig tableConfig, RebalanceConfig rebalanceConfig,
      @Nullable String rebalanceJobId, @Nullable Map<String, Set<String>> providedTierToSegmentsMap) {
    long startTime = System.currentTimeMillis();
    String tableNameWithType = tableConfig.getTableName();
    RebalanceResult.Status status = RebalanceResult.Status.UNKNOWN_ERROR;
    try {
      int jobCount = REBALANCE_JOB_COUNTER.incrementAndGet();
      if (_controllerMetrics != null) {
        _controllerMetrics.setValueOfGlobalGauge(ControllerGauge.TABLE_REBALANCE_IN_PROGRESS_GLOBAL, jobCount);
      }
      RebalanceResult result = doRebalance(tableConfig, rebalanceConfig, rebalanceJobId, providedTierToSegmentsMap);
      status = result.getStatus();
      return result;
    } finally {
      int jobCount = REBALANCE_JOB_COUNTER.decrementAndGet();
      if (_controllerMetrics != null) {
        _controllerMetrics.setValueOfGlobalGauge(ControllerGauge.TABLE_REBALANCE_IN_PROGRESS_GLOBAL, jobCount);
        _controllerMetrics.addTimedTableValue(String.format("%s.%s", tableNameWithType, status.toString()),
            ControllerTimer.TABLE_REBALANCE_EXECUTION_TIME_MS, System.currentTimeMillis() - startTime,
            TimeUnit.MILLISECONDS);
      }
    }
  }

  private RebalanceResult doRebalance(TableConfig tableConfig, RebalanceConfig rebalanceConfig,
      @Nullable String rebalanceJobId, @Nullable Map<String, Set<String>> providedTierToSegmentsMap) {
    long startTimeMs = System.currentTimeMillis();
    String tableNameWithType = tableConfig.getTableName();
    String loggerName =
        getClass().getSimpleName() + '-' + tableNameWithType + (rebalanceJobId == null ? "" : '-' + rebalanceJobId);
    Logger tableRebalanceLogger = LoggerFactory.getLogger(loggerName);
    if (rebalanceJobId == null) {
      // If not passed along, create one.
      // TODO - Add rebalanceJobId to all log messages for easy tracking.
      rebalanceJobId = createUniqueRebalanceJobIdentifier();
    }
    boolean dryRun = rebalanceConfig.isDryRun();
    boolean preChecks = rebalanceConfig.isPreChecks();
    boolean reassignInstances = rebalanceConfig.isReassignInstances();
    boolean includeConsuming = rebalanceConfig.isIncludeConsuming();
    boolean bootstrap = rebalanceConfig.isBootstrap();
    boolean downtime = rebalanceConfig.isDowntime();
    int minReplicasToKeepUpForNoDowntime = rebalanceConfig.getMinAvailableReplicas();
    boolean lowDiskMode = rebalanceConfig.isLowDiskMode();
    boolean bestEfforts = rebalanceConfig.isBestEfforts();
    int batchSizePerServer = rebalanceConfig.getBatchSizePerServer();
    Preconditions.checkState(batchSizePerServer != 0 && batchSizePerServer >= -1,
        "TableRebalance batchSizePerServer must be > 0 or -1 to disable");
    long externalViewCheckIntervalInMs = rebalanceConfig.getExternalViewCheckIntervalInMs();
    long externalViewStabilizationTimeoutInMs = rebalanceConfig.getExternalViewStabilizationTimeoutInMs();
    Enablement minimizeDataMovement = rebalanceConfig.getMinimizeDataMovement();
    boolean enableStrictReplicaGroup = tableConfig.getRoutingConfig() != null
        && RoutingConfig.STRICT_REPLICA_GROUP_INSTANCE_SELECTOR_TYPE.equalsIgnoreCase(
        tableConfig.getRoutingConfig().getInstanceSelectorType());
    boolean forceCommit = rebalanceConfig.isForceCommit();
    if (tableConfig.getTableType() == TableType.OFFLINE && forceCommit) {
      tableRebalanceLogger.warn("forceCommit is set to true for an OFFLINE table, resetting it to false");
      forceCommit = false;
    }
    tableRebalanceLogger.info(
        "Start rebalancing with dryRun: {}, preChecks: {}, reassignInstances: {}, "
            + "includeConsuming: {}, bootstrap: {}, downtime: {}, minReplicasToKeepUpForNoDowntime: {}, "
            + "enableStrictReplicaGroup: {}, lowDiskMode: {}, bestEfforts: {}, batchSizePerServer: {}, "
            + "externalViewCheckIntervalInMs: {}, externalViewStabilizationTimeoutInMs: {}, minimizeDataMovement: {}, "
            + "forceCommit: {}, forceCommitBatchSize: {}, forceCommitBatchStatusCheckIntervalMs: {}, "
            + "forceCommitBatchStatusCheckTimeoutMs: {}",
        dryRun, preChecks, reassignInstances, includeConsuming, bootstrap, downtime,
        minReplicasToKeepUpForNoDowntime, enableStrictReplicaGroup, lowDiskMode, bestEfforts, batchSizePerServer,
        externalViewCheckIntervalInMs, externalViewStabilizationTimeoutInMs, minimizeDataMovement,
        forceCommit, rebalanceConfig.getForceCommitBatchSize(),
        rebalanceConfig.getForceCommitBatchStatusCheckIntervalMs(),
        rebalanceConfig.getForceCommitBatchStatusCheckTimeoutMs());

    // Dry-run must be enabled to run pre-checks
    if (preChecks && !dryRun) {
      String errorMsg = "Pre-checks can only be enabled in dry-run mode, not triggering rebalance";
      tableRebalanceLogger.error(errorMsg);
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED, errorMsg, null, null, null, null,
          null);
    }

    // Fetch ideal state
    PropertyKey idealStatePropertyKey = _helixDataAccessor.keyBuilder().idealStates(tableNameWithType);
    IdealState currentIdealState;
    try {
      currentIdealState = _helixDataAccessor.getProperty(idealStatePropertyKey);
    } catch (Exception e) {
      onReturnFailure("Caught exception while fetching IdealState, aborting the rebalance", e,
          tableRebalanceLogger);
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
          "Caught exception while fetching IdealState: " + e, null, null, null, null, null);
    }
    if (currentIdealState == null) {
      onReturnFailure("Cannot find the IdealState, aborting the rebalance", null,
          tableRebalanceLogger);
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED, "Cannot find the IdealState for table",
          null, null, null, null, null);
    }
    if (!currentIdealState.isEnabled() && !downtime) {
      onReturnFailure("Cannot rebalance disabled table without downtime, aborting the rebalance", null,
          tableRebalanceLogger);
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
          "Cannot rebalance disabled table without downtime", null, null, null, null, null);
    }

    tableRebalanceLogger.info("Processing instance partitions");

    // Calculate instance partitions map
    Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap;
    boolean instancePartitionsUnchanged;
    try {
      Pair<Map<InstancePartitionsType, InstancePartitions>, Boolean> instancePartitionsMapAndUnchanged =
          getInstancePartitionsMap(tableConfig, reassignInstances, bootstrap, dryRun, minimizeDataMovement,
              tableRebalanceLogger);
      instancePartitionsMap = instancePartitionsMapAndUnchanged.getLeft();
      instancePartitionsUnchanged = instancePartitionsMapAndUnchanged.getRight();
    } catch (Exception e) {
      onReturnFailure("Caught exception while fetching/calculating instance partitions, aborting the rebalance", e,
          tableRebalanceLogger);
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
          "Caught exception while fetching/calculating instance partitions: " + e, null, null, null, null,
          null);
    }

    // Calculate instance partitions for tiers if configured
    List<Tier> sortedTiers;
    Map<String, InstancePartitions> tierToInstancePartitionsMap;
    boolean tierInstancePartitionsUnchanged;
    try {
      sortedTiers = getSortedTiers(tableConfig, providedTierToSegmentsMap);
      Pair<Map<String, InstancePartitions>, Boolean> tierToInstancePartitionsMapAndUnchanged =
          getTierToInstancePartitionsMap(tableConfig, sortedTiers, reassignInstances, bootstrap, dryRun,
              minimizeDataMovement, tableRebalanceLogger);
      tierToInstancePartitionsMap = tierToInstancePartitionsMapAndUnchanged.getLeft();
      tierInstancePartitionsUnchanged = tierToInstancePartitionsMapAndUnchanged.getRight();
    } catch (Exception e) {
      onReturnFailure("Caught exception while fetching/calculating tier instance partitions, aborting the rebalance", e,
          tableRebalanceLogger);
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
          "Caught exception while fetching/calculating tier instance partitions: " + e, null,
          null, null, null, null);
    }

    tableRebalanceLogger.info("Calculating the target assignment");
    SegmentAssignment segmentAssignment =
        SegmentAssignmentFactory.getSegmentAssignment(_helixManager, tableConfig, _controllerMetrics);
    Map<String, Map<String, String>> currentAssignment = currentIdealState.getRecord().getMapFields();
    Map<String, Map<String, String>> targetAssignment;
    try {
      targetAssignment = segmentAssignment.rebalanceTable(currentAssignment, instancePartitionsMap, sortedTiers,
          tierToInstancePartitionsMap, rebalanceConfig);
    } catch (Exception e) {
      onReturnFailure("Caught exception while calculating target assignment, aborting the rebalance", e,
          tableRebalanceLogger);
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
          "Caught exception while calculating target assignment: " + e, instancePartitionsMap,
          tierToInstancePartitionsMap, null, null, null);
    }

    boolean segmentAssignmentUnchanged = currentAssignment.equals(targetAssignment);
    tableRebalanceLogger.info(
        "instancePartitionsUnchanged: {}, tierInstancePartitionsUnchanged: {}, "
            + "segmentAssignmentUnchanged: {}", instancePartitionsUnchanged,
        tierInstancePartitionsUnchanged, segmentAssignmentUnchanged);

    TableSizeReader.TableSubTypeSizeDetails tableSubTypeSizeDetails =
        fetchTableSizeDetails(tableNameWithType, tableRebalanceLogger);

    Map<String, RebalancePreCheckerResult> preChecksResult = null;

    // Calculate summary here itself so that even if the table is already balanced, the caller can verify whether that
    // is expected or not based on the summary results
    RebalanceSummaryResult summaryResult =
        calculateDryRunSummary(currentAssignment, targetAssignment, tableNameWithType, tableSubTypeSizeDetails,
            tableConfig, tableRebalanceLogger);

    if (preChecks) {
      if (_rebalancePreChecker == null) {
        tableRebalanceLogger.warn(
            "Pre-checks are enabled but the pre-checker is not set, skipping pre-checks");
      } else {
        RebalancePreChecker.PreCheckContext preCheckContext =
            new RebalancePreChecker.PreCheckContext(rebalanceJobId, tableNameWithType, tableConfig, currentAssignment,
                targetAssignment, tableSubTypeSizeDetails, rebalanceConfig, summaryResult);
        preChecksResult = _rebalancePreChecker.check(preCheckContext);
      }
    }

    if (segmentAssignmentUnchanged) {
      tableRebalanceLogger.info("Table is already balanced");
      if (instancePartitionsUnchanged && tierInstancePartitionsUnchanged) {
        _tableRebalanceObserver.onNoop("Instance unchanged and table is already balanced");
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.NO_OP, "Table is already balanced",
            instancePartitionsMap, tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
      } else {
        tableRebalanceLogger.info("Instance reassigned but table is already balanced");
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.DONE,
            "Instance reassigned, table is already balanced", instancePartitionsMap,
            tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
      }
    }

    if (dryRun) {
      tableRebalanceLogger.info("Rebalancing in dry-run mode, returning the target assignment");
      return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.DONE, "Dry-run mode", instancePartitionsMap,
          tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
    }

    if (downtime) {
      tableRebalanceLogger.info("Rebalancing with downtime");
      if (forceCommit) {
        Set<String> consumingSegmentsToMoveNext = getMovingConsumingSegments(currentAssignment, targetAssignment);
        if (!consumingSegmentsToMoveNext.isEmpty()) {
          try {
            currentIdealState =
                forceCommitConsumingSegmentsAndWait(tableNameWithType, consumingSegmentsToMoveNext,
                    tableRebalanceLogger,
                    rebalanceConfig.getForceCommitBatchSize(),
                    rebalanceConfig.getForceCommitBatchStatusCheckIntervalMs(),
                    rebalanceConfig.getForceCommitBatchStatusCheckTimeoutMs());
          } catch (Exception e) {
            String errorMsg =
                "Caught exception while waiting for consuming segments to force commit, aborting the rebalance: "
                    + e.getMessage();
            onReturnFailure(errorMsg, e, tableRebalanceLogger);
            return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED, errorMsg, instancePartitionsMap,
                tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
          }
          currentAssignment = currentIdealState.getRecord().getMapFields();
          targetAssignment = segmentAssignment.rebalanceTable(currentAssignment, instancePartitionsMap, sortedTiers,
              tierToInstancePartitionsMap, rebalanceConfig);
        }
      }
      // Reuse current IdealState to update the IdealState in cluster
      ZNRecord idealStateRecord = currentIdealState.getRecord();
      idealStateRecord.setMapFields(targetAssignment);
      currentIdealState.setNumPartitions(targetAssignment.size());
      currentIdealState.setReplicas(Integer.toString(targetAssignment.values().iterator().next().size()));

      // Check version and update IdealState
      try {
        Preconditions.checkState(_helixDataAccessor.getBaseDataAccessor()
            .set(idealStatePropertyKey.getPath(), idealStateRecord, idealStateRecord.getVersion(),
                AccessOption.PERSISTENT), "Failed to update IdealState");
        String msg = "Finished rebalancing with downtime in " + (System.currentTimeMillis() - startTimeMs) + " ms.";
        tableRebalanceLogger.info(msg);
        _tableRebalanceObserver.onSuccess(msg);
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.DONE,
            "Success with downtime (replaced IdealState with the target segment assignment, ExternalView might not "
                + "reach the target segment assignment yet)", instancePartitionsMap, tierToInstancePartitionsMap,
            targetAssignment, preChecksResult, summaryResult);
      } catch (Exception e) {
        onReturnFailure("Caught exception while updating IdealState, aborting the rebalance", e, tableRebalanceLogger);
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
            "Caught exception while updating IdealState: " + e, instancePartitionsMap, tierToInstancePartitionsMap,
            targetAssignment, preChecksResult, summaryResult);
      }
    }

    List<String> segmentsToMove = SegmentAssignmentUtils.getSegmentsToMove(currentAssignment, targetAssignment);
    Set<String> segmentsToMonitor = new HashSet<>(segmentsToMove);

    long estimatedAverageSegmentSizeInBytes = summaryResult.getSegmentInfo().getEstimatedAverageSegmentSizeInBytes();
    Set<String> allSegmentsFromIdealState = currentAssignment.keySet();
    TableRebalanceObserver.RebalanceContext rebalanceContext = new TableRebalanceObserver.RebalanceContext(
        estimatedAverageSegmentSizeInBytes, allSegmentsFromIdealState, segmentsToMonitor);

    // Record the beginning of rebalance
    _tableRebalanceObserver.onTrigger(TableRebalanceObserver.Trigger.START_TRIGGER, currentAssignment,
        targetAssignment, rebalanceContext);

    // Calculate the min available replicas for no-downtime rebalance
    // NOTE:
    // 1. The calculation is based on the number of replicas of the target assignment. In case of increasing the number
    //    of replicas for the current assignment, the current instance state map might not have enough replicas to reach
    //    the minimum available replicas requirement. In this scenario we don't want to fail the check, but keep all the
    //    current instances as this is the best we can do, and can help the table get out of this state.
    // 2. Only check the segments to be moved because we don't need to maintain available replicas for segments not
    //    being moved, including segments with all replicas OFFLINE (error segments during consumption).
    int numReplicas = Integer.MAX_VALUE;
    for (String segment : segmentsToMove) {
      numReplicas = Math.min(targetAssignment.get(segment).size(), numReplicas);
    }
    int minAvailableReplicas;
    if (minReplicasToKeepUpForNoDowntime >= 0) {
      // For non-negative value, use it as min available replicas
      if (minReplicasToKeepUpForNoDowntime >= numReplicas) {
        onReturnFailure("Illegal config for minReplicasToKeepUpForNoDowntime: " + minReplicasToKeepUpForNoDowntime
                + ", must be less than number of replicas: " + numReplicas + ", aborting the rebalance", null,
            tableRebalanceLogger);
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
            "Illegal min available replicas config", instancePartitionsMap, tierToInstancePartitionsMap,
            targetAssignment, preChecksResult, summaryResult);
      }
      minAvailableReplicas = minReplicasToKeepUpForNoDowntime;
    } else {
      // For negative value, use it as max unavailable replicas
      minAvailableReplicas = Math.max(numReplicas + minReplicasToKeepUpForNoDowntime, 0);
    }

    int numCurrentAssignmentReplicas = Integer.MAX_VALUE;
    for (String segment : segmentsToMove) {
      numCurrentAssignmentReplicas = Math.min(currentAssignment.get(segment).size(), numCurrentAssignmentReplicas);
    }
    if (minAvailableReplicas > numCurrentAssignmentReplicas) {
      tableRebalanceLogger.warn("minAvailableReplicas: {} larger than existing number of replicas: {}, "
              + "resetting minAvailableReplicas to {}", minAvailableReplicas, numCurrentAssignmentReplicas,
          numCurrentAssignmentReplicas);
      minAvailableReplicas = numCurrentAssignmentReplicas;
    }

    tableRebalanceLogger.info(
        "Rebalancing with minAvailableReplicas: {}, enableStrictReplicaGroup: {}, "
            + "bestEfforts: {}, externalViewCheckIntervalInMs: {}, externalViewStabilizationTimeoutInMs: {}",
        minAvailableReplicas, enableStrictReplicaGroup, bestEfforts, externalViewCheckIntervalInMs,
        externalViewStabilizationTimeoutInMs);
    int expectedVersion = currentIdealState.getRecord().getVersion();

    // Cache segment partition id to avoid too many ZK SegmentMetadata reads. Similar behavior as the cache used in
    // StrictReplicaGroupAssignment::rebalanceTable() and similar limitations apply here as well
    Object2IntOpenHashMap<String> segmentPartitionIdMap = new Object2IntOpenHashMap<>();

    boolean isStrictRealtimeSegmentAssignment = (segmentAssignment instanceof StrictRealtimeSegmentAssignment);
    PartitionIdFetcher partitionIdFetcher =
        new PartitionIdFetcherImpl(tableNameWithType, TableConfigUtils.getPartitionColumn(tableConfig), _helixManager,
            isStrictRealtimeSegmentAssignment);
    // We repeat the following steps until the target assignment is reached:
    // 1. Wait for ExternalView to converge with the IdealState. Fail the rebalance if it doesn't make progress within
    //    the timeout.
    // 2. When IdealState changes during step 1, re-calculate the target assignment based on the new IdealState (current
    //    assignment).
    // 3. If forceCommit is enabled, do:
    //  3.1 calculate the next assignment based on the current assignment, target
    //      assignment, batch size per server, and min available replicas.
    //  3.2 Force commit the consuming segments that would be moved in the next assignment, and wait for them to finish
    //  3.3 Recalculate the target assignment based on the new IdealState
    // 4. Check if the target assignment is reached. Rebalance is done if it is reached.
    // 5. Calculate the next assignment based on the current assignment, target assignment, batch size per server,
    // and min available replicas.
    // 6. Update the IdealState to the next assignment. If the IdealState changes before the update, go back to step 1.
    //
    // NOTE: Monitor the segments to be moved from both the previous round and this round to ensure the moved segments
    //       in the previous round are also converged.
    while (true) {
      // Wait for ExternalView to converge before updating the next IdealState
      IdealState idealState;
      try {
        idealState = waitForExternalViewToConverge(tableNameWithType, lowDiskMode, bestEfforts, segmentsToMonitor,
            externalViewCheckIntervalInMs, externalViewStabilizationTimeoutInMs, estimatedAverageSegmentSizeInBytes,
            allSegmentsFromIdealState, tableRebalanceLogger);
      } catch (Exception e) {
        String errorMsg =
            "Caught exception while waiting for ExternalView to converge, aborting the rebalance: " + e.getMessage();
        onReturnFailure(errorMsg, e, tableRebalanceLogger);
        if (_tableRebalanceObserver.isStopped()) {
          return new RebalanceResult(rebalanceJobId, _tableRebalanceObserver.getStopStatus(),
              "Caught exception while waiting for ExternalView to converge: " + e, instancePartitionsMap,
              tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
        }
        _tableRebalanceObserver.onError(errorMsg);
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
            "Caught exception while waiting for ExternalView to converge: " + e, instancePartitionsMap,
            tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
      }

      // Re-calculate the target assignment if IdealState changed while waiting for ExternalView to converge
      ZNRecord idealStateRecord = idealState.getRecord();
      Map<String, Map<String, String>> nextAssignment;
      boolean needsRecalculation;
      boolean shouldForceCommit = forceCommit;

      do {
        needsRecalculation = false;

        // Step 1: Handle version mismatch and recalculate if needed
        if (idealStateRecord.getVersion() != expectedVersion) {
          tableRebalanceLogger.info(
              "IdealState version changed while waiting for ExternalView to converge, re-calculating the target "
                  + "assignment");
          Map<String, Map<String, String>> oldAssignment = currentAssignment;
          currentAssignment = idealStateRecord.getMapFields();
          expectedVersion = idealStateRecord.getVersion();

          // If all the segments to be moved remain unchanged (same instance state map) in the new ideal state, apply
          // the same target instance state map for these segments to the new ideal state as the target assignment
          boolean segmentsToMoveChanged = false;
          if (segmentAssignment instanceof StrictRealtimeSegmentAssignment) {
            // For StrictRealtimeSegmentAssignment, we need to recompute the target assignment because the assignment
            // for new added segments is based on the existing assignment
            segmentsToMoveChanged = true;
          } else {
            for (String segment : segmentsToMove) {
              Map<String, String> oldInstanceStateMap = oldAssignment.get(segment);
              Map<String, String> currentInstanceStateMap = currentAssignment.get(segment);
              // TODO: Consider allowing segment state change from CONSUMING to ONLINE
              if (!oldInstanceStateMap.equals(currentInstanceStateMap)) {
                tableRebalanceLogger.info(
                    "Segment state changed in IdealState from: {} to: {} for segment: {}, re-calculating the target "
                        + "assignment based on the new IdealState",
                    oldInstanceStateMap, currentInstanceStateMap, segment);
                segmentsToMoveChanged = true;
                break;
              }
            }
          }
          if (segmentsToMoveChanged) {
            try {
              // Re-calculate the instance partitions in case the instance configs changed during the rebalance
              instancePartitionsMap =
                  getInstancePartitionsMap(tableConfig, reassignInstances, bootstrap, false, minimizeDataMovement,
                      tableRebalanceLogger).getLeft();
              tierToInstancePartitionsMap =
                  getTierToInstancePartitionsMap(tableConfig, sortedTiers, reassignInstances, bootstrap, false,
                      minimizeDataMovement, tableRebalanceLogger).getLeft();
              targetAssignment = segmentAssignment.rebalanceTable(currentAssignment, instancePartitionsMap, sortedTiers,
                  tierToInstancePartitionsMap, rebalanceConfig);
            } catch (Exception e) {
              onReturnFailure("Caught exception while re-calculating the target assignment, aborting the rebalance", e,
                  tableRebalanceLogger);
              return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
                  "Caught exception while re-calculating the target assignment: " + e, instancePartitionsMap,
                  tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
            }
          } else {
            tableRebalanceLogger.info(
                "No state change found for segments to be moved, re-calculating the target assignment based on the "
                    + "previous target assignment");
            Map<String, Map<String, String>> oldTargetAssignment = targetAssignment;
            // Other instance assignment code returns a TreeMap to keep it sorted, doing the same here
            targetAssignment = new TreeMap<>(currentAssignment);
            for (String segment : segmentsToMove) {
              targetAssignment.put(segment, oldTargetAssignment.get(segment));
            }
          }
        }

        // Step 2: Handle force commit if flag is set, then recalculate if force commit occurred
        if (shouldForceCommit) {
          nextAssignment =
              getNextAssignment(currentAssignment, targetAssignment, minAvailableReplicas, enableStrictReplicaGroup,
                  lowDiskMode, batchSizePerServer, segmentPartitionIdMap, partitionIdFetcher, tableRebalanceLogger);
          Set<String> consumingSegmentsToMoveNext = getMovingConsumingSegments(currentAssignment, nextAssignment);

          if (!consumingSegmentsToMoveNext.isEmpty()) {
            needsRecalculation = true;
            _tableRebalanceObserver.onTrigger(
                TableRebalanceObserver.Trigger.FORCE_COMMIT_START_TRIGGER, null, null,
                null);
            try {
              idealState =
                  forceCommitConsumingSegmentsAndWait(tableNameWithType, consumingSegmentsToMoveNext,
                      tableRebalanceLogger, rebalanceConfig.getForceCommitBatchSize(),
                      rebalanceConfig.getForceCommitBatchStatusCheckIntervalMs(),
                      rebalanceConfig.getForceCommitBatchStatusCheckTimeoutMs());
            } catch (Exception e) {
              String errorMsg =
                  "Caught exception while waiting for consuming segments to force commit, aborting the rebalance: "
                      + e.getMessage();
              onReturnFailure(errorMsg, e, tableRebalanceLogger);
              return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED, errorMsg, instancePartitionsMap,
                  tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
            }
            idealStateRecord = idealState.getRecord();
            _tableRebalanceObserver.onTrigger(
                TableRebalanceObserver.Trigger.FORCE_COMMIT_END_TRIGGER, null, null,
                new TableRebalanceObserver.RebalanceContext(consumingSegmentsToMoveNext.size()));
          }
          shouldForceCommit = false; // Only attempt force commit once
        }
      } while (needsRecalculation);

      if (currentAssignment.equals(targetAssignment)) {
        String msg =
            "Finished rebalancing with minAvailableReplicas: " + minAvailableReplicas + ", enableStrictReplicaGroup: "
                + enableStrictReplicaGroup + ", bestEfforts: " + bestEfforts + " in " + (System.currentTimeMillis()
                - startTimeMs) + " ms.";
        tableRebalanceLogger.info(msg);
        // Record completion
        _tableRebalanceObserver.onSuccess(msg);
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.DONE,
            "Success with minAvailableReplicas: " + minAvailableReplicas
                + " (both IdealState and ExternalView should reach the target segment assignment)",
            instancePartitionsMap, tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
      }

      // Record change of current ideal state and the new target
      rebalanceContext = new TableRebalanceObserver.RebalanceContext(estimatedAverageSegmentSizeInBytes,
          allSegmentsFromIdealState, null);
      _tableRebalanceObserver.onTrigger(TableRebalanceObserver.Trigger.IDEAL_STATE_CHANGE_TRIGGER, currentAssignment,
          targetAssignment, rebalanceContext);
      if (_tableRebalanceObserver.isStopped()) {
        return new RebalanceResult(rebalanceJobId, _tableRebalanceObserver.getStopStatus(),
            "Rebalance has stopped already before updating the IdealState", instancePartitionsMap,
            tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
      }

      nextAssignment =
          getNextAssignment(currentAssignment, targetAssignment, minAvailableReplicas, enableStrictReplicaGroup,
              lowDiskMode, batchSizePerServer, segmentPartitionIdMap, partitionIdFetcher, tableRebalanceLogger);
      tableRebalanceLogger.info(
          "Got the next assignment with number of segments to be added/removed for each instance: {}",
          SegmentAssignmentUtils.getNumSegmentsToMovePerInstance(currentAssignment, nextAssignment));

      // Record change of current ideal state and the next assignment
      _tableRebalanceObserver.onTrigger(TableRebalanceObserver.Trigger.NEXT_ASSIGNMENT_CALCULATION_TRIGGER,
          currentAssignment, nextAssignment, rebalanceContext);
      if (_tableRebalanceObserver.isStopped()) {
        return new RebalanceResult(rebalanceJobId, _tableRebalanceObserver.getStopStatus(),
            "Rebalance has stopped already before updating the IdealState with the next assignment",
            instancePartitionsMap, tierToInstancePartitionsMap, targetAssignment, preChecksResult, summaryResult);
      }

      // Reuse current IdealState to update the IdealState in cluster
      idealStateRecord.setMapFields(nextAssignment);
      idealState.setNumPartitions(nextAssignment.size());
      idealState.setReplicas(Integer.toString(nextAssignment.values().iterator().next().size()));

      // Check version and update IdealState
      try {
        Preconditions.checkState(_helixDataAccessor.getBaseDataAccessor()
                .set(idealStatePropertyKey.getPath(), idealStateRecord, expectedVersion, AccessOption.PERSISTENT),
            "Failed to update IdealState");
        currentAssignment = nextAssignment;
        expectedVersion++;
        // IdealState update is successful. Update the segment list as the IDEAL_STATE_CHANGE_TRIGGER should have
        // captured the newly added / deleted segments
        allSegmentsFromIdealState = currentAssignment.keySet();
        tableRebalanceLogger.info("Successfully updated the IdealState");
      } catch (ZkBadVersionException e) {
        tableRebalanceLogger.info("Version changed while updating IdealState");
        // Since IdealState wasn't updated, rollback the stats changes made and continue. There is no need to update
        // segmentsToMonitor either since that hasn't changed without the IdealState update
        _tableRebalanceObserver.onRollback();
        continue;
      } catch (Exception e) {
        onReturnFailure("Caught exception while updating IdealState, aborting the rebalance", e, tableRebalanceLogger);
        return new RebalanceResult(rebalanceJobId, RebalanceResult.Status.FAILED,
            "Caught exception while updating IdealState: " + e, instancePartitionsMap, tierToInstancePartitionsMap,
            targetAssignment, preChecksResult, summaryResult);
      }

      segmentsToMonitor = new HashSet<>(segmentsToMove);
      segmentsToMove = SegmentAssignmentUtils.getSegmentsToMove(currentAssignment, targetAssignment);
      segmentsToMonitor.addAll(segmentsToMove);
    }
  }

  private TableSizeReader.TableSubTypeSizeDetails fetchTableSizeDetails(String tableNameWithType,
      Logger tableRebalanceLogger) {
    if (_tableSizeReader == null) {
      tableRebalanceLogger.warn("tableSizeReader is null, cannot calculate table size!");
      return null;
    }
    tableRebalanceLogger.info("Fetching the table size");
    try {
      TableSizeReader.TableSubTypeSizeDetails sizeDetails =
          _tableSizeReader.getTableSubtypeSize(tableNameWithType, TABLE_SIZE_READER_TIMEOUT_MS, true);
      tableRebalanceLogger.info("Fetched the table size details");
      return sizeDetails;
    } catch (InvalidConfigException e) {
      tableRebalanceLogger.error("Caught exception while trying to fetch table size details", e);
    }
    return null;
  }

  private long calculateTableSizePerReplicaInBytes(TableSizeReader.TableSubTypeSizeDetails tableSizeDetails) {
    return tableSizeDetails == null ? -1 : tableSizeDetails._reportedSizePerReplicaInBytes;
  }

  private RebalanceSummaryResult calculateDryRunSummary(Map<String, Map<String, String>> currentAssignment,
      Map<String, Map<String, String>> targetAssignment, String tableNameWithType,
      TableSizeReader.TableSubTypeSizeDetails tableSubTypeSizeDetails, TableConfig tableConfig,
      Logger tableRebalanceLogger) {
    tableRebalanceLogger.info("Calculating rebalance summary");
    boolean isOfflineTable = TableNameBuilder.getTableTypeFromTableName(tableNameWithType) == TableType.OFFLINE;
    int existingReplicationFactor = 0;
    int newReplicationFactor = 0;
    Map<String, Set<String>> existingServersToSegmentMap = new HashMap<>();
    Map<String, Set<String>> newServersToSegmentMap = new HashMap<>();
    Map<String, Set<String>> existingServersToConsumingSegmentMap = isOfflineTable ? null : new HashMap<>();
    Map<String, Set<String>> newServersToConsumingSegmentMap = isOfflineTable ? null : new HashMap<>();

    for (Map.Entry<String, Map<String, String>> entrySet : currentAssignment.entrySet()) {
      existingReplicationFactor = entrySet.getValue().size();
      String segmentName = entrySet.getKey();
      Collection<String> segmentStates = entrySet.getValue().values();
      boolean isSegmentConsuming = existingServersToConsumingSegmentMap != null && segmentStates.stream()
          .noneMatch(state -> state.equals(SegmentStateModel.ONLINE)) && segmentStates.stream()
          .anyMatch(state -> state.equals(SegmentStateModel.CONSUMING));

      for (String instanceName : entrySet.getValue().keySet()) {
        existingServersToSegmentMap.computeIfAbsent(instanceName, k -> new HashSet<>()).add(segmentName);
        if (isSegmentConsuming) {
          existingServersToConsumingSegmentMap.computeIfAbsent(instanceName, k -> new HashSet<>()).add(segmentName);
        }
      }
    }

    for (Map.Entry<String, Map<String, String>> entrySet : targetAssignment.entrySet()) {
      newReplicationFactor = entrySet.getValue().size();
      String segmentName = entrySet.getKey();
      Collection<String> segmentStates = entrySet.getValue().values();
      boolean isSegmentConsuming = existingServersToConsumingSegmentMap != null && segmentStates.stream()
          .noneMatch(state -> state.equals(SegmentStateModel.ONLINE)) && segmentStates.stream()
          .anyMatch(state -> state.equals(SegmentStateModel.CONSUMING));
      for (String instanceName : entrySet.getValue().keySet()) {
        newServersToSegmentMap.computeIfAbsent(instanceName, k -> new HashSet<>()).add(segmentName);
        if (isSegmentConsuming) {
          newServersToConsumingSegmentMap.computeIfAbsent(instanceName, k -> new HashSet<>()).add(segmentName);
        }
      }
    }
    RebalanceSummaryResult.RebalanceChangeInfo replicationFactor
        = new RebalanceSummaryResult.RebalanceChangeInfo(existingReplicationFactor, newReplicationFactor);

    int existingNumServers = existingServersToSegmentMap.size();
    int newNumServers = newServersToSegmentMap.size();
    RebalanceSummaryResult.RebalanceChangeInfo numServers
        = new RebalanceSummaryResult.RebalanceChangeInfo(existingNumServers, newNumServers);

    List<InstanceConfig> instanceConfigs = _helixDataAccessor.getChildValues(
        _helixDataAccessor.keyBuilder().instanceConfigs(), true);
    Map<String, List<String>> instanceToTagsMap = new HashMap<>();
    for (InstanceConfig instanceConfig : instanceConfigs) {
      instanceToTagsMap.put(instanceConfig.getInstanceName(), instanceConfig.getTags());
    }

    Set<String> serversAdded = new HashSet<>();
    Set<String> serversRemoved = new HashSet<>();
    Set<String> serversUnchanged = new HashSet<>();
    Set<String> serversGettingNewSegments = new HashSet<>();
    Map<String, RebalanceSummaryResult.TagInfo> tagsInfoMap = new HashMap<>();
    Set<String> relevantTags = TableConfigUtils.getRelevantTags(tableConfig);
    for (String tag : relevantTags) {
      tagsInfoMap.put(tag, new RebalanceSummaryResult.TagInfo(tag));
    }
    Map<String, RebalanceSummaryResult.ServerSegmentChangeInfo> serverSegmentChangeInfoMap = new HashMap<>();
    int segmentsNotMoved = 0;
    int totalSegmentsToBeDeleted = 0;
    int maxSegmentsAddedToServer = 0;
    for (Map.Entry<String, Set<String>> entry : newServersToSegmentMap.entrySet()) {
      String server = entry.getKey();
      Set<String> segmentSet = entry.getValue();
      int totalNewSegments = segmentSet.size();

      Set<String> newSegmentSet = new HashSet<>(segmentSet);
      Set<String> existingSegmentSet = new HashSet<>();
      int segmentsUnchanged = 0;
      int totalExistingSegments = 0;
      RebalanceSummaryResult.ServerStatus serverStatus = RebalanceSummaryResult.ServerStatus.ADDED;
      if (existingServersToSegmentMap.containsKey(server)) {
        Set<String> segmentSetForServer = existingServersToSegmentMap.get(server);
        totalExistingSegments = segmentSetForServer.size();
        existingSegmentSet.addAll(segmentSetForServer);
        Set<String> intersection = new HashSet<>(segmentSetForServer);
        intersection.retainAll(newSegmentSet);
        segmentsUnchanged = intersection.size();
        segmentsNotMoved += segmentsUnchanged;
        serverStatus = RebalanceSummaryResult.ServerStatus.UNCHANGED;
        serversUnchanged.add(server);
      } else {
        serversAdded.add(server);
      }
      newSegmentSet.removeAll(existingSegmentSet);
      int segmentsAdded = newSegmentSet.size();
      if (segmentsAdded > 0) {
        serversGettingNewSegments.add(server);
      }
      maxSegmentsAddedToServer = Math.max(maxSegmentsAddedToServer, segmentsAdded);
      int segmentsDeleted = existingSegmentSet.size() - segmentsUnchanged;
      totalSegmentsToBeDeleted += segmentsDeleted;

      serverSegmentChangeInfoMap.put(server, new RebalanceSummaryResult.ServerSegmentChangeInfo(serverStatus,
          totalNewSegments, totalExistingSegments, segmentsAdded, segmentsDeleted, segmentsUnchanged,
          instanceToTagsMap.getOrDefault(server, null)));
      Set<String> serverTags = new HashSet<>(getServerTag(server));
      Set<String> relevantTagsForServer = Sets.intersection(serverTags, relevantTags);
      // The segments remain unchanged or need to download will be accounted to every tag associated with this
      // server instance
      if (relevantTagsForServer.isEmpty()) {
        // this could happen when server's tags changed but reassignInstance=false in the rebalance config
        tableRebalanceLogger.warn("Server: {} was assigned but does not have any relevant tags", server);

        RebalanceSummaryResult.TagInfo tagsInfo =
            tagsInfoMap.computeIfAbsent(RebalanceSummaryResult.TagInfo.TAG_FOR_OUTDATED_SERVERS,
                RebalanceSummaryResult.TagInfo::new);
        tagsInfo.increaseNumSegmentsUnchanged(segmentsUnchanged);
        tagsInfo.increaseNumSegmentsToDownload(segmentsAdded);
        tagsInfo.increaseNumServerParticipants(1);
      } else {
        for (String tag : relevantTagsForServer) {
          RebalanceSummaryResult.TagInfo tagsInfo = tagsInfoMap.get(tag);
          tagsInfo.increaseNumSegmentsUnchanged(segmentsUnchanged);
          tagsInfo.increaseNumSegmentsToDownload(segmentsAdded);
          tagsInfo.increaseNumServerParticipants(1);
        }
      }
    }

    for (Map.Entry<String, Set<String>> entry : existingServersToSegmentMap.entrySet()) {
      String server = entry.getKey();
      if (!serverSegmentChangeInfoMap.containsKey(server)) {
        serversRemoved.add(server);
        serverSegmentChangeInfoMap.put(server, new RebalanceSummaryResult.ServerSegmentChangeInfo(
            RebalanceSummaryResult.ServerStatus.REMOVED, 0, entry.getValue().size(), 0, entry.getValue().size(), 0,
            instanceToTagsMap.getOrDefault(server, null)));
        totalSegmentsToBeDeleted += entry.getValue().size();
      }
    }

    if (existingServersToConsumingSegmentMap != null && newServersToConsumingSegmentMap != null) {
      // turn the map into {server: added consuming segments}
      for (Map.Entry<String, Set<String>> entry : newServersToConsumingSegmentMap.entrySet()) {
        String server = entry.getKey();
        entry.getValue().removeAll(existingServersToConsumingSegmentMap.getOrDefault(server, Collections.emptySet()));
      }
      newServersToConsumingSegmentMap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    RebalanceSummaryResult.RebalanceChangeInfo numSegmentsInSingleReplica
        = new RebalanceSummaryResult.RebalanceChangeInfo(currentAssignment.size(), targetAssignment.size());

    int existingNumberSegmentsTotal = existingReplicationFactor * currentAssignment.size();
    int newNumberSegmentsTotal = newReplicationFactor * targetAssignment.size();
    RebalanceSummaryResult.RebalanceChangeInfo numSegmentsAcrossAllReplicas
        = new RebalanceSummaryResult.RebalanceChangeInfo(existingNumberSegmentsTotal, newNumberSegmentsTotal);

    int totalSegmentsToBeAdded = newNumberSegmentsTotal - segmentsNotMoved;

    long tableSizePerReplicaInBytes = calculateTableSizePerReplicaInBytes(tableSubTypeSizeDetails);
    long averageSegmentSizeInBytes = tableSizePerReplicaInBytes <= 0 ? tableSizePerReplicaInBytes
        : tableSizePerReplicaInBytes / ((long) currentAssignment.size());
    long totalEstimatedDataToBeMovedInBytes = tableSizePerReplicaInBytes <= 0 ? tableSizePerReplicaInBytes
        : ((long) totalSegmentsToBeAdded) * averageSegmentSizeInBytes;

    // Set some of the sets to null if they are empty to ensure they don't show up in the result
    RebalanceSummaryResult.ServerInfo serverInfo = new RebalanceSummaryResult.ServerInfo(
        serversGettingNewSegments.size(), numServers, serversAdded, serversRemoved, serversUnchanged,
        serversGettingNewSegments, serverSegmentChangeInfoMap);
    // TODO: Add a metric to estimate the total time it will take to rebalance. Need some good heuristics on how
    //       rebalance time can vary with number of segments added
    RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary consumingSegmentToBeMovedSummary =
        isOfflineTable ? null
            : getConsumingSegmentSummary(tableConfig, newServersToConsumingSegmentMap, tableRebalanceLogger);
    RebalanceSummaryResult.SegmentInfo segmentInfo = new RebalanceSummaryResult.SegmentInfo(totalSegmentsToBeAdded,
        totalSegmentsToBeDeleted, maxSegmentsAddedToServer, averageSegmentSizeInBytes,
        totalEstimatedDataToBeMovedInBytes, replicationFactor, numSegmentsInSingleReplica,
        numSegmentsAcrossAllReplicas, consumingSegmentToBeMovedSummary);

    tableRebalanceLogger.info("Calculated rebalance summary");
    return new RebalanceSummaryResult(serverInfo, segmentInfo, new ArrayList<>(tagsInfoMap.values()));
  }

  private List<String> getServerTag(String serverName) {
    InstanceConfig instanceConfig =
        _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder().instanceConfig(serverName));
    return instanceConfig.getTags();
  }

  private RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary getConsumingSegmentSummary(TableConfig tableConfig,
      Map<String, Set<String>> newServersToConsumingSegmentMap, Logger tableRebalanceLogger) {
    String tableNameWithType = tableConfig.getTableName();
    if (newServersToConsumingSegmentMap.isEmpty()) {
      return new RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary(0, 0, new HashMap<>(), new HashMap<>(),
          new HashMap<>());
    }
    int numConsumingSegmentsToBeMoved =
        newServersToConsumingSegmentMap.values().stream().reduce(0, (a, b) -> a + b.size(), Integer::sum);
    Set<String> uniqueConsumingSegments =
        newServersToConsumingSegmentMap.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
    Map<String, SegmentZKMetadata> consumingSegmentZKmetadata = new HashMap<>();
    uniqueConsumingSegments.forEach(segment -> consumingSegmentZKmetadata.put(segment,
        ZKMetadataProvider.getSegmentZKMetadata(_helixManager.getHelixPropertyStore(), tableNameWithType, segment)));
    Map<String, Integer> consumingSegmentsOffsetsToCatchUp =
        getConsumingSegmentsOffsetsToCatchUp(tableConfig, consumingSegmentZKmetadata, tableRebalanceLogger);
    Map<String, Integer> consumingSegmentsAge =
        getConsumingSegmentsAge(consumingSegmentZKmetadata, tableRebalanceLogger);

    Map<String, Integer> consumingSegmentsOffsetsToCatchUpTopN;
    Map<String, RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary.ConsumingSegmentSummaryPerServer>
        consumingSegmentSummaryPerServer = new HashMap<>();
    if (consumingSegmentsOffsetsToCatchUp != null) {
      consumingSegmentsOffsetsToCatchUpTopN =
          getTopNConsumingSegmentWithValue(consumingSegmentsOffsetsToCatchUp, TOP_N_IN_CONSUMING_SEGMENT_SUMMARY);
      newServersToConsumingSegmentMap.forEach((server, segments) -> {
        int totalOffsetsToCatchUp =
            segments.stream().mapToInt(consumingSegmentsOffsetsToCatchUp::get).sum();
        consumingSegmentSummaryPerServer.put(server,
            new RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary.ConsumingSegmentSummaryPerServer(
                segments.size(), totalOffsetsToCatchUp));
      });
    } else {
      consumingSegmentsOffsetsToCatchUpTopN = null;
      newServersToConsumingSegmentMap.forEach((server, segments) -> {
        consumingSegmentSummaryPerServer.put(server,
            new RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary.ConsumingSegmentSummaryPerServer(
                segments.size(), -1));
      });
    }

    Map<String, Integer> consumingSegmentsOldestTopN =
        consumingSegmentsAge == null ? null
            : getTopNConsumingSegmentWithValue(consumingSegmentsAge, TOP_N_IN_CONSUMING_SEGMENT_SUMMARY);

    return new RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary(numConsumingSegmentsToBeMoved,
        newServersToConsumingSegmentMap.size(), consumingSegmentsOffsetsToCatchUpTopN, consumingSegmentsOldestTopN,
        consumingSegmentSummaryPerServer);
  }

  private static Map<String, Integer> getTopNConsumingSegmentWithValue(
      Map<String, Integer> consumingSegmentsWithValue, @Nullable Integer topN) {
    Map<String, Integer> topNConsumingSegments = new LinkedHashMap<>();
    consumingSegmentsWithValue.entrySet()
        .stream()
        .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
        .limit(topN == null ? consumingSegmentsWithValue.size() : topN)
        .forEach(entry -> topNConsumingSegments.put(entry.getKey(), entry.getValue()));
    return topNConsumingSegments;
  }

  /**
   * Fetches the age of each consuming segment in minutes.
   * The age of a consuming segment is the time since the segment was created in ZK, it could be different to when
   * the stream should start to be consumed for the segment.
   * consumingSegmentZKMetadata is a map from consuming segments to be moved to their ZK metadata. Returns a map from
   * segment name to the age of that consuming segment. Return null if failed to obtain info for any consuming segment.
   */
  @Nullable
  private Map<String, Integer> getConsumingSegmentsAge(Map<String, SegmentZKMetadata> consumingSegmentZKMetadata,
      Logger tableRebalanceLogger) {
    Map<String, Integer> consumingSegmentsAge = new HashMap<>();
    long now = System.currentTimeMillis();
    try {
      consumingSegmentZKMetadata.forEach(((s, segmentZKMetadata) -> {
        if (segmentZKMetadata == null) {
          tableRebalanceLogger.warn("SegmentZKMetadata is null for segment: {}", s);
          throw new RuntimeException("SegmentZKMetadata is null");
        }
        long creationTime = segmentZKMetadata.getCreationTime();
        if (creationTime < 0) {
          tableRebalanceLogger.warn("Creation time is not found for segment: {}", s);
          throw new RuntimeException("Creation time is not found");
        }
        consumingSegmentsAge.put(s, (int) (now - creationTime) / 60_000);
      }));
    } catch (Exception e) {
      return null;
    }
    return consumingSegmentsAge;
  }

  /**
   * Fetches the consuming segment info for the table and calculates the number of offsets to catch up for each
   * consuming segment. consumingSegmentZKMetadata is a map from consuming segments to be moved to their ZK metadata.
   * Returns a map from segment name to the number of offsets to catch up for that consuming
   * segment. Return null if failed to obtain info for any consuming segment.
   */
  @Nullable
  private Map<String, Integer> getConsumingSegmentsOffsetsToCatchUp(TableConfig tableConfig,
      Map<String, SegmentZKMetadata> consumingSegmentZKMetadata, Logger tableRebalanceLogger) {
    Map<String, Integer> segmentToOffsetsToCatchUp = new HashMap<>();
    try {
      for (Map.Entry<String, SegmentZKMetadata> entry : consumingSegmentZKMetadata.entrySet()) {
        String segmentName = entry.getKey();
        SegmentZKMetadata segmentZKMetadata = entry.getValue();
        if (segmentZKMetadata == null) {
          tableRebalanceLogger.warn("Cannot find SegmentZKMetadata for segment: {}", segmentName);
          return null;
        }
        String startOffset = segmentZKMetadata.getStartOffset();
        if (startOffset == null) {
          tableRebalanceLogger.warn("Start offset is null for segment: {}", segmentName);
          return null;
        }
        Integer partitionId = SegmentUtils.getPartitionIdFromSegmentName(segmentName);
        // for simplicity here we disable consuming segment info if they do not have partitionId in segmentName
        if (partitionId == null) {
          tableRebalanceLogger.warn("Cannot determine partition id for realtime segment: {}", segmentName);
          return null;
        }
        Integer latestOffset = getLatestOffsetOfStream(tableConfig, partitionId, tableRebalanceLogger);
        if (latestOffset == null) {
          return null;
        }
        int offsetsToCatchUp = latestOffset - Integer.parseInt(startOffset);
        segmentToOffsetsToCatchUp.put(segmentName, offsetsToCatchUp);
      }
    } catch (Exception e) {
      tableRebalanceLogger.warn("Caught exception while trying to fetch consuming segment info", e);
      return null;
    }
    tableRebalanceLogger.info("Successfully fetched consuming segments info");
    return segmentToOffsetsToCatchUp;
  }

  @VisibleForTesting
  StreamPartitionMsgOffset fetchStreamPartitionOffset(TableConfig tableConfig, int partitionId)
      throws Exception {
    StreamConsumerFactory streamConsumerFactory =
        StreamConsumerFactoryProvider.create(new StreamConfig(tableConfig.getTableName(),
            IngestionConfigUtils.getStreamConfigMap(tableConfig, partitionId)));
    try (StreamMetadataProvider streamMetadataProvider = streamConsumerFactory.createPartitionMetadataProvider(
        TableRebalancer.class.getCanonicalName(), partitionId)) {
      return streamMetadataProvider.fetchStreamPartitionOffset(OffsetCriteria.LARGEST_OFFSET_CRITERIA,
          STREAM_PARTITION_OFFSET_READ_TIMEOUT_MS);
    }
  }

  @Nullable
  private Integer getLatestOffsetOfStream(TableConfig tableConfig, int partitionId,
      Logger tableRebalanceLogger) {
    try {
      StreamPartitionMsgOffset partitionMsgOffset = fetchStreamPartitionOffset(tableConfig, partitionId);
      if (!(partitionMsgOffset instanceof LongMsgOffset)) {
        tableRebalanceLogger.warn("Unsupported stream partition message offset type: {}", partitionMsgOffset);
        return null;
      }
      return (int) ((LongMsgOffset) partitionMsgOffset).getOffset();
    } catch (Exception e) {
      tableRebalanceLogger.warn("Caught exception while trying to fetch stream partition of partitionId: {}",
          partitionId, e);
      return null;
    }
  }

  private void onReturnFailure(String errorMsg, Exception e, Logger tableRebalanceLogger) {
    if (e != null) {
      tableRebalanceLogger.warn(errorMsg, e);
    } else {
      tableRebalanceLogger.warn(errorMsg);
    }
    _tableRebalanceObserver.onError(errorMsg);
  }

  /**
   * This is called without the context of a rebalance job. Create a Logger without a jobId.
   */
  public Pair<Map<InstancePartitionsType, InstancePartitions>, Boolean> getInstancePartitionsMap(
      TableConfig tableConfig, boolean reassignInstances, boolean bootstrap, boolean dryRun) {
    return getInstancePartitionsMap(tableConfig, reassignInstances, bootstrap, dryRun, Enablement.DISABLE, LOGGER);
  }

  /**
   * Gets the instance partitions for instance partition types and also returns a boolean for whether they are unchanged
   */
  public Pair<Map<InstancePartitionsType, InstancePartitions>, Boolean> getInstancePartitionsMap(
      TableConfig tableConfig, boolean reassignInstances, boolean bootstrap, boolean dryRun,
      Enablement minimizeDataMovement, Logger tableRebalanceLogger) {
    boolean instancePartitionsUnchanged;
    Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap = new TreeMap<>();
    if (tableConfig.getTableType() == TableType.OFFLINE) {
      Pair<InstancePartitions, Boolean> partitionAndUnchangedForOffline =
          getInstancePartitions(tableConfig, InstancePartitionsType.OFFLINE, reassignInstances, bootstrap, dryRun,
              minimizeDataMovement, tableRebalanceLogger);
      instancePartitionsMap.put(InstancePartitionsType.OFFLINE, partitionAndUnchangedForOffline.getLeft());
      instancePartitionsUnchanged = partitionAndUnchangedForOffline.getRight();
    } else {
      Pair<InstancePartitions, Boolean> partitionAndUnchangedForConsuming =
          getInstancePartitions(tableConfig, InstancePartitionsType.CONSUMING, reassignInstances, bootstrap, dryRun,
              minimizeDataMovement, tableRebalanceLogger);
      instancePartitionsMap.put(InstancePartitionsType.CONSUMING, partitionAndUnchangedForConsuming.getLeft());
      instancePartitionsUnchanged = partitionAndUnchangedForConsuming.getRight();
      String tableNameWithType = tableConfig.getTableName();
      if (InstanceAssignmentConfigUtils.shouldRelocateCompletedSegments(tableConfig)) {
        Pair<InstancePartitions, Boolean> partitionAndUnchangedForCompleted =
            getInstancePartitions(tableConfig, InstancePartitionsType.COMPLETED, reassignInstances, bootstrap, dryRun,
                minimizeDataMovement, tableRebalanceLogger);
        tableRebalanceLogger.info(
            "COMPLETED segments should be relocated, fetching/computing COMPLETED instance partitions for table: {}",
            tableNameWithType);
        instancePartitionsMap.put(InstancePartitionsType.COMPLETED, partitionAndUnchangedForCompleted.getLeft());
        instancePartitionsUnchanged &= partitionAndUnchangedForCompleted.getRight();
      } else {
        tableRebalanceLogger.info(
            "COMPLETED segments should not be relocated, skipping fetching/computing COMPLETED instance partitions "
                + "for table: {}", tableNameWithType);
        if (!dryRun) {
          String instancePartitionsName = InstancePartitionsUtils.getInstancePartitionsName(tableNameWithType,
              InstancePartitionsType.COMPLETED.toString());
          tableRebalanceLogger.info("Removing instance partitions: {} from ZK if it exists", instancePartitionsName);
          InstancePartitionsUtils.removeInstancePartitions(_helixManager.getHelixPropertyStore(),
              instancePartitionsName);
        }
      }
    }
    return Pair.of(instancePartitionsMap, instancePartitionsUnchanged);
  }

  /**
   * Fetches/computes the instance partitions and also returns a boolean for whether they are unchanged
   */
  private Pair<InstancePartitions, Boolean> getInstancePartitions(TableConfig tableConfig,
      InstancePartitionsType instancePartitionsType, boolean reassignInstances, boolean bootstrap, boolean dryRun,
      Enablement minimizeDataMovement, Logger tableRebalanceLogger) {
    String tableNameWithType = tableConfig.getTableName();
    String instancePartitionsName =
        InstancePartitionsUtils.getInstancePartitionsName(tableNameWithType, instancePartitionsType.toString());
    InstancePartitions existingInstancePartitions =
        InstancePartitionsUtils.fetchInstancePartitions(_helixManager.getHelixPropertyStore(), instancePartitionsName);

    if (reassignInstances) {
      if (InstanceAssignmentConfigUtils.allowInstanceAssignment(tableConfig, instancePartitionsType)) {
        boolean hasPreConfiguredInstancePartitions =
            InstancePartitionsUtils.hasPreConfiguredInstancePartitions(tableConfig, instancePartitionsType);
        boolean isPreConfigurationBasedAssignment =
            InstanceAssignmentConfigUtils.isMirrorServerSetAssignment(tableConfig, instancePartitionsType);
        InstanceAssignmentDriver instanceAssignmentDriver = new InstanceAssignmentDriver(tableConfig);
        InstancePartitions instancePartitions;
        boolean instancePartitionsUnchanged;
        if (!hasPreConfiguredInstancePartitions) {
          tableRebalanceLogger.info("Reassigning {} instances for table: {}", instancePartitionsType,
              tableNameWithType);
          // Assign instances with existing instance partition to null if bootstrap mode is enabled, so that the
          // instance partition map can be fully recalculated.
          instancePartitions = instanceAssignmentDriver.assignInstances(instancePartitionsType,
              _helixDataAccessor.getChildValues(_helixDataAccessor.keyBuilder().instanceConfigs(), true),
              bootstrap ? null : existingInstancePartitions, minimizeDataMovement);
          instancePartitionsUnchanged = instancePartitions.equals(existingInstancePartitions);
          if (!dryRun && !instancePartitionsUnchanged) {
            tableRebalanceLogger.info("Persisting instance partitions: {} to ZK", instancePartitions);
            InstancePartitionsUtils.persistInstancePartitions(_helixManager.getHelixPropertyStore(),
                instancePartitions);
          }
        } else {
          String referenceInstancePartitionsName = tableConfig.getInstancePartitionsMap().get(instancePartitionsType);
          if (isPreConfigurationBasedAssignment) {
            InstancePartitions preConfiguredInstancePartitions =
                InstancePartitionsUtils.fetchInstancePartitionsWithRename(_helixManager.getHelixPropertyStore(),
                    referenceInstancePartitionsName, instancePartitionsName);
            instancePartitions = instanceAssignmentDriver.assignInstances(instancePartitionsType,
                _helixDataAccessor.getChildValues(_helixDataAccessor.keyBuilder().instanceConfigs(), true),
                bootstrap ? null : existingInstancePartitions, preConfiguredInstancePartitions,
                minimizeDataMovement);
            instancePartitionsUnchanged = instancePartitions.equals(existingInstancePartitions);
            if (!dryRun && !instancePartitionsUnchanged) {
              tableRebalanceLogger.info("Persisting instance partitions: {} (based on {})", instancePartitions,
                  preConfiguredInstancePartitions);
              InstancePartitionsUtils.persistInstancePartitions(_helixManager.getHelixPropertyStore(),
                  instancePartitions);
            }
          } else {
            instancePartitions =
                InstancePartitionsUtils.fetchInstancePartitionsWithRename(_helixManager.getHelixPropertyStore(),
                    referenceInstancePartitionsName, instancePartitionsName);
            instancePartitionsUnchanged = instancePartitions.equals(existingInstancePartitions);
            if (!dryRun && !instancePartitionsUnchanged) {
              tableRebalanceLogger.info("Persisting instance partitions: {} (referencing {})", instancePartitions,
                  referenceInstancePartitionsName);
              InstancePartitionsUtils.persistInstancePartitions(_helixManager.getHelixPropertyStore(),
                  instancePartitions);
            }
          }
        }
        return Pair.of(instancePartitions, instancePartitionsUnchanged);
      } else {
        tableRebalanceLogger.info(
            "{} instance assignment is not allowed, using default instance partitions for table: {}",
            instancePartitionsType, tableNameWithType);
        InstancePartitions instancePartitions =
            InstancePartitionsUtils.computeDefaultInstancePartitions(_helixManager, tableConfig,
                instancePartitionsType);
        boolean noExistingInstancePartitions = existingInstancePartitions == null;
        if (!dryRun && !noExistingInstancePartitions) {
          tableRebalanceLogger.info("Removing instance partitions: {} from ZK", instancePartitionsName);
          InstancePartitionsUtils.removeInstancePartitions(_helixManager.getHelixPropertyStore(),
              instancePartitionsName);
        }
        return Pair.of(instancePartitions, noExistingInstancePartitions);
      }
    } else {
      tableRebalanceLogger.info("Fetching/computing {} instance partitions for table: {}", instancePartitionsType,
          tableNameWithType);
      return Pair.of(
          InstancePartitionsUtils.fetchOrComputeInstancePartitions(_helixManager, tableConfig, instancePartitionsType),
          true);
    }
  }

  @Nullable
  private List<Tier> getSortedTiers(TableConfig tableConfig,
      @Nullable Map<String, Set<String>> providedTierToSegmentsMap) {
    List<TierConfig> tierConfigs = tableConfig.getTierConfigsList();
    if (CollectionUtils.isNotEmpty(tierConfigs)) {
      // Get tiers with storageType = "PINOT_SERVER". This is the only type available right now.
      // Other types should be treated differently
      return TierConfigUtils.getSortedTiersForStorageType(tierConfigs, TierFactory.PINOT_SERVER_STORAGE_TYPE,
          providedTierToSegmentsMap);
    } else {
      return null;
    }
  }

  /**
   * Fetches/computes the instance partitions for sorted tiers and also returns a boolean for whether the
   * instance partitions are unchanged.
   */
  private Pair<Map<String, InstancePartitions>, Boolean> getTierToInstancePartitionsMap(TableConfig tableConfig,
      @Nullable List<Tier> sortedTiers, boolean reassignInstances, boolean bootstrap, boolean dryRun,
      Enablement minimizeDataMovement, Logger tableRebalanceLogger) {
    if (sortedTiers == null) {
      return Pair.of(null, true);
    }
    boolean instancePartitionsUnchanged = true;
    Map<String, InstancePartitions> tierToInstancePartitionsMap = new HashMap<>();
    for (Tier tier : sortedTiers) {
      tableRebalanceLogger.info("Fetching/computing instance partitions for tier: {} of table: {}", tier.getName(),
          tableConfig.getTableName());
      Pair<InstancePartitions, Boolean> partitionsAndUnchanged =
          getInstancePartitionsForTier(tableConfig, tier, reassignInstances, bootstrap, dryRun, minimizeDataMovement,
              tableRebalanceLogger);
      tierToInstancePartitionsMap.put(tier.getName(), partitionsAndUnchanged.getLeft());
      instancePartitionsUnchanged = instancePartitionsUnchanged && partitionsAndUnchanged.getRight();
    }
    return Pair.of(tierToInstancePartitionsMap, instancePartitionsUnchanged);
  }

  /**
   * Computes the instance partitions for the given tier. If table's instanceAssignmentConfigMap has an entry for the
   * tier, it's used to calculate the instance partitions. Else default instance partitions are returned. Also returns
   * a boolean for whether the instance partition is unchanged.
   */
  private Pair<InstancePartitions, Boolean> getInstancePartitionsForTier(TableConfig tableConfig, Tier tier,
      boolean reassignInstances, boolean bootstrap, boolean dryRun, Enablement minimizeDataMovement,
      Logger tableRebalanceLogger) {
    String tableNameWithType = tableConfig.getTableName();
    String tierName = tier.getName();
    String instancePartitionsName =
        InstancePartitionsUtils.getInstancePartitionsNameForTier(tableNameWithType, tierName);
    InstancePartitions existingInstancePartitions =
        InstancePartitionsUtils.fetchInstancePartitions(_helixManager.getHelixPropertyStore(), instancePartitionsName);

    if (reassignInstances) {
      Map<String, InstanceAssignmentConfig> instanceAssignmentConfigMap = tableConfig.getInstanceAssignmentConfigMap();
      InstanceAssignmentConfig instanceAssignmentConfig =
          instanceAssignmentConfigMap != null ? instanceAssignmentConfigMap.get(tierName) : null;
      if (instanceAssignmentConfig == null) {
        tableRebalanceLogger.info(
            "Instance assignment config for tier: {} does not exist for table: {}, using default instance partitions",
            tierName, tableNameWithType);
        PinotServerTierStorage storage = (PinotServerTierStorage) tier.getStorage();
        InstancePartitions instancePartitions =
            InstancePartitionsUtils.computeDefaultInstancePartitionsForTag(_helixManager, tableConfig, tierName,
                storage.getServerTag());
        boolean noExistingInstancePartitions = existingInstancePartitions == null;
        if (!dryRun && !noExistingInstancePartitions) {
          tableRebalanceLogger.info("Removing instance partitions: {} from ZK", instancePartitionsName);
          InstancePartitionsUtils.removeInstancePartitions(_helixManager.getHelixPropertyStore(),
              instancePartitionsName);
        }
        return Pair.of(instancePartitions, noExistingInstancePartitions);
      } else {
        InstanceAssignmentDriver instanceAssignmentDriver = new InstanceAssignmentDriver(tableConfig);
        // Assign instances with existing instance partition to null if bootstrap mode is enabled, so that the instance
        // partition map can be fully recalculated.
        InstancePartitions instancePartitions = instanceAssignmentDriver.assignInstances(tierName,
            _helixDataAccessor.getChildValues(_helixDataAccessor.keyBuilder().instanceConfigs(), true),
            bootstrap ? null : existingInstancePartitions, instanceAssignmentConfig, minimizeDataMovement);
        boolean instancePartitionsUnchanged = instancePartitions.equals(existingInstancePartitions);
        if (!dryRun && !instancePartitionsUnchanged) {
          tableRebalanceLogger.info("Persisting instance partitions: {} to ZK", instancePartitions);
          InstancePartitionsUtils.persistInstancePartitions(_helixManager.getHelixPropertyStore(), instancePartitions);
        }
        return Pair.of(instancePartitions, instancePartitionsUnchanged);
      }
    } else {
      if (existingInstancePartitions != null) {
        return Pair.of(existingInstancePartitions, true);
      } else {
        PinotServerTierStorage storage = (PinotServerTierStorage) tier.getStorage();
        InstancePartitions instancePartitions =
            InstancePartitionsUtils.computeDefaultInstancePartitionsForTag(_helixManager, tableConfig, tierName,
                storage.getServerTag());
        return Pair.of(instancePartitions, true);
      }
    }
  }

  private IdealState waitForExternalViewToConverge(String tableNameWithType, boolean lowDiskMode, boolean bestEfforts,
      Set<String> segmentsToMonitor, long externalViewCheckIntervalInMs, long externalViewStabilizationTimeoutInMs,
      long estimateAverageSegmentSizeInBytes, Set<String> allSegmentsFromIdealState,
      Logger tableRebalanceLogger)
      throws InterruptedException, TimeoutException {
    long startTimeMs = System.currentTimeMillis();
    long endTimeMs = startTimeMs + externalViewStabilizationTimeoutInMs;
    int extensionCount = 0;

    IdealState idealState;
    ExternalView externalView;
    int previousRemainingSegments = -1;
    tableRebalanceLogger.info("Starting EV-IS convergence check loop, {} unique segments to monitor in current step",
        segmentsToMonitor.size());
    while (true) {
      do {
        tableRebalanceLogger.debug("Start to check if ExternalView converges to IdealStates");
        idealState = _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder().idealStates(tableNameWithType));
        // IdealState might be null if table got deleted, throwing exception to abort the rebalance
        Preconditions.checkState(idealState != null, "Failed to find the IdealState");

        externalView = _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder().externalView(tableNameWithType));
        // ExternalView might be null when table is just created, skipping check for this iteration
        if (externalView != null) {
          // Record external view and ideal state convergence status
          TableRebalanceObserver.RebalanceContext rebalanceContext = new TableRebalanceObserver.RebalanceContext(
              estimateAverageSegmentSizeInBytes, allSegmentsFromIdealState, segmentsToMonitor);
          _tableRebalanceObserver.onTrigger(
              TableRebalanceObserver.Trigger.EXTERNAL_VIEW_TO_IDEAL_STATE_CONVERGENCE_TRIGGER,
              externalView.getRecord().getMapFields(), idealState.getRecord().getMapFields(), rebalanceContext);
          // Update unique segment list as IS-EV trigger must have processed these
          allSegmentsFromIdealState = idealState.getRecord().getMapFields().keySet();
          if (_tableRebalanceObserver.isStopped()) {
            throw new RuntimeException(
                String.format("Rebalance has already stopped with status: %s",
                    _tableRebalanceObserver.getStopStatus()));
          }
          if (isExternalViewConverged(externalView.getRecord().getMapFields(), idealState.getRecord().getMapFields(),
              lowDiskMode, bestEfforts, segmentsToMonitor, tableRebalanceLogger)) {
            tableRebalanceLogger.info("ExternalView converged in {}ms, with {} extensions",
                System.currentTimeMillis() - startTimeMs, extensionCount);
            return idealState;
          }
          if (previousRemainingSegments < 0) {
            // initialize previousRemainingSegments
            previousRemainingSegments = getNumRemainingSegmentReplicasToProcess(externalView.getRecord().getMapFields(),
                idealState.getRecord().getMapFields(), lowDiskMode, bestEfforts, segmentsToMonitor,
                tableRebalanceLogger, false);
            tableRebalanceLogger.info("Remaining {} segment replicas to be processed.", previousRemainingSegments);
          }
        }
        tableRebalanceLogger.debug("ExternalView has not converged to IdealStates. Retry after: {}ms",
            externalViewCheckIntervalInMs);
        Thread.sleep(externalViewCheckIntervalInMs);
      } while (System.currentTimeMillis() < endTimeMs);

      if (externalView == null) {
        tableRebalanceLogger.warn("ExternalView is null, will not extend the EV stabilization timeout.");
        throw new TimeoutException(
            String.format("ExternalView is null, cannot wait for it to converge within %dms",
                externalViewStabilizationTimeoutInMs));
      }

      int currentRemainingSegments = getNumRemainingSegmentReplicasToProcess(externalView.getRecord().getMapFields(),
          idealState.getRecord().getMapFields(), lowDiskMode, bestEfforts, segmentsToMonitor, tableRebalanceLogger,
          false);

      // It is possible that remainingSegments increases so that currentRemainingSegments > previousRemainingSegments,
      // likely due to CONSUMING segments committing, where the state of the segment change to ONLINE. Therefore, if
      // the segment had converged, it then becomes un-converged and thus increases the count.
      if (currentRemainingSegments >= previousRemainingSegments) {
        if (bestEfforts) {
          tableRebalanceLogger.warn(
              "ExternalView has not made progress for the last {}ms, stop waiting after spending {}ms waiting ({} "
                  + "extensions), continuing the rebalance (best-efforts)",
              externalViewStabilizationTimeoutInMs, System.currentTimeMillis() - startTimeMs, extensionCount);
          return idealState;
        }
        throw new TimeoutException(
            String.format(
                "ExternalView has not made progress for the last %dms, timeout after spending %dms waiting (%d "
                    + "extensions)", externalViewStabilizationTimeoutInMs, System.currentTimeMillis() - startTimeMs,
                extensionCount));
      }

      tableRebalanceLogger.info(
          "Extending EV stabilization timeout for another {}ms, remaining {} segment replicas to be processed. "
              + "(Extension count: {})",
          externalViewStabilizationTimeoutInMs, currentRemainingSegments, ++extensionCount);
      previousRemainingSegments = currentRemainingSegments;
      endTimeMs = System.currentTimeMillis() + externalViewStabilizationTimeoutInMs;
    }
  }

  @VisibleForTesting
  static boolean isExternalViewConverged(Map<String, Map<String, String>> externalViewSegmentStates,
      Map<String, Map<String, String>> idealStateSegmentStates, boolean lowDiskMode, boolean bestEfforts,
      @Nullable Set<String> segmentsToMonitor) {
    return getNumRemainingSegmentReplicasToProcess(externalViewSegmentStates, idealStateSegmentStates, lowDiskMode,
        bestEfforts, segmentsToMonitor, LOGGER, true) == 0;
  }

  /**
   * Check if the external view has converged to the ideal state. See `getNumRemainingSegmentReplicasToProcess` for
   * details on how the convergence is determined.
   */
  private static boolean isExternalViewConverged(Map<String, Map<String, String>> externalViewSegmentStates,
      Map<String, Map<String, String>> idealStateSegmentStates, boolean lowDiskMode, boolean bestEfforts,
      @Nullable Set<String> segmentsToMonitor, Logger tableRebalanceLogger) {
    return getNumRemainingSegmentReplicasToProcess(externalViewSegmentStates, idealStateSegmentStates, lowDiskMode,
        bestEfforts, segmentsToMonitor, tableRebalanceLogger, true) == 0;
  }

  @VisibleForTesting
  static int getNumRemainingSegmentReplicasToProcess(Map<String, Map<String, String>> externalViewSegmentStates,
      Map<String, Map<String, String>> idealStateSegmentStates, boolean lowDiskMode, boolean bestEfforts,
      @Nullable Set<String> segmentsToMonitor) {
    return getNumRemainingSegmentReplicasToProcess(externalViewSegmentStates, idealStateSegmentStates, lowDiskMode,
        bestEfforts, segmentsToMonitor, LOGGER, false);
  }

  /**
   * If `earlyReturn=false`, it returns the number of segment replicas that are not in the expected state.
   * If `earlyReturn=true` it returns 1 if the number of said segment replicas are more than 0, returns 0 otherwise,
   * which is used to check whether the ExternalView has converged to the IdealState.
   * <p>
   * The method checks the following:
   * Only the segments in the IdealState and being monitored. Extra segments in ExternalView are ignored
   * because they are not managed by the rebalancer.
   * For each segment, go through instances in the instance map from IdealState and compare it with the one in
   * ExternalView, and increment the number of remaining segment replicas to process if:
   * <ul>
   * <li> The instance appears in IS instance map, but there is no instance map in EV, unless the IS instance state is
   *   OFFLINE
   * <li> The instance appears in IS instance map is not in the EV instance map, unless the IS instance state is OFFLINE
   * <li> The instance has different states between IS and EV instance map, unless the IS instance state is OFFLINE
   * </ul>
   * <p>
   * If `lowDiskMode=true`, go through the instance map from ExternalView and compare it with the one in IdealState,
   * and also increment the number of remaining segment replicas to process if the instance appears in EV instance map
   * does not appear in the IS instance map.
   * <p>
   * Once there's an ERROR state for any instance in ExternalView, throw an exception to abort the rebalance because
   * we are not able to get out of the ERROR state, unless `bestEfforts=true`, in which case, log a warning and keep
   * going as if that instance has converged.
   */
  private static int getNumRemainingSegmentReplicasToProcess(Map<String, Map<String, String>> externalViewSegmentStates,
      Map<String, Map<String, String>> idealStateSegmentStates, boolean lowDiskMode, boolean bestEfforts,
      @Nullable Set<String> segmentsToMonitor, Logger tableRebalanceLogger, boolean earlyReturn) {
    int remainingSegmentReplicasToProcess = 0;
    for (Map.Entry<String, Map<String, String>> entry : idealStateSegmentStates.entrySet()) {
      String segmentName = entry.getKey();
      if (segmentsToMonitor != null && !segmentsToMonitor.contains(segmentName)) {
        continue;
      }
      Map<String, String> externalViewInstanceStateMap = externalViewSegmentStates.get(segmentName);
      Map<String, String> idealStateInstanceStateMap = entry.getValue();

      for (Map.Entry<String, String> instanceStateEntry : idealStateInstanceStateMap.entrySet()) {
        // Ignore OFFLINE state in IdealState
        String idealStateInstanceState = instanceStateEntry.getValue();
        if (idealStateInstanceState.equals(SegmentStateModel.OFFLINE)) {
          continue;
        }

        // If the segment has not shown up in ExternalView, it is not added yet
        if (externalViewInstanceStateMap == null) {
          remainingSegmentReplicasToProcess++;
          if (earlyReturn) {
            return remainingSegmentReplicasToProcess;
          }
          continue;
        }

        // Check whether the instance state in ExternalView matches the IdealState
        String instanceName = instanceStateEntry.getKey();
        String externalViewInstanceState = externalViewInstanceStateMap.get(instanceName);
        if (!idealStateInstanceState.equals(externalViewInstanceState)) {
          if (SegmentStateModel.ERROR.equals(externalViewInstanceState)) {
            handleErrorInstance(segmentName, instanceName, bestEfforts, tableRebalanceLogger);
          } else {
            // The segment has been added, but not yet converged to the expected state
            remainingSegmentReplicasToProcess++;
            if (earlyReturn) {
              return remainingSegmentReplicasToProcess;
            }
          }
        }
      }

      // For low disk mode, check if there are extra instances in ExternalView that are not in IdealState
      if (lowDiskMode && externalViewInstanceStateMap != null) {
        for (Map.Entry<String, String> instanceStateEntry : externalViewInstanceStateMap.entrySet()) {
          String instanceName = instanceStateEntry.getKey();
          if (idealStateInstanceStateMap.containsKey(instanceName)) {
            continue;
          }
          if (SegmentStateModel.ERROR.equals(instanceStateEntry.getValue())) {
            handleErrorInstance(segmentName, instanceName, bestEfforts, tableRebalanceLogger);
          } else {
            // The segment should be deleted but still exists in ExternalView
            remainingSegmentReplicasToProcess++;
            if (earlyReturn) {
              return remainingSegmentReplicasToProcess;
            }
          }
        }
      }
    }
    return remainingSegmentReplicasToProcess;
  }

  private static void handleErrorInstance(String segmentName, String instanceName, boolean bestEfforts,
      Logger tableRebalanceLogger) {
    if (bestEfforts) {
      tableRebalanceLogger.warn(
          "Found ERROR instance: {} for segment: {}, counting it as good state (best-efforts)",
          instanceName, segmentName);
    } else {
      tableRebalanceLogger.warn("Found ERROR instance: {} for segment: {}", instanceName, segmentName);
      throw new IllegalStateException("Found segments in ERROR state");
    }
  }

  /**
   * Uses the default LOGGER
   */
  @VisibleForTesting
  static Map<String, Map<String, String>> getNextAssignment(Map<String, Map<String, String>> currentAssignment,
      Map<String, Map<String, String>> targetAssignment, int minAvailableReplicas, boolean enableStrictReplicaGroup,
      boolean lowDiskMode, int batchSizePerServer, Object2IntOpenHashMap<String> segmentPartitionIdMap,
      PartitionIdFetcher partitionIdFetcher) {
    return getNextAssignment(currentAssignment, targetAssignment, minAvailableReplicas, enableStrictReplicaGroup,
        lowDiskMode, batchSizePerServer, segmentPartitionIdMap, partitionIdFetcher, LOGGER);
  }

  /**
   * Returns the next assignment for the table based on the current assignment and the target assignment with regard to
   * the minimum available replicas requirement. For strict replica-group mode, track the available instances for all
   * the segments with the same instances in the next assignment, and ensure the minimum available replicas requirement
   * is met. If adding the assignment for a segment breaks the requirement, use the current assignment for the segment.
   * <p>
   * For strict replica group routing only (where the segment assignment is not StrictRealtimeSegmentAssignment)
   * if batching is enabled, the instances assigned for the same partitionId can be different for different segments.
   * For strict replica group routing with StrictRealtimeSegmentAssignment on the other hand, the assignment for a given
   * partitionId will be the same across all segments. We can treat both cases similarly by creating a mapping from
   * partitionId -> unique set of instance assignments -> currentAssignment. With StrictRealtimeSegmentAssignment,
   * this map will have a single entry for 'unique set of instance assignments'.
   * <p>
   * TODO: Ideally if strict replica group routing is enabled then StrictRealtimeSegmentAssignment should be used, but
   *       this is not enforced in the code today. Once enforcement is added, then the routing side and assignment side
   *       will be equivalent and all segments belonging to a given partitionId will be assigned to the same set of
   *       instances. Special handling to check each group of assigned instances can be removed in that case. The
   *       strict replica group routing can also be utilized for OFFLINE tables, thus StrictRealtimeSegmentAssignment
   *       also needs to be made more generic for the OFFLINE case.
   */
  private static Map<String, Map<String, String>> getNextAssignment(Map<String, Map<String, String>> currentAssignment,
      Map<String, Map<String, String>> targetAssignment, int minAvailableReplicas, boolean enableStrictReplicaGroup,
      boolean lowDiskMode, int batchSizePerServer, Object2IntOpenHashMap<String> segmentPartitionIdMap,
      PartitionIdFetcher partitionIdFetcher, Logger tableRebalanceLogger) {
    return enableStrictReplicaGroup
        ? getNextStrictReplicaGroupAssignment(currentAssignment, targetAssignment, minAvailableReplicas, lowDiskMode,
        batchSizePerServer, segmentPartitionIdMap, partitionIdFetcher, tableRebalanceLogger)
        : getNextNonStrictReplicaGroupAssignment(currentAssignment, targetAssignment, minAvailableReplicas,
            lowDiskMode, batchSizePerServer);
  }

  private static Map<String, Map<String, String>> getNextStrictReplicaGroupAssignment(
      Map<String, Map<String, String>> currentAssignment, Map<String, Map<String, String>> targetAssignment,
      int minAvailableReplicas, boolean lowDiskMode, int batchSizePerServer,
      Object2IntOpenHashMap<String> segmentPartitionIdMap, PartitionIdFetcher partitionIdFetcher,
      Logger tableRebalanceLogger) {
    Map<String, Map<String, String>> nextAssignment = new TreeMap<>();
    Map<String, Integer> numSegmentsToOffloadMap = getNumSegmentsToOffloadMap(currentAssignment, targetAssignment);
    Map<Pair<Set<String>, Set<String>>, Set<String>> assignmentMap = new HashMap<>();
    Map<Set<String>, Set<String>> availableInstancesMap = new HashMap<>();
    Map<String, Integer> serverToNumSegmentsAddedSoFar = new HashMap<>();

    if (batchSizePerServer == RebalanceConfig.DISABLE_BATCH_SIZE_PER_SERVER) {
      // Directly update the nextAssignment with anyServerExhaustedBatchSize = false and return if batching is disabled
      updateNextAssignmentForPartitionIdStrictReplicaGroup(currentAssignment, targetAssignment, nextAssignment,
          false, minAvailableReplicas, lowDiskMode, numSegmentsToOffloadMap, assignmentMap,
          availableInstancesMap, serverToNumSegmentsAddedSoFar);
      return nextAssignment;
    }

    // Batching is enabled, calculate the Pair(current instances, target instances) -> partitionId -> currentAssignment
    Map<Pair<Set<String>, Set<String>>, Map<Integer, Map<String, Map<String, String>>>>
        currentAndTargetInstancesToPartitionIdToCurrentAssignmentMap =
        getCurrentAndTargetInstancesToPartitionIdToCurrentAssignmentMap(currentAssignment, targetAssignment,
            segmentPartitionIdMap, partitionIdFetcher);

    // Iterating over the unique pairs of current and target instances
    for (Map<Integer, Map<String, Map<String, String>>> partitionIdToCurrentAssignment
        : currentAndTargetInstancesToPartitionIdToCurrentAssignmentMap.values()) {
      // Check if the servers of the first assignment for each unique partition has any space left to move the
      // segments assigned to the partition and unique assigned instances as a whole. If so, let's mark the partitions
      // as to be moved, otherwise we mark the partition as a whole as not moveable.
      // Iterating over the partitionIds with the same unique pair of current and assigned instances
      for (Map<String, Map<String, String>> curAssignment : partitionIdToCurrentAssignment.values()) {
        Map.Entry<String, Map<String, String>> firstEntry = curAssignment.entrySet().iterator().next();
        // It is enough to check for whether any server for one segment is above the limit or not since all segments
        // in curAssignment will have the same current and target instances and same partitionId
        Map<String, String> firstEntryInstanceStateMap = firstEntry.getValue();
        SingleSegmentAssignment firstAssignment =
            getNextSingleSegmentAssignment(firstEntryInstanceStateMap, targetAssignment.get(firstEntry.getKey()),
                minAvailableReplicas, lowDiskMode, numSegmentsToOffloadMap, assignmentMap);
        Set<String> serversAdded = getServersAddedInSingleSegmentAssignment(firstEntryInstanceStateMap,
            firstAssignment._instanceStateMap);
        boolean anyServerExhaustedBatchSize = false;
        for (String server : serversAdded) {
          int segmentsAddedToServerSoFar = serverToNumSegmentsAddedSoFar.getOrDefault(server, 0);
          // Case I: We already exceeded the batchSizePerServer for this server, cannot add any more segments
          // Case II: We have not yet exceeded the batchSizePerServer for this server, but we don't have sufficient
          // space to host the segments for this assignment on the server, and we have allocated some partitions so
          // far. If the batchSizePerServer is less than the number of segments in a given partitionId, we must host
          // at least 1 partition and exceed the batchSizePerServer to ensure progress is made. Thus, performing this
          // check only if segmentsAddedToServerSoFar > 0 is necessary.
          if ((segmentsAddedToServerSoFar >= batchSizePerServer)
              || (segmentsAddedToServerSoFar > 0
              && (segmentsAddedToServerSoFar + curAssignment.size()) > batchSizePerServer)) {
            anyServerExhaustedBatchSize = true;
            break;
          }
        }
        updateNextAssignmentForPartitionIdStrictReplicaGroup(curAssignment, targetAssignment, nextAssignment,
            anyServerExhaustedBatchSize, minAvailableReplicas, lowDiskMode, numSegmentsToOffloadMap, assignmentMap,
            availableInstancesMap, serverToNumSegmentsAddedSoFar);
      }
    }

    checkIfAnyServersAssignedMoreSegmentsThanBatchSize(batchSizePerServer, serverToNumSegmentsAddedSoFar,
        tableRebalanceLogger);
    return nextAssignment;
  }

  private static void updateNextAssignmentForPartitionIdStrictReplicaGroup(
      Map<String, Map<String, String>> currentAssignment, Map<String, Map<String, String>> targetAssignment,
      Map<String, Map<String, String>> nextAssignment, boolean anyServerExhaustedBatchSize, int minAvailableReplicas,
      boolean lowDiskMode, Map<String, Integer> numSegmentsToOffloadMap,
      Map<Pair<Set<String>, Set<String>>, Set<String>> assignmentMap,
      Map<Set<String>, Set<String>> availableInstancesMap, Map<String, Integer> serverToNumSegmentsAddedSoFar) {
    if (anyServerExhaustedBatchSize) {
      // Exhausted the batch size for at least 1 server, just copy over the remaining segments as is
      nextAssignment.putAll(currentAssignment);
    } else {
      // Process all the partitionIds even if segmentsAddedSoFar becomes larger than batchSizePerServer
      // Can only do bestEfforts w.r.t. StrictReplicaGroup since a whole partition must be moved together for
      // maintaining consistency
      for (Map.Entry<String, Map<String, String>> entry : currentAssignment.entrySet()) {
        String segmentName = entry.getKey();
        Map<String, String> currentInstanceStateMap = entry.getValue();
        Map<String, String> targetInstanceStateMap = targetAssignment.get(segmentName);
        SingleSegmentAssignment assignment =
            getNextSingleSegmentAssignment(currentInstanceStateMap, targetInstanceStateMap, minAvailableReplicas,
                lowDiskMode, numSegmentsToOffloadMap, assignmentMap);
        Set<String> assignedInstances = assignment._instanceStateMap.keySet();
        Set<String> availableInstances = assignment._availableInstances;
        availableInstancesMap.compute(assignedInstances, (k, currentAvailableInstances) -> {
          if (currentAvailableInstances == null) {
            // First segment assigned to these instances, use the new assignment and update the available instances
            nextAssignment.put(segmentName, assignment._instanceStateMap);
            updateNumSegmentsToOffloadMap(numSegmentsToOffloadMap, currentInstanceStateMap.keySet(), k);
            return availableInstances;
          } else {
            // There are other segments assigned to the same instances, check the available instances to see if
            // adding the new assignment can still hold the minimum available replicas requirement
            availableInstances.retainAll(currentAvailableInstances);
            if (availableInstances.size() >= minAvailableReplicas) {
              // New assignment can be added
              nextAssignment.put(segmentName, assignment._instanceStateMap);
              updateNumSegmentsToOffloadMap(numSegmentsToOffloadMap, currentInstanceStateMap.keySet(), k);
              return availableInstances;
            } else {
              // New assignment cannot be added, use the current instance state map
              nextAssignment.put(segmentName, currentInstanceStateMap);
              return currentAvailableInstances;
            }
          }
        });

        if (!nextAssignment.get(segmentName).equals(currentInstanceStateMap)) {
          Set<String> serversAddedForSegment = getServersAddedInSingleSegmentAssignment(currentInstanceStateMap,
              nextAssignment.get(segmentName));
          serversAddedForSegment.forEach(server -> serverToNumSegmentsAddedSoFar.merge(server, 1, Integer::sum));
        }
      }
    }
  }

  private static void checkIfAnyServersAssignedMoreSegmentsThanBatchSize(int batchSizePerServer,
      Map<String, Integer> serverToNumSegmentsAddedSoFar, Logger tableRebalanceLogger) {
    int maxSegmentsAddedToAnyServer = serverToNumSegmentsAddedSoFar.isEmpty() ? 0
        : Collections.max(serverToNumSegmentsAddedSoFar.values());
    if (maxSegmentsAddedToAnyServer > batchSizePerServer) {
      tableRebalanceLogger.warn("Found at least one server with {} segments added which is larger than "
          + "batchSizePerServer: {}. This is expected for strictReplicaGroup based assignment that needs to move a "
          + "full partition to maintain consistency for queries.", maxSegmentsAddedToAnyServer, batchSizePerServer);
    }
  }

  /**
   * Create a mapping of Pair(currentInstances, targetInstances) to partitionId to the current assignment of segments.
   * This is to be used for batching purposes for StrictReplicaGroup routing, for all segment assignment types:
   * RealtimeSegmentAssignment, StrictRealtimeSegmentAssignment and OfflineSegmentAssignment
   * @param currentAssignment the current assignment
   * @param targetAssignment the target assignment
   * @param segmentPartitionIdMap cache to store the partition ids to avoid fetching ZK segment metadata
   * @param partitionIdFetcher function to fetch the partition id
   * @return a mapping from Pair(currentInstances, targetInstances) to the partitionId to the segment assignment map of
   *         all segments that fall in that category
   */
  private static Map<Pair<Set<String>, Set<String>>, Map<Integer, Map<String, Map<String, String>>>>
  getCurrentAndTargetInstancesToPartitionIdToCurrentAssignmentMap(Map<String, Map<String, String>> currentAssignment,
      Map<String, Map<String, String>> targetAssignment, Object2IntOpenHashMap<String> segmentPartitionIdMap,
      PartitionIdFetcher partitionIdFetcher) {
    Map<Pair<Set<String>, Set<String>>, Map<Integer, Map<String, Map<String, String>>>>
        currentAndTargetInstancesToPartitionIdToCurrentAssignmentMap = new HashMap<>();

    for (Map.Entry<String, Map<String, String>> assignment : currentAssignment.entrySet()) {
      String segmentName = assignment.getKey();
      Map<String, String> currentInstanceStateMap = assignment.getValue();
      Map<String, String> targetInstanceStateMap = targetAssignment.get(segmentName);

      int partitionId =
          segmentPartitionIdMap.computeIfAbsent(segmentName, v -> partitionIdFetcher.fetch(segmentName));
      Pair<Set<String>, Set<String>> currentAndTargetInstances =
          Pair.of(currentInstanceStateMap.keySet(), targetInstanceStateMap.keySet());
      currentAndTargetInstancesToPartitionIdToCurrentAssignmentMap
          .computeIfAbsent(currentAndTargetInstances, k -> new TreeMap<>())
          .computeIfAbsent(partitionId, k -> new TreeMap<>()).put(segmentName, currentInstanceStateMap);
    }

    return currentAndTargetInstancesToPartitionIdToCurrentAssignmentMap;
  }

  @VisibleForTesting
  @FunctionalInterface
  interface PartitionIdFetcher {
    int fetch(String segmentName);
  }

  private static class PartitionIdFetcherImpl implements PartitionIdFetcher {
    private final String _tableNameWithType;
    private final String _partitionColumn;
    private final HelixManager _helixManager;
    private final boolean _isStrictRealtimeSegmentAssignment;

    private PartitionIdFetcherImpl(String tableNameWithType, @Nullable String partitionColumn,
        HelixManager helixManager, boolean isStrictRealtimeSegmentAssignment) {
      _tableNameWithType = tableNameWithType;
      _partitionColumn = partitionColumn;
      _helixManager = helixManager;
      _isStrictRealtimeSegmentAssignment = isStrictRealtimeSegmentAssignment;
    }

    @Override
    public int fetch(String segmentName) {
      Integer partitionId =
          SegmentUtils.getSegmentPartitionId(segmentName, _tableNameWithType, _helixManager, _partitionColumn);
      if (partitionId != null) {
        return partitionId;
      }
      // Partition id is mandatory for StrictRealtimeSegmentAssignment
      Preconditions.checkState(!_isStrictRealtimeSegmentAssignment,
          "Failed to find partition id for segment: %s of table: %s", segmentName, _tableNameWithType);
      return SegmentUtils.getDefaultPartitionId(segmentName);
    }
  }

  private static Map<String, Map<String, String>> getNextNonStrictReplicaGroupAssignment(
      Map<String, Map<String, String>> currentAssignment, Map<String, Map<String, String>> targetAssignment,
      int minAvailableReplicas, boolean lowDiskMode, int batchSizePerServer) {
    Map<String, Integer> serverToNumSegmentsAddedSoFar = new HashMap<>();
    Map<String, Map<String, String>> nextAssignment = new TreeMap<>();
    Map<String, Integer> numSegmentsToOffloadMap = getNumSegmentsToOffloadMap(currentAssignment, targetAssignment);
    Map<Pair<Set<String>, Set<String>>, Set<String>> assignmentMap = new HashMap<>();
    for (Map.Entry<String, Map<String, String>> entry : currentAssignment.entrySet()) {
      String segmentName = entry.getKey();
      Map<String, String> currentInstanceStateMap = entry.getValue();
      Map<String, String> targetInstanceStateMap = targetAssignment.get(segmentName);
      Map<String, String> nextInstanceStateMap =
          getNextSingleSegmentAssignment(currentInstanceStateMap, targetInstanceStateMap, minAvailableReplicas,
              lowDiskMode, numSegmentsToOffloadMap, assignmentMap)._instanceStateMap;
      Set<String> serversAddedForSegment = getServersAddedInSingleSegmentAssignment(currentInstanceStateMap,
          nextInstanceStateMap);
      boolean anyServerExhaustedBatchSize = false;
      if (batchSizePerServer != RebalanceConfig.DISABLE_BATCH_SIZE_PER_SERVER) {
        for (String server : serversAddedForSegment) {
          if (serverToNumSegmentsAddedSoFar.getOrDefault(server, 0) >= batchSizePerServer) {
            anyServerExhaustedBatchSize = true;
            break;
          }
        }
      }
      if (anyServerExhaustedBatchSize) {
        // Exhausted the batch size for at least 1 server, set to existing assignment
        nextAssignment.put(segmentName, currentInstanceStateMap);
      } else {
        // Add the next assignment and update the segments added so far counts
        serversAddedForSegment.forEach(server -> serverToNumSegmentsAddedSoFar.merge(server, 1, Integer::sum));
        nextAssignment.put(segmentName, nextInstanceStateMap);
        updateNumSegmentsToOffloadMap(numSegmentsToOffloadMap, currentInstanceStateMap.keySet(),
            nextInstanceStateMap.keySet());
      }
    }
    return nextAssignment;
  }

  private static Set<String> getServersAddedInSingleSegmentAssignment(Map<String, String> currentInstanceStateMap,
      Map<String, String> nextInstanceStateMap) {
    Set<String> serversWithSegmentsAdded = new HashSet<>(nextInstanceStateMap.keySet());
    serversWithSegmentsAdded.removeAll(currentInstanceStateMap.keySet());
    return serversWithSegmentsAdded;
  }

  /**
   * Returns the map from instance to number of segments to be offloaded from the instance based on the current and
   * target assignment.
   */
  @VisibleForTesting
  static Map<String, Integer> getNumSegmentsToOffloadMap(Map<String, Map<String, String>> currentAssignment,
      Map<String, Map<String, String>> targetAssignment) {
    Map<String, Integer> numSegmentsToOffloadMap = new HashMap<>();
    for (Map<String, String> currentInstanceStateMap : currentAssignment.values()) {
      for (String currentInstance : currentInstanceStateMap.keySet()) {
        numSegmentsToOffloadMap.merge(currentInstance, 1, Integer::sum);
      }
    }
    for (Map<String, String> targetInstanceStateMap : targetAssignment.values()) {
      for (String targetInstance : targetInstanceStateMap.keySet()) {
        numSegmentsToOffloadMap.merge(targetInstance, -1, Integer::sum);
      }
    }
    return numSegmentsToOffloadMap;
  }

  private static void updateNumSegmentsToOffloadMap(Map<String, Integer> numSegmentsToOffloadMap,
      Set<String> currentInstances, Set<String> newInstances) {
    for (String currentInstance : currentInstances) {
      numSegmentsToOffloadMap.merge(currentInstance, -1, Integer::sum);
    }
    for (String newInstance : newInstances) {
      numSegmentsToOffloadMap.merge(newInstance, 1, Integer::sum);
    }
  }

  /**
   * Returns the next assignment for a segment based on the current instance state map and the target instance state map
   * with regard to the minimum available replicas requirement.
   * It is possible that the current instance state map does not have enough replicas to reach the minimum available
   * replicas requirement, and in this scenario we will keep all the current instances as this is the best we can do.
   */
  @VisibleForTesting
  static SingleSegmentAssignment getNextSingleSegmentAssignment(Map<String, String> currentInstanceStateMap,
      Map<String, String> targetInstanceStateMap, int minAvailableReplicas, boolean lowDiskMode,
      Map<String, Integer> numSegmentsToOffloadMap, Map<Pair<Set<String>, Set<String>>, Set<String>> assignmentMap) {
    Map<String, String> nextInstanceStateMap = new TreeMap<>();

    // Assign the segment the same way as other segments if the current and target instances are the same. We need this
    // to guarantee the mirror servers for replica-group based routing strategies.
    Set<String> currentInstances = currentInstanceStateMap.keySet();
    Set<String> targetInstances = targetInstanceStateMap.keySet();
    Pair<Set<String>, Set<String>> assignmentKey = Pair.of(currentInstances, targetInstances);
    Set<String> instancesToAssign = assignmentMap.get(assignmentKey);
    if (instancesToAssign != null) {
      Set<String> availableInstances = new TreeSet<>();
      for (String instanceName : instancesToAssign) {
        String currentInstanceState = currentInstanceStateMap.get(instanceName);
        String targetInstanceState = targetInstanceStateMap.get(instanceName);
        if (currentInstanceState != null) {
          availableInstances.add(instanceName);
          // Use target instance state if available in case the state changes
          nextInstanceStateMap.put(instanceName,
              targetInstanceState != null ? targetInstanceState : currentInstanceState);
        } else {
          nextInstanceStateMap.put(instanceName, targetInstanceState);
        }
      }
      return new SingleSegmentAssignment(nextInstanceStateMap, availableInstances);
    }

    // Add all the common instances
    // Use target instance state in case the state changes
    for (Map.Entry<String, String> entry : targetInstanceStateMap.entrySet()) {
      String instanceName = entry.getKey();
      if (currentInstanceStateMap.containsKey(instanceName)) {
        nextInstanceStateMap.put(instanceName, entry.getValue());
      }
    }

    // Add current instances until the min available replicas achieved
    int numInstancesToKeep = minAvailableReplicas - nextInstanceStateMap.size();
    if (numInstancesToKeep > 0) {
      // Sort instances by number of segments to offload, and keep the ones with the least segments to offload
      List<Triple<String, String, Integer>> instancesInfo =
          getSortedInstancesOnNumSegmentsToOffload(currentInstanceStateMap, nextInstanceStateMap,
              numSegmentsToOffloadMap);
      numInstancesToKeep = Integer.min(numInstancesToKeep, instancesInfo.size());
      for (int i = 0; i < numInstancesToKeep; i++) {
        Triple<String, String, Integer> instanceInfo = instancesInfo.get(i);
        nextInstanceStateMap.put(instanceInfo.getLeft(), instanceInfo.getMiddle());
      }
    }
    Set<String> availableInstances = new TreeSet<>(nextInstanceStateMap.keySet());

    // After achieving the min available replicas, when low disk mode is enabled, only add new instances when all
    // current instances exist in the next assignment.
    // We want to first drop the extra instances as one step, then add the target instances as another step to avoid the
    // case where segments are first added to the instance before other segments are dropped from the instance, which
    // might cause server running out of disk. Note that even if segment addition and drop happen in the same step,
    // there is no guarantee that server process the segment drop before the segment addition.
    if (!lowDiskMode || currentInstanceStateMap.size() == nextInstanceStateMap.size()) {
      int numInstancesToAdd = targetInstanceStateMap.size() - nextInstanceStateMap.size();
      if (numInstancesToAdd > 0) {
        // Sort instances by number of segments to offload, and add the ones with the least segments to offload
        List<Triple<String, String, Integer>> instancesInfo =
            getSortedInstancesOnNumSegmentsToOffload(targetInstanceStateMap, nextInstanceStateMap,
                numSegmentsToOffloadMap);
        for (int i = 0; i < numInstancesToAdd; i++) {
          Triple<String, String, Integer> instanceInfo = instancesInfo.get(i);
          nextInstanceStateMap.put(instanceInfo.getLeft(), instanceInfo.getMiddle());
        }
      }
    }

    assignmentMap.put(assignmentKey, nextInstanceStateMap.keySet());
    return new SingleSegmentAssignment(nextInstanceStateMap, availableInstances);
  }

  /**
   * Returns the sorted instances by number of segments to offload. If there is a tie, sort the instances in
   * alphabetical order to get deterministic result.
   * The Triple stores {@code <instanceName, instanceState, numSegmentsToOffload>}.
   */
  private static List<Triple<String, String, Integer>> getSortedInstancesOnNumSegmentsToOffload(
      Map<String, String> instanceStateMap, Map<String, String> nextInstanceStateMap,
      Map<String, Integer> numSegmentsToOffloadMap) {
    List<Triple<String, String, Integer>> instancesInfo = new ArrayList<>(instanceStateMap.size());
    for (Map.Entry<String, String> entry : instanceStateMap.entrySet()) {
      String instanceName = entry.getKey();
      if (!nextInstanceStateMap.containsKey(instanceName)) {
        instancesInfo.add(Triple.of(instanceName, entry.getValue(), numSegmentsToOffloadMap.get(instanceName)));
      }
    }
    instancesInfo.sort(Comparator.comparingInt((ToIntFunction<Triple<String, String, Integer>>) Triple::getRight)
        .thenComparing(Triple::getLeft));
    return instancesInfo;
  }

  /**
   * Assignment result for a single segment.
   */
  @VisibleForTesting
  static class SingleSegmentAssignment {
    final Map<String, String> _instanceStateMap;
    // Instances that are common in both current instance state and next instance state of the segment
    final Set<String> _availableInstances;

    SingleSegmentAssignment(Map<String, String> instanceStateMap, Set<String> availableInstances) {
      _instanceStateMap = instanceStateMap;
      _availableInstances = availableInstances;
    }
  }

  @VisibleForTesting
  static Set<String> getMovingConsumingSegments(Map<String, Map<String, String>> currentAssignment,
      Map<String, Map<String, String>> targetAssignment) {
    Set<String> movingConsumingSegments = new HashSet<>();
    for (Map.Entry<String, Map<String, String>> entry : currentAssignment.entrySet()) {
      String segmentName = entry.getKey();
      Map<String, String> currentInstanceStateMap = entry.getValue();
      Map<String, String> targetInstanceStateMap = targetAssignment.get(segmentName);
      if (targetInstanceStateMap != null && targetInstanceStateMap.values().stream()
          .noneMatch(state -> state.equals(SegmentStateModel.ONLINE)) && targetInstanceStateMap.values().stream()
          .anyMatch(state -> state.equals(SegmentStateModel.CONSUMING))) {
        if (!currentInstanceStateMap.keySet().equals(targetInstanceStateMap.keySet())) {
          movingConsumingSegments.add(segmentName);
        }
      }
    }
    return movingConsumingSegments;
  }

  private IdealState forceCommitConsumingSegmentsAndWait(String tableNameWithType, Set<String> segmentsToCommit,
      Logger tableRebalanceLogger, int forceCommitBatchSize, int forceCommitBatchStatusCheckIntervalMs,
      int forceCommitBatchStatusCheckTimeoutMs)
      throws InterruptedException {
    tableRebalanceLogger.info("Force committing {} consuming segments before moving them",
        segmentsToCommit.size());
    Preconditions.checkState(_pinotLLCRealtimeSegmentManager != null,
        "PinotLLCRealtimeSegmentManager is not initialized");
    ForceCommitBatchConfig forceCommitBatchConfig =
        ForceCommitBatchConfig.of(forceCommitBatchSize, forceCommitBatchStatusCheckIntervalMs / 1000,
            forceCommitBatchStatusCheckTimeoutMs / 1000);
    segmentsToCommit = _pinotLLCRealtimeSegmentManager.forceCommit(tableNameWithType, null,
        StringUtil.join(",", segmentsToCommit.toArray(String[]::new)), forceCommitBatchConfig);
    // Wait until all committed segments have their status set to DONE.
    // Even for pauseless table, we wait until the segment has been uploaded (status DONE). Because we cannot
    // guarantee there will be available peers for the new instance to download (e.g. the only available replica
    // during the rebalance be the one who's committing, which has CONSUMING in EV), which may lead to download
    // timeout and essentially segment ERROR. Furthermore, we need to wait until EV-IS converge anyway, and that
    // happens only after the committing segment status is set to DONE.
    _pinotLLCRealtimeSegmentManager.waitUntilSegmentsForceCommitted(tableNameWithType, segmentsToCommit,
        forceCommitBatchConfig);
    tableRebalanceLogger.info("Successfully force committed {} consuming segments", segmentsToCommit.size());
    return _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder().idealStates(tableNameWithType));
  }
}
