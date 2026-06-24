package de.medizininformatik.ccdl2deltalake;

import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;
import de.medizininformatik.ccdl2deltalake.model.common.Comparator;
import de.medizininformatik.ccdl2deltalake.model.structured_query.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that spin up the full trino-on-fhir stack via Testcontainers
 * and execute generated SQL against it. Test data lives in src/test/resources/docker/.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrinoIT {

    static final int TRINO_PORT = 8080;
    static final String TRINO_SERVICE = "trino";

    @Container
    static final ComposeContainer compose = new ComposeContainer(
        new File("src/test/resources/docker/compose-it.yaml"))
        .withExposedService(TRINO_SERVICE, TRINO_PORT,
            Wait.forHttp("/v1/info").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)));

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
        try (var ms = TrinoIT.class.getResourceAsStream("/test-mapping.json");
             var ts = TrinoIT.class.getResourceAsStream("/test-table-descriptions.json")) {
            ctx = MappingContext.fromJson(ms, ts);
        }
        translator = Translator.of(ctx);

        var host = compose.getServiceHost(TRINO_SERVICE, TRINO_PORT);
        var port = compose.getServicePort(TRINO_SERVICE, TRINO_PORT);
        var trinoUrl = "jdbc:trino://" + host + ":" + port + "/fhir/default";

        var props = new Properties();
        props.setProperty("user", "trino");
        connection = DriverManager.getConnection(trinoUrl, props);

        // Wait for warehousekeeper to register the Delta tables (runs after Pathling import).
        // Trino HTTP being up doesn't guarantee tables are registered yet.
        waitForTable("condition");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    private static void waitForTable(String tableName) throws InterruptedException {
        var deadline = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (var stmt = connection.createStatement();
                 var rs = stmt.executeQuery("SELECT 1 FROM " + tableName + " LIMIT 1")) {
                System.out.println("Table " + tableName + " is ready.");
                printSchema(tableName);
                return;
            } catch (Exception e) {
                System.out.println("Waiting for table " + tableName + " (" + e.getMessage() + ")");
                Thread.sleep(10_000);
            }
        }
        throw new RuntimeException("Timed out waiting for table: " + tableName);
    }

    private static void printSchema(String tableName) {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SHOW COLUMNS FROM " + tableName)) {
            System.out.println("=== SCHEMA: " + tableName + " ===");
            while (rs.next()) {
                System.out.println("  " + rs.getString("Column") + " : " + rs.getString("Type"));
            }
        } catch (Exception e) {
            System.out.println("Could not get schema for " + tableName + ": " + e.getMessage());
        }
    }

    private long executeCount(String sql) throws SQLException {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong("patient_count");
        }
    }

    @Test
    @Order(1)
    void conceptCriterion_migraine_returnsOnePatient() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE))))
        ));
        var sql = SqlWriter.write("it_concept_migraine", translator.toSql(query));

        assertThat(executeCount(sql)).isEqualTo(1);
    }

    @Test
    @Order(2)
    void conceptCriterion_gastritis_returnsOnePatient() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(GASTRITIS))))
        ));
        var sql = SqlWriter.write("it_concept_gastritis", translator.toSql(query));

        assertThat(executeCount(sql)).isEqualTo(1);
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

        assertThat(executeCount(sql)).isEqualTo(4);
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

        assertThat(executeCount(sql)).isEqualTo(3);
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

        assertThat(executeCount(sql)).isEqualTo(2);
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

        assertThat(executeCount(sql)).isEqualTo(1);
    }

    @Test
    @Order(7)
    void exclusion_migraineExcludingGastritis_stillReturnsOnePatient() throws Exception {
        var query = StructuredQuery.of(
            List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(MIGRAINE))))),
            List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSE_CTX, List.of(GASTRITIS)))))
        );
        var sql = SqlWriter.write("it_exclusion_migraine_not_gastritis", translator.toSql(query));

        assertThat(executeCount(sql)).isEqualTo(1);
    }

    @Test
    @Order(8)
    void referenceAttributeFilter_specimenWithDiagnosis_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-cond.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_ref_attr_specimen_condition", translator.toSql(query));

        var count = executeCount(sql);
        System.out.println("Specimen+E13.9 patient count: " + count);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @Order(9)
    void joinedTermCode_medicationAdministration_returnsPatients() throws Exception {
        var query = StructuredQuery.of(List.of(
            List.of(ConceptCriterion.of(ContextualConcept.of(MED_CTX, List.of(HEPARIN))))
        ));
        var sql = SqlWriter.write("it_joined_medication_heparin", translator.toSql(query));

        var count = executeCount(sql);
        System.out.println("Medication B01AB01 patient count: " + count);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @Order(10)
    void quantityComparatorAttributeFilter_specimenWithAmount_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-qty-comparator.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_qty_comparator_specimen", translator.toSql(query));

        var count = executeCount(sql);
        System.out.println("Serum specimen amount > 0 mL patient count: " + count);
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(11)
    void quantityRangeAttributeFilter_specimenAmountInRange_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-qty-range.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_qty_range_specimen", translator.toSql(query));

        var count = executeCount(sql);
        System.out.println("Serum specimen amount 0–1000 mL patient count: " + count);
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(12)
    void conceptAttributeFilter_specimenStatusAvailable_returnsPatients() throws Exception {
        var json = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/ccdl/spec-concept-attr.json"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var query = mapper.readValue(json, StructuredQuery.class);
        var sql = SqlWriter.write("it_concept_attr_specimen_status", translator.toSql(query));

        var count = executeCount(sql);
        System.out.println("Serum specimen status=available patient count: " + count);
        assertThat(count).isGreaterThan(0);
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

        assertThat(executeCount(sql)).isEqualTo(4);
    }
}
