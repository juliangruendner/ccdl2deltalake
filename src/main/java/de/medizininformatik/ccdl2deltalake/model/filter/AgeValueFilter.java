package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

/**
 * Value filter that computes patient age in years from a date column.
 *
 * <p>{@link #valueSqlPath} returns a Trino {@code DATE_DIFF} expression; there is no unit column
 * so {@link #unitSqlPath} returns {@code null}, and callers must skip the unit condition.
 */
public record AgeValueFilter(String path) implements ValueFilter {

    public AgeValueFilter {
        requireNonNull(path, "path must not be null");
    }

    @JsonCreator
    public static AgeValueFilter of(@JsonProperty("path") String path) {
        return new AgeValueFilter(path);
    }

    @Override
    public String valueSqlPath(String tableAlias) {
        return "DATE_DIFF('year', DATE(" + tableAlias + "." + path + "), CURRENT_DATE)";
    }

    /** Returns {@code null} — age has no unit column; callers must guard against null. */
    @Override
    public String unitSqlPath(String tableAlias) {
        return null;
    }
}
