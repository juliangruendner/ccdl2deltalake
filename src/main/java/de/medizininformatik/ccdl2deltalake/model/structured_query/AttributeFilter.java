package de.medizininformatik.ccdl2deltalake.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.medizininformatik.ccdl2deltalake.model.TermCode;
import de.medizininformatik.ccdl2deltalake.model.common.Comparator;

import java.math.BigDecimal;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A CCDL attribute filter. Supported types:
 * <ul>
 *   <li>{@code reference} — primary resource references a secondary resource via extension or field
 *   <li>{@code quantity-comparator} — numeric comparison on a field of the primary table
 *   <li>{@code quantity-range} — numeric range on a field of the primary table
 *   <li>{@code concept} — coded value IN clause on a field of the primary table
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttributeFilter(
    String type,
    TermCode attributeCode,
    List<Criterion> criteria,
    Comparator comparator,
    BigDecimal value,
    BigDecimal minValue,
    BigDecimal maxValue,
    String unit,
    List<TermCode> selectedConcepts
) {

    public AttributeFilter {
        requireNonNull(type, "type must not be null");
        requireNonNull(attributeCode, "attributeCode must not be null");
        criteria = criteria != null ? List.copyOf(criteria) : List.of();
        selectedConcepts = selectedConcepts != null ? List.copyOf(selectedConcepts) : List.of();
    }

    @JsonCreator
    public static AttributeFilter of(
        @JsonProperty("type") String type,
        @JsonProperty("attributeCode") TermCode attributeCode,
        @JsonProperty("criteria") List<Criterion> criteria,
        @JsonProperty("comparator") Comparator comparator,
        @JsonProperty("value") BigDecimal value,
        @JsonProperty("minValue") BigDecimal minValue,
        @JsonProperty("maxValue") BigDecimal maxValue,
        @JsonProperty("unit") ObjectNode unitNode,
        @JsonProperty("selectedConcepts") List<TermCode> selectedConcepts
    ) {
        requireNonNull(type, "type must not be null");
        String unit = unitNode != null && unitNode.has("code") ? unitNode.get("code").asText() : null;
        return switch (type) {
            case "reference" -> new AttributeFilter(type, attributeCode, criteria,
                null, null, null, null, null, null);
            case "quantity-comparator" -> {
                requireNonNull(comparator, "comparator required for quantity-comparator");
                requireNonNull(value, "value required for quantity-comparator");
                yield new AttributeFilter(type, attributeCode, null,
                    comparator, value, null, null, unit, null);
            }
            case "quantity-range" -> {
                requireNonNull(minValue, "minValue required for quantity-range");
                requireNonNull(maxValue, "maxValue required for quantity-range");
                yield new AttributeFilter(type, attributeCode, null,
                    null, null, minValue, maxValue, unit, null);
            }
            case "concept" -> new AttributeFilter(type, attributeCode, null,
                null, null, null, null, null, selectedConcepts);
            default -> throw new IllegalArgumentException("Unknown attribute filter type: " + type);
        };
    }
}
