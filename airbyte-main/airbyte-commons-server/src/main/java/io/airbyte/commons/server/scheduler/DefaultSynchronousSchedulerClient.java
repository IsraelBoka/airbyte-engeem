/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.server.handlers.helpers.ContextBuilder;
import io.airbyte.commons.temporal.TemporalClient;
import io.airbyte.commons.temporal.TemporalJobType;
import io.airbyte.commons.temporal.TemporalResponse;
import io.airbyte.commons.temporal.scheduling.RouterService;
import io.airbyte.commons.version.Version;
import io.airbyte.config.ActorContext;
import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.ActorType;
import io.airbyte.config.ConnectorJobOutput;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.JobCheckConnectionConfig;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.JobDiscoverCatalogConfig;
import io.airbyte.config.JobGetSpecConfig;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.StandardCheckConnectionOutput;
import io.airbyte.config.persistence.ActorDefinitionVersionHelper;
import io.airbyte.config.persistence.ConfigInjector;
import io.airbyte.persistence.job.errorreporter.ConnectorJobReportingContext;
import io.airbyte.persistence.job.errorreporter.JobErrorReporter;
import io.airbyte.persistence.job.factory.OAuthConfigSupplier;
import io.airbyte.persistence.job.tracker.JobTracker;
import io.airbyte.persistence.job.tracker.JobTracker.JobState;
import io.airbyte.protocol.models.ConnectorSpecification;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporal job client for synchronous jobs (i.e. spec, check, discover).
 */
