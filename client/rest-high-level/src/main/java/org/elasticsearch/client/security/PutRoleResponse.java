/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.client.security;

import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParser.Token;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureFieldName;

/**
 * Response when adding a role to the native roles store. Returns a
 * single boolean field for whether the role was created (true) or updated (false).
 */
public final class PutRoleResponse {

    private final boolean created;

    public PutRoleResponse(boolean created) {
        this.created = created;
    }

    public boolean isCreated() {
        return created;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PutRoleResponse that = (PutRoleResponse) o;
        return created == that.created;
    }

    @Override
    public int hashCode() {
        return Objects.hash(created);
    }

    private static final ConstructingObjectParser<PutRoleResponse, Void> PARSER = new ConstructingObjectParser<>("put_role_response",
        true, args -> new PutRoleResponse((boolean) args[0]));

    static {
        PARSER.declareBoolean(constructorArg(), new ParseField("created"));
    }

    public static PutRoleResponse fromXContent(XContentParser parser) throws IOException {
        if (parser.currentToken() == null) {
            parser.nextToken();
        }
        // parse extraneous wrapper
        ensureExpectedToken(Token.START_OBJECT, parser.currentToken(), parser);
        ensureFieldName(parser, parser.nextToken(), "role");
        parser.nextToken();
        final PutRoleResponse roleResponse = PARSER.parse(parser, null);
        ensureExpectedToken(Token.END_OBJECT, parser.nextToken(), parser);
        return roleResponse;
    }
}
