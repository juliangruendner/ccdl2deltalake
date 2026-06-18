package de.medizininformatik.ccdl2deltalake.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum Comparator {
    LESS_THAN("<"),
    LESS_THAN_EQUAL("<="),
    EQUAL("="),
    GREATER_THAN_EQUAL(">="),
    GREATER_THAN(">"),
    NOT_EQUAL("!=");

    private final String sql;

    Comparator(String sql) {
        this.sql = sql;
    }

    public String toSql() {
        return sql;
    }

    @JsonCreator
    public static Comparator fromJson(String value) {
        return switch (value) {
            case "lt" -> LESS_THAN;
            case "le" -> LESS_THAN_EQUAL;
            case "eq" -> EQUAL;
            case "ge" -> GREATER_THAN_EQUAL;
            case "gt" -> GREATER_THAN;
            case "ne" -> NOT_EQUAL;
            default -> throw new IllegalArgumentException("Unknown comparator: " + value);
        };
    }
}
