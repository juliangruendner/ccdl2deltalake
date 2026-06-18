package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

/**
 * Describes a secondary table that must be joined to reach the coding array.
 *
 * <p>Example: MedicationAdministration has no inline coding — codes live in Medication.
 * The primary table links to the secondary via a reference field.
 *
 * <p>Generated SQL fragment:
 * <pre>{@code
 * JOIN <catalog>.<table> j ON t.<primaryRefPath> = j.<secondaryIdPath>
 * CROSS JOIN UNNEST(j.<termCodeFilter.arrayPath>) AS tc
 * }</pre>
 */
public record TermCodeJoin(String table, String primaryRefPath, String secondaryIdPath) {

    public TermCodeJoin {
        requireNonNull(table, "table must not be null");
        requireNonNull(primaryRefPath, "primaryRefPath must not be null");
        requireNonNull(secondaryIdPath, "secondaryIdPath must not be null");
    }

    @JsonCreator
    public static TermCodeJoin of(
        @JsonProperty("table") String table,
        @JsonProperty("primaryRefPath") String primaryRefPath,
        @JsonProperty("secondaryIdPath") String secondaryIdPath
    ) {
        return new TermCodeJoin(table, primaryRefPath, secondaryIdPath);
    }
}
