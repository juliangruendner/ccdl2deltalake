package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.medizininformatik.ccdl2deltalake.model.filter.DateFilter;
import de.medizininformatik.ccdl2deltalake.model.filter.TermCodeFilter;
import de.medizininformatik.ccdl2deltalake.model.filter.ValueFilter;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Maps a medical concept (TermCode in a context) to a Delta Lake table and its relevant columns.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "context":       { "system": "fdpg.mii.cds", "code": "Diagnose", "display": "Diagnose" },
 *   "key":           { "system": "http://snomed.info/sct", "code": "37796009", "display": "Migraine" },
 *   "tableName":     "condition",
 *   "patientRefPath":"subject.reference",
 *   "termCodeFilter":{ "arrayPath": "code.coding" },
 *   "dateFilter":    { "path": "recordeddate", "type": "DATE" }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Mapping(
    TermCode context,
    TermCode key,
    String tableName,
    String patientRefPath,
    TermCodeFilter termCodeFilter,
    ValueFilter valueFilter,
    DateFilter dateFilter
) {

    @JsonCreator
    public static Mapping create(
        @JsonProperty("context") TermCode context,
        @JsonProperty("key") TermCode key,
        @JsonProperty("tableName") String tableName,
        @JsonProperty("patientRefPath") String patientRefPath,
        @JsonProperty("termCodeFilter") TermCodeFilter termCodeFilter,
        @JsonProperty("valueFilter") ValueFilter valueFilter,
        @JsonProperty("dateFilter") DateFilter dateFilter
    ) {
        return new Mapping(
            requireNonNull(context, "missing: context"),
            requireNonNull(key, "missing: key"),
            requireNonNull(tableName, "missing: tableName"),
            requireNonNull(patientRefPath, "missing: patientRefPath"),
            requireNonNull(termCodeFilter, "missing: termCodeFilter"),
            valueFilter,
            dateFilter
        );
    }

    public ContextualTermCode contextualKey() {
        return ContextualTermCode.of(context, key);
    }

    public Optional<ValueFilter> getValueFilter() {
        return Optional.ofNullable(valueFilter);
    }

    public Optional<DateFilter> getDateFilter() {
        return Optional.ofNullable(dateFilter);
    }
}
