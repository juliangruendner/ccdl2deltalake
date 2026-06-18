package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

/**
 * Describes where to find the coding used for term-code matching.
 *
 * <p>Three modes (mutually exclusive):
 *
 * <p><b>Array path</b> (default): the coding lives in a CodeableConcept array on the primary
 * table (e.g. {@code Condition.code.coding}).  Set {@code arrayPath}; leave {@code singlePath}
 * null.  Generates {@code CROSS JOIN UNNEST(t.<arrayPath>) AS tc} + {@code WHERE tc.system/code}.
 *
 * <p><b>Joined array path</b>: the coding array lives on a secondary table joined via
 * {@code join} (e.g. MedicationAdministration → Medication.code.coding).  Set both
 * {@code arrayPath} and {@code join}.  Generates a {@code JOIN} + {@code CROSS JOIN UNNEST}.
 *
 * <p><b>Single coding path</b>: the field is a single {@code Coding} struct, not an array
 * (e.g. {@code Encounter.class}).  Set {@code singlePath}; leave {@code arrayPath} null.
 * Generates no UNNEST — instead {@code WHERE t.<singlePath>.system = '...' AND t.<singlePath>.code IN (...)}.
 */
public record TermCodeFilter(String arrayPath, TermCodeJoin join, String singlePath) {

    public TermCodeFilter {
        if (singlePath == null && arrayPath == null) {
            throw new IllegalArgumentException("TermCodeFilter requires either arrayPath or singlePath");
        }
        if (singlePath != null && arrayPath != null) {
            throw new IllegalArgumentException("TermCodeFilter: arrayPath and singlePath are mutually exclusive");
        }
    }

    @JsonCreator
    public static TermCodeFilter of(
        @JsonProperty("arrayPath") String arrayPath,
        @JsonProperty("join") TermCodeJoin join,
        @JsonProperty("singlePath") String singlePath
    ) {
        return new TermCodeFilter(arrayPath, join, singlePath);
    }

    public Optional<TermCodeJoin> getJoin() { return Optional.ofNullable(join); }
    public Optional<String> getSinglePath() { return Optional.ofNullable(singlePath); }
}
