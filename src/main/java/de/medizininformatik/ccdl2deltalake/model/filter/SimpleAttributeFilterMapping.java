package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Mapping config for a non-reference attribute filter.
 *
 * <p>The {@code type} field uses FHIR type names to determine the SQL generation strategy:
 * <ul>
 *   <li>{@code null} / absent — scalar concept: {@code t.<path> IN ('<code1>', ...)}
 *   <li>{@code "CodeableConcept"} — coding array: resolves {@code <path>.coding} through the
 *       table description, emits {@code CROSS JOIN UNNEST} + system/code WHERE conditions
 *   <li>quantity types — use {@code valueField} and {@code unitCodeField}
 * </ul>
 */
public record SimpleAttributeFilterMapping(
    String attributeCode,
    String type,
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
        @JsonProperty("type") String type,
        @JsonProperty("path") String path,
        @JsonProperty("valueField") String valueField,
        @JsonProperty("unitCodeField") String unitCodeField
    ) {
        return new SimpleAttributeFilterMapping(attributeCode, type, path, valueField, unitCodeField);
    }

    public boolean isCodeableConcept() { return "CodeableConcept".equals(type); }
    public Optional<String> getValueField() { return Optional.ofNullable(valueField); }
    public Optional<String> getUnitCodeField() { return Optional.ofNullable(unitCodeField); }
}
