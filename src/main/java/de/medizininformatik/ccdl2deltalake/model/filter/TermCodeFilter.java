package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Describes where to find the coding used for term-code matching.
 *
 * <p>{@code path} is a dot-notation FHIRPath from the resource root to the coding leaf
 * (e.g. {@code "code.coding"}, {@code "class"}, {@code "provision.provision.code.coding"}).
 * How many UNNEST steps are generated — and whether any are needed at all — is determined at
 * query-build time by consulting the {@link de.medizininformatik.ccdl2deltalake.model.TableDescription}
 * for the target table.  Paths whose every segment is a scalar produce no UNNEST.
 *
 * <p>If {@code join} is present the coding lives on a secondary table joined via a
 * {@code JOIN} clause (e.g. MedicationAdministration → Medication.code.coding).
 */
public record TermCodeFilter(String path, TermCodeJoin join) {

    public TermCodeFilter {
        requireNonNull(path, "TermCodeFilter requires a path");
    }

    @JsonCreator
    public static TermCodeFilter of(
        @JsonProperty("path") String path,
        @JsonProperty("join") TermCodeJoin join
    ) {
        return new TermCodeFilter(path, join);
    }

    public Optional<TermCodeJoin> getJoin() { return Optional.ofNullable(join); }
}
