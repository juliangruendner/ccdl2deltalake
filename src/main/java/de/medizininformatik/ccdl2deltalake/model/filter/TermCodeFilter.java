package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Describes where to find the coding array used for term-code matching.
 *
 * <p>When {@code join} is absent the coding array lives directly on the primary table
 * (the common case, e.g. Condition.code.coding).
 *
 * <p>When {@code join} is present the coding array lives on a secondary table that
 * must be joined first (e.g. MedicationAdministration → Medication.code.coding).
 */
public record TermCodeFilter(String arrayPath, TermCodeJoin join) {

    public TermCodeFilter {
        requireNonNull(arrayPath, "arrayPath must not be null");
    }

    @JsonCreator
    public static TermCodeFilter of(
        @JsonProperty("arrayPath") String arrayPath,
        @JsonProperty("join") TermCodeJoin join
    ) {
        return new TermCodeFilter(arrayPath, join);
    }

    public Optional<TermCodeJoin> getJoin() {
        return Optional.ofNullable(join);
    }
}
