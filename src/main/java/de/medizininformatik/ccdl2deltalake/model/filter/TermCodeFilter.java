package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Describes where to find the coding used for term-code matching.
 *
 * <p>Four modes (mutually exclusive):
 *
 * <p><b>Array path</b> (default): coding in a CodeableConcept array on the primary table
 * (e.g. {@code Condition.code.coding}).  Set {@code arrayPath}.
 * Generates {@code CROSS JOIN UNNEST(t.<arrayPath>) AS tc}.
 *
 * <p><b>Joined array path</b>: coding array on a secondary table joined via {@code join}
 * (e.g. MedicationAdministration → Medication.code.coding).  Set {@code arrayPath} + {@code join}.
 *
 * <p><b>Single coding path</b>: field is a single {@code Coding} struct, not an array
 * (e.g. {@code Encounter.class}).  Set {@code singlePath}.
 * Generates no UNNEST; uses {@code WHERE t.<singlePath>.system/code} directly.
 *
 * <p><b>Chained array paths</b>: coding reached via multiple nested arrays
 * (e.g. {@code Consent}: provision → sub-provisions → code → coding).
 * Set {@code chainedArrayPaths = ["provision.provision", "code", "coding"]}.
 * Generates a chain of UNNEST steps; intermediate aliases are {@code _tc0}, {@code _tc1}, …;
 * the final alias is {@code tc} (used in WHERE as usual).
 */
public record TermCodeFilter(String arrayPath, TermCodeJoin join, String singlePath,
                              List<String> chainedArrayPaths) {

    public TermCodeFilter {
        boolean hasArray   = arrayPath != null;
        boolean hasSingle  = singlePath != null;
        boolean hasChained = chainedArrayPaths != null && !chainedArrayPaths.isEmpty();
        int modes = (hasArray ? 1 : 0) + (hasSingle ? 1 : 0) + (hasChained ? 1 : 0);
        if (modes == 0) {
            throw new IllegalArgumentException(
                "TermCodeFilter requires one of: arrayPath, singlePath, or chainedArrayPaths");
        }
        if (modes > 1) {
            throw new IllegalArgumentException(
                "TermCodeFilter: arrayPath, singlePath, and chainedArrayPaths are mutually exclusive");
        }
        if (hasChained && chainedArrayPaths.size() < 2) {
            throw new IllegalArgumentException(
                "TermCodeFilter.chainedArrayPaths must have at least 2 elements");
        }
        chainedArrayPaths = hasChained ? List.copyOf(chainedArrayPaths) : null;
    }

    @JsonCreator
    public static TermCodeFilter of(
        @JsonProperty("arrayPath") String arrayPath,
        @JsonProperty("join") TermCodeJoin join,
        @JsonProperty("singlePath") String singlePath,
        @JsonProperty("chainedArrayPaths") List<String> chainedArrayPaths
    ) {
        return new TermCodeFilter(arrayPath, join, singlePath, chainedArrayPaths);
    }

    public Optional<TermCodeJoin> getJoin() { return Optional.ofNullable(join); }
    public Optional<String> getSinglePath() { return Optional.ofNullable(singlePath); }
    public Optional<List<String>> getChainedArrayPaths() { return Optional.ofNullable(chainedArrayPaths); }
}
