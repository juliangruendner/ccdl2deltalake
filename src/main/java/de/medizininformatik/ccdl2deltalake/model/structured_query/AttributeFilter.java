package de.medizininformatik.ccdl2deltalake.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.medizininformatik.ccdl2deltalake.model.TermCode;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A CCDL attribute filter of type "reference": the primary resource holds an extension
 * that references another resource, and that other resource must match the inner criteria.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttributeFilter(TermCode attributeCode, List<Criterion> criteria) {

    public AttributeFilter {
        requireNonNull(attributeCode, "attributeCode must not be null");
        criteria = criteria != null ? List.copyOf(criteria) : List.of();
    }

    @JsonCreator
    public static AttributeFilter of(
        @JsonProperty("type") String type,
        @JsonProperty("attributeCode") TermCode attributeCode,
        @JsonProperty("criteria") List<Criterion> criteria
    ) {
        if (!"reference".equals(type)) {
            throw new IllegalArgumentException("Only 'reference' attribute filters are supported, got: " + type);
        }
        return new AttributeFilter(attributeCode, criteria);
    }
}
