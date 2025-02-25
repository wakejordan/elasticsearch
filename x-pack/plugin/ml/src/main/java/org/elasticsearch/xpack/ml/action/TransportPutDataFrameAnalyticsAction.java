/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ml.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.common.validation.SourceDestValidator;
import org.elasticsearch.xpack.core.ml.MachineLearningField;
import org.elasticsearch.xpack.core.ml.MlConfigIndex;
import org.elasticsearch.xpack.core.ml.action.PutDataFrameAnalyticsAction;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.core.security.SecurityContext;
import org.elasticsearch.xpack.core.security.action.user.HasPrivilegesAction;
import org.elasticsearch.xpack.core.security.action.user.HasPrivilegesRequest;
import org.elasticsearch.xpack.core.security.action.user.HasPrivilegesResponse;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.core.security.authz.permission.ResourcePrivileges;
import org.elasticsearch.xpack.core.security.support.Exceptions;
import org.elasticsearch.xpack.ml.dataframe.SourceDestValidations;
import org.elasticsearch.xpack.ml.dataframe.persistence.DataFrameAnalyticsConfigProvider;
import org.elasticsearch.xpack.ml.notifications.DataFrameAnalyticsAuditor;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.xpack.ml.utils.SecondaryAuthorizationUtils.useSecondaryAuthIfAvailable;

