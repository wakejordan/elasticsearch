/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.client.ml;

import org.elasticsearch.client.core.PageParams;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.test.AbstractXContentTestCase;

import java.io.IOException;

public class GetCategoriesRequestTests extends AbstractXContentTestCase<GetCategoriesRequest> {

    @Override
    protected GetCategoriesRequest createTestInstance() {
        GetCategoriesRequest request = new GetCategoriesRequest(randomAlphaOfLengthBetween(1, 20));
        if (randomBoolean()) {
            request.setCategoryId(randomNonNegativeLong());
        } else {
            int from = randomInt(10000);
            int size = randomInt(10000);
            request.setPageParams(new PageParams(from, size));
        }
        if (randomBoolean()) {
            request.setPartitionFieldValue(randomAlphaOfLength(10));
        }
        return request;
    }

    @Override
    protected GetCategoriesRequest doParseInstance(XContentParser parser) throws IOException {
        return GetCategoriesRequest.PARSER.apply(parser, null);
    }

    @Override
    protected boolean supportsUnknownFields() {
        return false;
    }
}
