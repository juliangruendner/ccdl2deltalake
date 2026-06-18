package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

/**
 * Describes which column holds the date/datetime for time restriction filtering.
 */
public record DateFilter(String path, DateType type) {

    public enum DateType { DATE, DATETIME }

    public DateFilter {
        requireNonNull(path, "path must not be null");
        type = type == null ? DateType.DATE : type;
    }

    @JsonCreator
    public static DateFilter of(
        @JsonProperty("path") String path,
        @JsonProperty("type") DateType type
    ) {
        return new DateFilter(path, type);
    }

    /**
     * Returns a SQL expression that yields a DATE value from the column,
     * e.g. "DATE(t.recordeddate)" or "DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime))".
     */
    public String toSqlExpr(String tableAlias) {
        String colRef = tableAlias + "." + path;
        return type == DateType.DATE
            ? "DATE(" + colRef + ")"
            : "DATE(FROM_ISO8601_TIMESTAMP(" + colRef + "))";
    }
}
