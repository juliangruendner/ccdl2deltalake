package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.MappingNotFoundException;
import de.medizininformatik.ccdl2deltalake.TranslationException;
import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;
import de.medizininformatik.ccdl2deltalake.model.common.Comparator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public final class NumericCriterion extends AbstractCriterion {

    private final Comparator comparator;
    private final BigDecimal value;
    private final String unit;

    private NumericCriterion(ContextualConcept concept, Comparator comparator, BigDecimal value,
                              String unit, TimeRestriction timeRestriction,
                              List<AttributeFilter> attributeFilters) {
        super(concept, timeRestriction, attributeFilters);
        this.comparator = requireNonNull(comparator);
        this.value = requireNonNull(value);
        this.unit = unit;
    }

    public static NumericCriterion of(ContextualConcept concept, Comparator comparator,
                                      BigDecimal value, TimeRestriction timeRestriction) {
        return new NumericCriterion(concept, comparator, value, null, timeRestriction, List.of());
    }

    public static NumericCriterion of(ContextualConcept concept, Comparator comparator,
                                      BigDecimal value, String unit, TimeRestriction timeRestriction) {
        return new NumericCriterion(concept, comparator, value, unit, timeRestriction, List.of());
    }

    public static NumericCriterion of(ContextualConcept concept, Comparator comparator,
                                      BigDecimal value, TimeRestriction timeRestriction,
                                      List<AttributeFilter> attributeFilters) {
        return new NumericCriterion(concept, comparator, value, null, timeRestriction, attributeFilters);
    }

    public static NumericCriterion of(ContextualConcept concept, Comparator comparator,
                                      BigDecimal value, String unit, TimeRestriction timeRestriction,
                                      List<AttributeFilter> attributeFilters) {
        return new NumericCriterion(concept, comparator, value, unit, timeRestriction, attributeFilters);
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
                    "Numeric criterion requires a valueFilter in mapping for: " + ctc));

            var additionalWhere = new StringBuilder();
            additionalWhere.append(valueFilter.valueSqlPath("t"))
                           .append(" ").append(comparator.toSql())
                           .append(" ").append(value.toPlainString());
            if (unit != null) {
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
