package de.medizininformatik.ccdl2deltalake;

import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.structured_query.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Translates a CCDL {@link StructuredQuery} into a Trino SQL query against Delta Lake tables.
 *
 * <p>The generated SQL returns {@code SELECT COUNT(*) AS patient_count} over the patient set.
 * Inclusion criteria are combined as CNF (INTERSECT of UNIONs).
 * Exclusion criteria are combined as DNF (UNION of INTERSECTs) and subtracted with EXCEPT.
 *
 * <p>Instances are immutable and thread-safe.
 */
public class Translator {

    private final MappingContext mappingContext;

    private Translator(MappingContext mappingContext) {
        this.mappingContext = requireNonNull(mappingContext);
    }

    public static Translator of(MappingContext mappingContext) {
        return new Translator(mappingContext);
    }

    public String toSql(StructuredQuery query) {
        String inclusionSql = buildInclusion(query.inclusionCriteria());
        boolean hasExclusion = query.exclusionCriteria().stream().anyMatch(g -> !g.isEmpty());

        String innerSql;
        if (!hasExclusion) {
            innerSql = inclusionSql;
        } else {
            String exclusionSql = buildExclusion(query.exclusionCriteria());
            innerSql = exclusionSql.isBlank()
                ? inclusionSql
                : "(\n" + inclusionSql + "\n)\nEXCEPT\n(\n" + exclusionSql + "\n)";
        }

        return "SELECT COUNT(*) AS patient_count FROM (\n" + innerSql + "\n)";
    }

    /** CNF: outer list items are AND-ed (INTERSECT); inner list items are OR-ed (UNION). */
    private String buildInclusion(List<List<Criterion>> criteria) {
        var groups = criteria.stream()
            .filter(g -> !g.isEmpty())
            .map(this::unionGroup)
            .toList();

        if (groups.isEmpty()) throw new TranslationException("Empty inclusion criteria");
        if (groups.size() == 1) return groups.get(0);

        return groups.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nINTERSECT\n"));
    }

    /** DNF: outer list items are OR-ed (UNION); inner list items are AND-ed (INTERSECT). */
    private String buildExclusion(List<List<Criterion>> criteria) {
        var groups = criteria.stream()
            .filter(g -> !g.isEmpty())
            .map(this::intersectGroup)
            .toList();

        if (groups.isEmpty()) return "";
        if (groups.size() == 1) return groups.get(0);

        return groups.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nUNION\n"));
    }

    private String unionGroup(List<Criterion> criteria) {
        var sqls = mergeConceptCriteria(criteria).stream()
            .map(c -> c.toSql(mappingContext))
            .toList();
        if (sqls.size() == 1) return sqls.get(0);
        return sqls.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nUNION\n"));
    }

    private String intersectGroup(List<Criterion> criteria) {
        var sqls = criteria.stream()
            .map(c -> c.toSql(mappingContext))
            .toList();
        if (sqls.size() == 1) return sqls.get(0);
        return sqls.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nINTERSECT\n"));
    }

    /**
     * Merges ConceptCriteria within a UNION group when they share the same structural signature
     * (same table, patientRefPath, termCodeFilter, and timeRestriction) and have no attribute
     * filters. Merged criteria produce a single SELECT with an expanded IN clause instead of N
     * separate table scans.
     */
    private List<Criterion> mergeConceptCriteria(List<Criterion> criteria) {
        var result = new ArrayList<Criterion>();
        var groups = new LinkedHashMap<String, List<ConceptCriterion>>();

        for (var c : criteria) {
            if (!(c instanceof ConceptCriterion cc) || !cc.getAttributeFilters().isEmpty()) {
                result.add(c);
                continue;
            }
            var key = conceptMergeKey(cc);
            if (key == null) {
                result.add(c);
            } else {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cc);
            }
        }

        for (var group : groups.values()) {
            if (group.size() == 1) {
                result.add(group.get(0));
            } else {
                var allCodes = group.stream()
                    .flatMap(cc -> cc.getConcept().termCodes().stream())
                    .distinct()
                    .toList();
                result.add(ConceptCriterion.of(
                    ContextualConcept.of(group.get(0).getConcept().context(), allCodes),
                    group.get(0).getTimeRestriction()
                ));
            }
        }

        return result;
    }

    /**
     * Returns a string key capturing the structural identity of a ConceptCriterion for merging:
     * tableName, patientRefPath, termCodeFilter path+join, and timeRestriction.
     * Returns null if the mapping cannot be resolved or the criterion has no term codes.
     */
    private String conceptMergeKey(ConceptCriterion cc) {
        if (cc.getConcept().termCodes().isEmpty()) return null;
        var firstTc = cc.getConcept().termCodes().get(0);
        var ctc = ContextualTermCode.of(cc.getConcept().context(), firstTc);
        var mapping = mappingContext.findMapping(ctc).orElse(null);
        if (mapping == null) return null;

        var tcfKey = mapping.getTermCodeFilter()
            .map(tcf -> tcf.path() + tcf.getJoin().map(j -> "|" + j.table() + "|" + j.primaryRefPath() + "|" + j.secondaryIdPath()).orElse(""))
            .orElse("");

        var trKey = cc.getTimeRestriction() == null ? "" : cc.getTimeRestriction().toString();

        return mapping.tableName() + "|" + mapping.patientRefPath() + "|" + tcfKey + "|" + trKey;
    }
}
