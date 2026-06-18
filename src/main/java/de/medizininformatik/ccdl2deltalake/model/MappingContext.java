package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public class MappingContext {

    private final Map<ContextualTermCode, Mapping> mappings;
    private final MappingTreeBase mappingTree;
    private final Map<String, TableDescription> tableDescriptions;

    private MappingContext(Map<ContextualTermCode, Mapping> mappings, MappingTreeBase mappingTree,
                           Map<String, TableDescription> tableDescriptions) {
        this.mappings = mappings;
        this.mappingTree = mappingTree;
        this.tableDescriptions = tableDescriptions;
    }

    public static MappingContext of(List<Mapping> mappings) {
        return new MappingContext(
            mappings.stream().collect(Collectors.toMap(Mapping::contextualKey, m -> m)),
            null, Map.of()
        );
    }

    public static MappingContext of(List<Mapping> mappings, Map<String, TableDescription> tableDescs,
                                    MappingTreeBase tree) {
        return new MappingContext(
            mappings.stream().collect(Collectors.toMap(Mapping::contextualKey, m -> m)),
            requireNonNull(tree), requireNonNull(tableDescs)
        );
    }

    public static MappingContext of(List<Mapping> mappings, Map<String, TableDescription> tableDescs) {
        return new MappingContext(
            mappings.stream().collect(Collectors.toMap(Mapping::contextualKey, m -> m)),
            null, requireNonNull(tableDescs)
        );
    }

    /** Loads mapping only; table descriptions and tree are absent. */
    public static MappingContext fromJson(InputStream mappingStream) throws IOException {
        var mapper = new ObjectMapper();
        var mappingList = mapper.readValue(mappingStream, new TypeReference<List<Mapping>>() {});
        return of(mappingList);
    }

    /** Loads mapping + table descriptions (no ontology tree). */
    public static MappingContext fromJson(InputStream mappingStream,
                                          InputStream tableDescStream) throws IOException {
        var mapper = new ObjectMapper();
        var mappingList = mapper.readValue(mappingStream, new TypeReference<List<Mapping>>() {});
        var tableDescs = mapper.readValue(tableDescStream,
            new TypeReference<Map<String, TableDescription>>() {});
        return of(mappingList, tableDescs);
    }

    /** Loads mapping + table descriptions + ontology tree. */
    public static MappingContext fromJson(InputStream mappingStream, InputStream tableDescStream,
                                          InputStream treeStream) throws IOException {
        var mapper = new ObjectMapper();
        var mappingList = mapper.readValue(mappingStream, new TypeReference<List<Mapping>>() {});
        var tableDescs = mapper.readValue(tableDescStream,
            new TypeReference<Map<String, TableDescription>>() {});
        var rootNode = mapper.readTree(treeStream);
        List<MappingTreeModuleRoot> treeModules = rootNode.isArray()
            ? mapper.convertValue(rootNode, new TypeReference<List<MappingTreeModuleRoot>>() {})
            : List.of(mapper.convertValue(rootNode, MappingTreeModuleRoot.class));
        return of(mappingList, tableDescs, new MappingTreeBase(treeModules));
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

    /**
     * Result of {@link #resolveTermCodePath}: the UNNEST FROM clauses to emit, the alias of the
     * last generated row (or {@code startAlias} if no UNNESTs were needed), and any remaining
     * struct-path suffix to access on that alias.
     */
    public record TermCodeResolution(List<String> unnestClauses, String terminalAlias,
                                     String remainingPath) {}

    /**
     * Walks {@code path} (dot-notation FHIRPath) left to right, generating a
     * {@code CROSS JOIN UNNEST(...)} clause at each segment whose absolute prefix appears in the
     * table description's {@code arrays} set.  Segments not in the set are accumulated as a struct
     * navigation suffix used in WHERE.
     *
     * <p>Intermediate aliases are {@code "_" + finalAlias + n} (e.g. {@code _tc0}, {@code _tc1})
     * and the very last alias is {@code finalAlias} (e.g. {@code tc}).
     *
     * @param tableName  the FHIR resource table to look up cardinality for
     * @param path       dot-notation FHIRPath (e.g. {@code "provision.provision.code.coding"})
     * @param startAlias SQL alias of the primary table row ({@code "t"} or {@code "j"} for joins)
     * @param finalAlias desired alias for the last UNNEST result (e.g. {@code "tc"})
     */
    public TermCodeResolution resolveTermCodePath(String tableName, String path,
                                                   String startAlias, String finalAlias) {
        var td = tableDescriptions.get(tableName);
        Set<String> arrays = (td != null) ? td.arrays() : Set.of();

        var unnestClauses = new ArrayList<String>();
        String prevAlias = startAlias;
        String currentAbsPath = "";
        String currentRelPath = "";

        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            currentRelPath = currentRelPath.isEmpty() ? parts[i] : currentRelPath + "." + parts[i];
            String absPath = currentAbsPath.isEmpty() ? currentRelPath : currentAbsPath + "." + currentRelPath;

            if (arrays.contains(absPath)) {
                boolean isLast = (i == parts.length - 1);
                String alias = isLast ? finalAlias : ("_" + finalAlias + unnestClauses.size());
                unnestClauses.add("CROSS JOIN UNNEST(" + prevAlias + "." + currentRelPath
                    + ") AS " + alias + "\n");
                prevAlias = alias;
                currentAbsPath = absPath;
                currentRelPath = "";
            }
        }

        return new TermCodeResolution(List.copyOf(unnestClauses), prevAlias, currentRelPath);
    }
}
