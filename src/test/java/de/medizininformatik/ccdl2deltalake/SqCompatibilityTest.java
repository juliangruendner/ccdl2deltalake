package de.medizininformatik.ccdl2deltalake;

import de.medizininformatik.ccdl2deltalake.model.MappingContext;
import de.medizininformatik.ccdl2deltalake.model.TermCode;
import de.medizininformatik.ccdl2deltalake.model.common.Comparator;
import de.medizininformatik.ccdl2deltalake.model.structured_query.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the translator can parse and generate valid SQL for each SQ fixture
 * sourced from the sq2cql test resource directory.
 *
 * <p>Gap analysis — the following sq2cql SQs are NOT covered by tests because they require
 * features not yet implemented:
 *
 * <ol>
 *   <li><b>Encounter (Fall)</b> — EncounterInpatient, EncounterInpatientAbteilung,
 *       EncounterInpatientEinrichtung, EncounterInpatientEinrichtungTimeRestriction:
 *       {@code Encounter.class} is a single {@code Coding} struct (not a CodeableConcept array),
 *       so UNNEST won't work.  Needs a non-array termCodeFilter variant (e.g.
 *       {@code "arrayPath": null, "singlePath": "class"}) that generates
 *       {@code WHERE t.class.system = '...' AND t.class.code = '...'}.
 *
 *   <li><b>Patient age</b> — example-all-crits-time (SNOMED 424144002):
 *       Requires {@code DATEDIFF('year', DATE(t.birthdate), CURRENT_DATE) <op> <val>} — a
 *       computed column not expressible with the current numeric value filter path.
 *
 *   <li><b>Consent (Einwilligung)</b> — consent.json, NoSearchPathSQ, returningOnePatient/Consent,
 *       and Einwilligung criteria in example-all-crits-time:
 *       The {@code fdpg.consent.combined} system is a virtual combined-consent system not directly
 *       stored in Pathling Delta Lake.  Standard Consent uses
 *       {@code provision.code[*].coding[*]}, a deeply nested array that requires a two-level UNNEST
 *       (provision array → code array → coding array) not supported by the single-level
 *       {@code termCodeFilter.arrayPath}.
 *
 *   <li><b>Body-site concept attribute filter (CodeableConcept)</b> — SpecimenSQAndBodySite:
 *       The {@code icd-o-3} attribute maps to {@code Specimen.collection.bodySite.coding}, which
 *       is a coding array.  The current {@code concept} attribute filter type generates
 *       {@code t.<path> IN (...)} (scalar string comparison).  A new {@code "coding"} attribute
 *       filter type is needed that generates a UNNEST + system/code WHERE conditions instead.
 *
 *   <li><b>Non-standard context</b> — NonPrimarySearchPathSQ uses context code
 *       "Procedure Category" which has no FHIR/Delta-Lake mapping.
 * </ol>
 */
class SqCompatibilityTest {

    static final String SQ = "src/test/resources/ccdl/sq2cql/";
    static final String SQ_ROP = SQ + "returningOnePatient/";

    static MappingContext ctx;
    static Translator translator;

    @BeforeAll
    static void setup() throws Exception {
        try (var stream = SqCompatibilityTest.class.getResourceAsStream("/test-mapping.json")) {
            ctx = MappingContext.fromJson(stream);
        }
        translator = Translator.of(ctx);
    }

    private String translate(String filePath) throws Exception {
        var json = Files.readString(Path.of(filePath));
        var query = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(json, StructuredQuery.class);
        return SqlWriter.write(filePath.replaceAll(".*/", "").replace(".json", ""), translator.toSql(query));
    }

    // ── Encounter (Fall) ─────────────────────────────────────────────────────
    // EncounterInpatientAbteilung / Einrichtung / EinrichtungTimeRestriction also need
    // the Kontaktebene attribute filter, which requires Gap 5 (coding array on type.coding).

    @Test
    void encounterInpatient_singleCodingPath_noUnnest() throws Exception {
        var sql = translate(SQ + "EncounterInpatient.json");

        assertThat(sql).contains("FROM fhir.default.encounter t");
        // No UNNEST — class is a single Coding struct
        assertThat(sql).doesNotContain("CROSS JOIN UNNEST");
        assertThat(sql).contains("t.class.system = 'http://terminology.hl7.org/CodeSystem/v3-ActCode'");
        assertThat(sql).contains("t.class.code IN ('IMP')");
    }

