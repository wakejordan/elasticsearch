/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.client.core;

import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Represents a response to a request that is broadcast to a collection of shards.
 */
public class BroadcastResponse {

    private final Shards shards;

    /**
     * Represents the shard-level summary of the response execution.
     *
     * @return the shard-level response summary
     */
    public Shards shards() {
        return shards;
    }

    protected BroadcastResponse(final Shards shards) {
        this.shards = Objects.requireNonNull(shards);
    }

    private static final ParseField SHARDS_FIELD = new ParseField("_shards");

    static final ConstructingObjectParser<BroadcastResponse, Void> PARSER = new ConstructingObjectParser<>(
            "broadcast_response",
            a -> new BroadcastResponse((Shards) a[0]));

    static {
        declareShardsField(PARSER);
    }

    /**
     * Parses a broadcast response.
     *
     * @param parser the parser
     * @return a broadcast response parsed from the specified parser
     * @throws IOException if an I/O exception occurs parsing the response
     */
    public static BroadcastResponse fromXContent(final XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    protected static <T extends BroadcastResponse> void declareShardsField(ConstructingObjectParser<T, Void> PARSER) {
        PARSER.declareObject(ConstructingObjectParser.constructorArg(), Shards.SHARDS_PARSER, SHARDS_FIELD);
    }

    /**
     * Represents the results of a collection of shards on which a request was executed against.
     */
    public static class Shards {

        private final int total;

        /**
         * The total number of shards on which a request was executed against.
         *
         * @return the total number of shards
         */
        public int total() {
            return total;
        }

        private final int successful;

        /**
         * The number of successful shards on which a request was executed against.
         *
         * @return the number of successful shards
         */
        public int successful() {
            return successful;
        }

        private final int skipped;

        /**
         * The number of shards skipped by the request.
         *
         * @return the number of skipped shards
         */
        public int skipped() {
            return skipped;
        }

        private final int failed;

        /**
         * The number of shards on which a request failed to be executed against.
         *
         * @return the number of failed shards
         */
        public int failed() {
            return failed;
        }

        private final Collection<DefaultShardOperationFailedException> failures;

        /**
         * The failures corresponding to the shards on which a request failed to be executed against. Note that the number of failures might
         * not match {@link #failed()} as some responses group together shard failures.
         *
         * @return the failures
         */
        public Collection<DefaultShardOperationFailedException> failures() {
            return failures;
        }

        Shards(
                final int total,
                final int successful,
                final int skipped,
                final int failed,
                final Collection<DefaultShardOperationFailedException> failures) {
            this.total = total;
            this.successful = successful;
            this.skipped = skipped;
            this.failed = failed;
            this.failures = Collections.unmodifiableCollection(Objects.requireNonNull(failures));
        }

        private static final ParseField TOTAL_FIELD = new ParseField("total");
        private static final ParseField SUCCESSFUL_FIELD = new ParseField("successful");
        private static final ParseField SKIPPED_FIELD = new ParseField("skipped");
        private static final ParseField FAILED_FIELD = new ParseField("failed");
        private static final ParseField FAILURES_FIELD = new ParseField("failures");

        @SuppressWarnings("unchecked")
        static final ConstructingObjectParser<Shards, Void> SHARDS_PARSER = new ConstructingObjectParser<>(
                "shards",
                a -> new Shards(
                        (int) a[0], // total
                        (int) a[1], // successful
                        a[2] == null ? 0 : (int) a[2], // skipped
                        (int) a[3], // failed
                        a[4] == null ? Collections.emptyList() : (Collection<DefaultShardOperationFailedException>) a[4])); // failures

        static {
            SHARDS_PARSER.declareInt(ConstructingObjectParser.constructorArg(), TOTAL_FIELD);
            SHARDS_PARSER.declareInt(ConstructingObjectParser.constructorArg(), SUCCESSFUL_FIELD);
            SHARDS_PARSER.declareInt(ConstructingObjectParser.optionalConstructorArg(), SKIPPED_FIELD);
            SHARDS_PARSER.declareInt(ConstructingObjectParser.constructorArg(), FAILED_FIELD);
            SHARDS_PARSER.declareObjectArray(
                    ConstructingObjectParser.optionalConstructorArg(),
                    DefaultShardOperationFailedException.PARSER, FAILURES_FIELD);
        }

    }

}
