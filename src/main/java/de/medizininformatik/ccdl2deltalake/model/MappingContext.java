package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public class MappingContext {

    private final Map<ContextualTermCode, Mapping> mappings;
    private final MappingTreeBase mappingTree;

    private MappingContext(Map<ContextualTermCode, Mapping> mappings, MappingTreeBase mappingTree) {
        this.mappings = mappings;
        this.mappingTree = mappingTree;
    }

    public static MappingContext of(List<Mapping> mappings) {
        return new MappingContext(
            mappings.stream().collect(Collectors.toMap(Mapping::contextualKey, m -> m)),
            null
        );
    }

    public static MappingContext of(List<Mapping> mappings, MappingTreeBase tree) {
        return new MappingContext(
            mappings.stream().collect(Collectors.toMap(Mapping::contextualKey, m -> m)),
            requireNonNull(tree)
        );
    }

    public static MappingContext fromJson(InputStream mappingStream) throws IOException {
        var mapper = new ObjectMapper();
        var mappingList = mapper.readValue(mappingStream, new TypeReference<List<Mapping>>() {});
        return of(mappingList);
    }

    public static MappingContext fromJson(InputStream mappingStream, InputStream treeStream) throws IOException {
        var mapper = new ObjectMapper();
        var mappingList = mapper.readValue(mappingStream, new TypeReference<List<Mapping>>() {});
        var rootNode = mapper.readTree(treeStream);
        List<MappingTreeModuleRoot> treeModules = rootNode.isArray()
            ? mapper.convertValue(rootNode, new TypeReference<List<MappingTreeModuleRoot>>() {})
            : List.of(mapper.convertValue(rootNode, MappingTreeModuleRoot.class));
        return of(mappingList, new MappingTreeBase(treeModules));
    }

    public Optional<Mapping> findMapping(ContextualTermCode ctc) {
        return Optional.ofNullable(mappings.get(ctc));
    }

    /**
     * Expands a term code to itself plus all ontology descendants.
     * Falls back to just the input term code if no tree is configured or no expansion found.
     */
    public Stream<TermCode> expandTermCode(ContextualTermCode ctc) {
        if (mappingTree == null) {
            return Stream.of(ctc.termCode());
        }
        var expanded = mappingTree.expand(ctc).map(ContextualTermCode::termCode).toList();
        return expanded.isEmpty() ? Stream.of(ctc.termCode()) : expanded.stream();
    }
}
