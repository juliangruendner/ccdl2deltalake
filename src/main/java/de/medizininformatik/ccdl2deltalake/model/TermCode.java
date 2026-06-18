package de.medizininformatik.ccdl2deltalake.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TermCode(String system, String code, String display) {

    public TermCode {
        requireNonNull(system, "system must not be null");
        requireNonNull(code, "code must not be null");
        display = display == null ? "" : display;
    }

    @JsonCreator
    public static TermCode of(@JsonProperty("system") String system,
                              @JsonProperty("code") String code,
                              @JsonProperty("display") String display) {
        return new TermCode(system, code, display);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TermCode t)) return false;
        return system.equals(t.system) && code.equals(t.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(system, code);
    }
}
