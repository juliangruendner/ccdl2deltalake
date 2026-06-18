package de.medizininformatik.ccdl2deltalake.model.structured_query;

import de.medizininformatik.ccdl2deltalake.model.ContextualTermCode;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.util.List;

import static java.util.Objects.requireNonNull;

public record ContextualConcept(TermCode context, List<TermCode> termCodes) {

    public ContextualConcept {
        requireNonNull(context);
        termCodes = List.copyOf(requireNonNull(termCodes));
    }

    public static ContextualConcept of(TermCode context, List<TermCode> termCodes) {
        return new ContextualConcept(context, termCodes);
    }

    public List<ContextualTermCode> contextualTermCodes() {
        return termCodes.stream()
            .map(tc -> ContextualTermCode.of(context, tc))
            .toList();
    }
}
