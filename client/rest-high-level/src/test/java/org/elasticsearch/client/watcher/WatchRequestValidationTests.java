/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.client.watcher;

import org.elasticsearch.client.ValidationException;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;

import java.util.Optional;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

public class WatchRequestValidationTests extends ESTestCase {

    public void testAcknowledgeWatchInvalidWatchId()  {
        ValidationException e = expectThrows(ValidationException.class,
            () ->  new AckWatchRequest("id with whitespaces"));
        assertThat(e.validationErrors(), hasItem("watch id contains whitespace"));
    }

    public void testAcknowledgeWatchInvalidActionId() {
        ValidationException e = expectThrows(ValidationException.class,
            () -> new AckWatchRequest("_id", "action id with whitespaces"));
        assertThat(e.validationErrors(), hasItem("action id [action id with whitespaces] contains whitespace"));
    }

    public void testAcknowledgeWatchNullActionArray() {
        // need this to prevent some compilation errors, i.e. in 1.8.0_91
        String[] nullArray = null;
        Optional<ValidationException> e = new AckWatchRequest("_id", nullArray).validate();
        assertFalse(e.isPresent());
    }

    public void testAcknowledgeWatchNullActionId() {
        ValidationException e = expectThrows(ValidationException.class,
            () ->  new AckWatchRequest("_id", new String[] {null}));
        assertThat(e.validationErrors(), hasItem("action id may not be null"));
    }

    public void testDeleteWatchInvalidWatchId() {
        final IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> new DeleteWatchRequest("id with whitespaces"));
        assertThat(exception.getMessage(), is("watch id contains whitespace"));
    }

    public void testDeleteWatchNullId() {
        final NullPointerException exception = expectThrows(NullPointerException.class,
            () -> new DeleteWatchRequest(null));
        assertThat(exception.getMessage(), is("watch id is missing"));
    }

    public void testPutWatchInvalidWatchId() {
        final IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> new PutWatchRequest("id with whitespaces", BytesArray.EMPTY, XContentType.JSON));
        assertThat(exception.getMessage(), is("watch id contains whitespace"));
    }

    public void testPutWatchNullId() {
        final NullPointerException exception = expectThrows(NullPointerException.class,
            () -> new PutWatchRequest(null, BytesArray.EMPTY, XContentType.JSON));
        assertThat(exception.getMessage(), is("watch id is missing"));
    }

    public void testPutWatchSourceNull() {
        final NullPointerException exception = expectThrows(NullPointerException.class,
            () -> new PutWatchRequest("foo", null, XContentType.JSON));
        assertThat(exception.getMessage(), is("watch source is missing"));
    }

    public void testPutWatchContentNull() {
        final NullPointerException exception = expectThrows(NullPointerException.class,
            () -> new PutWatchRequest("foo", BytesArray.EMPTY, null));
        assertThat(exception.getMessage(), is("request body is missing"));
    }

    public void testGetWatchInvalidWatchId()  {
        ValidationException e = expectThrows(ValidationException.class,
            () ->  new GetWatchRequest("id with whitespaces"));
        assertThat(e.validationErrors(), hasItem("watch id contains whitespace"));
    }
}
