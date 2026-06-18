package de.medizininformatik.ccdl2deltalake.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;
import de.medizininformatik.ccdl2deltalake.model.common.Comparator;

import java.util.List;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNullElse;

/**
 * A single, atomic criterion in a Structured Query.
 * Deserialized from the CCDL JSON format (same as sq2cql input).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public interface Criterion {

    @JsonCreator
    static Criterion create(
        @JsonProperty("context") TermCode context,
        @JsonProperty("termCodes") List<TermCode> termCodes,
        @JsonProperty("valueFilter") ObjectNode valueFilter,
        @JsonProperty("timeRestriction") TimeRestriction timeRestriction,
        @JsonProperty("attributeFilters") List<AttributeFilter> attributeFilters
    ) {
        var concept = ContextualConcept.of(
            context != null ? context : TermCode.of("", "", ""),
            termCodes != null ? termCodes : List.of()
        );
        var attrs = requireNonNullElse(attributeFilters, List.<AttributeFilter>of());

        if (valueFilter == null) {
            return ConceptCriterion.of(concept, timeRestriction, attrs);
        }

        var type = valueFilter.get("type").asText();
        return switch (type) {
            case "quantity-comparator" -> {
                var comparator = Comparator.fromJson(valueFilter.get("comparator").asText());
                var value = valueFilter.get("value").decimalValue();
                var unitNode = valueFilter.get("unit");
                yield unitNode == null
                    ? NumericCriterion.of(concept, comparator, value, timeRestriction, attrs)
                    : NumericCriterion.of(concept, comparator, value,
                        unitNode.get("code").asText(), timeRestriction, attrs);
            }
            case "quantity-range" -> {
                var lower = valueFilter.get("minValue").decimalValue();
                var upper = valueFilter.get("maxValue").decimalValue();
                var unitNode = valueFilter.get("unit");
                yield unitNode == null
                    ? RangeCriterion.of(concept, lower, upper, timeRestriction, attrs)
                    : RangeCriterion.of(concept, lower, upper,
                        unitNode.get("code").asText(), timeRestriction, attrs);
            }
            case "concept" -> {
                var selectedConcepts = valueFilter.get("selectedConcepts");
                var concepts = StreamSupport.stream(selectedConcepts.spliterator(), false)
                    .map(n -> TermCode.of(
                        n.get("system").asText(),
                        n.get("code").asText(),
                        n.has("display") ? n.get("display").asText() : ""))
                    .toList();
                yield ValueSetCriterion.of(concept, concepts, timeRestriction, attrs);
            }
            default -> throw new IllegalArgumentException("Unknown valueFilter type: " + type);
        };
    }

    /**
     * Translates this criterion into a SQL SELECT returning distinct patient_id values.
     *
     * @param mappingContext holds the table/column mappings and ontology tree
     * @param catalog        fully-qualified catalog+schema prefix, e.g. {@code "fhir.default"}
     */
    String toSql(MappingContext mappingContext, String catalog);

    ContextualConcept getConcept();

    TimeRestriction getTimeRestriction();

    List<AttributeFilter> getAttributeFilters();
}
