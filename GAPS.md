# Implementation Gaps

Features not yet supported by the translator, in recommended implementation order.
Each gap lists the affected SQ files, the problem, and the required changes.

---

## ~~Gap 1 — Encounter / `Fall` context~~ ✓ DONE

## ~~Gap 2 — Patient gender~~ ✓ DONE

## Gap 1 — Encounter / `Fall` context (`Encounter.class` single Coding)

**Affected SQs:**
- `EncounterInpatient.json`
- `EncounterInpatientAbteilung.json`
- `EncounterInpatientEinrichtung.json`
- `EncounterInpatientEinrichtungTimeRestriction.json`

**Problem:**
`Encounter.class` is a single `Coding` struct in FHIR R4 (not a `CodeableConcept` array).
The current translator always generates `CROSS JOIN UNNEST(t.<arrayPath>) AS tc`, which requires an array.

**Required SQL shape:**
```sql
SELECT DISTINCT SPLIT_PART(t.subject.reference, '/', 2) AS patient_id
FROM fhir.default.encounter t
WHERE t.class.system = 'http://terminology.hl7.org/CodeSystem/v3-ActCode'
  AND t.class.code IN ('IMP')
```

**Required changes:**
- Add `"singlePath"` field to `TermCodeFilter` (alongside existing `arrayPath`)
- In `AbstractCriterion.buildTermCodeSql`, branch on `tcf.getSinglePath()`:
  - If present: no UNNEST, generate `WHERE t.<singlePath>.system = '...' AND t.<singlePath>.code IN (...)`
  - If absent: existing UNNEST path

**Example mapping entry:**
```json
{
  "context": { "system": "fdpg.mii.cds", "code": "Fall", "display": "Fall", "version": "1.0.0" },
  "key": { "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "IMP", "display": "inpatient encounter" },
  "tableName": "encounter",
  "patientRefPath": "subject.reference",
  "termCodeFilter": { "singlePath": "class" },
  "dateFilter": { "path": "periodstart", "type": "DATE" }
}
```

---

## ~~Gap 2 — Patient gender (`Patient` table + scalar string column)~~ ✓ DONE

**Affected SQs:**
- `SpecimenSQExclusion.json`
- `SpecimenSQTwoInclusion.json`
- `example-all-crits-time.json` (gender criterion)

**Problem — two separate sub-issues:**

### 2a. Patient ID extraction
`Patient.id` in Pathling Delta Lake is a plain ID string (e.g. `mii-exa-test-data-patient-1`),
with no `/` prefix. The current template `SPLIT_PART(t.<patientRefPath>, '/', 2)` returns `""`
when there is no slash.

**Required change:**
Add `"patientIdDirect": true` (boolean, default false) to `Mapping`.
When true, generate `t.<patientRefPath>` directly instead of wrapping in `SPLIT_PART(... '/' 2)`.

**Example mapping entry:**
```json
{
  "patientRefPath": "id",
  "patientIdDirect": true
}
```

### 2b. Scalar string column value filter
`Patient.gender` is stored as a plain string (`"female"`, `"male"`, ...), not a coding array.
The `ValueSetCriterion` currently expects a `valueFilter.type=CODING` in the mapping, which
generates a UNNEST.  A scalar column needs:
```sql
WHERE t.gender IN ('female')
```

**Required change:**
Add `"type": "CODING_SCALAR"` (or similar) to `ValueFilter` in the mapping, with just a `path`
field. `ValueSetCriterion.toSql` branches on this type to emit `t.<path> IN (...)` directly.

**Example mapping entry:**
```json
{
  "context": { "system": "fdpg.mii.cds", "code": "Patient", "display": "Patient", "version": "1.0.0" },
  "key": { "system": "http://snomed.info/sct", "code": "263495000", "display": "Geschlecht" },
  "tableName": "patient",
  "patientRefPath": "id",
  "patientIdDirect": true,
  "termCodeFilter": { "arrayPath": "identifier.type.coding" },
  "valueFilter": { "type": "CODING_SCALAR", "path": "gender" }
}
```

---

## Gap 3 — Patient age (computed expression)

