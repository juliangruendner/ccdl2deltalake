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
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

abstract class AbstractCriterion implements Criterion {

    protected final ContextualConcept concept;
    protected final TimeRestriction timeRestriction;
    protected final List<AttributeFilter> attributeFilters;

    AbstractCriterion(ContextualConcept concept, TimeRestriction timeRestriction,
                      List<AttributeFilter> attributeFilters) {
        this.concept = concept;
        this.timeRestriction = timeRestriction;
        this.attributeFilters = attributeFilters != null ? List.copyOf(attributeFilters) : List.of();
    }

    @Override
    public ContextualConcept getConcept() { return concept; }

    @Override
    public TimeRestriction getTimeRestriction() { return timeRestriction; }

    @Override
    public List<AttributeFilter> getAttributeFilters() { return attributeFilters; }

    /**
     * Builds a SQL SELECT returning distinct patient_id values for the given codes,
     * including any reference attribute filter JOINs.
     */
    protected String buildTermCodeSql(String catalog, Mapping mapping, List<TermCode> codes,
                                       String additionalWhere, MappingContext ctx) {
        Map<String, List<TermCode>> bySystem = codes.stream()
            .collect(Collectors.groupingBy(TermCode::system));

        var refFragments = buildRefFilterFragments(catalog, mapping, ctx);
        var simpleAttrConditions = buildSimpleAttributeWhereConditions(mapping);

        var groupQueries = new ArrayList<String>();
        for (var entry : bySystem.entrySet()) {
            String system = entry.getKey();
            String inClause = entry.getValue().stream()
                .map(tc -> "'" + escape(tc.code()) + "'")
                .collect(Collectors.joining(", "));

            var tcf = mapping.termCodeFilter();
            var sb = new StringBuilder();
            sb.append("SELECT DISTINCT SPLIT_PART(t.").append(mapping.patientRefPath())
              .append(", '/', 2) AS patient_id\n");
            sb.append("FROM ").append(catalog).append(".").append(mapping.tableName()).append(" t\n");

            if (tcf.getSinglePath().isPresent()) {
                // Single Coding struct (e.g. Encounter.class) — no UNNEST
            } else {
                tcf.getJoin().ifPresentOrElse(join -> {
                    sb.append("JOIN ").append(catalog).append(".").append(join.table())
                      .append(" j ON t.").append(join.primaryRefPath())
                      .append(" = j.").append(join.secondaryIdPath()).append("\n");
                    sb.append("CROSS JOIN UNNEST(j.").append(tcf.arrayPath()).append(") AS tc\n");
                }, () -> {
                    sb.append("CROSS JOIN UNNEST(t.").append(tcf.arrayPath()).append(") AS tc\n");
                });
            }

            for (var frag : refFragments) {
                sb.append(frag.fromClause());
            }

            if (tcf.getSinglePath().isPresent()) {
                String sp = tcf.getSinglePath().get();
                sb.append("WHERE t.").append(sp).append(".system = '").append(escape(system)).append("'\n");
                sb.append("  AND t.").append(sp).append(".code IN (").append(inClause).append(")");
            } else {
                sb.append("WHERE tc.system = '").append(escape(system)).append("'\n");
                sb.append("  AND tc.code IN (").append(inClause).append(")");
            }

            if (additionalWhere != null && !additionalWhere.isBlank()) {
                sb.append("\n  AND ").append(additionalWhere);
            }

            if (timeRestriction != null) {
                String dateConditions = timeRestriction.toSqlConditions(mapping, "t");
                if (!dateConditions.isBlank()) {
                    sb.append("\n  AND ").append(dateConditions);
                }
            }

            for (var cond : simpleAttrConditions) {
                sb.append("\n  AND ").append(cond);
            }

            for (var frag : refFragments) {
                sb.append("\n  AND ").append(frag.whereCondition());
            }

            groupQueries.add(sb.toString());
        }

        if (groupQueries.size() == 1) return groupQueries.get(0);
        return groupQueries.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nUNION\n"));
    }

    private record RefFilterFragment(String fromClause, String whereCondition) {}

