/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ml.datafeed.extractor.aggregation;

import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.core.ml.datafeed.extractor.DataExtractor;
import org.elasticsearch.xpack.core.ml.job.config.Job;
import org.elasticsearch.xpack.core.ml.utils.Intervals;
import org.elasticsearch.xpack.ml.datafeed.DatafeedTimingStatsReporter;
import org.elasticsearch.xpack.ml.datafeed.extractor.DataExtractorFactory;

import java.util.Objects;

public class AggregationDataExtractorFactory implements DataExtractorFactory {

    private final Client client;
    private final DatafeedConfig datafeedConfig;
    private final Job job;
    private final NamedXContentRegistry xContentRegistry;
    private final DatafeedTimingStatsReporter timingStatsReporter;

    public static AggregatedSearchRequestBuilder requestBuilder(
        Client client,
        String[] indices,
        IndicesOptions indicesOptions
    ) {
        return (searchSourceBuilder) ->
            new SearchRequestBuilder(client, SearchAction.INSTANCE)
                .setSource(searchSourceBuilder)
                .setIndicesOptions(indicesOptions)
                .setAllowPartialSearchResults(false)
                .setIndices(indices);
    }

    public AggregationDataExtractorFactory(
            Client client,
            DatafeedConfig datafeedConfig,
            Job job,
            NamedXContentRegistry xContentRegistry,
            DatafeedTimingStatsReporter timingStatsReporter) {
        this.client = Objects.requireNonNull(client);
        this.datafeedConfig = Objects.requireNonNull(datafeedConfig);
        this.job = Objects.requireNonNull(job);
        this.xContentRegistry = xContentRegistry;
        this.timingStatsReporter = Objects.requireNonNull(timingStatsReporter);
    }

    @Override
    public DataExtractor newExtractor(long start, long end) {
        long histogramInterval = datafeedConfig.getHistogramIntervalMillis(xContentRegistry);
        AggregationDataExtractorContext dataExtractorContext = new AggregationDataExtractorContext(
                job.getId(),
                job.getDataDescription().getTimeField(),
                job.getAnalysisConfig().analysisFields(),
                datafeedConfig.getIndices(),
                datafeedConfig.getParsedQuery(xContentRegistry),
                datafeedConfig.getParsedAggregations(xContentRegistry),
                Intervals.alignToCeil(start, histogramInterval),
                Intervals.alignToFloor(end, histogramInterval),
                job.getAnalysisConfig().getSummaryCountFieldName().equals(DatafeedConfig.DOC_COUNT),
                datafeedConfig.getHeaders(),
                datafeedConfig.getIndicesOptions(),
                datafeedConfig.getRuntimeMappings());
        return new AggregationDataExtractor(client, dataExtractorContext, timingStatsReporter);
    }
}