    @Test
    void encounterInpatient_timeRestriction_addsDateFilter() throws Exception {
        // EncounterInpatientEinrichtungTimeRestriction.json also has a Kontaktebene
        // attribute filter which requires Gap 5 (coding array). Test the base criterion only.
        var concept = de.medizininformatik.ccdl2deltalake.model.structured_query.ContextualConcept.of(
            de.medizininformatik.ccdl2deltalake.model.TermCode.of("fdpg.mii.cds", "Fall", "Fall"),
            java.util.List.of(
                de.medizininformatik.ccdl2deltalake.model.TermCode.of(
                    "http://terminology.hl7.org/CodeSystem/v3-ActCode", "IMP", "inpatient encounter")));
        var tr = de.medizininformatik.ccdl2deltalake.model.structured_query.TimeRestriction
            .create("2024-01-01", "2024-02-15");
        var criterion = de.medizininformatik.ccdl2deltalake.model.structured_query.ConceptCriterion.of(concept, tr);
        var sql = SqlWriter.write("encounter_tr", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("FROM fhir.default.encounter t");
        assertThat(sql).doesNotContain("CROSS JOIN UNNEST");
        assertThat(sql).contains("t.class.code IN ('IMP')");
        assertThat(sql).contains("DATE(t.periodstart) >= DATE('2024-01-01')");
        assertThat(sql).contains("DATE(t.periodstart) <= DATE('2024-02-15')");
    }

    // ── MedicationAdministration ──────────────────────────────────────────────

    @Test
    void medicationAdministrationSQ_generatesJoinSql() throws Exception {
        var sql = translate(SQ + "MedicationAdministrationSQ.json");

        assertThat(sql).contains("FROM fhir.default.medicationadministration t");
        assertThat(sql).contains("JOIN fhir.default.medication j ON t.medicationreference.reference = j.id_versioned");
        assertThat(sql).contains("CROSS JOIN UNNEST(j.code.coding) AS tc");
        assertThat(sql).contains("tc.system = 'http://fhir.de/CodeSystem/bfarm/atc'");
        assertThat(sql).contains("tc.code IN ('B01AB01')");
        assertThat(sql).doesNotContain("INTERSECT");
        assertThat(sql).doesNotContain("DATE(");
    }

    @Test
    void medicationAdministrationSQTimeRestriction_addsDateFilter() throws Exception {
        var sql = translate(SQ + "MedicationAdministrationSQTimeRestriction.json");

        assertThat(sql).contains("tc.code IN ('B01AB01')");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) >= DATE('2024-01-01')");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) <= DATE('2024-02-01')");
    }

    @Test
    void medicationAdministrationSQDoubleCriteria_generatesTwoIntersectGroupsWithTimeRestriction() throws Exception {
        var sql = translate(SQ + "MedicationAdministrationSQDoubleCriteria.json");

        assertThat(sql).contains("INTERSECT");
        // Both groups reference B01AB01; first group 2024, second group 2023
        assertThat(sql).contains("DATE('2024-01-01')");
        assertThat(sql).contains("DATE('2023-01-01')");
    }

    @Test
    void medicationAdministrationSQTwoCriteria_intersectsTwoDifferentDrugs() throws Exception {
        var sql = translate(SQ + "MedicationAdministrationSQTwoCriteria.json");

        assertThat(sql).contains("INTERSECT");
        assertThat(sql).contains("tc.code IN ('B01AB01')");
        assertThat(sql).contains("tc.code IN ('B01AC06')");
    }

    // ── MedicationRequest ─────────────────────────────────────────────────────

    @Test
    void medicationRequestSQ_generatesJoinSql() throws Exception {
        var sql = translate(SQ + "MedicationRequestSQ.json");

        assertThat(sql).contains("FROM fhir.default.medicationrequest t");
        assertThat(sql).contains("JOIN fhir.default.medication j ON t.medicationreference.reference = j.id_versioned");
        assertThat(sql).contains("tc.code IN ('B01AB01')");
        assertThat(sql).doesNotContain("DATE(");
    }

    @Test
    void medicationRequestSQTimeRestriction_addsDateFilter() throws Exception {
        var sql = translate(SQ + "MedicationRequestSQTimeRestriction.json");

        assertThat(sql).contains("FROM fhir.default.medicationrequest t");
        assertThat(sql).contains("DATE(t.authoredon) >= DATE('2024-01-01')");
        assertThat(sql).contains("DATE(t.authoredon) <= DATE('2024-02-01')");
    }

    // ── MedicationStatement ───────────────────────────────────────────────────

    @Test
    void medicationStatementSQ_generatesJoinSql() throws Exception {
        var sql = translate(SQ + "MedicationStatementSQ.json");

        assertThat(sql).contains("FROM fhir.default.medicationstatement t");
        assertThat(sql).contains("JOIN fhir.default.medication j ON t.medicationreference.reference = j.id_versioned");
        assertThat(sql).contains("tc.code IN ('B01AB01')");
        assertThat(sql).doesNotContain("DATE(");
    }

    @Test
    void medicationStatementSQTimeRestriction_addsDateFilter() throws Exception {
        var sql = translate(SQ + "MedicationStatementSQTimeRestriction.json");

        assertThat(sql).contains("FROM fhir.default.medicationstatement t");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) >= DATE('2024-01-01')");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) <= DATE('2024-02-01')");
    }

    // ── Procedure ─────────────────────────────────────────────────────────────

    @Test
    void primaryPathSQ_snomed_generatesCorrectSql() throws Exception {
        var sql = translate(SQ + "PrimaryPathSQ.json");

        assertThat(sql).contains("FROM fhir.default.procedure t");
        assertThat(sql).contains("CROSS JOIN UNNEST(t.code.coding) AS tc");
        assertThat(sql).contains("tc.system = 'http://snomed.info/sct'");
        assertThat(sql).contains("tc.code IN ('726427004')");
        assertThat(sql).doesNotContain("JOIN fhir.default.medication");
    }

    @Test
    void procedure_ops_generatesCorrectSql() throws Exception {
        var sql = translate(SQ_ROP + "Procedure.json");

        assertThat(sql).contains("FROM fhir.default.procedure t");
        assertThat(sql).contains("tc.system = 'http://fhir.de/CodeSystem/bfarm/ops'");
        assertThat(sql).contains("tc.code IN ('5-403.05')");
    }

    @Test
    void largeQueryWorstCase_twoInclusionGroupsWithExclusion() throws Exception {
        var sql = translate(SQ + "large-query-worst-case-with-time-constraints.json");

        assertThat(sql).contains("FROM fhir.default.procedure t");
        assertThat(sql).contains("INTERSECT");
        assertThat(sql).contains("EXCEPT");
        assertThat(sql).contains("tc.code IN ('8-810')");
        assertThat(sql).contains("tc.code IN ('5-39')");
        assertThat(sql).contains("tc.code IN ('5-45')");
        // 8-810 group has time restriction
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.performeddatetime))");
    }

    @Test
    void largeQueryMoreCritTimeRest_fiveInclusionGroupsWithExclusions() throws Exception {
        var sql = translate(SQ + "test-large-query-more-crit-time-rest-1.json");

        assertThat(sql).contains("FROM fhir.default.procedure t");
        assertThat(sql).contains("INTERSECT");
        assertThat(sql).contains("EXCEPT");
        assertThat(sql).contains("tc.code IN ('8-810')");
        assertThat(sql).contains("tc.code IN ('5-39')");
        assertThat(sql).contains("tc.code IN ('6-002')");
        assertThat(sql).contains("tc.code IN ('5-32')");
        assertThat(sql).contains("tc.code IN ('5-800')");
        assertThat(sql).contains("tc.code IN ('5-45')");
        assertThat(sql).contains("tc.code IN ('5-780')");
        assertThat(sql).contains("tc.code IN ('5-781')");
    }

    // ── Specimen ──────────────────────────────────────────────────────────────

    @Test
    void specimenSQ_referenceAttributeFilter_generatesExtensionJoin() throws Exception {
        var sql = translate(SQ + "SpecimenSQ.json");

        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("CROSS JOIN UNNEST(t._extension)");
        assertThat(sql).contains("INNER JOIN fhir.default.condition ref0");
        assertThat(sql).contains("tc.code IN ('119364003')");
        assertThat(sql).contains("ref_tc0.code IN ('E13.9')");
    }

    @Test
    void specimenSQTwoReferenceCriteria_bothCodesInOneInClause() throws Exception {
        var sql = translate(SQ + "SpecimenSQTwoReferenceCriteria.json");

        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("INNER JOIN fhir.default.condition ref0");
        // E13.9 and E13.1 from two inner criteria → merged into one IN clause (OR semantics)
        assertThat(sql).contains("ref_tc0.code IN (");
        assertThat(sql).contains("'E13.9'");
        assertThat(sql).contains("'E13.1'");
    }

    @Test
    void specimenROP_differentSnomedCode_generatesCorrectSql() throws Exception {
        var sql = translate(SQ_ROP + "Specimen.json");

        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("tc.code IN ('396997002')");
    }

    @Test
    void specimenSQExclusion_patientGenderInclusion_generatesExceptSql() throws Exception {
        var sql = translate(SQ + "SpecimenSQExclusion.json");

        // Inclusion: patient gender (no SPLIT_PART, direct t.id)
        assertThat(sql).contains("t.id AS patient_id");
        assertThat(sql).contains("FROM fhir.default.patient t");
        assertThat(sql).contains("t.gender IN ('female')");
        // Structure: inclusion EXCEPT exclusion
        assertThat(sql).contains("EXCEPT");
        // Exclusion: specimen with festgestellteDiagnose
        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("tc.code IN ('119364003')");
        assertThat(sql).contains("INNER JOIN fhir.default.condition ref0");
        assertThat(sql).contains("ref_tc0.code IN ('E13.9')");
    }

    @Test
    void specimenSQTwoInclusion_patientAndSpecimen_generatesIntersectSql() throws Exception {
        var sql = translate(SQ + "SpecimenSQTwoInclusion.json");

        // Inclusion group 1: patient gender
        assertThat(sql).contains("t.id AS patient_id");
        assertThat(sql).contains("FROM fhir.default.patient t");
        assertThat(sql).contains("t.gender IN ('female')");
        // Inclusion group 2: specimen with festgestellteDiagnose
        assertThat(sql).contains("FROM fhir.default.specimen t");
        assertThat(sql).contains("tc.code IN ('119364003')");
        assertThat(sql).contains("ref_tc0.code IN ('E13.9')");
        // Structure: INTERSECT between the two groups
        assertThat(sql).contains("INTERSECT");
        assertThat(sql).doesNotContain("EXCEPT");
    }

    // ── Patient age ───────────────────────────────────────────────────────────
    // example-all-crits-time.json has multiple unsupported gaps (Consent, Einwilligung),
    // so the age criterion is tested programmatically rather than from the full file.

    static final TermCode PATIENT_CTX = TermCode.of("fdpg.mii.cds", "Patient", "Patient");
    static final TermCode AGE_TC = TermCode.of("http://snomed.info/sct", "424144002",
                                                "Gegenwärtiges chronologisches Alter");

    @Test
    void patientAge_comparatorEq_generatesDateDiffSql() throws Exception {
        var concept = ContextualConcept.of(PATIENT_CTX, List.of(AGE_TC));
        var criterion = NumericCriterion.of(concept, Comparator.EQUAL, new BigDecimal("20"), "a", null);
        var sql = SqlWriter.write("patient_age_eq20", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("t.id AS patient_id");
        assertThat(sql).contains("FROM fhir.default.patient t");
        assertThat(sql).contains("DATE_DIFF('year', DATE(t.birthdate), CURRENT_DATE) = 20");
        // Unit 'a' has no column to compare against — must not appear as a WHERE condition
        assertThat(sql).doesNotContain("= 'a'");
        assertThat(sql).doesNotContain("SPLIT_PART");
        assertThat(sql).doesNotContain("UNNEST");
    }

    @Test
    void patientAge_rangeGt18Lt65_generatesDateDiffBetweenSql() throws Exception {
        var concept = ContextualConcept.of(PATIENT_CTX, List.of(AGE_TC));
        var criterion = RangeCriterion.of(concept, new BigDecimal("18"), new BigDecimal("65"), "a", null);
        var sql = SqlWriter.write("patient_age_range", criterion.toSql(ctx, "fhir.default"));

        assertThat(sql).contains("t.id AS patient_id");
        assertThat(sql).contains("FROM fhir.default.patient t");
        assertThat(sql).contains("DATE_DIFF('year', DATE(t.birthdate), CURRENT_DATE) BETWEEN 18 AND 65");
        assertThat(sql).doesNotContain("= 'a'");
        assertThat(sql).doesNotContain("SPLIT_PART");
    }

    // ── Diagnose ─────────────────────────────────────────────────────────────

    @Test
    void diagnoseROP_icd_generatesConditionSql() throws Exception {
        var sql = translate(SQ_ROP + "Diagnose.json");

        assertThat(sql).contains("FROM fhir.default.condition t");
        assertThat(sql).contains("tc.system = 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'");
        assertThat(sql).contains("tc.code IN ('I08.0')");
    }

    @Test
    void diagnoseROP_withTimeRestriction_addsDateFilter() throws Exception {
        var sql = translate(SQ_ROP + "Diagnose-TimeRestriction.json");

        assertThat(sql).contains("tc.code IN ('S14.11')");
        assertThat(sql).contains("DATE(t.recordeddate) >= DATE('2023-02-01')");
        assertThat(sql).contains("DATE(t.recordeddate) <= DATE('2023-02-28')");
    }

    @Test
    void todesursacheROP_treatedAsDiagnose() throws Exception {
        var sql = translate(SQ_ROP + "Todesursache.json");

        // Todesursache uses Diagnose context → maps to condition table
        assertThat(sql).contains("FROM fhir.default.condition t");
        assertThat(sql).contains("tc.code IN ('S14.11')");
    }

    // ── Observation ───────────────────────────────────────────────────────────

    @Test
    void observationLabROP_generatesObservationSql() throws Exception {
        var sql = translate(SQ_ROP + "ObservationLab.json");

        assertThat(sql).contains("FROM fhir.default.observation t");
        assertThat(sql).contains("tc.system = 'http://loinc.org'");
        assertThat(sql).contains("tc.code IN ('800-3')");
    }

    @Test
    void observationLabROP_withTimeRestriction_addsDatetimeFilter() throws Exception {
        var sql = translate(SQ_ROP + "ObservationLab-TimeRestriction.json");

        assertThat(sql).contains("tc.code IN ('800-3')");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) >= DATE('2004-07-24')");
        assertThat(sql).contains("DATE(FROM_ISO8601_TIMESTAMP(t.effectivedatetime)) <= DATE('2004-07-24')");
    }

    // ── returningOnePatient MedAdmin / MedRequest / MedStatement ──────────────

    @Test
    void medicationAdminROP_p01ca_generatesJoinSql() throws Exception {
        var sql = translate(SQ_ROP + "MedicationAdministration.json");

        assertThat(sql).contains("FROM fhir.default.medicationadministration t");
        assertThat(sql).contains("JOIN fhir.default.medication j");
        assertThat(sql).contains("tc.code IN ('P01CA')");
    }

    @Test
    void medicationRequestROP_p01ca_generatesJoinSql() throws Exception {
        var sql = translate(SQ_ROP + "MedicationRequest.json");

        assertThat(sql).contains("FROM fhir.default.medicationrequest t");
        assertThat(sql).contains("JOIN fhir.default.medication j");
        assertThat(sql).contains("tc.code IN ('P01CA')");
    }

    @Test
    void medicationStatementROP_p01ca_generatesJoinSql() throws Exception {
        var sql = translate(SQ_ROP + "MedicationStatement.json");

        assertThat(sql).contains("FROM fhir.default.medicationstatement t");
        assertThat(sql).contains("JOIN fhir.default.medication j");
        assertThat(sql).contains("tc.code IN ('P01CA')");
    }
}
