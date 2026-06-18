package de.medizininformatik.ccdl2deltalake;

import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;
import de.medizininformatik.ccdl2deltalake.model.common.Comparator;
import de.medizininformatik.ccdl2deltalake.model.structured_query.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranslatorTest {

    static final TermCode DIAGNOSE_CTX = TermCode.of("fdpg.mii.cds", "Diagnose", "Diagnose");
    static final TermCode LAB_CTX = TermCode.of("fdpg.mii.cds", "Laboruntersuchung", "Laboruntersuchung");
    static final TermCode MED_CTX = TermCode.of("fdpg.mii.cds", "Medikamentenverabreichung", "Verabreichung von Medikamenten");
    static final TermCode SPECIMEN_CTX = TermCode.of("fdpg.mii.cds", "Specimen", "Bioprobe");

    static final TermCode MIGRAINE = TermCode.of("http://snomed.info/sct", "37796009", "Migraine");
    static final TermCode GASTRITIS = TermCode.of("http://snomed.info/sct", "4556007", "Gastritis");
    static final TermCode LEUKOCYTES = TermCode.of("http://loinc.org", "26464-8", "Leukocytes");
    static final TermCode HEMOGLOBIN = TermCode.of("http://loinc.org", "718-7", "Hemoglobin");
    static final TermCode HEPARIN = TermCode.of("http://fhir.de/CodeSystem/bfarm/atc", "B01AB01", "Ölsäure-Derivate");
    static final TermCode SERUM_SPECIMEN = TermCode.of("http://snomed.info/sct", "119364003", "Serum specimen");
    static final TermCode DIABETES_E13_9 = TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "E13.9", "Sonstiger Diabetes: Ohne Komplikationen");
    static final TermCode DIAGNOSE_CTX_TC = TermCode.of("fdpg.mii.cds", "Diagnose", "Diagnose");

    static MappingContext ctx;
    static Translator translator;

    @BeforeAll
    static void setup() throws IOException {
        try (var stream = TranslatorTest.class.getResourceAsStream("/test-mapping.json")) {
            ctx = MappingContext.fromJson(stream);
        }
        translator = Translator.of(ctx);
    }

    @Test
    void conceptCriterion_generatesCorrectSql() {
        var criterion = ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE)));
        var sql = SqlWriter.write("concept_criterion", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("SELECT DISTINCT SPLIT_PART(t.subject.reference, '/', 2) AS patient_id");
        assertThat(sql).contains("FROM fhir.default.condition t");
        assertThat(sql).contains("CROSS JOIN UNNEST(t.code.coding) AS tc");
        assertThat(sql).contains("tc.system = 'http://snomed.info/sct'");
        assertThat(sql).contains("tc.code IN ('37796009')");
    }

    @Test
    void numericCriterion_generatesCorrectSql() {
        var criterion = NumericCriterion.of(
            ContextualConcept.of(LAB_CTX, List.of(LEUKOCYTES)),
            Comparator.GREATER_THAN, new BigDecimal("10.0"), "/nL", null);
        var sql = SqlWriter.write("numeric_criterion", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("FROM fhir.default.observation t");
        assertThat(sql).contains("tc.code IN ('26464-8')");
        assertThat(sql).contains("t.valuequantity.value > 10.0");
        assertThat(sql).contains("t.valuequantity.code = '/nL'");
    }

    @Test
    void rangeCriterion_generatesCorrectSql() {
        var criterion = RangeCriterion.of(
            ContextualConcept.of(LAB_CTX, List.of(HEMOGLOBIN)),
            new BigDecimal("10.0"), new BigDecimal("14.0"), "g/dL", null);
        var sql = SqlWriter.write("range_criterion", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("t.valuequantity.value BETWEEN 10.0 AND 14.0");
        assertThat(sql).contains("t.valuequantity.code = 'g/dL'");
    }

    @Test
    void singleInclusion_noExclusion_noWrapping() {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE))))
        ));
        var sql = SqlWriter.write("single_inclusion", translator.toSql(query));

        assertThat(sql).doesNotContain("INTERSECT");
        assertThat(sql).doesNotContain("EXCEPT");
        assertThat(sql).contains("tc.code IN ('37796009')");
    }

    @Test
    void twoInclusionGroups_generatesIntersect() {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE)))),
            List.of(NumericCriterion.of(
                ContextualConcept.of(LAB_CTX, List.of(LEUKOCYTES)),
                Comparator.GREATER_THAN, new BigDecimal("10.0"), "/nL", null))
        ));
        var sql = SqlWriter.write("two_inclusion_groups", translator.toSql(query));

        assertThat(sql).contains("INTERSECT");
        assertThat(sql).contains("tc.code IN ('37796009')");
        assertThat(sql).contains("tc.code IN ('26464-8')");
    }

    @Test
    void orWithinGroup_generatesUnion() {
        var query = StructuredQuery.of(List.of(
            List.of(
                ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE))),
                ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(GASTRITIS)))
            )
        ));
        var sql = SqlWriter.write("or_within_group", translator.toSql(query));

        assertThat(sql).contains("UNION");
        assertThat(sql).contains("'37796009'");
        assertThat(sql).contains("'4556007'");
    }

    @Test
    void exclusion_generatesExcept() {
        var query = StructuredQuery.of(
            List.of(List.of(NumericCriterion.of(
                ContextualConcept.of(LAB_CTX, List.of(LEUKOCYTES)),
                Comparator.GREATER_THAN, new BigDecimal("5.0"), "/nL", null))),
            List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE)))))
        );
        var sql = SqlWriter.write("exclusion", translator.toSql(query));

        assertThat(sql).contains("EXCEPT");
        assertThat(sql).contains("'26464-8'");
        assertThat(sql).contains("'37796009'");
    }

    @Test
    void timeRestriction_addsDateFilter() {
        var tr = TimeRestriction.create("2024-01-01", "2024-12-31");
        var criterion = ConceptCriterion.of(
            ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE)), tr);
        var sql = SqlWriter.write("time_restriction", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("DATE(t.recordeddate) >= DATE('2024-01-01')");
        assertThat(sql).contains("DATE(t.recordeddate) <= DATE('2024-12-31')");
    }

    @Test
    void jsonDeserialization_conceptCriterion() throws Exception {
        var json = """
            {
              "inclusionCriteria": [[{
                "context": {"system": "fdpg.mii.cds", "code": "Diagnose", "display": "Diagnose"},
                "termCodes": [{"system": "http://snomed.info/sct", "code": "37796009", "display": "Migraine"}]
              }]]
            }
            """;

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("json_concept_criterion", translator.toSql(query));

        assertThat(sql).contains("'37796009'");
    }

    @Test
    void labCondFile_translatesHemoglobinAndDiabetesWithIntersect() throws Exception {
        MappingContext ctxWithTree;
        try (var ms = TranslatorTest.class.getResourceAsStream("/test-mapping.json");
             var ts = TranslatorTest.class.getResourceAsStream("/tree.json")) {
            ctxWithTree = MappingContext.fromJson(ms, ts);
        }
        var translatorWithTree = Translator.of(ctxWithTree);

        var json = Files.readString(Path.of("src/test/resources/ccdl/lab-cond.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("lab_cond", translatorWithTree.toSql(query));

        assertThat(sql).contains("INTERSECT");
        // Hemoglobin — not in tree, should stay as single code
        assertThat(sql).contains("tc.code IN ('718-7')");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) >= DATE('2026-06-01')");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) <= DATE('2026-06-26')");
        // Diabetes E10-E14 — expanded to root + all 155 descendants (156 codes total)
        assertThat(sql).contains("'E10-E14'");
        assertThat(sql).contains("'E10'");
        assertThat(sql).contains("'E11'");
        assertThat(sql).contains("'E12'");
        assertThat(sql).contains("'E13'");
        assertThat(sql).contains("'E14'");
        assertThat(sql).contains("'E10.01'");
        assertThat(sql).contains("'E11.90'");
        assertThat(sql).contains("DATE(t.recordeddate) <= DATE('2026-06-16')");
    }

    @Test
    void jsonDeserialization_numericCriterion() throws Exception {
        var json = """
            {
              "inclusionCriteria": [[{
                "context": {"system": "fdpg.mii.cds", "code": "Laboruntersuchung", "display": "Laboruntersuchung"},
                "termCodes": [{"system": "http://loinc.org", "code": "26464-8", "display": "Leukocytes"}],
                "valueFilter": {
                  "type": "quantity-comparator",
                  "comparator": "gt",
                  "value": 10.0,
                  "unit": {"code": "/nL"}
                }
              }]]
            }
            """;

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("json_numeric_criterion", translator.toSql(query));

        assertThat(sql).contains("valuequantity.value > 10.0");
        assertThat(sql).contains("'/nL'");
    }


    @Test
    void referenceAttributeFilter_specimenWithDiagnosis_generatesJoinSql() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-cond.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("ref_attr_filter_specimen_condition", translator.toSql(query));

        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("CROSS JOIN UNNEST(t.type.coding) AS tc");
        assertThat(sql).contains("CROSS JOIN UNNEST(t._extension)");
        assertThat(sql).contains("CROSS JOIN UNNEST(_earr0) AS ext0");
        assertThat(sql).contains("INNER JOIN fhir.default.condition ref0");
        assertThat(sql).contains("ref0.id = SPLIT_PART(ext0.valuereference.reference, '/', 2)");
        assertThat(sql).contains("CROSS JOIN UNNEST(ref0.code.coding) AS ref_tc0");
        assertThat(sql).contains("tc.system = 'http://snomed.info/sct'");
        assertThat(sql).contains("tc.code IN ('119364003')");
        assertThat(sql).contains("ext0.url = 'https://www.medizininformatik-initiative.de/fhir/ext/modul-biobank/StructureDefinition/Diagnose'");
        assertThat(sql).contains("ref_tc0.system = 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'");
        assertThat(sql).contains("ref_tc0.code IN ('E13.9')");
    }

    @Test
    void joinedTermCode_medicationAdministration_generatesJoinSql() {
        var criterion = ConceptCriterion.of(ContextualConcept.of(MED_CTX, List.of(HEPARIN)));
        var sql = SqlWriter.write("joined_termcode_medication", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("FROM fhir.default.medicationadministration t");
        assertThat(sql).contains("JOIN fhir.default.medication j ON t.medicationreference.reference = j.id_versioned");
        assertThat(sql).contains("CROSS JOIN UNNEST(j.code.coding) AS tc");
        assertThat(sql).contains("tc.system = 'http://fhir.de/CodeSystem/bfarm/atc'");
        assertThat(sql).contains("tc.code IN ('B01AB01')");
        assertThat(sql).doesNotContain("UNNEST(t.");
    }

    @Test
    void quantityComparatorAttributeFilter_generatesCorrectSql() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-qty-comparator.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("qty_comparator_attr_filter", translator.toSql(query));

        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("CROSS JOIN UNNEST(t.type.coding) AS tc");
        assertThat(sql).contains("tc.code IN ('119364003')");
        assertThat(sql).contains("t.collection.quantity.value > 0.0");
        assertThat(sql).contains("t.collection.quantity.code = 'mL'");
        assertThat(sql).doesNotContain("INNER JOIN");
    }

    @Test
    void quantityRangeAttributeFilter_generatesCorrectSql() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-qty-range.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("qty_range_attr_filter", translator.toSql(query));

        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("tc.code IN ('119364003')");
        assertThat(sql).contains("t.collection.quantity.value BETWEEN 0.0 AND 1000.0");
        assertThat(sql).contains("t.collection.quantity.code = 'mL'");
        assertThat(sql).doesNotContain("INNER JOIN");
    }

    @Test
    void conceptAttributeFilter_generatesCorrectSql() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-concept-attr.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("concept_attr_filter", translator.toSql(query));

        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("tc.code IN ('119364003')");
        assertThat(sql).contains("t.status IN ('available')");
        assertThat(sql).doesNotContain("INNER JOIN");
    }

    @Test
    void testNew() throws Exception {
        MappingContext ctxWithTree;
        try (var ms = TranslatorTest.class.getResourceAsStream("/test-mapping.json");
             var ts = TranslatorTest.class.getResourceAsStream("/tree.json")) {
            ctxWithTree = MappingContext.fromJson(ms, ts);
        }
        var translatorWithTree = Translator.of(ctxWithTree);

        var json = Files.readString(Path.of("src/test/resources/ccdl/test-new.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("test-new", translatorWithTree.toSql(query));


    }
    
}
