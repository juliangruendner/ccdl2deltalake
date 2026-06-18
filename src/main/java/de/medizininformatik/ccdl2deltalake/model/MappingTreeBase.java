package de.medizininformatik.ccdl2deltalake.model;

import java.util.List;
import java.util.stream.Stream;

public record MappingTreeBase(List<MappingTreeModuleRoot> moduleRoots) {

    /**
     * Expands the given term code to itself plus all descendants in the ontology tree.
     * Returns an empty stream if no matching module is found.
     */
    public Stream<ContextualTermCode> expand(ContextualTermCode ctc) {
        String key = ctc.termCode().code();
        return moduleRoots.stream().flatMap(root ->
            root.isModuleMatching(ctc) ? root.expand(key) : Stream.empty()
        );
    }
}
