package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

/**
 * Value filter for a plain scalar string column that directly holds a code value
 * (e.g. {@code Patient.gender = "female"}).
 *
 * <p>Both {@link #valueSqlPath} and {@link #unitSqlPath} return {@code <alias>.<path>}
 * so that {@code ValueSetCriterion} emits {@code t.<path> IN ('code1', ...)}.
 */
public record CodingScalarValueFilter(String path) implements ValueFilter {

    public CodingScalarValueFilter {
        requireNonNull(path, "path must not be null");
    }

    @JsonCreator
    public static CodingScalarValueFilter of(@JsonProperty("path") String path) {
        return new CodingScalarValueFilter(path);
    }

    @Override
    public String valueSqlPath(String tableAlias) { return tableAlias + "." + path; }

    @Override
    public String unitSqlPath(String tableAlias) { return tableAlias + "." + path; }
}
