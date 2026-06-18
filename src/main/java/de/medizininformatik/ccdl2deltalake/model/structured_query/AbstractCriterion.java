package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.model.Mapping;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

abstract class AbstractCriterion implements Criterion {

    protected final ContextualConcept concept;
    protected final TimeRestriction timeRestriction;

    AbstractCriterion(ContextualConcept concept, TimeRestriction timeRestriction) {
        this.concept = concept;
        this.timeRestriction = timeRestriction;
    }

    @Override
    public ContextualConcept getConcept() {
        return concept;
    }

    @Override
    public TimeRestriction getTimeRestriction() {
        return timeRestriction;
    }

    /**
     * Builds a SQL SELECT that returns distinct patient_id values matching the given codes,
     * with an optional additional WHERE clause (for value/range filters).
     */
    protected String buildTermCodeSql(String catalog, Mapping mapping, List<TermCode> codes,
                                       String additionalWhere) {
        Map<String, List<TermCode>> bySystem = codes.stream()
            .collect(Collectors.groupingBy(TermCode::system));

        var groupQueries = new ArrayList<String>();
        for (var entry : bySystem.entrySet()) {
            String system = entry.getKey();
            String inClause = entry.getValue().stream()
                .map(tc -> "'" + tc.code().replace("'", "''") + "'")
                .collect(Collectors.joining(", "));

            var tcf = mapping.termCodeFilter();
            var sb = new StringBuilder();
            sb.append("SELECT DISTINCT SPLIT_PART(t.").append(mapping.patientRefPath())
              .append(", '/', 2) AS patient_id\n");

            tcf.getJoin().ifPresentOrElse(join -> {
                sb.append("FROM ").append(catalog).append(".").append(mapping.tableName()).append(" t\n");
                sb.append("JOIN ").append(catalog).append(".").append(join.table())
                  .append(" j ON t.").append(join.primaryRefPath())
                  .append(" = j.").append(join.secondaryIdPath()).append("\n");
                sb.append("CROSS JOIN UNNEST(j.").append(tcf.arrayPath()).append(") AS tc\n");
            }, () -> {
                sb.append("FROM ").append(catalog).append(".").append(mapping.tableName())
                  .append(" t, UNNEST(t.").append(tcf.arrayPath()).append(") AS tc\n");
            });

            sb.append("WHERE tc.system = '").append(system.replace("'", "''")).append("'\n");
            sb.append("  AND tc.code IN (").append(inClause).append(")");

            if (additionalWhere != null && !additionalWhere.isBlank()) {
                sb.append("\n  AND ").append(additionalWhere);
            }

            if (timeRestriction != null) {
                String dateConditions = timeRestriction.toSqlConditions(mapping, "t");
                if (!dateConditions.isBlank()) {
                    sb.append("\n  AND ").append(dateConditions);
                }
            }

            groupQueries.add(sb.toString());
        }

        if (groupQueries.size() == 1) return groupQueries.get(0);
        return groupQueries.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nUNION\n"));
    }

    protected static String escape(String s) {
        return s.replace("'", "''");
    }
}