    private List<RefFilterFragment> buildRefFilterFragments(String catalog, Mapping mapping,
                                                             MappingContext ctx) {
        var fragments = new ArrayList<RefFilterFragment>();

        int i = 0;
        for (var af : attributeFilters) {
            if (!"reference".equals(af.type())) continue;
            var rafConfig = mapping.findRefAttributeFilter(af.attributeCode().code())
                .orElseThrow(() -> new TranslationException(
                    "No referenceAttributeFilter config for attributeCode '"
                    + af.attributeCode().code() + "' in mapping for " + mapping.tableName()));

            String extAlias = "ext" + i;
            String refAlias = "ref" + i;
            String refTcAlias = "ref_tc" + i;

            // Collect inner codes grouped by system, across all inner criteria
            Mapping innerMapping = null;
            TimeRestriction innerTr = null;
            var innerBySystem = new LinkedHashMap<String, List<String>>();

            for (Criterion inner : af.criteria()) {
                if (innerTr == null) innerTr = inner.getTimeRestriction();
                for (TermCode innerTc : inner.getConcept().termCodes()) {
                    var innerCtc = ContextualTermCode.of(inner.getConcept().context(), innerTc);
                    var im = ctx.findMapping(innerCtc)
                        .orElseThrow(() -> new MappingNotFoundException(innerCtc));
                    if (innerMapping == null) innerMapping = im;
                    var expanded = ctx.expandTermCode(innerCtc).toList();
                    innerBySystem.computeIfAbsent(innerTc.system(), k -> new ArrayList<>())
                        .addAll(expanded.stream().map(TermCode::code).toList());
                }
            }

            if (innerMapping == null) {
                throw new TranslationException("Reference attribute filter has no inner criteria: " + af.attributeCode().code());
            }

            // FROM clause — two modes depending on where the reference lives
            var fromSb = new StringBuilder();
            if (rafConfig.isExtensionBased()) {
                // _extension is MAP<integer, ARRAY<struct>> in Pathling's Delta Lake format.
                // Double-UNNEST: expand map entries, then expand the inner array.
                String mapAlias = "_emap" + i;
                String arrAlias = "_earr" + i;
                fromSb.append("CROSS JOIN UNNEST(t.")
                      .append(rafConfig.extensionArrayPath())
                      .append(") AS ").append(mapAlias)
                      .append("(_ek").append(i).append(", ").append(arrAlias).append(")\n");
                fromSb.append("CROSS JOIN UNNEST(").append(arrAlias).append(") AS ").append(extAlias).append("\n");
                fromSb.append("INNER JOIN ").append(catalog).append(".").append(innerMapping.tableName())
                      .append(" ").append(refAlias)
                      .append(" ON ").append(refAlias).append(".id = SPLIT_PART(")
                      .append(extAlias).append(".").append(rafConfig.referenceValuePath())
                      .append(", '/', 2)\n");
            } else {
                // Direct reference field on the primary table (e.g. t.encounter.reference)
                fromSb.append("INNER JOIN ").append(catalog).append(".").append(innerMapping.tableName())
                      .append(" ").append(refAlias)
                      .append(" ON ").append(refAlias).append(".id = SPLIT_PART(t.")
                      .append(rafConfig.referenceValuePath())
                      .append(", '/', 2)\n");
            }
            fromSb.append("CROSS JOIN UNNEST(").append(refAlias).append(".")
                  .append(innerMapping.termCodeFilter().arrayPath()).append(") AS ").append(refTcAlias).append("\n");

            // WHERE conditions for inner codes
            var sysConditions = innerBySystem.entrySet().stream().map(e -> {
                String inClause = e.getValue().stream()
                    .map(c -> "'" + escape(c) + "'")
                    .collect(Collectors.joining(", "));
                return refTcAlias + ".system = '" + escape(e.getKey()) + "'"
                    + " AND " + refTcAlias + ".code IN (" + inClause + ")";
            }).toList();

            String codeCondition = sysConditions.size() == 1
                ? sysConditions.get(0)
                : "(\n    " + String.join("\n    OR ", sysConditions) + "\n  )";

            // Extension mode: also filter by URL in WHERE
            String whereCondition = rafConfig.isExtensionBased()
                ? extAlias + ".url = '" + escape(rafConfig.extensionUrl()) + "'\n  AND " + codeCondition
                : codeCondition;

            // Optional inner time restriction applied to the referenced table
            final Mapping finalInnerMapping = innerMapping;
            final TimeRestriction finalInnerTr = innerTr;
            if (finalInnerTr != null) {
                String trCondition = finalInnerTr.toSqlConditions(finalInnerMapping, refAlias);
                if (!trCondition.isBlank()) {
                    whereCondition = whereCondition + "\n  AND " + trCondition;
                }
            }

            fragments.add(new RefFilterFragment(fromSb.toString(), whereCondition));
            i++;
        }

        return fragments;
    }

    private List<String> buildSimpleAttributeWhereConditions(Mapping mapping) {
        var conditions = new ArrayList<String>();
        for (var af : attributeFilters) {
            if ("reference".equals(af.type())) continue;
            var cfg = mapping.findSimpleAttributeFilter(af.attributeCode().code())
                .orElseThrow(() -> new TranslationException(
                    "No simpleAttributeFilter config for attributeCode '"
                    + af.attributeCode().code() + "' in mapping for " + mapping.tableName()));

            switch (af.type()) {
                case "quantity-comparator" -> {
                    String valueField = cfg.getValueField().orElseThrow(() -> new TranslationException(
                        "valueField required for quantity-comparator in mapping for " + mapping.tableName()));
                    conditions.add("t." + cfg.path() + "." + valueField + " "
                        + af.comparator().toSql() + " " + af.value());
                    if (af.unit() != null) {
                        cfg.getUnitCodeField().ifPresent(ucf ->
                            conditions.add("t." + cfg.path() + "." + ucf + " = '" + escape(af.unit()) + "'"));
                    }
                }
                case "quantity-range" -> {
                    String valueField = cfg.getValueField().orElseThrow(() -> new TranslationException(
                        "valueField required for quantity-range in mapping for " + mapping.tableName()));
                    conditions.add("t." + cfg.path() + "." + valueField
                        + " BETWEEN " + af.minValue() + " AND " + af.maxValue());
                    if (af.unit() != null) {
                        cfg.getUnitCodeField().ifPresent(ucf ->
                            conditions.add("t." + cfg.path() + "." + ucf + " = '" + escape(af.unit()) + "'"));
                    }
                }
                case "concept" -> {
                    String inClause = af.selectedConcepts().stream()
                        .map(tc -> "'" + escape(tc.code()) + "'")
                        .collect(Collectors.joining(", "));
                    conditions.add("t." + cfg.path() + " IN (" + inClause + ")");
                }
                default -> throw new TranslationException("Unknown simple attribute filter type: " + af.type());
            }
        }
        return conditions;
    }

    protected static String escape(String s) {
        return s.replace("'", "''");
    }
}
