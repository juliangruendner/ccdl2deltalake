package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.MappingNotFoundException;
import de.medizininformatik.ccdl2deltalake.TranslationException;
import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class ConceptCriterion extends AbstractCriterion {

    private ConceptCriterion(ContextualConcept concept, TimeRestriction timeRestriction,
                              List<AttributeFilter> attributeFilters) {
        super(concept, timeRestriction, attributeFilters);
    }

    public static ConceptCriterion of(ContextualConcept concept) {
        return new ConceptCriterion(concept, null, List.of());
    }

    public static ConceptCriterion of(ContextualConcept concept, TimeRestriction timeRestriction) {
        return new ConceptCriterion(concept, timeRestriction, List.of());
    }

    public static ConceptCriterion of(ContextualConcept concept, TimeRestriction timeRestriction,
                                      List<AttributeFilter> attributeFilters) {
        return new ConceptCriterion(concept, timeRestriction, attributeFilters);
    }

    @Override
    public String toSql(MappingContext ctx, String catalog) {
        var subQueries = new ArrayList<String>();

        for (TermCode termCode : concept.termCodes()) {
            var ctc = ContextualTermCode.of(concept.context(), termCode);
            var mapping = ctx.findMapping(ctc)
                .orElseThrow(() -> new MappingNotFoundException(ctc));
            var expanded = ctx.expandTermCode(ctc).toList();
            subQueries.add(buildTermCodeSql(catalog, mapping, expanded, null, ctx));
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
