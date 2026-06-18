package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MappingTreeModuleEntry(String key, List<String> parents, List<String> children) {

    @JsonCreator
    public static MappingTreeModuleEntry create(
        @JsonProperty("key") String key,
        @JsonProperty("parents") List<String> parents,
        @JsonProperty("children") List<String> children
    ) {
        return new MappingTreeModuleEntry(
            key,
            parents == null ? List.of() : List.copyOf(parents),
            children == null ? List.of() : List.copyOf(children)
        );
    }
}
