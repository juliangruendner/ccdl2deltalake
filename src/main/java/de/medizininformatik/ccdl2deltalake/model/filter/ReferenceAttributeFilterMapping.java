package de.medizininformatik.ccdl2deltalake.model.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static java.util.Objects.requireNonNull;

/**
 * Mapping config for a reference attribute filter.
 *
 * <p>Two modes depending on where the reference lives:
 *
 * <p><b>Direct field</b> (e.g. {@code Specimen.encounter.reference}):
 * set only {@code referenceValuePath = "encounter.reference"}.
 * Generated SQL:
 * <pre>{@code
 * INNER JOIN <catalog>.<refTable> ref<i> ON ref<i>.id = SPLIT_PART(t.<referenceValuePath>, '/', 2)
 * }</pre>
 *
 * <p><b>Extension</b> (e.g. biobank Diagnose extension):
 * set {@code extensionArrayPath = "_extension"}, {@code extensionUrl = "https://..."},
 * and {@code referenceValuePath = "valuereference.reference"}.
 * Generated SQL:
 * <pre>{@code
 * CROSS JOIN UNNEST(t.<extensionArrayPath>) AS _emap<i>(_ek<i>, _earr<i>)
 * CROSS JOIN UNNEST(_earr<i>) AS ext<i>
 * INNER JOIN <catalog>.<refTable> ref<i> ON ref<i>.id = SPLIT_PART(ext<i>.<referenceValuePath>, '/', 2)
 * WHERE ... AND ext<i>.url = '<extensionUrl>' AND ...
 * }</pre>
 */
public record ReferenceAttributeFilterMapping(
    String attributeCode,
    String referenceValuePath,
    String extensionArrayPath,
    String extensionUrl
) {
    public ReferenceAttributeFilterMapping {
        requireNonNull(attributeCode, "attributeCode must not be null");
        requireNonNull(referenceValuePath, "referenceValuePath must not be null");
    }

    @JsonCreator
    public static ReferenceAttributeFilterMapping of(
        @JsonProperty("attributeCode") String attributeCode,
        @JsonProperty("referenceValuePath") String referenceValuePath,
        @JsonProperty("extensionArrayPath") String extensionArrayPath,
        @JsonProperty("extensionUrl") String extensionUrl
    ) {
        return new ReferenceAttributeFilterMapping(attributeCode, referenceValuePath, extensionArrayPath, extensionUrl);
    }

    public boolean isExtensionBased() {
        return extensionUrl != null && extensionArrayPath != null;
    }
}
