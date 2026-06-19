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

    private String patientIdExpr(Mapping mapping) {
        if ("patient".equals(mapping.tableName())) {
            return "t." + mapping.patientRefPath();
        }
        return "SPLIT_PART(t." + mapping.patientRefPath() + ", '/', 2)";
    }

    /**
     * Builds a SQL SELECT returning distinct patient_id values for the given codes,
     * including any reference attribute filter JOINs.
     */
    protected String buildTermCodeSql(String catalog, Mapping mapping, List<TermCode> codes,
                                       String additionalWhere, MappingContext ctx) {
        var refFragments = buildRefFilterFragments(catalog, mapping, ctx);
        var simpleAttrConditions = buildSimpleAttributeWhereConditions(mapping);
        var codingAttrFragments = buildCodingAttributeFragments(mapping, ctx);

        // Patient-like resources: no term-code array — just apply other conditions
        if (mapping.getTermCodeFilter().isEmpty()) {
            return buildNoTermCodeSql(catalog, mapping, additionalWhere, simpleAttrConditions,
                refFragments, codingAttrFragments);
        }

        Map<String, List<TermCode>> bySystem = codes.stream()
            .collect(Collectors.groupingBy(TermCode::system));

        var groupQueries = new ArrayList<String>();
        for (var entry : bySystem.entrySet()) {
            String system = entry.getKey();
            String inClause = entry.getValue().stream()
                .map(tc -> "'" + escape(tc.code()) + "'")
                .collect(Collectors.joining(", "));

            var tcf = mapping.termCodeFilter();

            // Determine start alias and table for cardinality lookup
            String startAlias = tcf.getJoin().isPresent() ? "j" : "t";
            String tableForLookup = tcf.getJoin().map(j -> j.table()).orElse(mapping.tableName());
            var resolution = ctx.resolveTermCodePath(tableForLookup, tcf.path(), startAlias, "tc");

            var sb = new StringBuilder();
            sb.append("SELECT DISTINCT ").append(patientIdExpr(mapping)).append(" AS patient_id\n");
            sb.append("FROM ").append(catalog).append(".").append(mapping.tableName()).append(" t\n");

            tcf.getJoin().ifPresent(join ->
                sb.append("JOIN ").append(catalog).append(".").append(join.table())
                  .append(" j ON t.").append(join.primaryRefPath())
                  .append(" = j.").append(join.secondaryIdPath()).append("\n")
            );

            for (var unnest : resolution.unnestClauses()) {
                sb.append(unnest);
            }

            for (var frag : refFragments) {
                sb.append(frag.fromClause());
            }

            for (var frag : codingAttrFragments) {
                sb.append(frag.fromClause());
            }

            String termPfx = resolution.terminalAlias()
                + (resolution.remainingPath().isEmpty() ? "" : "." + resolution.remainingPath());

            sb.append("WHERE ").append(termPfx).append(".system = '").append(escape(system)).append("'\n");
            sb.append("  AND ").append(termPfx).append(".code IN (").append(inClause).append(")");

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

            for (var frag : codingAttrFragments) {
                sb.append("\n  AND ").append(frag.whereCondition());
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

    /**
     * Generates a SELECT with no term-code UNNEST/filter — used for Patient-type resources
     * where the concept term code is a semantic label, not a data column filter.
     */
    private String buildNoTermCodeSql(String catalog, Mapping mapping, String additionalWhere,
                                       List<String> simpleAttrConditions,
                                       List<RefFilterFragment> refFragments,
                                       List<CodingAttrFragment> codingAttrFragments) {
        var sb = new StringBuilder();
        sb.append("SELECT DISTINCT ").append(patientIdExpr(mapping)).append(" AS patient_id\n");
        sb.append("FROM ").append(catalog).append(".").append(mapping.tableName()).append(" t\n");

        for (var frag : refFragments) sb.append(frag.fromClause());
        for (var frag : codingAttrFragments) sb.append(frag.fromClause());

        boolean hasWhere = false;

        if (additionalWhere != null && !additionalWhere.isBlank()) {
            sb.append("WHERE ").append(additionalWhere);
            hasWhere = true;
        }

        if (timeRestriction != null) {
            String dc = timeRestriction.toSqlConditions(mapping, "t");
            if (!dc.isBlank()) {
                sb.append(hasWhere ? "\n  AND " : "WHERE ").append(dc);
                hasWhere = true;
            }
        }

        for (var cond : simpleAttrConditions) {
            sb.append(hasWhere ? "\n  AND " : "WHERE ").append(cond);
            hasWhere = true;
        }

        for (var frag : codingAttrFragments) {
            sb.append(hasWhere ? "\n  AND " : "WHERE ").append(frag.whereCondition());
            hasWhere = true;
        }

        for (var frag : refFragments) {
            sb.append(hasWhere ? "\n  AND " : "WHERE ").append(frag.whereCondition());
            hasWhere = true;
        }

        return sb.toString();
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
                throw new TranslationException(
                    "Reference attribute filter has no inner criteria: " + af.attributeCode().code());
            }

            final Mapping finalInnerMapping = innerMapping;
            final TimeRestriction finalInnerTr = innerTr;

            var fromSb = new StringBuilder();
            if (rafConfig.isExtensionBased()) {
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
                fromSb.append("INNER JOIN ").append(catalog).append(".").append(finalInnerMapping.tableName())
                      .append(" ").append(refAlias)
                      .append(" ON ").append(refAlias).append(".id = SPLIT_PART(t.")
                      .append(rafConfig.referenceValuePath())
                      .append(", '/', 2)\n");
            }

            // Resolve UNNEST chain for inner mapping's term code path
            var innerTcf = finalInnerMapping.getTermCodeFilter().orElseThrow(() ->
                new TranslationException("Inner mapping for " + finalInnerMapping.tableName()
                    + " has no termCodeFilter"));
            var innerResolution = ctx.resolveTermCodePath(
                finalInnerMapping.tableName(), innerTcf.path(), refAlias, refTcAlias);
            for (var clause : innerResolution.unnestClauses()) {
                fromSb.append(clause);
            }

            String innerTermPfx = innerResolution.terminalAlias()
                + (innerResolution.remainingPath().isEmpty() ? "" : "." + innerResolution.remainingPath());

            var sysConditions = innerBySystem.entrySet().stream().map(e -> {
                String inClause = e.getValue().stream()
                    .map(c -> "'" + escape(c) + "'")
                    .collect(Collectors.joining(", "));
                return innerTermPfx + ".system = '" + escape(e.getKey()) + "'"
                    + " AND " + innerTermPfx + ".code IN (" + inClause + ")";
            }).toList();

            String codeCondition = sysConditions.size() == 1
                ? sysConditions.get(0)
                : "(\n    " + String.join("\n    OR ", sysConditions) + "\n  )";

            String whereCondition = rafConfig.isExtensionBased()
                ? extAlias + ".url = '" + escape(rafConfig.extensionUrl()) + "'\n  AND " + codeCondition
                : codeCondition;

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

    private record CodingAttrFragment(String fromClause, String whereCondition) {}

    private List<CodingAttrFragment> buildCodingAttributeFragments(Mapping mapping, MappingContext ctx) {
        var fragments = new ArrayList<CodingAttrFragment>();
        int i = 0;
        for (var af : attributeFilters) {
            if (!"concept".equals(af.type())) continue;
            var cfg = mapping.findSimpleAttributeFilter(af.attributeCode().code()).orElse(null);
            if (cfg == null || !cfg.isCodeableConcept()) continue;

            String alias = "attr_tc" + i;
            String codingPath = cfg.path() + ".coding";
            var resolution = ctx.resolveTermCodePath(mapping.tableName(), codingPath, "t", alias);

            String fromClause = String.join("", resolution.unnestClauses());
            if (fromClause.isEmpty()) {
                fromClause = "CROSS JOIN UNNEST(t." + codingPath + ") AS " + alias + "\n";
            }

            var bySystem = new LinkedHashMap<String, List<String>>();
            for (var tc : af.selectedConcepts()) {
                bySystem.computeIfAbsent(tc.system(), k -> new ArrayList<>()).add(tc.code());
            }

            String termPfx = resolution.terminalAlias()
                + (resolution.remainingPath().isEmpty() ? "" : "." + resolution.remainingPath());

            var sysConditions = bySystem.entrySet().stream().map(e -> {
                String inClause = e.getValue().stream()
                    .map(c -> "'" + escape(c) + "'")
                    .collect(Collectors.joining(", "));
                return termPfx + ".system = '" + escape(e.getKey()) + "'"
                    + " AND " + termPfx + ".code IN (" + inClause + ")";
            }).toList();

            String whereCondition = sysConditions.size() == 1
                ? sysConditions.get(0)
                : "(\n    " + String.join("\n    OR ", sysConditions) + "\n  )";

            fragments.add(new CodingAttrFragment(fromClause, whereCondition));
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
                    if (cfg.isCodeableConcept()) break; // handled by buildCodingAttributeFragments
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
