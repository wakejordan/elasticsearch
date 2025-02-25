/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.client.ml.job.config;

import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.test.AbstractXContentTestCase;

public class PerPartitionCategorizationConfigTests extends AbstractXContentTestCase<PerPartitionCategorizationConfig> {

    @Override
    protected PerPartitionCategorizationConfig createTestInstance() {
        boolean enabled = randomBoolean();
        return new PerPartitionCategorizationConfig(enabled, randomBoolean() ? null : enabled && randomBoolean());
    }

    @Override
    protected PerPartitionCategorizationConfig doParseInstance(XContentParser parser) {
        return PerPartitionCategorizationConfig.PARSER.apply(parser, null);
    }

    @Override
    protected boolean supportsUnknownFields() {
        return true;
    }
}
