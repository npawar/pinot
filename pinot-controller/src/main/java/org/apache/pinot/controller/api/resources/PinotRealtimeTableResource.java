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
package org.apache.pinot.controller.api.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiKeyAuthDefinition;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.SecurityDefinition;
import io.swagger.annotations.SwaggerDefinition;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.pinot.common.utils.DatabaseUtils;
import org.apache.pinot.controller.ControllerConf;
import org.apache.pinot.controller.api.exception.ControllerApplicationException;
import org.apache.pinot.controller.helix.core.PinotHelixResourceManager;
import org.apache.pinot.controller.helix.core.controllerjob.ControllerJobTypes;
import org.apache.pinot.controller.helix.core.realtime.PinotLLCRealtimeSegmentManager;
import org.apache.pinot.controller.util.ConsumingSegmentInfoReader;
import org.apache.pinot.core.auth.Actions;
import org.apache.pinot.core.auth.Authorize;
import org.apache.pinot.core.auth.TargetType;
import org.apache.pinot.spi.config.table.PauseState;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.utils.CommonConstants;
import org.apache.pinot.spi.utils.JsonUtils;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.spi.utils.CommonConstants.DATABASE;
import static org.apache.pinot.spi.utils.CommonConstants.SWAGGER_AUTHORIZATION_KEY;