public class TransportPutDataFrameAnalyticsAction
    extends TransportMasterNodeAction<PutDataFrameAnalyticsAction.Request, PutDataFrameAnalyticsAction.Response> {

    private static final Logger logger = LogManager.getLogger(TransportPutDataFrameAnalyticsAction.class);

    private final XPackLicenseState licenseState;
    private final DataFrameAnalyticsConfigProvider configProvider;
    private final SecurityContext securityContext;
    private final Client client;
    private final DataFrameAnalyticsAuditor auditor;
    private final SourceDestValidator sourceDestValidator;
    private final Settings settings;

    private volatile ByteSizeValue maxModelMemoryLimit;

    @Inject
    public TransportPutDataFrameAnalyticsAction(Settings settings, TransportService transportService, ActionFilters actionFilters,
                                                XPackLicenseState licenseState, Client client, ThreadPool threadPool,
                                                ClusterService clusterService, IndexNameExpressionResolver indexNameExpressionResolver,
                                                DataFrameAnalyticsConfigProvider configProvider, DataFrameAnalyticsAuditor auditor) {
        super(PutDataFrameAnalyticsAction.NAME, transportService, clusterService, threadPool, actionFilters,
                PutDataFrameAnalyticsAction.Request::new, indexNameExpressionResolver, PutDataFrameAnalyticsAction.Response::new,
                ThreadPool.Names.SAME);
        this.licenseState = licenseState;
        this.configProvider = configProvider;
        this.securityContext = XPackSettings.SECURITY_ENABLED.get(settings) ?
            new SecurityContext(settings, threadPool.getThreadContext()) : null;
        this.client = client;
        this.auditor = Objects.requireNonNull(auditor);
        this.settings = settings;

        maxModelMemoryLimit = MachineLearningField.MAX_MODEL_MEMORY_LIMIT.get(settings);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(MachineLearningField.MAX_MODEL_MEMORY_LIMIT, this::setMaxModelMemoryLimit);

        this.sourceDestValidator = new SourceDestValidator(
            indexNameExpressionResolver,
            transportService.getRemoteClusterService(),
            null,
            null,
            clusterService.getNodeName(),
            License.OperationMode.PLATINUM.description()
        );
    }

    private void setMaxModelMemoryLimit(ByteSizeValue maxModelMemoryLimit) {
        this.maxModelMemoryLimit = maxModelMemoryLimit;
    }

    @Override
    protected ClusterBlockException checkBlock(PutDataFrameAnalyticsAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    @Override
    protected void masterOperation(Task task, PutDataFrameAnalyticsAction.Request request, ClusterState state,
                                   ActionListener<PutDataFrameAnalyticsAction.Response> listener) {

        final DataFrameAnalyticsConfig config = request.getConfig();

        ActionListener<Boolean> sourceDestValidationListener = ActionListener.wrap(
            aBoolean -> putValidatedConfig(config, request.masterNodeTimeout(), listener),
            listener::onFailure
        );

        sourceDestValidator.validate(clusterService.state(), config.getSource().getIndex(), config.getDest().getIndex(), null,
            SourceDestValidations.ALL_VALIDATIONS, sourceDestValidationListener);
    }

    private void putValidatedConfig(DataFrameAnalyticsConfig config, TimeValue masterNodeTimeout,
                                    ActionListener<PutDataFrameAnalyticsAction.Response> listener) {
        DataFrameAnalyticsConfig preparedForPutConfig =
            new DataFrameAnalyticsConfig.Builder(config, maxModelMemoryLimit)
                .setCreateTime(Instant.now())
                .setVersion(Version.CURRENT)
                .build();

        if (XPackSettings.SECURITY_ENABLED.get(settings)) {
            useSecondaryAuthIfAvailable(securityContext, () -> {
                final String username = securityContext.getUser().principal();
                RoleDescriptor.IndicesPrivileges sourceIndexPrivileges = RoleDescriptor.IndicesPrivileges.builder()
                    .indices(preparedForPutConfig.getSource().getIndex())
                    .privileges("read")
                    .build();
                RoleDescriptor.IndicesPrivileges destIndexPrivileges = RoleDescriptor.IndicesPrivileges.builder()
                    .indices(preparedForPutConfig.getDest().getIndex())
                    .privileges("read", "index", "create_index")
                    .build();

                HasPrivilegesRequest privRequest = new HasPrivilegesRequest();
                privRequest.applicationPrivileges(new RoleDescriptor.ApplicationResourcePrivileges[0]);
                privRequest.username(username);
                privRequest.clusterPrivileges(Strings.EMPTY_ARRAY);
                privRequest.indexPrivileges(sourceIndexPrivileges, destIndexPrivileges);

                ActionListener<HasPrivilegesResponse> privResponseListener = ActionListener.wrap(
                    r -> handlePrivsResponse(username, preparedForPutConfig, r, masterNodeTimeout, listener),
                    listener::onFailure);

                client.execute(HasPrivilegesAction.INSTANCE, privRequest, privResponseListener);
            });
        } else {
            updateDocMappingAndPutConfig(
                preparedForPutConfig,
                threadPool.getThreadContext().getHeaders(),
                masterNodeTimeout,
                ActionListener.wrap(
                    unused -> listener.onResponse(new PutDataFrameAnalyticsAction.Response(preparedForPutConfig)),
                    listener::onFailure
                ));
        }
    }

    private void handlePrivsResponse(String username, DataFrameAnalyticsConfig memoryCappedConfig,
                                     HasPrivilegesResponse response,
                                     TimeValue masterNodeTimeout,
                                     ActionListener<PutDataFrameAnalyticsAction.Response> listener) throws IOException {
        if (response.isCompleteMatch()) {
            updateDocMappingAndPutConfig(
                memoryCappedConfig,
                threadPool.getThreadContext().getHeaders(),
                masterNodeTimeout,
                ActionListener.wrap(
                    unused -> listener.onResponse(new PutDataFrameAnalyticsAction.Response(memoryCappedConfig)),
                    listener::onFailure
            ));
        } else {
            XContentBuilder builder = JsonXContent.contentBuilder();
            builder.startObject();
            for (ResourcePrivileges index : response.getIndexPrivileges()) {
                builder.field(index.getResource());
                builder.map(index.getPrivileges());
            }
            builder.endObject();

            listener.onFailure(Exceptions.authorizationError("Cannot create data frame analytics [{}]" +
                    " because user {} lacks permissions on the indices: {}",
                    memoryCappedConfig.getId(), username, Strings.toString(builder)));
        }
    }

    private void updateDocMappingAndPutConfig(DataFrameAnalyticsConfig config,
                                              Map<String, String> headers,
                                              TimeValue masterNodeTimeout,
                                              ActionListener<DataFrameAnalyticsConfig> listener) {
        ClusterState clusterState = clusterService.state();
        if (clusterState == null) {
            logger.warn("Cannot update doc mapping because clusterState == null");
            configProvider.put(config, headers, masterNodeTimeout, listener);
            return;
        }
        ElasticsearchMappings.addDocMappingIfMissing(
            MlConfigIndex.indexName(),
            MlConfigIndex::mapping,
            client,
            clusterState,
            masterNodeTimeout,
            ActionListener.wrap(
                unused -> configProvider.put(config, headers, masterNodeTimeout, ActionListener.wrap(
                    indexResponse -> {
                        auditor.info(
                            config.getId(),
                            Messages.getMessage(Messages.DATA_FRAME_ANALYTICS_AUDIT_CREATED, config.getAnalysis().getWriteableName()));
                        listener.onResponse(config);
                    },
                    listener::onFailure)),
                listener::onFailure));
    }

    @Override
    protected void doExecute(Task task, PutDataFrameAnalyticsAction.Request request,
                             ActionListener<PutDataFrameAnalyticsAction.Response> listener) {
        if (licenseState.checkFeature(XPackLicenseState.Feature.MACHINE_LEARNING)) {
            super.doExecute(task, request, listener);
        } else {
            listener.onFailure(LicenseUtils.newComplianceException(XPackField.MACHINE_LEARNING));
        }
    }
}
