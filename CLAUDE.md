# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Compile
/opt/apache-maven/bin/mvn compile

# Unit tests only
/opt/apache-maven/bin/mvn test

# Unit + integration tests (requires Trino running at localhost:8080)
/opt/apache-maven/bin/mvn verify

# Run a single test class
/opt/apache-maven/bin/mvn test -Dtest=TranslatorTest
/opt/apache-maven/bin/mvn verify -Dit.test=TrinoIT
```

## Integration test dependency

The integration tests (`TrinoIT`) are **self-contained**: they spin up the full trino-on-fhir stack (MinIO, Pathling, Hive Metastore, Trino) automatically via Testcontainers using `src/test/resources/docker/compose-it.yaml`. Docker must be running. No external stack is required.

The first run pulls Docker images and runs Pathling import (~5–15 min). Subsequent runs reuse cached images and are faster.

## Architecture

**Purpose:** Translates CCDL structured queries (same JSON format as sq2cql) into Trino SQL queries against Delta Lake tables, returning a list of matching patient IDs.

**Input → Output:**
```
StructuredQuery (CCDL JSON)
  → MappingContext (looks up table/column config per concept)
    → Translator (combines SQL with INTERSECT/UNION/EXCEPT)
      → SQL SELECT returning patient_id column
```

**Key design:**
- `Translator.toSql(StructuredQuery)` builds: inclusion (CNF = INTERSECT of UNIONs) EXCEPT exclusion (DNF = UNION of INTERSECTs)
- Each `Criterion` generates a standalone `SELECT DISTINCT ... AS patient_id FROM ...` subquery
- `MappingContext` maps `ContextualTermCode` (context + termCode) → `Mapping` (table, columns, filters)
- `MappingTreeBase` (same format as sq2cql's `mapping_tree.json`) expands concepts to child codes for IN-clause broadening

**Criterion types:**
- `ConceptCriterion` — existence of a coded resource (e.g., Condition with SNOMED code)
- `NumericCriterion` — value comparison (`> 10.0 /nL`)
- `RangeCriterion` — value range (`BETWEEN 10 AND 14 g/dL`)
- `ValueSetCriterion` — coded value selection

**Mapping JSON schema** (`mapping_deltalake_example.json` in `src/main/resources/`):
```json
{
  "context":        { "system": "fdpg.mii.cds", "code": "Diagnose", "display": "..." },
  "key":            { "system": "http://snomed.info/sct", "code": "37796009", "display": "..." },
  "tableName":      "condition",
  "patientRefPath": "subject.reference",
  "termCodeFilter": { "arrayPath": "code.coding" },
  "valueFilter":    { "type": "QUANTITY", "path": "valuequantity", "valueField": "value", "unitCodeField": "code" },
  "dateFilter":     { "path": "recordeddate", "type": "DATE" }
}
```
`valueFilter` and `dateFilter` are optional. `dateFilter.type` is `DATE` or `DATETIME` (the latter wraps with `FROM_ISO8601_TIMESTAMP`).

**Generated SQL pattern:**
```sql
SELECT DISTINCT SPLIT_PART(t.subject.reference, '/', 2) AS patient_id
FROM fhir.default.condition t, UNNEST(t.code.coding) AS tc
WHERE tc.system = 'http://snomed.info/sct'
  AND tc.code IN ('37796009')
  [AND t.valuequantity.value > 10.0]
  [AND t.valuequantity.code = '/nL']
  [AND DATE(t.recordeddate) >= DATE('2024-01-01')]
```

**Related repositories:**
- `sq2cql` at `/Users/pi19vypi/code/sq2cql` — the sibling project translating same CCDL input to CQL; structural reference
- `trino-on-fhir` at `/Users/pi19vypi/code/trino-on-fhir` — the Delta Lake + Trino stack with Synthea test data
- `example-mapping` at `/Users/pi19vypi/code/example-mapping` — large reference mapping files (CQL + FHIR + tree)
