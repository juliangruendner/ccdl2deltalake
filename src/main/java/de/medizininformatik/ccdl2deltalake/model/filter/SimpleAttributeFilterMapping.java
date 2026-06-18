package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Mapping config for a non-reference attribute filter (quantity-comparator, quantity-range, concept).
 *
 * <p>For quantity types set {@code valueField} and {@code unitCodeField}.
 * For concept type, omit them — only {@code path} is used.
 *
 * <p>Generated SQL conditions (table alias {@code t}):
 * <ul>
 *   <li>quantity-comparator: {@code t.<path>.<valueField> <op> <val> [AND t.<path>.<unitCodeField> = '<unit>']}
 *   <li>quantity-range: {@code t.<path>.<valueField> BETWEEN <lo> AND <hi> [AND t.<path>.<unitCodeField> = '<unit>']}
 *   <li>concept: {@code t.<path> IN ('<code1>', ...)}
 * </ul>
 */
public record SimpleAttributeFilterMapping(
    String attributeCode,
    String path,
    String valueField,
    String unitCodeField
) {
    public SimpleAttributeFilterMapping {
        requireNonNull(attributeCode, "attributeCode must not be null");
        requireNonNull(path, "path must not be null");
    }

    @JsonCreator
    public static SimpleAttributeFilterMapping of(
        @JsonProperty("attributeCode") String attributeCode,
        @JsonProperty("path") String path,
        @JsonProperty("valueField") String valueField,
        @JsonProperty("unitCodeField") String unitCodeField
    ) {
        return new SimpleAttributeFilterMapping(attributeCode, path, valueField, unitCodeField);
    }

    public Optional<String> getValueField() { return Optional.ofNullable(valueField); }
    public Optional<String> getUnitCodeField() { return Optional.ofNullable(unitCodeField); }
}