**Affected SQs:**
- `example-all-crits-time.json` (SNOMED `424144002` "Gegenwärtiges chronologisches Alter")

**Problem:**
Age is not a stored column — it is computed from `Patient.birthdate`.
The current `NumericCriterion` generates `t.<path>.<valueField> <op> <value>`,
which assumes a stored numeric column.  Age requires:
```sql
DATE_DIFF('year', DATE(t.birthdate), CURRENT_DATE) = 20
```

**Required changes:**
- Add `"type": "AGE"` to `ValueFilter` in mapping
- `NumericCriterion.toSql` (and `RangeCriterion`) branch on `AGE` type to emit the `DATE_DIFF` expression instead of a field path

**Example mapping entry:**
```json
{
  "context": { "system": "fdpg.mii.cds", "code": "Patient", "display": "Patient", "version": "1.0.0" },
  "key": { "system": "http://snomed.info/sct", "code": "424144002", "display": "Gegenwärtiges chronologisches Alter" },
  "tableName": "patient",
  "patientRefPath": "id",
  "patientIdDirect": true,
  "termCodeFilter": { "arrayPath": "identifier.type.coding" },
  "valueFilter": { "type": "AGE", "path": "birthdate" }
}
```

**Generated SQL (for comparator `eq`, value `20`, unit `a`):**
```sql
SELECT DISTINCT t.id AS patient_id
FROM fhir.default.patient t
WHERE DATE_DIFF('year', DATE(t.birthdate), CURRENT_DATE) = 20
```

---

## Gap 4 — Consent / `Einwilligung` context

**Affected SQs:**
- `consent.json`
- `NoSearchPathSQ.json`
- `returningOnePatient/Consent.json`
- `example-all-crits-time.json` (Einwilligung criterion)

**Problem — two separate sub-issues:**

### 4a. Deep nested array path
`Consent.provision.code[*].coding[*]` is a two-level nested array.
The current `termCodeFilter.arrayPath` supports one UNNEST level.
The raw Pathling Delta Lake path would be something like `provision.code` (itself an array of
`CodeableConcept`), each containing a `coding` array — requiring double UNNEST:
```sql
CROSS JOIN UNNEST(t.provision) AS prov
CROSS JOIN UNNEST(prov.code.coding) AS tc
```

**Required change:**
Either support a second `nestedArrayPath` in `TermCodeFilter`, or add a `provision` join step.

### 4b. Virtual combined consent system (`fdpg.consent.combined`)
The `returningOnePatient/Consent.json` uses `system: "fdpg.consent.combined"` — a pre-aggregated
virtual code system computed by the FDPG consent module, not a column in Pathling Delta Lake.
This would need a completely separate query pattern against a pre-computed consent summary table.

---

## Gap 5 — Body-site concept attribute filter (`CodeableConcept` array)

**Affected SQs:**
- `SpecimenSQAndBodySite.json` (`icd-o-3` attribute, `selectedConcepts: [C44.6]`)

**Problem:**
`Specimen.collection.bodySite` is a `CodeableConcept` containing a `.coding` array.
The current `concept` attribute filter type generates `t.<path> IN (...)` (scalar string
comparison), which only works for simple string columns like `status`.

**Required change:**
Add a `"coding"` attribute filter type in `SimpleAttributeFilterMapping` (or a separate
`CodingAttributeFilterMapping`) that generates a UNNEST + system/code WHERE conditions:
```sql
CROSS JOIN UNNEST(t.collection.bodysite.coding) AS bodysite_tc
WHERE ... AND bodysite_tc.system = 'urn:oid:...' AND bodysite_tc.code IN ('C44.6')
```

**Required mapping entry addition to Specimen:**
```json
{
  "attributeCode": "icd-o-3",
  "type": "coding",
  "path": "collection.bodysite",
  "arrayPath": "coding"
}
```

---

## Gap 6 — Non-standard context (`Procedure Category`)

**Affected SQs:**
- `NonPrimarySearchPathSQ.json`

**Problem:**
Context code `"Procedure Category"` (`system: "fdpg.mii.cds"`) is not a standard MII CDS
context and has no FHIR resource or Pathling Delta Lake table backing it.

**Resolution:** No implementation planned — document as unsupported in the translator.
