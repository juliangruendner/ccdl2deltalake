package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Function.identity;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingTreeModuleRoot(TermCode context, String system, Map<String, MappingTreeModuleEntry> entries) {

    @JsonCreator
    public static MappingTreeModuleRoot fromJson(
        @JsonProperty("context") TermCode context,
        @JsonProperty("system") String system,
        @JsonProperty("entries") List<MappingTreeModuleEntry> entries
    ) {
        return new MappingTreeModuleRoot(
            context,
            system,
            entries.stream().collect(Collectors.toMap(MappingTreeModuleEntry::key, identity()))
        );
    }

    public Stream<ContextualTermCode> expand(String key) {
        var self = new ContextualTermCode(context, new TermCode(system, key, ""));
        var entry = entries.get(key);
        if (entry == null) return Stream.of(self);
        return Stream.concat(
            Stream.of(self),
            entry.children().stream().flatMap(this::expand)
        );
    }

    public boolean isModuleMatching(ContextualTermCode ctc) {
        return context.equals(ctc.context())
            && system.equals(ctc.termCode().system())
            && entries.containsKey(ctc.termCode().code());
    }
}
