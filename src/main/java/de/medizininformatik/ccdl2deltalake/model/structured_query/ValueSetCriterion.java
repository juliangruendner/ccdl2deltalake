package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.MappingNotFoundException;
import de.medizininformatik.ccdl2deltalake.TranslationException;
import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public final class ValueSetCriterion extends AbstractCriterion {

    private final List<TermCode> selectedConcepts;

    private ValueSetCriterion(ContextualConcept concept, List<TermCode> selectedConcepts,
                               TimeRestriction timeRestriction, List<AttributeFilter> attributeFilters) {
        super(concept, timeRestriction, attributeFilters);
        this.selectedConcepts = List.copyOf(requireNonNull(selectedConcepts));
    }

    public static ValueSetCriterion of(ContextualConcept concept, List<TermCode> selectedConcepts,
                                       TimeRestriction timeRestriction) {
        return new ValueSetCriterion(concept, selectedConcepts, timeRestriction, List.of());
    }

    public static ValueSetCriterion of(ContextualConcept concept, List<TermCode> selectedConcepts,
                                       TimeRestriction timeRestriction,
                                       List<AttributeFilter> attributeFilters) {
        return new ValueSetCriterion(concept, selectedConcepts, timeRestriction, attributeFilters);
    }

    @Override
    public String toSql(MappingContext ctx) {
        var subQueries = new ArrayList<String>();

        for (TermCode termCode : concept.termCodes()) {
            var ctc = ContextualTermCode.of(concept.context(), termCode);
            var mapping = ctx.findMapping(ctc)
                .orElseThrow(() -> new MappingNotFoundException(ctc));
            var valueFilter = mapping.getValueFilter()
                .orElseThrow(() -> new TranslationException(
                    "ValueSet criterion requires a valueFilter in mapping for: " + ctc));

            var bySystem = selectedConcepts.stream()
                .collect(Collectors.groupingBy(TermCode::system));

            var valueConditions = bySystem.entrySet().stream().map(entry -> {
                String inClause = entry.getValue().stream()
                    .map(c -> "'" + escape(c.code()) + "'")
                    .collect(Collectors.joining(", "));
                return valueFilter.unitSqlPath("t") + " IN (" + inClause + ")";
            }).collect(Collectors.joining("\n  OR "));

            var expanded = ctx.expandTermCode(ctc).toList();
            subQueries.add(buildTermCodeSql(mapping, expanded, valueConditions, ctx));
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
