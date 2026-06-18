package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.medizininformatik.ccdl2deltalake.model.filter.DateFilter;
import de.medizininformatik.ccdl2deltalake.model.filter.ReferenceAttributeFilterMapping;
import de.medizininformatik.ccdl2deltalake.model.filter.SimpleAttributeFilterMapping;
import de.medizininformatik.ccdl2deltalake.model.filter.TermCodeFilter;
import de.medizininformatik.ccdl2deltalake.model.filter.ValueFilter;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Mapping(
    TermCode context,
    TermCode key,
    String tableName,
    String patientRefPath,
    TermCodeFilter termCodeFilter,
    ValueFilter valueFilter,
    DateFilter dateFilter,
    List<ReferenceAttributeFilterMapping> referenceAttributeFilters,
    List<SimpleAttributeFilterMapping> simpleAttributeFilters
) {

    @JsonCreator
    public static Mapping create(
        @JsonProperty("context") TermCode context,
        @JsonProperty("key") TermCode key,
        @JsonProperty("tableName") String tableName,
        @JsonProperty("patientRefPath") String patientRefPath,
        @JsonProperty("termCodeFilter") TermCodeFilter termCodeFilter,
        @JsonProperty("valueFilter") ValueFilter valueFilter,
        @JsonProperty("dateFilter") DateFilter dateFilter,
        @JsonProperty("referenceAttributeFilters") List<ReferenceAttributeFilterMapping> referenceAttributeFilters,
        @JsonProperty("simpleAttributeFilters") List<SimpleAttributeFilterMapping> simpleAttributeFilters
    ) {
        return new Mapping(
            requireNonNull(context, "missing: context"),
            requireNonNull(key, "missing: key"),
            requireNonNull(tableName, "missing: tableName"),
            requireNonNull(patientRefPath, "missing: patientRefPath"),
            requireNonNull(termCodeFilter, "missing: termCodeFilter"),
            valueFilter,
            dateFilter,
            referenceAttributeFilters != null ? List.copyOf(referenceAttributeFilters) : List.of(),
            simpleAttributeFilters != null ? List.copyOf(simpleAttributeFilters) : List.of()
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

    public Optional<ReferenceAttributeFilterMapping> findRefAttributeFilter(String attributeCode) {
        return referenceAttributeFilters.stream()
            .filter(r -> r.attributeCode().equals(attributeCode))
            .findFirst();
    }

    public Optional<SimpleAttributeFilterMapping> findSimpleAttributeFilter(String attributeCode) {
        return simpleAttributeFilters.stream()
            .filter(s -> s.attributeCode().equals(attributeCode))
            .findFirst();
    }
}
