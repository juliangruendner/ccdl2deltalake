# ccdl2deltalake

Translates [CCDL](https://github.com/medizininformatik-initiative/clinical-data-definition-language) structured queries into Trino SQL queries against FHIR data stored in Delta Lake, returning a patient count.

It uses the same JSON input format as [sq2cql](https://github.com/medizininformatik-initiative/sq2cql) and is designed to run alongside the [trino-on-fhir](https://github.com/medizininformatik-initiative/trino-on-fhir) stack.

## How it works

```
StructuredQuery (CCDL JSON)
  → MappingContext  (resolves each concept to a table, column paths, and filters)
    → Translator    (combines subqueries with INTERSECT / UNION / EXCEPT)
      → SQL         (SELECT COUNT(*) AS patient_count FROM (...))
```

Each criterion becomes a standalone `SELECT DISTINCT ... AS patient_id` subquery. Inclusion criteria are combined in CNF (INTERSECT of UNIONs). Exclusion criteria are combined in DNF (UNION of INTERSECTs) and subtracted with EXCEPT.

### Criterion types

| Type | Description | Example |
|---|---|---|
| `ConceptCriterion` | Existence of a coded resource | Condition with SNOMED 37796009 (Migraine) |
| `NumericCriterion` | Quantity comparison | Observation value > 10 /nL |
| `RangeCriterion` | Quantity range | Observation value BETWEEN 10 AND 14 g/dL |
| `ValueSetCriterion` | Coded value match | Observation with specific coded result |

Criteria also support time restrictions, attribute filters (coded, quantity, reference), and ontology tree expansion for hierarchical code systems.

### Generated SQL example

```sql
SELECT COUNT(*) AS patient_count FROM (
SELECT DISTINCT SPLIT_PART(t.subject.reference, '/', 2) AS patient_id
FROM fhir.default.condition t
CROSS JOIN UNNEST(t.code.coding) AS tc
WHERE tc.system = 'http://snomed.info/sct'
  AND tc.code IN ('37796009')
  AND DATE(t.recordeddate) >= DATE('2024-01-01')
)
```

## Usage

### 1. Define a mapping

Each mapping entry tells the translator which table and column paths correspond to a concept. See `src/main/resources/mapping_deltalake_example.json` for a full example.

```json
{
  "context":        { "system": "fdpg.mii.cds", "code": "Diagnose", "display": "Diagnose" },
  "key":            { "system": "http://snomed.info/sct", "code": "37796009", "display": "Migraine" },
  "tableName":      "condition",
  "patientRefPath": "subject.reference",
  "termCodeFilter": { "path": "code.coding" },
  "dateFilter":     { "path": "recordeddate", "type": "DATE" }
}
```

`valueFilter` and `dateFilter` are optional. `dateFilter.type` is `DATE` or `DATETIME`.

### 2. Define table descriptions

Table descriptions tell the translator which field paths are arrays (requiring `CROSS JOIN UNNEST`) vs. scalars. This must reflect the actual Pathling Delta Lake schema.

```json
{
  "condition":   { "arrays": ["code.coding"] },
  "observation": { "arrays": ["code.coding"] },
  "specimen":    { "arrays": ["type.coding", "collection.bodysite.coding"] }
}
```

### 3. Translate a query in Java

```java
// Load mapping + table descriptions (+ optional ontology tree for code expansion)
MappingContext ctx;
try (var ms = new FileInputStream("mapping.json");
     var ts = new FileInputStream("table-descriptions.json")) {
    ctx = MappingContext.fromJson(ms, ts);
}

Translator translator = Translator.of(ctx);

// Parse a CCDL structured query from JSON
ObjectMapper mapper = new ObjectMapper();
StructuredQuery query = mapper.readValue(queryJson, StructuredQuery.class);

// Translate to SQL
String sql = translator.toSql(query);
// → "SELECT COUNT(*) AS patient_count FROM (...)"
```

To use a custom Trino catalog/schema prefix (default is `fhir.default`):

```java
Translator translator = Translator.of(ctx, "my_catalog.my_schema");
```

### 4. Execute against Trino

```java
Properties props = new Properties();
props.setProperty("user", "trino");
Connection conn = DriverManager.getConnection("jdbc:trino://localhost:8080/fhir/default", props);

try (var stmt = conn.createStatement();
     var rs = stmt.executeQuery(sql)) {
    rs.next();
    long count = rs.getLong("patient_count");
}
```

### CCDL query format

The input JSON follows the same format as sq2cql:

```json
{
  "inclusionCriteria": [[
    {
      "context":   { "system": "fdpg.mii.cds", "code": "Laboruntersuchung", "display": "Laboruntersuchung" },
      "termCodes": [{ "system": "http://loinc.org", "code": "26464-8", "display": "Leukocytes" }],
      "valueFilter": {
        "type":       "quantity-comparator",
        "comparator": "gt",
        "value":      10.0,
        "unit":       { "code": "/nL" }
      }
    }
  ]],
  "exclusionCriteria": []
}
```

Inner arrays are OR-ed (UNION); outer arrays are AND-ed (INTERSECT). `exclusionCriteria` follows the same structure and is subtracted with EXCEPT.

## Building

Requires Java 17+ and Maven.

```bash
# Compile
mvn compile

# Unit tests
mvn test

# Unit + integration tests (starts the full stack via Docker — see below)
mvn verify
```

## Integration tests

`TrinoIT` is fully self-contained. Running `mvn verify` automatically spins up the complete trino-on-fhir stack (MinIO → Pathling → Hive Metastore → warehousekeeper → Trino) via Testcontainers using `src/test/resources/docker/compose-it.yaml`. Synthea-generated test data is bundled under `src/test/resources/docker/testdata/`.

**Requirements:**
- Docker must be running
- On macOS with Docker Desktop, the `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` env var is set automatically by the Maven failsafe plugin configuration

The first run pulls Docker images and runs the Pathling FHIR import (~5–15 min). Subsequent runs reuse cached images and are significantly faster.

## Exceptions

| Exception | When thrown |
|---|---|
| `MappingNotFoundException` | A term code in the query has no entry in the mapping |
| `TranslationException` | Any other translation error (empty criteria, unsupported filter type, etc.) |

Both extend `RuntimeException`.
