/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.client.ml.inference.trainedmodel.ensemble;

import org.elasticsearch.client.ml.inference.NamedXContentObjectHelper;
import org.elasticsearch.client.ml.inference.trainedmodel.TargetType;
import org.elasticsearch.client.ml.inference.trainedmodel.TrainedModel;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Ensemble implements TrainedModel {

    public static final String NAME = "ensemble";
    public static final ParseField FEATURE_NAMES = new ParseField("feature_names");
    public static final ParseField TRAINED_MODELS = new ParseField("trained_models");
    public static final ParseField AGGREGATE_OUTPUT  = new ParseField("aggregate_output");
    public static final ParseField CLASSIFICATION_LABELS = new ParseField("classification_labels");
    public static final ParseField CLASSIFICATION_WEIGHTS = new ParseField("classification_weights");

    private static final ObjectParser<Builder, Void> PARSER = new ObjectParser<>(
        NAME,
        true,
        Ensemble.Builder::new);

    static {
        PARSER.declareStringArray(Ensemble.Builder::setFeatureNames, FEATURE_NAMES);
        PARSER.declareNamedObjects(Ensemble.Builder::setTrainedModels,
            (p, c, n) ->
                    p.namedObject(TrainedModel.class, n, null),
            (ensembleBuilder) -> { /* Noop does not matter client side */ },
            TRAINED_MODELS);
        PARSER.declareNamedObject(Ensemble.Builder::setOutputAggregator,
            (p, c, n) -> p.namedObject(OutputAggregator.class, n, null),
            AGGREGATE_OUTPUT);
        PARSER.declareString(Ensemble.Builder::setTargetType, TargetType.TARGET_TYPE);
        PARSER.declareStringArray(Ensemble.Builder::setClassificationLabels, CLASSIFICATION_LABELS);
        PARSER.declareDoubleArray(Ensemble.Builder::setClassificationWeights, CLASSIFICATION_WEIGHTS);
    }

    public static Ensemble fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null).build();
    }

    private final List<String> featureNames;
    private final List<TrainedModel> models;
    private final OutputAggregator outputAggregator;
    private final TargetType targetType;
    private final List<String> classificationLabels;
    private final double[] classificationWeights;

    Ensemble(List<String> featureNames,
             List<TrainedModel> models,
             @Nullable OutputAggregator outputAggregator,
             TargetType targetType,
             @Nullable List<String> classificationLabels,
             @Nullable double[] classificationWeights) {
        this.featureNames = featureNames;
        this.models = models;
        this.outputAggregator = outputAggregator;
        this.targetType = targetType;
        this.classificationLabels = classificationLabels;
        this.classificationWeights = classificationWeights;
    }

    @Override
    public List<String> getFeatureNames() {
        return featureNames;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        builder.startObject();
        if (featureNames != null && featureNames.isEmpty() == false) {
            builder.field(FEATURE_NAMES.getPreferredName(), featureNames);
        }
        if (models != null) {
            NamedXContentObjectHelper.writeNamedObjects(builder, params, true, TRAINED_MODELS.getPreferredName(), models);
        }
        if (outputAggregator != null) {
            NamedXContentObjectHelper.writeNamedObjects(builder,
                params,
                false,
                AGGREGATE_OUTPUT.getPreferredName(),
                Collections.singletonList(outputAggregator));
        }
        if (targetType != null) {
            builder.field(TargetType.TARGET_TYPE.getPreferredName(), targetType);
        }
        if (classificationLabels != null) {
            builder.field(CLASSIFICATION_LABELS.getPreferredName(), classificationLabels);
        }
        if (classificationWeights != null) {
            builder.field(CLASSIFICATION_WEIGHTS.getPreferredName(), classificationWeights);
        }
        builder.endObject();
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Ensemble that = (Ensemble) o;
        return Objects.equals(featureNames, that.featureNames)
            && Objects.equals(models, that.models)
            && Objects.equals(targetType, that.targetType)
            && Objects.equals(classificationLabels, that.classificationLabels)
            && Arrays.equals(classificationWeights, that.classificationWeights)
            && Objects.equals(outputAggregator, that.outputAggregator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(featureNames,
            models,
            outputAggregator,
            classificationLabels,
            targetType,
            Arrays.hashCode(classificationWeights));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<String> featureNames = Collections.emptyList();
        private List<TrainedModel> trainedModels;
        private OutputAggregator outputAggregator;
        private TargetType targetType;
        private List<String> classificationLabels;
        private double[] classificationWeights;

        public Builder setFeatureNames(List<String> featureNames) {
            this.featureNames = featureNames;
            return this;
        }

        public Builder setTrainedModels(List<TrainedModel> trainedModels) {
            this.trainedModels = trainedModels;
            return this;
        }

        public Builder setOutputAggregator(OutputAggregator outputAggregator) {
            this.outputAggregator = outputAggregator;
            return this;
        }

        public Builder setTargetType(TargetType targetType) {
            this.targetType = targetType;
            return this;
        }

        public Builder setClassificationLabels(List<String> classificationLabels) {
            this.classificationLabels = classificationLabels;
            return this;
        }

        public Builder setClassificationWeights(List<Double> classificationWeights) {
            this.classificationWeights = classificationWeights.stream().mapToDouble(Double::doubleValue).toArray();
            return this;
        }


        private void setTargetType(String targetType) {
            this.targetType = TargetType.fromString(targetType);
        }

        public Ensemble build() {
            return new Ensemble(featureNames, trainedModels, outputAggregator, targetType, classificationLabels, classificationWeights);
        }
    }
}
