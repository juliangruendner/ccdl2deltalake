package de.medizininformatik.ccdl2deltalake.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.medizininformatik.ccdl2deltalake.model.Mapping;

import java.time.LocalDate;
import java.util.ArrayList;

import static java.util.Objects.requireNonNull;

public record TimeRestriction(LocalDate afterDate, LocalDate beforeDate) {

    public static final LocalDate MIN_AFTER_DATE = LocalDate.of(1, 1, 1);
    public static final LocalDate MAX_BEFORE_DATE = LocalDate.of(9999, 12, 31);

    public TimeRestriction {
        requireNonNull(afterDate);
        requireNonNull(beforeDate);
        if (beforeDate.isBefore(afterDate)) {
            throw new IllegalArgumentException(
                "beforeDate '%s' must not be before afterDate '%s'".formatted(beforeDate, afterDate));
        }
    }

    @JsonCreator
    public static TimeRestriction create(
        @JsonProperty("afterDate") String afterDate,
        @JsonProperty("beforeDate") String beforeDate
    ) {
        if (afterDate == null && beforeDate == null) return null;
        var after = afterDate != null ? LocalDate.parse(afterDate) : MIN_AFTER_DATE;
        var before = beforeDate != null ? LocalDate.parse(beforeDate) : MAX_BEFORE_DATE;
        return new TimeRestriction(after, before);
    }

    /**
     * Returns SQL WHERE conditions for the date restriction, e.g.:
     * {@code DATE(t.recordeddate) >= DATE('2020-01-01')\n  AND DATE(t.recordeddate) <= DATE('2021-12-31')}
     */
    public String toSqlConditions(Mapping mapping, String tableAlias) {
        return mapping.getDateFilter().map(df -> {
            var expr = df.toSqlExpr(tableAlias);
            var parts = new ArrayList<String>();
            if (!afterDate.equals(MIN_AFTER_DATE)) {
                parts.add(expr + " >= DATE('" + afterDate + "')");
            }
            if (!beforeDate.equals(MAX_BEFORE_DATE)) {
                parts.add(expr + " <= DATE('" + beforeDate + "')");
            }
            return String.join("\n  AND ", parts);
        }).orElse("");
    }
}
