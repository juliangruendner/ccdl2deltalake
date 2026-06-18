package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Describes how to access a value column in a Delta Lake table row.
 * Used by NumericCriterion, RangeCriterion, and ValueSetCriterion.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = QuantityValueFilter.class, name = "QUANTITY"),
    @JsonSubTypes.Type(value = CodingScalarValueFilter.class, name = "CODING_SCALAR"),
    @JsonSubTypes.Type(value = AgeValueFilter.class, name = "AGE")
})
public interface ValueFilter {

    /** Returns the SQL path for the numeric value, e.g. "t.valuequantity.value" */
    String valueSqlPath(String tableAlias);

    /** Returns the SQL path for the unit code, e.g. "t.valuequantity.code" */
    String unitSqlPath(String tableAlias);
}