public class DefaultSynchronousSchedulerClient implements SynchronousSchedulerClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSynchronousSchedulerClient.class);

  private static final HashFunction HASH_FUNCTION = Hashing.md5();

  private final TemporalClient temporalClient;
  private final JobTracker jobTracker;
  private final JobErrorReporter jobErrorReporter;
  private final OAuthConfigSupplier oAuthConfigSupplier;

  private final RouterService routerService;
  private final ConfigInjector configInjector;
  private final ContextBuilder contextBuilder;

  @SuppressWarnings("ParameterName")
  public DefaultSynchronousSchedulerClient(final TemporalClient temporalClient,
                                           final JobTracker jobTracker,
                                           final JobErrorReporter jobErrorReporter,
                                           final OAuthConfigSupplier oAuthConfigSupplier,
                                           final RouterService routerService,
                                           final ConfigInjector configInjector,
                                           final ContextBuilder contextBuilder) {
    this.temporalClient = temporalClient;
    this.jobTracker = jobTracker;
    this.jobErrorReporter = jobErrorReporter;
    this.oAuthConfigSupplier = oAuthConfigSupplier;
    this.routerService = routerService;
    this.configInjector = configInjector;
    this.contextBuilder = contextBuilder;
  }

  @Override
  public SynchronousResponse<StandardCheckConnectionOutput> createSourceCheckConnectionJob(
                                                                                           final SourceConnection source,
                                                                                           final ActorDefinitionVersion sourceVersion,
                                                                                           final boolean isCustomConnector,
                                                                                           final ResourceRequirements resourceRequirements)
      throws IOException {
    final String dockerImage = ActorDefinitionVersionHelper.getDockerImageName(sourceVersion);
    final JsonNode sourceConfiguration = oAuthConfigSupplier.injectSourceOAuthParameters(
        source.getSourceDefinitionId(),
        source.getSourceId(),
        source.getWorkspaceId(),
        source.getConfiguration());
    final JobCheckConnectionConfig jobCheckConnectionConfig = new JobCheckConnectionConfig()
        .withActorType(ActorType.SOURCE)
        .withActorId(source.getSourceId())
        .withConnectionConfiguration(configInjector.injectConfig(sourceConfiguration, source.getSourceDefinitionId()))
        .withDockerImage(dockerImage)
        .withProtocolVersion(new Version(sourceVersion.getProtocolVersion()))
        .withIsCustomConnector(isCustomConnector)
        .withResourceRequirements(resourceRequirements);

    final UUID jobId = UUID.randomUUID();
    final ConnectorJobReportingContext jobReportingContext = new ConnectorJobReportingContext(jobId, dockerImage, sourceVersion.getReleaseStage());
    final String taskQueue = routerService.getTaskQueueForWorkspace(source.getWorkspaceId(), TemporalJobType.CHECK_CONNECTION);

    final ActorContext context = contextBuilder.fromSource(source);

    return execute(
        ConfigType.CHECK_CONNECTION_SOURCE,
        jobReportingContext,
        source.getSourceDefinitionId(),
        () -> temporalClient.submitCheckConnection(UUID.randomUUID(), 0, source.getWorkspaceId(), taskQueue, jobCheckConnectionConfig, context),
        ConnectorJobOutput::getCheckConnection,
        source.getWorkspaceId(),
        source.getSourceId());
  }

  @Override
  public SynchronousResponse<StandardCheckConnectionOutput> createDestinationCheckConnectionJob(
                                                                                                final DestinationConnection destination,
                                                                                                final ActorDefinitionVersion destinationVersion,
                                                                                                final boolean isCustomConnector,
                                                                                                final ResourceRequirements resourceRequirements)
      throws IOException {
    final String dockerImage = ActorDefinitionVersionHelper.getDockerImageName(destinationVersion);
    final JsonNode destinationConfiguration = oAuthConfigSupplier.injectDestinationOAuthParameters(
        destination.getDestinationDefinitionId(),
        destination.getDestinationId(),
        destination.getWorkspaceId(),
        destination.getConfiguration());
    final JobCheckConnectionConfig jobCheckConnectionConfig = new JobCheckConnectionConfig()
        .withActorType(ActorType.DESTINATION)
        .withActorId(destination.getDestinationId())
        .withConnectionConfiguration(configInjector.injectConfig(destinationConfiguration, destination.getDestinationDefinitionId()))
        .withDockerImage(dockerImage)
        .withProtocolVersion(new Version(destinationVersion.getProtocolVersion()))
        .withIsCustomConnector(isCustomConnector)
        .withResourceRequirements(resourceRequirements);

    final UUID jobId = UUID.randomUUID();
    final ConnectorJobReportingContext jobReportingContext =
        new ConnectorJobReportingContext(jobId, dockerImage, destinationVersion.getReleaseStage());
    final String taskQueue = routerService.getTaskQueueForWorkspace(destination.getWorkspaceId(), TemporalJobType.CHECK_CONNECTION);

    final ActorContext context = contextBuilder.fromDestination(destination);

    return execute(
        ConfigType.CHECK_CONNECTION_DESTINATION,
        jobReportingContext,
        destination.getDestinationDefinitionId(),
        () -> temporalClient.submitCheckConnection(jobId, 0, destination.getWorkspaceId(), taskQueue, jobCheckConnectionConfig, context),
        ConnectorJobOutput::getCheckConnection,
        destination.getWorkspaceId(),
        destination.getDestinationId());
  }

  @Override
  public SynchronousResponse<UUID> createDiscoverSchemaJob(final SourceConnection source,
                                                           final ActorDefinitionVersion sourceVersion,
                                                           final boolean isCustomConnector,
                                                           final ResourceRequirements actorDefinitionResourceRequirements)
      throws IOException {
    final String dockerImage = ActorDefinitionVersionHelper.getDockerImageName(sourceVersion);
    final JsonNode sourceConfiguration = oAuthConfigSupplier.injectSourceOAuthParameters(
        source.getSourceDefinitionId(),
        source.getSourceId(),
        source.getWorkspaceId(),
        source.getConfiguration());
    final JobDiscoverCatalogConfig jobDiscoverCatalogConfig = new JobDiscoverCatalogConfig()
        .withConnectionConfiguration(configInjector.injectConfig(sourceConfiguration, source.getSourceDefinitionId()))
        .withDockerImage(dockerImage)
        .withProtocolVersion(new Version(sourceVersion.getProtocolVersion()))
        .withSourceId(source.getSourceId().toString())
        .withConfigHash(HASH_FUNCTION.hashBytes(Jsons.serialize(source.getConfiguration()).getBytes(
            Charsets.UTF_8)).toString())
        .withConnectorVersion(sourceVersion.getDockerImageTag())
        .withIsCustomConnector(isCustomConnector)
        .withResourceRequirements(actorDefinitionResourceRequirements);

    final UUID jobId = UUID.randomUUID();
    final ConnectorJobReportingContext jobReportingContext = new ConnectorJobReportingContext(jobId, dockerImage, sourceVersion.getReleaseStage());

    final String taskQueue = routerService.getTaskQueueForWorkspace(source.getWorkspaceId(), TemporalJobType.DISCOVER_SCHEMA);

    final ActorContext context = contextBuilder.fromSource(source);

    return execute(
        ConfigType.DISCOVER_SCHEMA,
        jobReportingContext,
        source.getSourceDefinitionId(),
        () -> temporalClient.submitDiscoverSchema(jobId, 0, source.getWorkspaceId(), taskQueue, jobDiscoverCatalogConfig, context),
        ConnectorJobOutput::getDiscoverCatalogId,
        source.getWorkspaceId(),
        source.getSourceId());
  }

  @Override
  public SynchronousResponse<ConnectorSpecification> createGetSpecJob(final String dockerImage,
                                                                      final boolean isCustomConnector,
                                                                      final @Nullable UUID workspaceId) {
    final JobGetSpecConfig jobSpecConfig = new JobGetSpecConfig().withDockerImage(dockerImage).withIsCustomConnector(isCustomConnector);

    final UUID jobId = UUID.randomUUID();
    final ConnectorJobReportingContext jobReportingContext = new ConnectorJobReportingContext(jobId, dockerImage, null);

    return execute(
        ConfigType.GET_SPEC,
        jobReportingContext,
        null,
        () -> temporalClient.submitGetSpec(jobId, 0, workspaceId, jobSpecConfig),
        ConnectorJobOutput::getSpec,
        null, null);
  }

  @VisibleForTesting
  <T> SynchronousResponse<T> execute(final ConfigType configType,
                                     final ConnectorJobReportingContext jobContext,
                                     @Nullable final UUID connectorDefinitionId,
                                     final Supplier<TemporalResponse<ConnectorJobOutput>> executor,
                                     final Function<ConnectorJobOutput, T> outputMapper,
                                     @Nullable final UUID workspaceId,
                                     @Nullable final UUID actorId) {
    final long createdAt = Instant.now().toEpochMilli();
    final UUID jobId = jobContext.jobId();
    try {
      track(jobId, configType, connectorDefinitionId, workspaceId, actorId, JobState.STARTED, null);
      final TemporalResponse<ConnectorJobOutput> temporalResponse = executor.get();
      final Optional<ConnectorJobOutput> jobOutput = temporalResponse.getOutput();
      final JobState outputState = temporalResponse.getMetadata().isSucceeded() ? JobState.SUCCEEDED : JobState.FAILED;

      track(jobId, configType, connectorDefinitionId, workspaceId, actorId, outputState, jobOutput.orElse(null));

      if (outputState == JobState.FAILED && jobOutput.isPresent()) {
        reportError(configType, jobContext, jobOutput.get(), connectorDefinitionId, workspaceId);
      }

      final long endedAt = Instant.now().toEpochMilli();
      return SynchronousResponse.fromTemporalResponse(
          temporalResponse,
          outputMapper,
          jobId,
          configType,
          connectorDefinitionId,
          createdAt,
          endedAt);
    } catch (final RuntimeException e) {
      track(jobId, configType, connectorDefinitionId, workspaceId, actorId, JobState.FAILED, null);
      throw e;
    }
  }

  /*
   * @param connectorDefinitionId either source or destination definition id
   */
  private <T> void track(final UUID jobId,
                         final ConfigType configType,
                         final UUID connectorDefinitionId,
                         @Nullable final UUID workspaceId,
                         @Nullable final UUID actorId,
                         final JobState jobState,
                         final @Nullable ConnectorJobOutput jobOutput) {
    switch (configType) {
      case CHECK_CONNECTION_SOURCE -> jobTracker.trackCheckConnectionSource(
          jobId,
          connectorDefinitionId,
          workspaceId,
          actorId,
          jobState,
          jobOutput);
      case CHECK_CONNECTION_DESTINATION -> jobTracker.trackCheckConnectionDestination(
          jobId,
          connectorDefinitionId,
          workspaceId,
          actorId,
          jobState,
          jobOutput);
      case DISCOVER_SCHEMA -> jobTracker.trackDiscover(jobId, connectorDefinitionId, workspaceId, actorId, jobState, jobOutput);
      case GET_SPEC -> {
        // skip tracking for get spec to avoid noise.
      }
      default -> throw new IllegalArgumentException(
          String.format("Jobs of type %s cannot be processed here. They should be consumed in the JobSubmitter.", configType));
    }

  }

  private <S, T> void reportError(final ConfigType configType,
                                  final ConnectorJobReportingContext jobContext,
                                  final T jobOutput,
                                  final UUID connectorDefinitionId,
                                  final UUID workspaceId) {
    Exceptions.swallow(() -> {
      switch (configType) {
        case CHECK_CONNECTION_SOURCE -> jobErrorReporter.reportSourceCheckJobFailure(
            connectorDefinitionId,
            workspaceId,
            ((ConnectorJobOutput) jobOutput).getFailureReason(),
            jobContext);
        case CHECK_CONNECTION_DESTINATION -> jobErrorReporter.reportDestinationCheckJobFailure(
            connectorDefinitionId,
            workspaceId,
            ((ConnectorJobOutput) jobOutput).getFailureReason(),
            jobContext);
        case DISCOVER_SCHEMA -> jobErrorReporter.reportDiscoverJobFailure(
            connectorDefinitionId,
            workspaceId,
            ((ConnectorJobOutput) jobOutput).getFailureReason(),
            jobContext);
        case GET_SPEC -> jobErrorReporter.reportSpecJobFailure(
            ((ConnectorJobOutput) jobOutput).getFailureReason(),
            jobContext);
        default -> LOGGER.error("Tried to report job failure for type {}, but this job type is not supported", configType);
      }
    });
  }

}
