package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.MappingNotFoundException;
import de.medizininformatik.ccdl2deltalake.TranslationException;
import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.Mapping;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

public final class ConceptCriterion extends AbstractCriterion {

    private ConceptCriterion(ContextualConcept concept, TimeRestriction timeRestriction,
                              List<AttributeFilter> attributeFilters) {
        super(concept, timeRestriction, attributeFilters);
    }

    public static ConceptCriterion of(ContextualConcept concept) {
        return new ConceptCriterion(concept, null, List.of());
    }

    public static ConceptCriterion of(ContextualConcept concept, TimeRestriction timeRestriction) {
        return new ConceptCriterion(concept, timeRestriction, List.of());
    }

    public static ConceptCriterion of(ContextualConcept concept, TimeRestriction timeRestriction,
                                      List<AttributeFilter> attributeFilters) {
        return new ConceptCriterion(concept, timeRestriction, attributeFilters);
    }

    @Override
    public String toSql(MappingContext ctx) {
        if (concept.termCodes().isEmpty()) {
            throw new TranslationException("No term codes in concept: " + concept);
        }

        // Group term codes by structural mapping signature so codes hitting the same
        // table/path configuration produce a single SELECT ... IN (...) instead of N separate scans
        var groupMappings = new LinkedHashMap<String, Mapping>();
        var groupCodes = new LinkedHashMap<String, List<TermCode>>();

        for (TermCode termCode : concept.termCodes()) {
            var ctc = ContextualTermCode.of(concept.context(), termCode);
            var mapping = ctx.findMapping(ctc)
                .orElseThrow(() -> new MappingNotFoundException(ctc));
            var key = structureKey(mapping);
            groupMappings.putIfAbsent(key, mapping);
            groupCodes.computeIfAbsent(key, k -> new ArrayList<>()).add(termCode);
        }

        var subQueries = new ArrayList<String>();
        for (var key : groupMappings.keySet()) {
            var mapping = groupMappings.get(key);
            var codes = groupCodes.get(key);
            var expanded = codes.stream()
                .flatMap(tc -> ctx.expandTermCode(ContextualTermCode.of(concept.context(), tc)))
                .distinct()
                .toList();
            subQueries.add(buildTermCodeSql(mapping, expanded, null, ctx));
        }

        if (subQueries.size() == 1) return subQueries.get(0);
        return subQueries.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nUNION\n"));
    }

    private static String structureKey(Mapping m) {
        return m.tableName() + "|" + m.patientRefPath() + "|"
            + m.getTermCodeFilter()
                .map(tcf -> tcf.path() + tcf.getJoin()
                    .map(j -> "|" + j.table() + "|" + j.primaryRefPath() + "|" + j.secondaryIdPath())
                    .orElse(""))
                .orElse("");
    }
}
