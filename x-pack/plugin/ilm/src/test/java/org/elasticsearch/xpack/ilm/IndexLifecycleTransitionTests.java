/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ilm;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.xpack.core.ilm.AbstractStepTestCase;
import org.elasticsearch.xpack.core.ilm.ErrorStep;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleAction;
import org.elasticsearch.xpack.core.ilm.LifecycleExecutionState;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicy;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicyMetadata;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicyTests;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.ilm.MockAction;
import org.elasticsearch.xpack.core.ilm.MockStep;
import org.elasticsearch.xpack.core.ilm.OperationMode;
import org.elasticsearch.xpack.core.ilm.Phase;
import org.elasticsearch.xpack.core.ilm.PhaseCompleteStep;
import org.elasticsearch.xpack.core.ilm.RolloverAction;
import org.elasticsearch.xpack.core.ilm.RolloverStep;
import org.elasticsearch.xpack.core.ilm.SetPriorityAction;
import org.elasticsearch.xpack.core.ilm.Step;
import org.elasticsearch.xpack.core.ilm.WaitForRolloverReadyStep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.core.ilm.LifecycleExecutionState.ILM_CUSTOM_METADATA_KEY;
import static org.elasticsearch.xpack.core.ilm.PhaseCacheManagement.eligibleToCheckForRefresh;
import static org.elasticsearch.xpack.core.ilm.PhaseCacheManagement.refreshPhaseDefinition;
import static org.elasticsearch.xpack.ilm.IndexLifecycleRunnerTests.createOneStepPolicyStepRegistry;
import static org.elasticsearch.xpack.ilm.IndexLifecycleTransition.moveStateToNextActionAndUpdateCachedPhase;
import static org.elasticsearch.xpack.ilm.LifecyclePolicyTestsUtils.newTestLifecyclePolicy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class IndexLifecycleTransitionTests extends ESTestCase {

    public void testMoveClusterStateToNextStep() {
        String indexName = "my_index";
        LifecyclePolicy policy = randomValueOtherThanMany(p -> p.getPhases().size() == 0,
            () -> LifecyclePolicyTests.randomTestLifecyclePolicy("policy"));
        Phase nextPhase = policy.getPhases().values().stream()
            .findFirst().orElseThrow(() -> new AssertionError("expected next phase to be present"));
        List<LifecyclePolicyMetadata> policyMetadatas = Collections.singletonList(
            new LifecyclePolicyMetadata(policy, Collections.emptyMap(), randomNonNegativeLong(), randomNonNegativeLong()));
        Step.StepKey currentStep = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey nextStep = new Step.StepKey(nextPhase.getName(), "next_action", "next_step");
        long now = randomNonNegativeLong();

        // test going from null lifecycle settings to next step
        ClusterState clusterState = buildClusterState(indexName,
            Settings.builder()
                .put(LifecycleSettings.LIFECYCLE_NAME, policy.getName()), LifecycleExecutionState.builder().build(), policyMetadatas);
        Index index = clusterState.metadata().index(indexName).getIndex();
        PolicyStepsRegistry stepsRegistry = createOneStepPolicyStepRegistry(policy.getName(),
            new MockStep(nextStep, nextStep));
        ClusterState newClusterState = IndexLifecycleTransition.moveClusterStateToStep(index, clusterState, nextStep,
            () -> now, stepsRegistry, false);
        assertClusterStateOnNextStep(clusterState, index, currentStep, nextStep, newClusterState, now);

        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        // test going from set currentStep settings to nextStep
        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, policy.getName());
        if (randomBoolean()) {
            lifecycleState.setStepInfo(randomAlphaOfLength(20));
        }

        clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        index = clusterState.metadata().index(indexName).getIndex();
        newClusterState = IndexLifecycleTransition.moveClusterStateToStep(index, clusterState,
            nextStep, () -> now, stepsRegistry, false);
        assertClusterStateOnNextStep(clusterState, index, currentStep, nextStep, newClusterState, now);
    }

    public void testMoveClusterStateToNextStepSamePhase() {
        String indexName = "my_index";
        LifecyclePolicy policy = randomValueOtherThanMany(p -> p.getPhases().size() == 0,
            () -> LifecyclePolicyTests.randomTestLifecyclePolicy("policy"));
        List<LifecyclePolicyMetadata> policyMetadatas = Collections.singletonList(
            new LifecyclePolicyMetadata(policy, Collections.emptyMap(), randomNonNegativeLong(), randomNonNegativeLong()));
        Step.StepKey currentStep = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey nextStep = new Step.StepKey("current_phase", "next_action", "next_step");
        long now = randomNonNegativeLong();

        ClusterState clusterState = buildClusterState(indexName,
            Settings.builder()
                .put(LifecycleSettings.LIFECYCLE_NAME, policy.getName()),
            LifecycleExecutionState.builder()
                .setPhase(currentStep.getPhase())
                .setAction(currentStep.getAction())
                .setStep(currentStep.getName())
                .build(), policyMetadatas);
        Index index = clusterState.metadata().index(indexName).getIndex();
        PolicyStepsRegistry stepsRegistry = createOneStepPolicyStepRegistry(policy.getName(),
            new MockStep(nextStep, nextStep));
        ClusterState newClusterState = IndexLifecycleTransition.moveClusterStateToStep(index, clusterState, nextStep,
            () -> now, stepsRegistry, false);
        assertClusterStateOnNextStep(clusterState, index, currentStep, nextStep, newClusterState, now);

        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        if (randomBoolean()) {
            lifecycleState.setStepInfo(randomAlphaOfLength(20));
        }

        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, policy.getName());

        clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        index = clusterState.metadata().index(indexName).getIndex();
        newClusterState = IndexLifecycleTransition.moveClusterStateToStep(index, clusterState, nextStep,
            () -> now, stepsRegistry, false);
        assertClusterStateOnNextStep(clusterState, index, currentStep, nextStep, newClusterState, now);
    }

    public void testMoveClusterStateToNextStepSameAction() {
        String indexName = "my_index";
        LifecyclePolicy policy = randomValueOtherThanMany(p -> p.getPhases().size() == 0,
            () -> LifecyclePolicyTests.randomTestLifecyclePolicy("policy"));
        List<LifecyclePolicyMetadata> policyMetadatas = Collections.singletonList(
            new LifecyclePolicyMetadata(policy, Collections.emptyMap(), randomNonNegativeLong(), randomNonNegativeLong()));
        Step.StepKey currentStep = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey nextStep = new Step.StepKey("current_phase", "current_action", "next_step");
        long now = randomNonNegativeLong();

        ClusterState clusterState = buildClusterState(indexName,
            Settings.builder()
                .put(LifecycleSettings.LIFECYCLE_NAME, policy.getName()),
            LifecycleExecutionState.builder()
                .setPhase(currentStep.getPhase())
                .setAction(currentStep.getAction())
                .setStep(currentStep.getName())
                .build(), policyMetadatas);
        Index index = clusterState.metadata().index(indexName).getIndex();
        PolicyStepsRegistry stepsRegistry = createOneStepPolicyStepRegistry(policy.getName(),
            new MockStep(nextStep, nextStep));
        ClusterState newClusterState = IndexLifecycleTransition.moveClusterStateToStep(index, clusterState, nextStep,
            () -> now, stepsRegistry, false);
        assertClusterStateOnNextStep(clusterState, index, currentStep, nextStep, newClusterState, now);

        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        if (randomBoolean()) {
            lifecycleState.setStepInfo(randomAlphaOfLength(20));
        }

        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, policy.getName());

        clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        index = clusterState.metadata().index(indexName).getIndex();
        newClusterState = IndexLifecycleTransition.moveClusterStateToStep(index, clusterState, nextStep,
            () -> now, stepsRegistry, false);
        assertClusterStateOnNextStep(clusterState, index, currentStep, nextStep, newClusterState, now);
    }

    public void testSuccessfulValidatedMoveClusterStateToNextStep() {
        String indexName = "my_index";
        String policyName = "my_policy";
        LifecyclePolicy policy = randomValueOtherThanMany(p -> p.getPhases().size() == 0,
            () -> LifecyclePolicyTests.randomTestLifecyclePolicy(policyName));
        Phase nextPhase = policy.getPhases().values().stream()
            .findFirst().orElseThrow(() -> new AssertionError("expected next phase to be present"));
        List<LifecyclePolicyMetadata> policyMetadatas = Collections.singletonList(
            new LifecyclePolicyMetadata(policy, Collections.emptyMap(), randomNonNegativeLong(), randomNonNegativeLong()));
        Step.StepKey currentStepKey = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey nextStepKey = new Step.StepKey(nextPhase.getName(), "next_action", "next_step");
        long now = randomNonNegativeLong();
        Step step = new MockStep(nextStepKey, nextStepKey);
        PolicyStepsRegistry stepRegistry = createOneStepPolicyStepRegistry(policyName, step);

        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStepKey.getPhase());
        lifecycleState.setAction(currentStepKey.getAction());
        lifecycleState.setStep(currentStepKey.getName());

        Settings.Builder indexSettingsBuilder = Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, policyName);
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        Index index = clusterState.metadata().index(indexName).getIndex();
        ClusterState newClusterState = IndexLifecycleTransition.moveClusterStateToStep(index, clusterState,
            nextStepKey, () -> now, stepRegistry, true);
        assertClusterStateOnNextStep(clusterState, index, currentStepKey, nextStepKey, newClusterState, now);
    }

    public void testValidatedMoveClusterStateToNextStepWithoutPolicy() {
        String indexName = "my_index";
        String policyName = "policy";
        Step.StepKey currentStepKey = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey nextStepKey = new Step.StepKey("next_phase", "next_action", "next_step");
        long now = randomNonNegativeLong();
        Step step = new MockStep(nextStepKey, nextStepKey);
        PolicyStepsRegistry stepRegistry = createOneStepPolicyStepRegistry(policyName, step);

        Settings.Builder indexSettingsBuilder = Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, randomBoolean() ? "" : null);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStepKey.getPhase());
        lifecycleState.setAction(currentStepKey.getAction());
        lifecycleState.setStep(currentStepKey.getName());

        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), Collections.emptyList());
        Index index = clusterState.metadata().index(indexName).getIndex();
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.moveClusterStateToStep(index, clusterState, nextStepKey, () -> now, stepRegistry, true));
        assertThat(exception.getMessage(), equalTo("index [my_index] is not associated with an Index Lifecycle Policy"));
    }

    public void testValidatedMoveClusterStateToNextStepInvalidNextStep() {
        String indexName = "my_index";
        String policyName = "my_policy";
        Step.StepKey currentStepKey = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey nextStepKey = new Step.StepKey("next_phase", "next_action", "next_step");
        long now = randomNonNegativeLong();
        Step step = new MockStep(currentStepKey, nextStepKey);
        PolicyStepsRegistry stepRegistry = createOneStepPolicyStepRegistry(policyName, step);

        Settings.Builder indexSettingsBuilder = Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, policyName);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStepKey.getPhase());
        lifecycleState.setAction(currentStepKey.getAction());
        lifecycleState.setStep(currentStepKey.getName());

        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), Collections.emptyList());
        Index index = clusterState.metadata().index(indexName).getIndex();
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.moveClusterStateToStep(index, clusterState, nextStepKey, () -> now, stepRegistry, true));
        assertThat(exception.getMessage(),
            equalTo("step [{\"phase\":\"next_phase\",\"action\":\"next_action\",\"name\":\"next_step\"}] " +
                "for index [my_index] with policy [my_policy] does not exist"));
    }

    public void testMoveClusterStateToErrorStep() throws IOException {
        String indexName = "my_index";
        Step.StepKey currentStep = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey nextStepKey = new Step.StepKey("next_phase", "next_action", "next_step");
        long now = randomNonNegativeLong();
        Exception cause = new ElasticsearchException("THIS IS AN EXPECTED CAUSE");

        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        ClusterState clusterState = buildClusterState(indexName, Settings.builder(), lifecycleState.build(), Collections.emptyList());
        Index index = clusterState.metadata().index(indexName).getIndex();

        ClusterState newClusterState = IndexLifecycleTransition.moveClusterStateToErrorStep(index, clusterState, cause,
            () -> now, (idxMeta, stepKey) -> new MockStep(stepKey, nextStepKey));
        assertClusterStateOnErrorStep(clusterState, index, currentStep, newClusterState, now,
            "{\"type\":\"exception\",\"reason\":\"THIS IS AN EXPECTED CAUSE\"");

        cause = new IllegalArgumentException("non elasticsearch-exception");
        newClusterState = IndexLifecycleTransition.moveClusterStateToErrorStep(index, clusterState, cause, () -> now,
            (idxMeta, stepKey) -> new MockStep(stepKey, nextStepKey));
        assertClusterStateOnErrorStep(clusterState, index, currentStep, newClusterState, now,
            "{\"type\":\"illegal_argument_exception\",\"reason\":\"non elasticsearch-exception\",\"stack_trace\":\"");
    }

    public void testAddStepInfoToClusterState() throws IOException {
        String indexName = "my_index";
        Step.StepKey currentStep = new Step.StepKey("current_phase", "current_action", "current_step");
        RandomStepInfo stepInfo = new RandomStepInfo(() -> randomAlphaOfLength(10));

        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        ClusterState clusterState = buildClusterState(indexName, Settings.builder(), lifecycleState.build(), Collections.emptyList());
        Index index = clusterState.metadata().index(indexName).getIndex();
        ClusterState newClusterState = IndexLifecycleTransition.addStepInfoToClusterState(index, clusterState, stepInfo);
        assertClusterStateStepInfo(clusterState, index, currentStep, newClusterState, stepInfo);
        ClusterState runAgainClusterState = IndexLifecycleTransition.addStepInfoToClusterState(index, newClusterState, stepInfo);
        assertSame(newClusterState, runAgainClusterState);
    }


    public void testRemovePolicyForIndex() {
        String indexName = randomAlphaOfLength(10);
        String oldPolicyName = "old_policy";
        Step.StepKey currentStep = new Step.StepKey(randomAlphaOfLength(10), MockAction.NAME, randomAlphaOfLength(10));
        LifecyclePolicy oldPolicy = createPolicy(oldPolicyName, currentStep, null);
        Settings.Builder indexSettingsBuilder = Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, oldPolicyName);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        List<LifecyclePolicyMetadata> policyMetadatas = new ArrayList<>();
        policyMetadatas.add(new LifecyclePolicyMetadata(oldPolicy, Collections.emptyMap(),
            randomNonNegativeLong(), randomNonNegativeLong()));
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        Index index = clusterState.metadata().index(indexName).getIndex();
        Index[] indices = new Index[] { index };
        List<String> failedIndexes = new ArrayList<>();

        ClusterState newClusterState = IndexLifecycleTransition.removePolicyForIndexes(indices, clusterState, failedIndexes);

        assertTrue(failedIndexes.isEmpty());
        assertIndexNotManagedByILM(newClusterState, index);
    }

    public void testRemovePolicyForIndexNoCurrentPolicy() {
        String indexName = randomAlphaOfLength(10);
        Settings.Builder indexSettingsBuilder = Settings.builder();
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, LifecycleExecutionState.builder().build(),
            Collections.emptyList());
        Index index = clusterState.metadata().index(indexName).getIndex();
        Index[] indices = new Index[] { index };
        List<String> failedIndexes = new ArrayList<>();

        ClusterState newClusterState = IndexLifecycleTransition.removePolicyForIndexes(indices, clusterState, failedIndexes);

        assertTrue(failedIndexes.isEmpty());
        assertIndexNotManagedByILM(newClusterState, index);
    }

    public void testRemovePolicyForIndexIndexDoesntExist() {
        String indexName = randomAlphaOfLength(10);
        String oldPolicyName = "old_policy";
        LifecyclePolicy oldPolicy = newTestLifecyclePolicy(oldPolicyName, Collections.emptyMap());
        Step.StepKey currentStep = AbstractStepTestCase.randomStepKey();
        Settings.Builder indexSettingsBuilder = Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, oldPolicyName);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        List<LifecyclePolicyMetadata> policyMetadatas = new ArrayList<>();
        policyMetadatas.add(new LifecyclePolicyMetadata(oldPolicy, Collections.emptyMap(),
            randomNonNegativeLong(), randomNonNegativeLong()));
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        Index index = new Index("doesnt_exist", "im_not_here");
        Index[] indices = new Index[] { index };
        List<String> failedIndexes = new ArrayList<>();

        ClusterState newClusterState = IndexLifecycleTransition.removePolicyForIndexes(indices, clusterState, failedIndexes);

        assertEquals(1, failedIndexes.size());
        assertEquals("doesnt_exist", failedIndexes.get(0));
        assertSame(clusterState, newClusterState);
    }

    public void testRemovePolicyForIndexIndexInUnsafe() {
        String indexName = randomAlphaOfLength(10);
        String oldPolicyName = "old_policy";
        Step.StepKey currentStep = new Step.StepKey(randomAlphaOfLength(10), MockAction.NAME, randomAlphaOfLength(10));
        LifecyclePolicy oldPolicy = createPolicy(oldPolicyName, null, currentStep);
        Settings.Builder indexSettingsBuilder = Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, oldPolicyName);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        List<LifecyclePolicyMetadata> policyMetadatas = new ArrayList<>();
        policyMetadatas.add(new LifecyclePolicyMetadata(oldPolicy, Collections.emptyMap(),
            randomNonNegativeLong(), randomNonNegativeLong()));
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        Index index = clusterState.metadata().index(indexName).getIndex();
        Index[] indices = new Index[] { index };
        List<String> failedIndexes = new ArrayList<>();

        ClusterState newClusterState = IndexLifecycleTransition.removePolicyForIndexes(indices, clusterState, failedIndexes);

        assertTrue(failedIndexes.isEmpty());
        assertIndexNotManagedByILM(newClusterState, index);
    }

    public void testRemovePolicyWithIndexingComplete() {
        String indexName = randomAlphaOfLength(10);
        String oldPolicyName = "old_policy";
        Step.StepKey currentStep = new Step.StepKey(randomAlphaOfLength(10), MockAction.NAME, randomAlphaOfLength(10));
        LifecyclePolicy oldPolicy = createPolicy(oldPolicyName, null, currentStep);
        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, oldPolicyName)
            .put(LifecycleSettings.LIFECYCLE_INDEXING_COMPLETE, true);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(currentStep.getPhase());
        lifecycleState.setAction(currentStep.getAction());
        lifecycleState.setStep(currentStep.getName());
        List<LifecyclePolicyMetadata> policyMetadatas = new ArrayList<>();
        policyMetadatas.add(new LifecyclePolicyMetadata(oldPolicy, Collections.emptyMap(),
            randomNonNegativeLong(), randomNonNegativeLong()));
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), policyMetadatas);
        Index index = clusterState.metadata().index(indexName).getIndex();
        Index[] indices = new Index[] { index };
        List<String> failedIndexes = new ArrayList<>();

        ClusterState newClusterState = IndexLifecycleTransition.removePolicyForIndexes(indices, clusterState, failedIndexes);

        assertTrue(failedIndexes.isEmpty());
        assertIndexNotManagedByILM(newClusterState, index);
    }

    public void testValidateTransitionThrowsExceptionForMissingIndexPolicy() {
        IndexMetadata indexMetadata = IndexMetadata.builder("index").settings(settings(Version.CURRENT))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();

        Step.StepKey currentStepKey = new Step.StepKey("hot", "action", "firstStep");
        Step.StepKey nextStepKey = new Step.StepKey("hot", "action", "secondStep");
        Step currentStep = new MockStep(currentStepKey, nextStepKey);
        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry("policy", currentStep);

        expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.validateTransition(indexMetadata, currentStepKey, nextStepKey, policyRegistry));
    }

    public void testValidateTransitionThrowsExceptionIfTheCurrentStepIsIncorrect() {
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase("hot");
        lifecycleState.setAction("action");
        lifecycleState.setStep("another_step");
        String policy = "policy";
        IndexMetadata indexMetadata = buildIndexMetadata(policy, lifecycleState);

        Step.StepKey currentStepKey = new Step.StepKey("hot", "action", "firstStep");
        Step.StepKey nextStepKey = new Step.StepKey("hot", "action", "secondStep");
        Step currentStep = new MockStep(currentStepKey, nextStepKey);
        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policy, currentStep);

        expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.validateTransition(indexMetadata, currentStepKey, nextStepKey, policyRegistry));
    }

    public void testValidateTransitionThrowsExceptionIfNextStepDoesNotExist() {
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase("hot");
        lifecycleState.setAction("action");
        lifecycleState.setStep("firstStep");
        String policy = "policy";
        IndexMetadata indexMetadata = buildIndexMetadata(policy, lifecycleState);

        Step.StepKey currentStepKey = new Step.StepKey("hot", "action", "firstStep");
        Step.StepKey nextStepKey = new Step.StepKey("hot", "action", "secondStep");
        Step currentStep = new MockStep(currentStepKey, nextStepKey);
        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policy, currentStep);

        expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.validateTransition(indexMetadata, currentStepKey, nextStepKey, policyRegistry));
    }

    public void testValidateValidTransition() {
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase("hot");
        lifecycleState.setAction("action");
        lifecycleState.setStep("firstStep");
        String policy = "policy";
        IndexMetadata indexMetadata = buildIndexMetadata(policy, lifecycleState);

        Step.StepKey currentStepKey = new Step.StepKey("hot", "action", "firstStep");
        Step.StepKey nextStepKey = new Step.StepKey("hot", "action", "secondStep");
        Step finalStep = new MockStep(nextStepKey, new Step.StepKey("hot", "action", "completed"));
        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policy, finalStep);

        try {
            IndexLifecycleTransition.validateTransition(indexMetadata, currentStepKey, nextStepKey, policyRegistry);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            fail("validateTransition should not throw exception on valid transitions");
        }
    }

    public void testValidateTransitionToCachedStepMissingFromPolicy() {
        LifecycleExecutionState.Builder executionState = LifecycleExecutionState.builder()
            .setPhase("hot")
            .setAction("rollover")
            .setStep("check-rollover-ready")
            .setPhaseDefinition("{\n" +
                "        \"policy\" : \"my-policy\",\n" +
                "        \"phase_definition\" : {\n" +
                "          \"min_age\" : \"20m\",\n" +
                "          \"actions\" : {\n" +
                "            \"rollover\" : {\n" +
                "              \"max_age\" : \"5s\"\n" +
                "            },\n" +
                "            \"set_priority\" : {\n" +
                "              \"priority\" : 150\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"version\" : 1,\n" +
                "        \"modified_date_in_millis\" : 1578521007076\n" +
                "      }");

        IndexMetadata meta = buildIndexMetadata("my-policy", executionState);

        Map<String, LifecycleAction> actions = new HashMap<>();
        actions.put(SetPriorityAction.NAME, new SetPriorityAction(100));
        Phase hotPhase = new Phase("hot", TimeValue.ZERO, actions);
        Map<String, Phase> phases = Collections.singletonMap("hot", hotPhase);
        LifecyclePolicy policyWithoutRollover = new LifecyclePolicy("my-policy", phases);
        LifecyclePolicyMetadata policyMetadata = new LifecyclePolicyMetadata(policyWithoutRollover, Collections.emptyMap(), 2L, 2L);

        ClusterState existingState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .metadata(Metadata.builder(Metadata.EMPTY_METADATA)
                .put(meta, false)
                .build())
            .build();
        try (Client client = new NoOpClient(getTestName())) {
            Step.StepKey currentStepKey = new Step.StepKey("hot", RolloverAction.NAME, WaitForRolloverReadyStep.NAME);
            Step.StepKey nextStepKey = new Step.StepKey("hot", RolloverAction.NAME, RolloverStep.NAME);
            Step currentStep = new WaitForRolloverReadyStep(currentStepKey, nextStepKey, client, null, null, null, 1L);
            try {
                IndexLifecycleTransition.validateTransition(meta, currentStepKey, nextStepKey, createOneStepPolicyStepRegistry("my-policy",
                    currentStep));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                fail("validateTransition should not throw exception on valid transitions");
            }
        }
    }

    public void testMoveClusterStateToFailedStep() {
        String indexName = "my_index";
        String policyName = "my_policy";
        long now = randomNonNegativeLong();
        Step.StepKey failedStepKey = new Step.StepKey("current_phase", MockAction.NAME, "current_step");
        Step.StepKey errorStepKey = new Step.StepKey(failedStepKey.getPhase(), failedStepKey.getAction(), ErrorStep.NAME);
        Step step = new MockStep(failedStepKey, null);
        LifecyclePolicy policy = createPolicy(policyName, failedStepKey, null);
        LifecyclePolicyMetadata policyMetadata = new LifecyclePolicyMetadata(policy, Collections.emptyMap(),
            randomNonNegativeLong(), randomNonNegativeLong());

        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policyName, step);
        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, policyName);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(errorStepKey.getPhase());
        lifecycleState.setPhaseTime(now);
        lifecycleState.setAction(errorStepKey.getAction());
        lifecycleState.setActionTime(now);
        lifecycleState.setStep(errorStepKey.getName());
        lifecycleState.setStepTime(now);
        lifecycleState.setFailedStep(failedStepKey.getName());
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(),
            Collections.singletonList(policyMetadata));
        Index index = clusterState.metadata().index(indexName).getIndex();
        ClusterState nextClusterState = IndexLifecycleTransition.moveClusterStateToPreviouslyFailedStep(clusterState,
            indexName, () -> now, policyRegistry, false);
        IndexLifecycleRunnerTests.assertClusterStateOnNextStep(clusterState, index, errorStepKey, failedStepKey,
            nextClusterState, now);
        LifecycleExecutionState executionState = LifecycleExecutionState.fromIndexMetadata(nextClusterState.metadata().index(indexName));
        assertThat("manual move to failed step should not count as a retry", executionState.getFailedStepRetryCount(), is(nullValue()));
    }

    public void testMoveClusterStateToFailedStepWithUnknownStep() {
        String indexName = "my_index";
        String policyName = "my_policy";
        long now = randomNonNegativeLong();
        Step.StepKey failedStepKey = new Step.StepKey("current_phase", MockAction.NAME, "current_step");
        Step.StepKey errorStepKey = new Step.StepKey(failedStepKey.getPhase(), failedStepKey.getAction(), ErrorStep.NAME);

        Step.StepKey registeredStepKey = new Step.StepKey(randomFrom(failedStepKey.getPhase(), "other"),
            MockAction.NAME, "different_step");
        Step step = new MockStep(registeredStepKey, null);
        LifecyclePolicy policy = createPolicy(policyName, failedStepKey, null);
        LifecyclePolicyMetadata policyMetadata = new LifecyclePolicyMetadata(policy, Collections.emptyMap(),
            randomNonNegativeLong(), randomNonNegativeLong());

        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policyName, step);
        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, policyName);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(errorStepKey.getPhase());
        lifecycleState.setPhaseTime(now);
        lifecycleState.setAction(errorStepKey.getAction());
        lifecycleState.setActionTime(now);
        lifecycleState.setStep(errorStepKey.getName());
        lifecycleState.setStepTime(now);
        lifecycleState.setFailedStep(failedStepKey.getName());
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(),
            Collections.singletonList(policyMetadata));
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.moveClusterStateToPreviouslyFailedStep(clusterState,
                indexName, () -> now, policyRegistry, false));
        assertThat(exception.getMessage(), equalTo("step [" + failedStepKey
            + "] for index [my_index] with policy [my_policy] does not exist"));
    }

    public void testMoveClusterStateToFailedStepIndexNotFound() {
        String existingIndexName = "my_index";
        String invalidIndexName = "does_not_exist";
        ClusterState clusterState = buildClusterState(existingIndexName, Settings.builder(), LifecycleExecutionState.builder().build(),
            Collections.emptyList());
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.moveClusterStateToPreviouslyFailedStep(clusterState,
                invalidIndexName, () -> 0L, null, false));
        assertThat(exception.getMessage(), equalTo("index [" + invalidIndexName + "] does not exist"));
    }

    public void testMoveClusterStateToFailedStepInvalidPolicySetting() {
        String indexName = "my_index";
        String policyName = "my_policy";
        long now = randomNonNegativeLong();
        Step.StepKey failedStepKey = new Step.StepKey("current_phase", "current_action", "current_step");
        Step.StepKey errorStepKey = new Step.StepKey(failedStepKey.getPhase(), failedStepKey.getAction(), ErrorStep.NAME);
        Step step = new MockStep(failedStepKey, null);
        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policyName, step);
        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, (String) null);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(errorStepKey.getPhase());
        lifecycleState.setAction(errorStepKey.getAction());
        lifecycleState.setStep(errorStepKey.getName());
        lifecycleState.setFailedStep(failedStepKey.getName());
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), Collections.emptyList());
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.moveClusterStateToPreviouslyFailedStep(clusterState,
                indexName, () -> now, policyRegistry, false));
        assertThat(exception.getMessage(), equalTo("index [" + indexName + "] is not associated with an Index Lifecycle Policy"));
    }

    public void testMoveClusterStateToFailedNotOnError() {
        String indexName = "my_index";
        String policyName = "my_policy";
        long now = randomNonNegativeLong();
        Step.StepKey failedStepKey = new Step.StepKey("current_phase", "current_action", "current_step");
        Step step = new MockStep(failedStepKey, null);
        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policyName, step);
        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, (String) null);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(failedStepKey.getPhase());
        lifecycleState.setAction(failedStepKey.getAction());
        lifecycleState.setStep(failedStepKey.getName());
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(), Collections.emptyList());
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> IndexLifecycleTransition.moveClusterStateToPreviouslyFailedStep(clusterState,
                indexName, () -> now, policyRegistry, false));
        assertThat(exception.getMessage(), equalTo("cannot retry an action for an index [" + indexName
            + "] that has not encountered an error when running a Lifecycle Policy"));
    }

    public void testMoveClusterStateToPreviouslyFailedStepAsAutomaticRetry() {
        String indexName = "my_index";
        String policyName = "my_policy";
        long now = randomNonNegativeLong();
        Step.StepKey failedStepKey = new Step.StepKey("current_phase", MockAction.NAME, "current_step");
        Step.StepKey errorStepKey = new Step.StepKey(failedStepKey.getPhase(), failedStepKey.getAction(), ErrorStep.NAME);
        Step retryableStep = new IndexLifecycleRunnerTests.RetryableMockStep(failedStepKey, null);
        LifecyclePolicy policy = createPolicy(policyName, failedStepKey, null);
        LifecyclePolicyMetadata policyMetadata = new LifecyclePolicyMetadata(policy, Collections.emptyMap(),
            randomNonNegativeLong(), randomNonNegativeLong());

        PolicyStepsRegistry policyRegistry = createOneStepPolicyStepRegistry(policyName, retryableStep);
        Settings.Builder indexSettingsBuilder = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, policyName);
        LifecycleExecutionState.Builder lifecycleState = LifecycleExecutionState.builder();
        lifecycleState.setPhase(errorStepKey.getPhase());
        lifecycleState.setPhaseTime(now);
        lifecycleState.setAction(errorStepKey.getAction());
        lifecycleState.setActionTime(now);
        lifecycleState.setStep(errorStepKey.getName());
        lifecycleState.setStepTime(now);
        lifecycleState.setFailedStep(failedStepKey.getName());
        ClusterState clusterState = buildClusterState(indexName, indexSettingsBuilder, lifecycleState.build(),
            Collections.singletonList(policyMetadata));
        Index index = clusterState.metadata().index(indexName).getIndex();
        ClusterState nextClusterState = IndexLifecycleTransition.moveClusterStateToPreviouslyFailedStep(clusterState,
            indexName, () -> now, policyRegistry, true);
        IndexLifecycleRunnerTests.assertClusterStateOnNextStep(clusterState, index, errorStepKey, failedStepKey,
            nextClusterState, now);
        LifecycleExecutionState executionState = LifecycleExecutionState.fromIndexMetadata(nextClusterState.metadata().index(indexName));
        assertThat(executionState.getFailedStepRetryCount(), is(1));
    }

    public void testRefreshPhaseJson() {
        LifecycleExecutionState.Builder exState = LifecycleExecutionState.builder()
            .setPhase("hot")
            .setAction("rollover")
            .setStep("check-rollover-ready")
            .setPhaseDefinition("{\n" +
                "        \"policy\" : \"my-policy\",\n" +
                "        \"phase_definition\" : {\n" +
                "          \"min_age\" : \"20m\",\n" +
                "          \"actions\" : {\n" +
                "            \"rollover\" : {\n" +
                "              \"max_age\" : \"5s\"\n" +
                "            },\n" +
                "            \"set_priority\" : {\n" +
                "              \"priority\" : 150\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"version\" : 1,\n" +
                "        \"modified_date_in_millis\" : 1578521007076\n" +
                "      }");

        IndexMetadata meta = buildIndexMetadata("my-policy", exState);
        String index = meta.getIndex().getName();

        Map<String, LifecycleAction> actions = new HashMap<>();
        actions.put("rollover", new RolloverAction(null, null, null, 1L));
        actions.put("set_priority", new SetPriorityAction(100));
        Phase hotPhase = new Phase("hot", TimeValue.ZERO, actions);
        Map<String, Phase> phases = Collections.singletonMap("hot", hotPhase);
        LifecyclePolicy newPolicy = new LifecyclePolicy("my-policy", phases);
        LifecyclePolicyMetadata policyMetadata = new LifecyclePolicyMetadata(newPolicy, Collections.emptyMap(), 2L, 2L);

        ClusterState existingState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .metadata(Metadata.builder(Metadata.EMPTY_METADATA)
                .put(meta, false)
                .build())
            .build();

        ClusterState changedState = refreshPhaseDefinition(existingState, index, policyMetadata);

        IndexMetadata newIdxMeta = changedState.metadata().index(index);
        LifecycleExecutionState afterExState = LifecycleExecutionState.fromIndexMetadata(newIdxMeta);
        Map<String, String> beforeState = new HashMap<>(exState.build().asMap());
        beforeState.remove("phase_definition");
        Map<String, String> afterState = new HashMap<>(afterExState.asMap());
        afterState.remove("phase_definition");
        // Check that no other execution state changes have been made
        assertThat(beforeState, equalTo(afterState));

        // Check that the phase definition has been refreshed
        assertThat(afterExState.getPhaseDefinition(),
            equalTo("{\"policy\":\"my-policy\",\"phase_definition\":{\"min_age\":\"0ms\",\"actions\":{\"rollover\":{\"max_docs\":1}," +
                "\"set_priority\":{\"priority\":100}}},\"version\":2,\"modified_date_in_millis\":2}"));
    }

    public void testEligibleForRefresh() {
        IndexMetadata meta = IndexMetadata.builder("index")
            .settings(Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 10))
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 5))
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, randomAlphaOfLength(5)))
            .build();
        assertFalse(eligibleToCheckForRefresh(meta));

        LifecycleExecutionState state = LifecycleExecutionState.builder().build();
        meta = IndexMetadata.builder("index")
            .settings(Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 10))
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 5))
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, randomAlphaOfLength(5)))
            .putCustom(ILM_CUSTOM_METADATA_KEY, state.asMap())
            .build();
        assertFalse(eligibleToCheckForRefresh(meta));

        state = LifecycleExecutionState.builder()
            .setPhase("phase")
            .setAction("action")
            .setStep("step")
            .build();
        meta = IndexMetadata.builder("index")
            .settings(Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 10))
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 5))
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, randomAlphaOfLength(5)))
            .putCustom(ILM_CUSTOM_METADATA_KEY, state.asMap())
            .build();
        assertFalse(eligibleToCheckForRefresh(meta));

        state = LifecycleExecutionState.builder()
            .setPhaseDefinition("{}")
            .build();
        meta = IndexMetadata.builder("index")
            .settings(Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 10))
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 5))
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, randomAlphaOfLength(5)))
            .putCustom(ILM_CUSTOM_METADATA_KEY, state.asMap())
            .build();
        assertFalse(eligibleToCheckForRefresh(meta));

        state = LifecycleExecutionState.builder()
            .setPhase("phase")
            .setAction("action")
            .setStep(ErrorStep.NAME)
            .setPhaseDefinition("{}")
            .build();
        meta = IndexMetadata.builder("index")
            .settings(Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 10))
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 5))
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, randomAlphaOfLength(5)))
            .putCustom(ILM_CUSTOM_METADATA_KEY, state.asMap())
            .build();
        assertFalse(eligibleToCheckForRefresh(meta));

        state = LifecycleExecutionState.builder()
            .setPhase("phase")
            .setAction("action")
            .setStep("step")
            .setPhaseDefinition("{}")
            .build();
        meta = IndexMetadata.builder("index")
            .settings(Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 10))
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 5))
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_INDEX_UUID, randomAlphaOfLength(5)))
            .putCustom(ILM_CUSTOM_METADATA_KEY, state.asMap())
            .build();
        assertTrue(eligibleToCheckForRefresh(meta));
    }

    public void testMoveStateToNextActionAndUpdateCachedPhase() {
        LifecycleExecutionState.Builder currentExecutionState = LifecycleExecutionState.builder()
            .setPhase("hot")
            .setAction("rollover")
            .setStep("check-rollover-ready")
            .setPhaseDefinition("{\n" +
                "        \"policy\" : \"my-policy\",\n" +
                "        \"phase_definition\" : {\n" +
                "          \"min_age\" : \"20m\",\n" +
                "          \"actions\" : {\n" +
                "            \"rollover\" : {\n" +
                "              \"max_age\" : \"5s\"\n" +
                "            },\n" +
                "            \"set_priority\" : {\n" +
                "              \"priority\" : 150\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"version\" : 1,\n" +
                "        \"modified_date_in_millis\" : 1578521007076\n" +
                "      }");

        IndexMetadata meta = buildIndexMetadata("my-policy", currentExecutionState);

        Map<String, LifecycleAction> actions = new HashMap<>();
        actions.put("rollover", new RolloverAction(null, null, null, 1L));
        actions.put("set_priority", new SetPriorityAction(100));
        Phase hotPhase = new Phase("hot", TimeValue.ZERO, actions);
        Map<String, Phase> phases = Collections.singletonMap("hot", hotPhase);
        LifecyclePolicy currentPolicy = new LifecyclePolicy("my-policy", phases);

        {
            // test index is in step for action that was removed in the updated policy
            // the expected new state is that the index was moved into the next *action* and its cached phase definition updated to reflect
            // the updated policy
            Map<String, LifecycleAction> actionsWithoutRollover = new HashMap<>();
            actionsWithoutRollover.put("set_priority", new SetPriorityAction(100));
            Phase hotPhaseNoRollover = new Phase("hot", TimeValue.ZERO, actionsWithoutRollover);
            Map<String, Phase> phasesNoRollover = Collections.singletonMap("hot", hotPhaseNoRollover);
            LifecyclePolicyMetadata updatedPolicyMetadata = new LifecyclePolicyMetadata(new LifecyclePolicy("my-policy",
                phasesNoRollover), Collections.emptyMap(), 2L, 2L);

            try (Client client = new NoOpClient(getTestName())) {
                LifecycleExecutionState newState = moveStateToNextActionAndUpdateCachedPhase(meta,
                    LifecycleExecutionState.fromIndexMetadata(meta), System::currentTimeMillis, currentPolicy, updatedPolicyMetadata,
                    client, null);

                Step.StepKey hotPhaseCompleteStepKey = PhaseCompleteStep.finalStep("hot").getKey();
                assertThat(newState.getAction(), is(hotPhaseCompleteStepKey.getAction()));
                assertThat(newState.getStep(), is(hotPhaseCompleteStepKey.getName()));
                assertThat("the cached phase should not contain rollover anymore", newState.getPhaseDefinition(),
                    not(containsString(RolloverAction.NAME)));
            }
        }

        {
            // test that the index is in an action that still exists in the update policy
            // the expected new state is that the index is moved into the next action (could be the complete one) and the cached phase
            // definition is updated
            Map<String, LifecycleAction> actionsWitoutSetPriority = new HashMap<>();
            actionsWitoutSetPriority.put("rollover", new RolloverAction(null, null, null, 1L));
            Phase hotPhaseNoSetPriority = new Phase("hot", TimeValue.ZERO, actionsWitoutSetPriority);
            Map<String, Phase> phasesWithoutSetPriority = Collections.singletonMap("hot", hotPhaseNoSetPriority);
            LifecyclePolicyMetadata updatedPolicyMetadata = new LifecyclePolicyMetadata(new LifecyclePolicy("my-policy",
                phasesWithoutSetPriority), Collections.emptyMap(), 2L, 2L);

            try (Client client = new NoOpClient(getTestName())) {
                LifecycleExecutionState newState = moveStateToNextActionAndUpdateCachedPhase(meta,
                    LifecycleExecutionState.fromIndexMetadata(meta), System::currentTimeMillis, currentPolicy, updatedPolicyMetadata,
                    client, null);

                Step.StepKey hotPhaseCompleteStepKey = PhaseCompleteStep.finalStep("hot").getKey();
                // the state was still moved into the next action, even if the updated policy still contained the action the index was
                // currently executing
                assertThat(newState.getAction(), is(hotPhaseCompleteStepKey.getAction()));
                assertThat(newState.getStep(), is(hotPhaseCompleteStepKey.getName()));
                assertThat(newState.getPhaseDefinition(), containsString(RolloverAction.NAME));
                assertThat("the cached phase should not contain set_priority anymore", newState.getPhaseDefinition(),
                    not(containsString(SetPriorityAction.NAME)));
            }
        }
    }

    private static LifecyclePolicy createPolicy(String policyName, Step.StepKey safeStep, Step.StepKey unsafeStep) {
        Map<String, Phase> phases = new HashMap<>();
        if (safeStep != null) {
            assert MockAction.NAME.equals(safeStep.getAction()) : "The safe action needs to be MockAction.NAME";
            assert unsafeStep == null
                || safeStep.getPhase().equals(unsafeStep.getPhase()) == false : "safe and unsafe actions must be in different phases";
            Map<String, LifecycleAction> actions = new HashMap<>();
            List<Step> steps = Collections.singletonList(new MockStep(safeStep, null));
            MockAction safeAction = new MockAction(steps, true);
            actions.put(safeAction.getWriteableName(), safeAction);
            Phase phase = new Phase(safeStep.getPhase(), TimeValue.timeValueMillis(0), actions);
            phases.put(phase.getName(), phase);
        }
        if (unsafeStep != null) {
            assert MockAction.NAME.equals(unsafeStep.getAction()) : "The unsafe action needs to be MockAction.NAME";
            Map<String, LifecycleAction> actions = new HashMap<>();
            List<Step> steps = Collections.singletonList(new MockStep(unsafeStep, null));
            MockAction unsafeAction = new MockAction(steps, false);
            actions.put(unsafeAction.getWriteableName(), unsafeAction);
            Phase phase = new Phase(unsafeStep.getPhase(), TimeValue.timeValueMillis(0), actions);
            phases.put(phase.getName(), phase);
        }
        return newTestLifecyclePolicy(policyName, phases);
    }

    private ClusterState buildClusterState(String indexName, Settings.Builder indexSettingsBuilder,
                                           LifecycleExecutionState lifecycleState,
                                           List<LifecyclePolicyMetadata> lifecyclePolicyMetadatas) {
        Settings indexSettings = indexSettingsBuilder.put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0).put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT).build();
        IndexMetadata indexMetadata = IndexMetadata.builder(indexName)
            .settings(indexSettings)
            .putCustom(ILM_CUSTOM_METADATA_KEY, lifecycleState.asMap())
            .build();

        Map<String, LifecyclePolicyMetadata> lifecyclePolicyMetadatasMap = lifecyclePolicyMetadatas.stream()
            .collect(Collectors.toMap(LifecyclePolicyMetadata::getName, Function.identity()));
        IndexLifecycleMetadata indexLifecycleMetadata = new IndexLifecycleMetadata(lifecyclePolicyMetadatasMap, OperationMode.RUNNING);

        Metadata metadata = Metadata.builder().put(indexMetadata, true).putCustom(IndexLifecycleMetadata.TYPE, indexLifecycleMetadata)
            .build();
        return ClusterState.builder(new ClusterName("my_cluster")).metadata(metadata).build();
    }

    public static void assertIndexNotManagedByILM(ClusterState clusterState, Index index) {
        Metadata metadata = clusterState.metadata();
        assertNotNull(metadata);
        IndexMetadata indexMetadata = metadata.getIndexSafe(index);
        assertNotNull(indexMetadata);
        Settings indexSettings = indexMetadata.getSettings();
        assertNotNull(indexSettings);
        assertFalse(LifecycleSettings.LIFECYCLE_NAME_SETTING.exists(indexSettings));
        assertFalse(RolloverAction.LIFECYCLE_ROLLOVER_ALIAS_SETTING.exists(indexSettings));
        assertFalse(LifecycleSettings.LIFECYCLE_INDEXING_COMPLETE_SETTING.exists(indexSettings));
    }

    public static void assertClusterStateOnNextStep(ClusterState oldClusterState, Index index, Step.StepKey currentStep,
                                                    Step.StepKey nextStep, ClusterState newClusterState, long now) {
        assertNotSame(oldClusterState, newClusterState);
        Metadata newMetadata = newClusterState.metadata();
        assertNotSame(oldClusterState.metadata(), newMetadata);
        IndexMetadata newIndexMetadata = newMetadata.getIndexSafe(index);
        assertNotSame(oldClusterState.metadata().index(index), newIndexMetadata);
        LifecycleExecutionState newLifecycleState = LifecycleExecutionState
            .fromIndexMetadata(newClusterState.metadata().index(index));
        LifecycleExecutionState oldLifecycleState = LifecycleExecutionState
            .fromIndexMetadata(oldClusterState.metadata().index(index));
        assertNotSame(oldLifecycleState, newLifecycleState);
        assertEquals(nextStep.getPhase(), newLifecycleState.getPhase());
        assertEquals(nextStep.getAction(), newLifecycleState.getAction());
        assertEquals(nextStep.getName(), newLifecycleState.getStep());
        if (currentStep.getPhase().equals(nextStep.getPhase())) {
            assertEquals("expected phase times to be the same but they were different",
                oldLifecycleState.getPhaseTime(), newLifecycleState.getPhaseTime());
        } else {
            assertEquals(now, newLifecycleState.getPhaseTime().longValue());
        }
        if (currentStep.getAction().equals(nextStep.getAction())) {
            assertEquals("expected action times to be the same but they were different",
                oldLifecycleState.getActionTime(), newLifecycleState.getActionTime());
        } else {
            assertEquals(now, newLifecycleState.getActionTime().longValue());
        }
        assertEquals(now, newLifecycleState.getStepTime().longValue());
        assertEquals(null, newLifecycleState.getFailedStep());
        assertEquals(null, newLifecycleState.getStepInfo());
    }

    private IndexMetadata buildIndexMetadata(String policy, LifecycleExecutionState.Builder lifecycleState) {
        return IndexMetadata.builder("index")
            .settings(settings(Version.CURRENT).put(LifecycleSettings.LIFECYCLE_NAME, policy))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .putCustom(ILM_CUSTOM_METADATA_KEY, lifecycleState.build().asMap())
            .build();
    }

    private void assertClusterStateOnErrorStep(ClusterState oldClusterState, Index index, Step.StepKey currentStep,
                                               ClusterState newClusterState, long now, String expectedCauseValue) {
        assertNotSame(oldClusterState, newClusterState);
        Metadata newMetadata = newClusterState.metadata();
        assertNotSame(oldClusterState.metadata(), newMetadata);
        IndexMetadata newIndexMetadata = newMetadata.getIndexSafe(index);
        assertNotSame(oldClusterState.metadata().index(index), newIndexMetadata);
        LifecycleExecutionState newLifecycleState = LifecycleExecutionState
            .fromIndexMetadata(newClusterState.metadata().index(index));
        LifecycleExecutionState oldLifecycleState = LifecycleExecutionState
            .fromIndexMetadata(oldClusterState.metadata().index(index));
        assertNotSame(oldLifecycleState, newLifecycleState);
        assertEquals(currentStep.getPhase(), newLifecycleState.getPhase());
        assertEquals(currentStep.getAction(), newLifecycleState.getAction());
        assertEquals(ErrorStep.NAME, newLifecycleState.getStep());
        assertEquals(currentStep.getName(), newLifecycleState.getFailedStep());
        assertThat(newLifecycleState.getStepInfo(), containsString(expectedCauseValue));
        assertEquals(oldLifecycleState.getPhaseTime(), newLifecycleState.getPhaseTime());
        assertEquals(oldLifecycleState.getActionTime(), newLifecycleState.getActionTime());
        assertEquals(now, newLifecycleState.getStepTime().longValue());
    }

    private void assertClusterStateStepInfo(ClusterState oldClusterState, Index index, Step.StepKey currentStep,
                                            ClusterState newClusterState, ToXContentObject stepInfo) throws IOException {
        XContentBuilder stepInfoXContentBuilder = JsonXContent.contentBuilder();
        stepInfo.toXContent(stepInfoXContentBuilder, ToXContent.EMPTY_PARAMS);
        String expectedstepInfoValue = BytesReference.bytes(stepInfoXContentBuilder).utf8ToString();
        assertNotSame(oldClusterState, newClusterState);
        Metadata newMetadata = newClusterState.metadata();
        assertNotSame(oldClusterState.metadata(), newMetadata);
        IndexMetadata newIndexMetadata = newMetadata.getIndexSafe(index);
        assertNotSame(oldClusterState.metadata().index(index), newIndexMetadata);
        LifecycleExecutionState newLifecycleState = LifecycleExecutionState
            .fromIndexMetadata(newClusterState.metadata().index(index));
        LifecycleExecutionState oldLifecycleState = LifecycleExecutionState
            .fromIndexMetadata(oldClusterState.metadata().index(index));
        assertNotSame(oldLifecycleState, newLifecycleState);
        assertEquals(currentStep.getPhase(), newLifecycleState.getPhase());
        assertEquals(currentStep.getAction(), newLifecycleState.getAction());
        assertEquals(currentStep.getName(), newLifecycleState.getStep());
        assertEquals(expectedstepInfoValue, newLifecycleState.getStepInfo());
        assertEquals(oldLifecycleState.getPhaseTime(), newLifecycleState.getPhaseTime());
        assertEquals(oldLifecycleState.getActionTime(), newLifecycleState.getActionTime());
        assertEquals(newLifecycleState.getStepTime(), newLifecycleState.getStepTime());
    }
}
