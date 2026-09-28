package com.example.agentvoice.protocol;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public final class BoardAdapterRegistry {
    private final Map<String, BoardProtocolAdapter> adapters;
    public BoardAdapterRegistry(List<BoardProtocolAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(BoardProtocolAdapter::model, Function.identity()));
    }
    public BoardProtocolAdapter require(String model) {
        BoardProtocolAdapter adapter = adapters.get(model);
        if (adapter == null) throw new IllegalArgumentException("unsupported board model: " + model);
        return adapter;
    }
}
