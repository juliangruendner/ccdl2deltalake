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

/**
 * Selects patients with a resource whose coded value is one of the selected concepts.
 * The valueFilter in the mapping must be a QuantityValueFilter where unitSqlPath points to the code field.
 */
public final class ValueSetCriterion extends AbstractCriterion {

    private final List<TermCode> selectedConcepts;

    private ValueSetCriterion(ContextualConcept concept, List<TermCode> selectedConcepts,
                               TimeRestriction timeRestriction) {
        super(concept, timeRestriction);
        this.selectedConcepts = List.copyOf(requireNonNull(selectedConcepts));
    }

    public static ValueSetCriterion of(ContextualConcept concept, List<TermCode> selectedConcepts,
                                       TimeRestriction timeRestriction) {
        return new ValueSetCriterion(concept, selectedConcepts, timeRestriction);
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
                    "ValueSet criterion requires a valueFilter in mapping for: " + ctc));

            // Group selected concepts by system and build a filter per system
            var bySystem = selectedConcepts.stream()
                .collect(Collectors.groupingBy(TermCode::system));

            var valueConditions = bySystem.entrySet().stream().map(entry -> {
                String inClause = entry.getValue().stream()
                    .map(c -> "'" + escape(c.code()) + "'")
                    .collect(Collectors.joining(", "));
                // Use the unitSqlPath as the value code column
                return valueFilter.unitSqlPath("t") + " IN (" + inClause + ")";
            }).collect(Collectors.joining("\n  OR "));

            var expanded = ctx.expandTermCode(ctc).toList();
            subQueries.add(buildTermCodeSql(catalog, mapping, expanded, valueConditions));
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