@Api(tags = Constants.TABLE_TAG, authorizations = {
    @Authorization(value = SWAGGER_AUTHORIZATION_KEY),
    @Authorization(value = DATABASE)
})
@SwaggerDefinition(securityDefinition = @SecurityDefinition(apiKeyAuthDefinitions = {
    @ApiKeyAuthDefinition(name = HttpHeaders.AUTHORIZATION, in = ApiKeyAuthDefinition.ApiKeyLocation.HEADER,
        key = SWAGGER_AUTHORIZATION_KEY,
        description = "The format of the key is  ```\"Basic <token>\" or \"Bearer <token>\"```"),
    @ApiKeyAuthDefinition(name = DATABASE, in = ApiKeyAuthDefinition.ApiKeyLocation.HEADER, key = DATABASE,
        description = "Database context passed through http header. If no context is provided 'default' database "
            + "context will be considered.")
}))
@Path("/")
public class PinotRealtimeTableResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(PinotRealtimeTableResource.class);

  @Inject
  ControllerConf _controllerConf;

  @Inject
  Executor _executor;

  @Inject
  HttpClientConnectionManager _connectionManager;

  @Inject
  PinotHelixResourceManager _pinotHelixResourceManager;

  @Inject
  PinotLLCRealtimeSegmentManager _pinotLLCRealtimeSegmentManager;

  @POST
  @Path("/tables/{tableName}/pauseConsumption")
  @Authorize(targetType = TargetType.TABLE, paramName = "tableName", action = Actions.Table.PAUSE_CONSUMPTION)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Pause consumption of a realtime table", notes = "Pause the consumption of a realtime table")
  public Response pauseConsumption(
      @ApiParam(value = "Name of the table", required = true) @PathParam("tableName") String tableName,
      @ApiParam(value = "Comment on pausing the consumption") @QueryParam("comment") String comment,
      @Context HttpHeaders headers) {
    tableName = DatabaseUtils.translateTableName(tableName, headers);
    String tableNameWithType = TableNameBuilder.REALTIME.tableNameWithType(tableName);
    validateTable(tableNameWithType);
    try {
      return Response.ok(_pinotLLCRealtimeSegmentManager.pauseConsumption(tableNameWithType,
          PauseState.ReasonCode.ADMINISTRATIVE, comment)).build();
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @POST
  @Path("/tables/{tableName}/resumeConsumption")
  @Authorize(targetType = TargetType.TABLE, paramName = "tableName", action = Actions.Table.RESUME_CONSUMPTION)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Resume consumption of a realtime table", notes =
      "Resume the consumption for a realtime table. ConsumeFrom parameter indicates from which offsets "
          + "consumption should resume. Recommended value is 'lastConsumed', which indicates consumption should "
          + "continue based on the offsets in segment ZK metadata, and in case the offsets are already gone, the first "
          + "available offsets are picked to minimize the data loss.")
  public Response resumeConsumption(
      @ApiParam(value = "Name of the table", required = true) @PathParam("tableName") String tableName,
      @ApiParam(value = "Comment on pausing the consumption") @QueryParam("comment") String comment,
      @ApiParam(
          value = "lastConsumed (safer) | smallest (repeat rows) | largest (miss rows)",
          allowableValues = "lastConsumed, smallest, largest",
          defaultValue = "lastConsumed"
      )
      @QueryParam("consumeFrom") String consumeFrom, @Context HttpHeaders headers) {
    tableName = DatabaseUtils.translateTableName(tableName, headers);
    String tableNameWithType = TableNameBuilder.REALTIME.tableNameWithType(tableName);
    validateTable(tableNameWithType);
    if ("lastConsumed".equalsIgnoreCase(consumeFrom)) {
      consumeFrom = null;
    }
    if (consumeFrom != null && !consumeFrom.equalsIgnoreCase("smallest") && !consumeFrom.equalsIgnoreCase("largest")) {
      throw new ControllerApplicationException(LOGGER,
          String.format("consumeFrom param '%s' is not valid. Valid values are 'lastConsumed', 'smallest' and "
              + "'largest'.", consumeFrom), Response.Status.BAD_REQUEST);
    }
    try {
      return Response.ok(_pinotLLCRealtimeSegmentManager.resumeConsumption(tableNameWithType, consumeFrom,
          PauseState.ReasonCode.ADMINISTRATIVE, comment)).build();
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @POST
  @Path("/tables/{tableName}/forceCommit")
  @Authorize(targetType = TargetType.TABLE, paramName = "tableName", action = Actions.Table.FORCE_COMMIT)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Force commit the current consuming segments",
      notes = "Force commit the current segments in consuming state and restart consumption. "
          + "This should be used after schema/table config changes. "
          + "Please note that this is an asynchronous operation, "
          + "and 200 response does not mean it has actually been done already."
          + "If specific partitions or consuming segments are provided, "
          + "only those partitions or consuming segments will be force committed.")
  public Map<String, String> forceCommit(
      @ApiParam(value = "Name of the table", required = true) @PathParam("tableName") String tableName,
      @ApiParam(value = "Comma separated list of partition group IDs to be committed") @QueryParam("partitions")
      String partitionGroupIds,
      @ApiParam(value = "Comma separated list of consuming segments to be committed") @QueryParam("segments")
      String consumingSegments,
      @ApiParam(value = "Max number of consuming segments to commit at once")
      @QueryParam("batchSize") @DefaultValue(ForceCommitBatchConfig.DEFAULT_BATCH_SIZE + "") int batchSize,
      @ApiParam(value = "How often to check whether the current batch of segments have been successfully committed or"
          + " not")
      @QueryParam("batchStatusCheckIntervalSec")
      @DefaultValue(ForceCommitBatchConfig.DEFAULT_STATUS_CHECK_INTERVAL_SEC + "") int batchStatusCheckIntervalSec,
      @ApiParam(value = "Timeout based on which the controller will stop checking the forceCommit status of the batch"
          + " of segments and throw an exception")
      @QueryParam("batchStatusCheckTimeoutSec")
      @DefaultValue(ForceCommitBatchConfig.DEFAULT_STATUS_CHECK_TIMEOUT_SEC + "") int batchStatusCheckTimeoutSec,
      @Context HttpHeaders headers) {
    tableName = DatabaseUtils.translateTableName(tableName, headers);
    if (partitionGroupIds != null && consumingSegments != null) {
      throw new ControllerApplicationException(LOGGER, "Cannot specify both partitions and segments to commit",
          Response.Status.BAD_REQUEST);
    }
    ForceCommitBatchConfig batchConfig;
    try {
      batchConfig = ForceCommitBatchConfig.of(batchSize, batchStatusCheckIntervalSec, batchStatusCheckTimeoutSec);
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, "Invalid batch config", Response.Status.BAD_REQUEST, e);
    }
    long startTimeMs = System.currentTimeMillis();
    String tableNameWithType = TableNameBuilder.REALTIME.tableNameWithType(tableName);
    validateTable(tableNameWithType);
    Map<String, String> response = new HashMap<>();
    try {
      Set<String> consumingSegmentsForceCommitted =
          _pinotLLCRealtimeSegmentManager.forceCommit(tableNameWithType, partitionGroupIds, consumingSegments,
              batchConfig);
      response.put("forceCommitStatus", "SUCCESS");
      try {
        String jobId = UUID.randomUUID().toString();
        if (!_pinotHelixResourceManager.addNewForceCommitJob(tableNameWithType, jobId, startTimeMs,
            consumingSegmentsForceCommitted)) {
          throw new IllegalStateException("Failed to update table jobs ZK metadata");
        }
        response.put("jobMetaZKWriteStatus", "SUCCESS");
        response.put("forceCommitJobId", jobId);
      } catch (Exception e) {
        response.put("jobMetaZKWriteStatus", "FAILED");
        LOGGER.error("Could not add force commit job metadata to ZK table : {}", tableNameWithType, e);
      }
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR, e);
    }

    return response;
  }

  @GET
  @Path("/tables/forceCommitStatus/{jobId}")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.GET_FORCE_COMMIT_STATUS)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Get status for a submitted force commit operation",
      notes = "Get status for a submitted force commit operation")
  public JsonNode getForceCommitJobStatus(
      @ApiParam(value = "Force commit job id", required = true) @PathParam("jobId") String forceCommitJobId)
      throws Exception {
    Map<String, String> controllerJobZKMetadata =
        _pinotHelixResourceManager.getControllerJobZKMetadata(forceCommitJobId,
            ControllerJobTypes.FORCE_COMMIT);
    if (controllerJobZKMetadata == null) {
      throw new ControllerApplicationException(LOGGER, "Failed to find controller job id: " + forceCommitJobId,
          Response.Status.NOT_FOUND);
    }
    String tableNameWithType = controllerJobZKMetadata.get(CommonConstants.ControllerJob.TABLE_NAME_WITH_TYPE);
    Set<String> consumingSegmentCommitted = JsonUtils.stringToObject(
        controllerJobZKMetadata.get(CommonConstants.ControllerJob.CONSUMING_SEGMENTS_FORCE_COMMITTED_LIST), Set.class);

    Set<String> segmentsToCheck;
    String segmentsPendingToBeComittedString =
        controllerJobZKMetadata.get(CommonConstants.ControllerJob.CONSUMING_SEGMENTS_YET_TO_BE_COMMITTED_LIST);

    if (segmentsPendingToBeComittedString != null) {
      segmentsToCheck = JsonUtils.stringToObject(segmentsPendingToBeComittedString, Set.class);
    } else {
      segmentsToCheck = consumingSegmentCommitted;
    }

    Set<String> segmentsYetToBeCommitted =
        _pinotLLCRealtimeSegmentManager.getSegmentsYetToBeCommitted(tableNameWithType, segmentsToCheck);

    if (segmentsYetToBeCommitted.size() < segmentsToCheck.size()) {
      controllerJobZKMetadata.put(CommonConstants.ControllerJob.CONSUMING_SEGMENTS_YET_TO_BE_COMMITTED_LIST,
          JsonUtils.objectToString(segmentsYetToBeCommitted));
      _pinotHelixResourceManager.addControllerJobToZK(forceCommitJobId, controllerJobZKMetadata,
          ControllerJobTypes.FORCE_COMMIT);
    }

    Map<String, Object> result = new HashMap<>(controllerJobZKMetadata);
    result.put(CommonConstants.ControllerJob.CONSUMING_SEGMENTS_YET_TO_BE_COMMITTED_LIST, segmentsYetToBeCommitted);
    result.put(CommonConstants.ControllerJob.NUM_CONSUMING_SEGMENTS_YET_TO_BE_COMMITTED,
        segmentsYetToBeCommitted.size());
    return JsonUtils.objectToJsonNode(result);
  }

  @GET
  @Path("/tables/{tableName}/pauseStatus")
  @Authorize(targetType = TargetType.TABLE, paramName = "tableName", action = Actions.Table.GET_PAUSE_STATUS)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Return pause status of a realtime table",
      notes = "Return pause status of a realtime table along with list of consuming segments.")
  public Response getPauseStatus(
      @ApiParam(value = "Name of the table", required = true) @PathParam("tableName") String tableName,
      @Context HttpHeaders headers) {
    tableName = DatabaseUtils.translateTableName(tableName, headers);
    String tableNameWithType = TableNameBuilder.REALTIME.tableNameWithType(tableName);
    validateTable(tableNameWithType);
    try {
      return Response.ok().entity(_pinotLLCRealtimeSegmentManager.getPauseStatusDetails(tableNameWithType)).build();
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @GET
  @Path("/tables/{tableName}/consumingSegmentsInfo")
  @Authorize(targetType = TargetType.TABLE, paramName = "tableName", action = Actions.Table.GET_CONSUMING_SEGMENTS)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Returns state of consuming segments", notes = "Gets the status of consumers from all servers."
      + "Note that the partitionToOffsetMap has been deprecated and will be removed in the next release. The info is "
      + "now embedded within each partition's state as currentOffsetsMap.")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "Table not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public ConsumingSegmentInfoReader.ConsumingSegmentsInfoMap getConsumingSegmentsInfo(
      @ApiParam(value = "Realtime table name with or without type", required = true,
          example = "myTable | myTable_REALTIME") @PathParam("tableName") String realtimeTableName,
      @Context HttpHeaders headers) {
    realtimeTableName = DatabaseUtils.translateTableName(realtimeTableName, headers);
    try {
      TableType tableType = TableNameBuilder.getTableTypeFromTableName(realtimeTableName);
      if (TableType.OFFLINE == tableType) {
        throw new IllegalStateException("Cannot get consuming segments info for OFFLINE table: " + realtimeTableName);
      }
      String tableNameWithType = TableNameBuilder.forType(TableType.REALTIME).tableNameWithType(realtimeTableName);
      ConsumingSegmentInfoReader consumingSegmentInfoReader =
          new ConsumingSegmentInfoReader(_executor, _connectionManager, _pinotHelixResourceManager);
      return consumingSegmentInfoReader.getConsumingSegmentsInfo(tableNameWithType,
          _controllerConf.getServerAdminRequestTimeoutSeconds() * 1000);
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER,
          String.format("Failed to get consuming segments info for table %s. %s", realtimeTableName, e.getMessage()),
          Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @GET
  @Path("/tables/{tableName}/pauselessDebugInfo")
  @Authorize(targetType = TargetType.TABLE, paramName = "tableName", action = Actions.Table.GET_DEBUG_INFO)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Returns state of pauseless table", notes =
      "Gets the segments that are in error state and segments with COMMITTING status in ZK metadata")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "Table not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String getPauselessTableDebugInfo(
      @ApiParam(value = "Realtime table name with or without type", required = true, example = "myTable | "
          + "myTable_REALTIME") @PathParam("tableName") String realtimeTableName,
      @Context HttpHeaders headers) {
    realtimeTableName = DatabaseUtils.translateTableName(realtimeTableName, headers);
    try {
      TableType tableType = TableNameBuilder.getTableTypeFromTableName(realtimeTableName);
      if (TableType.OFFLINE == tableType) {
        throw new IllegalStateException("Cannot get consuming segments info for OFFLINE table: " + realtimeTableName);
      }

      String tableNameWithType = TableNameBuilder.forType(TableType.REALTIME).tableNameWithType(realtimeTableName);

      Map<String, Object> result = new HashMap<>();

      result.put("instanceToErrorSegmentsMap", getInstanceToErrorSegmentsMap(tableNameWithType));

      result.put("committingSegments", _pinotLLCRealtimeSegmentManager.getCommittingSegments(tableNameWithType));

      return JsonUtils.objectToPrettyString(result);
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER,
          String.format("Failed to get pauseless debug info for table %s. %s", realtimeTableName, e.getMessage()),
          Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  private Map<String, Set<String>> getInstanceToErrorSegmentsMap(String tableNameWithType) {
    ExternalView externalView = _pinotHelixResourceManager.getTableExternalView(tableNameWithType);
    Preconditions.checkState(externalView != null, "External view does not exist for table: " + tableNameWithType);

    Map<String, Set<String>> instanceToErrorSegmentsMap = new HashMap<>();

    for (String segmentName : externalView.getPartitionSet()) {
      Map<String, String> externalViewStateMap = externalView.getStateMap(segmentName);
      for (String instance : externalViewStateMap.keySet()) {
        if (CommonConstants.Helix.StateModel.SegmentStateModel.ERROR.equals(
            externalViewStateMap.get(instance))) {
          instanceToErrorSegmentsMap.computeIfAbsent(instance, unused -> new HashSet<>()).add(segmentName);
        }
      }
    }
    return instanceToErrorSegmentsMap;
  }

  private void validateTable(String tableNameWithType) {
    IdealState idealState = _pinotHelixResourceManager.getTableIdealState(tableNameWithType);
    if (idealState == null) {
      throw new ControllerApplicationException(LOGGER, String.format("Table %s not found!", tableNameWithType),
          Response.Status.NOT_FOUND);
    }
    if (!idealState.isEnabled()) {
      throw new ControllerApplicationException(LOGGER, String.format("Table %s is disabled!", tableNameWithType),
          Response.Status.BAD_REQUEST);
    }
  }
}
