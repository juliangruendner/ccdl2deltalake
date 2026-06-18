package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Structural metadata for a single resource table: which dot-notation FHIR paths are arrays
 * (i.e. require UNNEST) vs. scalars.  Kept separate from concept mappings so cardinality info
 * (derived from the base FHIR spec / Pathling encoding) does not pollute profile-specific paths.
 */
public record TableDescription(Set<String> arrays) {

    @JsonCreator
    public static TableDescription of(@JsonProperty("arrays") Set<String> arrays) {
        return new TableDescription(arrays != null ? Set.copyOf(arrays) : Set.of());
    }
}
