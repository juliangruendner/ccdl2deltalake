package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.MappingNotFoundException;
import de.medizininformatik.ccdl2deltalake.TranslationException;
import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public final class RangeCriterion extends AbstractCriterion {

    private final BigDecimal lowerBound;
    private final BigDecimal upperBound;
    private final String unit;

    private RangeCriterion(ContextualConcept concept, BigDecimal lowerBound, BigDecimal upperBound,
                            String unit, TimeRestriction timeRestriction,
                            List<AttributeFilter> attributeFilters) {
        super(concept, timeRestriction, attributeFilters);
        this.lowerBound = requireNonNull(lowerBound);
        this.upperBound = requireNonNull(upperBound);
        this.unit = unit;
    }

    public static RangeCriterion of(ContextualConcept concept, BigDecimal lowerBound,
                                    BigDecimal upperBound, TimeRestriction timeRestriction) {
        return new RangeCriterion(concept, lowerBound, upperBound, null, timeRestriction, List.of());
    }

    public static RangeCriterion of(ContextualConcept concept, BigDecimal lowerBound,
                                    BigDecimal upperBound, String unit, TimeRestriction timeRestriction) {
        return new RangeCriterion(concept, lowerBound, upperBound, unit, timeRestriction, List.of());
    }

    public static RangeCriterion of(ContextualConcept concept, BigDecimal lowerBound,
                                    BigDecimal upperBound, TimeRestriction timeRestriction,
                                    List<AttributeFilter> attributeFilters) {
        return new RangeCriterion(concept, lowerBound, upperBound, null, timeRestriction, attributeFilters);
    }

    public static RangeCriterion of(ContextualConcept concept, BigDecimal lowerBound,
                                    BigDecimal upperBound, String unit, TimeRestriction timeRestriction,
                                    List<AttributeFilter> attributeFilters) {
        return new RangeCriterion(concept, lowerBound, upperBound, unit, timeRestriction, attributeFilters);
    }

    @Override
    public String toSql(MappingContext ctx, String catalog) {
        var subQueries = new ArrayList<String>();

        for (TermCode termCode : concept.termCodes()) {
            var ctc = ContextualTermCode.of(concept.context(), termCode);
            var mapping = ctx.findMapping(ctc)
                .orElseThrow(() -> new MappingNotFoundException(ctc));
            var valueFilter = mapping.getValueFilter()
                .orElseThrow(() -> new TranslationException(
                    "Range criterion requires a valueFilter in mapping for: " + ctc));

            var additionalWhere = new StringBuilder();
            additionalWhere.append(valueFilter.valueSqlPath("t"))
                           .append(" BETWEEN ").append(lowerBound.toPlainString())
                           .append(" AND ").append(upperBound.toPlainString());
            if (unit != null && valueFilter.unitSqlPath("t") != null) {
                additionalWhere.append("\n  AND ").append(valueFilter.unitSqlPath("t"))
                               .append(" = '").append(escape(unit)).append("'");
            }

            var expanded = ctx.expandTermCode(ctc).toList();
            subQueries.add(buildTermCodeSql(catalog, mapping, expanded, additionalWhere.toString(), ctx));
        }

        if (subQueries.isEmpty()) {
            throw new TranslationException("No term codes in concept: " + concept);
        }
        if (subQueries.size() == 1) return subQueries.get(0);
        return subQueries.stream()
            .map(s -> "(\n" + s + "\n)")
            .collect(Collectors.joining("\nUNION\n"));
    }
}
