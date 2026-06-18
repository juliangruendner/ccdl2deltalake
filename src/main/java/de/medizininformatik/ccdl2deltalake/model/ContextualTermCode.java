package de.medizininformatik.ccdl2deltalake.model;

import static java.util.Objects.requireNonNull;

public record ContextualTermCode(TermCode context, TermCode termCode) {

    public ContextualTermCode {
        requireNonNull(context);
        requireNonNull(termCode);
    }

    public static ContextualTermCode of(TermCode context, TermCode termCode) {
        return new ContextualTermCode(context, termCode);
    }
}
