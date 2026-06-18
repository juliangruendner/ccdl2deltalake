package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

/**
 * Value filter for FHIR Quantity types (e.g., Observation.valueQuantity).
 */
public record QuantityValueFilter(
    String path,
    String valueField,
    String unitCodeField
) implements ValueFilter {

    public QuantityValueFilter {
        requireNonNull(path, "path must not be null");
        requireNonNull(valueField, "valueField must not be null");
        requireNonNull(unitCodeField, "unitCodeField must not be null");
    }

    @JsonCreator
    public static QuantityValueFilter of(
        @JsonProperty("path") String path,
        @JsonProperty("valueField") String valueField,
        @JsonProperty("unitCodeField") String unitCodeField
    ) {
        return new QuantityValueFilter(path, valueField, unitCodeField);
    }

    @Override
    public String valueSqlPath(String tableAlias) {
        return tableAlias + "." + path + "." + valueField;
    }

    @Override
    public String unitSqlPath(String tableAlias) {
        return tableAlias + "." + path + "." + unitCodeField;
    }
}
