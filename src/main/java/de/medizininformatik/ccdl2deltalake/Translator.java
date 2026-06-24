package de.medizininformatik.ccdl2deltalake;

import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.structured_query.Criterion;
import de.medizininformatik.ccdl2deltalake.model.structured_query.StructuredQuery;

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
        var sqls = criteria.stream()
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
}
