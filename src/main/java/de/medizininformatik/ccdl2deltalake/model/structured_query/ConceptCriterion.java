package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.MappingNotFoundException;
import de.medizininformatik.ccdl2deltalake.TranslationException;
import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Selects patients that have at least one resource matching the concept (e.g., a Condition with a specific code).
 */
public final class ConceptCriterion extends AbstractCriterion {

    private ConceptCriterion(ContextualConcept concept, TimeRestriction timeRestriction) {
        super(concept, timeRestriction);
    }

    public static ConceptCriterion of(ContextualConcept concept) {
        return new ConceptCriterion(concept, null);
    }

    public static ConceptCriterion of(ContextualConcept concept, TimeRestriction timeRestriction) {
        return new ConceptCriterion(concept, timeRestriction);
    }

    @Override
    public String toSql(MappingContext ctx, String catalog) {
        var subQueries = new ArrayList<String>();

        for (TermCode termCode : concept.termCodes()) {
            var ctc = ContextualTermCode.of(concept.context(), termCode);
            var mapping = ctx.findMapping(ctc)
                .orElseThrow(() -> new MappingNotFoundException(ctc));
            var expanded = ctx.expandTermCode(ctc).toList();
            subQueries.add(buildTermCodeSql(catalog, mapping, expanded, null));
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
