package de.medizininformatik.ccdl2deltalake;

import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;
import de.medizininformatik.ccdl2deltalake.model.common.Comparator;
import de.medizininformatik.ccdl2deltalake.model.structured_query.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that execute generated SQL against the live Trino instance.
 * Requires the trino-on-fhir stack running at http://localhost:8080.
 * Generated SQL files are written to target/sql/ for inspection in VS Code.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrinoIT {

    static final String TRINO_URL = "jdbc:trino://localhost:8080/fhir/default";

    static final TermCode DIAGNOSE_CTX = TermCode.of("fdpg.mii.cds", "Diagnose", "Diagnose");
    static final TermCode LAB_CTX = TermCode.of("fdpg.mii.cds", "Laboruntersuchung", "Laboruntersuchung");
    static final TermCode MED_CTX = TermCode.of("fdpg.mii.cds", "Medikamentenverabreichung", "Verabreichung von Medikamenten");

    static final TermCode MIGRAINE = TermCode.of("http://snomed.info/sct", "37796009", "Migraine");
    static final TermCode GASTRITIS = TermCode.of("http://snomed.info/sct", "4556007", "Gastritis");
    static final TermCode LEUKOCYTES = TermCode.of("http://loinc.org", "26464-8", "Leukocytes");
    static final TermCode HEMOGLOBIN = TermCode.of("http://loinc.org", "718-7", "Hemoglobin");
    static final TermCode HEPARIN = TermCode.of("http://fhir.de/CodeSystem/bfarm/atc", "B01AB01", "Ölsäure-Derivate");

    static MappingContext ctx;
    static Translator translator;
    static Connection connection;

    @BeforeAll
    static void setup() throws Exception {
        try (var stream = TrinoIT.class.getResourceAsStream("/test-mapping.json")) {
            ctx = MappingContext.fromJson(stream);
        }
        translator = Translator.of(ctx);

        var props = new Properties();
        props.setProperty("user", "trino");
        connection = DriverManager.getConnection(TRINO_URL, props);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    private List<String> executeQuery(String sql) throws SQLException {
        var results = new ArrayList<String>();
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                results.add(rs.getString("patient_id"));
            }
        }
        return results;
    }

    @Test
    @Order(1)
    void conceptCriterion_migraine_returnsOnePatient() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE))))
        ));
        var sql = SqlWriter.write("it_concept_migraine", translator.toSql(query));

        assertThat(executeQuery(sql)).containsExactlyInAnyOrder("mii-exa-test-data-patient-10");
    }

    @Test
    @Order(2)
    void conceptCriterion_gastritis_returnsOnePatient() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(GASTRITIS))))
        ));
        var sql = SqlWriter.write("it_concept_gastritis", translator.toSql(query));

        assertThat(executeQuery(sql)).containsExactlyInAnyOrder("mii-exa-test-data-patient-6");
    }

    @Test
    @Order(3)
    void numericCriterion_leukocytesAbove10_returnsFourPatients() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(NumericCriterion.of(
                ContextualConcept.of(LAB_CTX, List.of(LEUKOCYTES)),
                Comparator.GREATER_THAN, new BigDecimal("10.0"), "/nL", null))
        ));
        var sql = SqlWriter.write("it_numeric_leukocytes", translator.toSql(query));

        assertThat(executeQuery(sql)).containsExactlyInAnyOrder(
            "mii-exa-test-data-patient-1",
            "mii-exa-test-data-patient-3",
            "mii-exa-test-data-patient-7",
            "mii-exa-test-data-patient-8"
        );
    }

    @Test
    @Order(4)
    void rangeCriterion_hemoglobinInRange_returnsThreePatients() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(RangeCriterion.of(
                ContextualConcept.of(LAB_CTX, List.of(HEMOGLOBIN)),
                new BigDecimal("10.0"), new BigDecimal("14.0"), "g/dL", null))
        ));
        var sql = SqlWriter.write("it_range_hemoglobin", translator.toSql(query));

        assertThat(executeQuery(sql)).containsExactlyInAnyOrder(
            "mii-exa-test-data-patient-5",
            "mii-exa-test-data-patient-7",
            "mii-exa-test-data-patient-9"
        );
    }

    @Test
    @Order(5)
    void orWithinGroup_migraineOrGastritis_returnsTwoPatients() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(
                ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE))),
                ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(GASTRITIS)))
            )
        ));
        var sql = SqlWriter.write("it_or_migraine_gastritis", translator.toSql(query));

        assertThat(executeQuery(sql)).containsExactlyInAnyOrder(
            "mii-exa-test-data-patient-6",
            "mii-exa-test-data-patient-10"
        );
    }

    @Test
    @Order(6)
    void intersect_leukocytesHighAndHemoglobinInRange_returnsOnePatient() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(NumericCriterion.of(
                ContextualConcept.of(LAB_CTX, List.of(LEUKOCYTES)),
                Comparator.GREATER_THAN, new BigDecimal("10.0"), "/nL", null)),
            List.of(RangeCriterion.of(
                ContextualConcept.of(LAB_CTX, List.of(HEMOGLOBIN)),
                new BigDecimal("10.0"), new BigDecimal("14.0"), "g/dL", null))
        ));
        var sql = SqlWriter.write("it_intersect_leukocytes_hemoglobin", translator.toSql(query));

        // patient-7 has leukocytes=15.2 > 10 AND hemoglobin=11.8 in [10,14]
        assertThat(executeQuery(sql)).containsExactlyInAnyOrder("mii-exa-test-data-patient-7");
    }

    @Test
    @Order(7)
    void exclusion_migraineExcludingGastritis_stillReturnsPatient10() throws Exception {
        var query = StructuredQuery.of(
            List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE))))),
            List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(GASTRITIS)))))
        );
        var sql = SqlWriter.write("it_exclusion_migraine_not_gastritis", translator.toSql(query));

        assertThat(executeQuery(sql)).containsExactlyInAnyOrder("mii-exa-test-data-patient-10");
    }

    @Test
    @Order(8)
    void referenceAttributeFilter_specimenWithDiagnosis_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-cond.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_ref_attr_specimen_condition", translator.toSql(query));

        var results = executeQuery(sql);
        System.out.println("Specimen+E13.9 patients: " + results);
        assertThat(results).isNotEmpty();
    }

    @Test
    @Order(9)
    void joinedTermCode_medicationAdministration_returnsPatients() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(MED_CTX, List.of(HEPARIN))))
        ));
        var sql = SqlWriter.write("it_joined_medication_heparin", translator.toSql(query));

        var results = executeQuery(sql);
        System.out.println("Medication B01AB01 patients: " + results);
        assertThat(results).isNotEmpty();
    }

    @Test
    @Order(10)
    void quantityComparatorAttributeFilter_specimenWithAmount_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-qty-comparator.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_qty_comparator_specimen", translator.toSql(query));

        var results = executeQuery(sql);
        System.out.println("Serum specimen amount > 0 mL patients: " + results);
        assertThat(results).isNotEmpty();
    }

    @Test
    @Order(11)
    void quantityRangeAttributeFilter_specimenAmountInRange_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-qty-range.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_qty_range_specimen", translator.toSql(query));

        var results = executeQuery(sql);
        System.out.println("Serum specimen amount 0–1000 mL patients: " + results);
        assertThat(results).isNotEmpty();
    }

    @Test
    @Order(12)
    void conceptAttributeFilter_specimenStatusAvailable_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-concept-attr.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_concept_attr_specimen_status", translator.toSql(query));

        var results = executeQuery(sql);
        System.out.println("Serum specimen status=available patients: " + results);
        assertThat(results).isNotEmpty();
    }

    @Test
    @Order(13)
    void jsonInput_endToEnd() throws Exception {
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
        var sql = SqlWriter.write("it_json_end_to_end", translator.toSql(query));

        assertThat(executeQuery(sql)).hasSize(4).contains("mii-exa-test-data-patient-1");
    }
}
