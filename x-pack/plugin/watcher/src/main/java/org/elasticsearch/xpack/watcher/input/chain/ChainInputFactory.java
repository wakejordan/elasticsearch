/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.watcher.input.chain;

import org.elasticsearch.core.Tuple;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xpack.core.watcher.input.ExecutableInput;
import org.elasticsearch.xpack.core.watcher.input.Input;
import org.elasticsearch.xpack.watcher.input.InputFactory;
import org.elasticsearch.xpack.watcher.input.InputRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChainInputFactory extends InputFactory<ChainInput, ChainInput.Result, ExecutableChainInput> {

    private final InputRegistry inputRegistry;

    public ChainInputFactory(InputRegistry inputRegistry) {
        this.inputRegistry = inputRegistry;
    }

    @Override
    public String type() {
        return ChainInput.TYPE;
    }

    @Override
    public ChainInput parseInput(String watchId, XContentParser parser) throws IOException {
        return ChainInput.parse(watchId, parser, inputRegistry);
    }

    @Override
    public ExecutableChainInput createExecutable(ChainInput input) {
        List<Tuple<String, ExecutableInput<?, ?>>> executableInputs = new ArrayList<>();
        for (Tuple<String, Input> tuple : input.getInputs()) {
            @SuppressWarnings("unchecked")
            ExecutableInput<?, ?> executableInput =
                ((InputFactory<Input, ?, ?>) inputRegistry.factories().get(tuple.v2().type())).createExecutable(tuple.v2());
            executableInputs.add(new Tuple<>(tuple.v1(), executableInput));
        }

        return new ExecutableChainInput(input, executableInputs);
    }
}
