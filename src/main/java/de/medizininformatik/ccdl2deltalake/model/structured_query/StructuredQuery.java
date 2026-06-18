package de.medizininformatik.ccdl2deltalake.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The root input object — a CCDL structured query with inclusion and exclusion criteria.
 *
 * <p>Inclusion criteria are interpreted as Conjunctive Normal Form (CNF): AND of ORs.
 * Exclusion criteria are interpreted as Disjunctive Normal Form (DNF): OR of ANDs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredQuery(
    List<List<Criterion>> inclusionCriteria,
    List<List<Criterion>> exclusionCriteria
) {

    public StructuredQuery {
        inclusionCriteria = inclusionCriteria.stream().map(List::copyOf).toList();
        exclusionCriteria = exclusionCriteria.stream().map(List::copyOf).toList();
    }

    public static StructuredQuery of(List<List<Criterion>> inclusionCriteria) {
        return new StructuredQuery(inclusionCriteria, List.of(List.of()));
    }

    @JsonCreator
    public static StructuredQuery of(
        @JsonProperty("inclusionCriteria") List<List<Criterion>> inclusionCriteria,
        @JsonProperty("exclusionCriteria") List<List<Criterion>> exclusionCriteria
    ) {
        if (inclusionCriteria == null || inclusionCriteria.isEmpty()
            || inclusionCriteria.stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("inclusionCriteria must not be empty");
        }
        return new StructuredQuery(
            inclusionCriteria,
            exclusionCriteria == null ? List.of(List.of()) : exclusionCriteria
        );
    }
}
