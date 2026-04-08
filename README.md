# Pentaho Migration Framework

A declarative Java execution engine for migrating 1 500+ Pentaho jobs (KJB) and transformations (KTR) to plain Java — without static code generation.

Pentaho XML is converted to an intermediate YAML job definition. At runtime the engine reads the YAML, resolves step types through a registry, instantiates and wires Java components dynamically, and executes the pipeline. The same 85 step implementations handle every job; there is nothing per-job to maintain.

---

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `framework/` | `migration-framework` | Core engine, all 85 step implementations, external merge sort |
| `converter/` | `migration-converter` | Converts Pentaho KJB/KTR XML files to YAML job definitions |
| `api/` | `migration-api` | Spring Boot REST API for submitting and monitoring executions |

**Dependency graph:**
```
api  →  framework
api  →  converter
converter  →  framework
```

---

## Requirements

- Java 21
- Maven 3.9+

---

## Build & Run

```bash
# Build all modules and run all tests
mvn clean package -Dmaven.artifact.threads=1

# Start the REST API (port 8080)
java -jar api/target/migration-api-1.0-SNAPSHOT.jar
```

Running tests only:
```bash
mvn test -Dmaven.artifact.threads=1
# 94 tests across 10 test classes — all pass with no external dependencies
```

---

## Architecture

### Declarative engine

A **TransformationDefinition** (or **JobDefinition**) is a plain data object — a list of steps (or job entries) and a list of hops (directed edges). The executor:

1. Topologically sorts the DAG (Kahn's algorithm).
2. Resolves each step type string to a `Step` implementation via `StepRegistry`.
3. Calls `step.configure(params)` with the step's parameter map.
4. Wires iterators: each step's output becomes the next step's input.
5. For fan-out branches, materialises the upstream iterator to a temp file via `BroadcastBuffer` and serves one independent reader per downstream step.

### Step execution patterns

| Pattern | Description | Examples |
|---------|-------------|---------|
| **Source** | Produces rows; no upstream | `CsvInput`, `TableInput`, `ExcelInput` |
| **Sink** | Consumes rows; no downstream | `TextFileOutput`, `TableOutput`, `ExcelOutput` |
| **Streaming** | Transforms one row at a time; zero buffering | `SelectValues`, `Calculator`, `InsertUpdate` |
| **Blocking** | Must see all rows before emitting | `SortRows`, `GroupBy`, `Unique` |
| **Fan-in** | Merges N upstream iterators | `Append`, `MergeJoin`, `SortedMerge` |
| **Routing** | Routes rows to named downstream branches | `FilterRows`, `SwitchCase` |
| **Sub-transformation** | Executes a nested transformation inline | `Mapping`, `SimpleMapping` |

### External merge sort

`SortRows` uses a disk-based external merge sort (`ExternalMergeSort`) backed by `MappedByteBuffer` temp files. It can sort datasets larger than available heap:

- **Phase 1 — split & sort**: accumulates rows in memory up to `chunkSizeMb`, sorts each chunk, spills to a temp file.
- **Phase 2 — merge**: multi-pass k-way merge until a single sorted iterator remains.
- Memory usage is bounded by `chunkSizeMb` regardless of input size.

---

## YAML Job Definition Format

### Transformation (KTR equivalent)

```yaml
name: customer_sort
steps:
  - id: read_customers
    type: CsvInput
    params:
      filePath: /data/customers.csv
      hasHeader: "true"

  - id: sort_by_name
    type: SortRows
    params:
      columns: last_name,first_name
      chunkSizeMb: "64"

  - id: write_output
    type: TextFileOutput
    params:
      filePath: /data/customers_sorted.csv
      writeHeader: "true"

hops:
  - from: read_customers
    to: sort_by_name
  - from: sort_by_name
    to: write_output
```

### Job (KJB equivalent)

```yaml
name: daily_etl
entries:
  - id: START
    type: Start

  - id: load_customers
    type: RunTransformation
    params:
      transformationPath: /jobs/customer_sort.yaml

  - id: load_orders
    type: RunTransformation
    params:
      transformationPath: /jobs/order_load.yaml

  - id: SUCCESS
    type: Success

hops:
  - from: START
    to: load_customers
    evaluation: unconditional
  - from: load_customers
    to: load_orders
    evaluation: success
  - from: load_orders
    to: SUCCESS
    evaluation: success
```

**Hop `evaluation` values:** `unconditional` · `success` · `failure`

---

## Step Reference

All `params` values are strings. Boolean flags use `"true"` / `"false"`.

### Source steps

#### `CsvInput`
Reads a delimited file.

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Path to the CSV file |
| `hasHeader` | `"true"` | Skip first row as header |
| `separator` | `","` | Column separator |
| `enclosure` | `"\""` | Quote character |

#### `TextFileInput`
Reads a delimited text file (like `CsvInput` but for fixed-format files).

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Path to the input file |
| `hasHeader` | `"true"` | Skip header row |
| `separator` | `","` | Delimiter |

#### `TableInput`
Reads rows from a relational database via JDBC.

| Param | Default | Description |
|-------|---------|-------------|
| `query` | — | Raw SQL SELECT (takes precedence over `tableName`) |
| `tableName` | — | Table to `SELECT *` from |
| `whereClause` | — | WHERE clause appended when using `tableName` |
| `jdbcUrl` | — | Full JDBC URL (takes precedence over host/port params) |
| `dbType` | — | Shorthand: `oracle`, `mysql`, `postgresql`, `sqlserver`, `h2`, `db2` |
| `jdbcDriver` | — | Explicit driver class (overrides `dbType`) |
| `jdbcHost` | `localhost` | Database host (used when `jdbcUrl` absent) |
| `jdbcPort` | *(per dbType)* | Database port |
| `jdbcDatabase` | — | Database/schema name |
| `jdbcSid` | `ORCL` | Oracle SID (Oracle only; use `jdbcServiceName` for service-name URLs) |
| `jdbcServiceName` | — | Oracle service name → `jdbc:oracle:thin:@//host:port/svc` |
| `jdbcUser` | `""` | Database username |
| `jdbcPassword` | `""` | Database password |

See [Oracle connection examples](#oracle-connection-examples) below.

#### `ExcelInput`
Reads rows from an `.xls` / `.xlsx` file via Apache POI.

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Path to the Excel file |
| `sheetName` | first sheet | Sheet to read |
| `hasHeader` | `"true"` | Skip first row as header |

#### `RowGenerator`
Emits a fixed number of identical rows (useful for testing).

| Param | Default | Description |
|-------|---------|-------------|
| `rowCount` | `"1"` | Number of rows to emit |
| `fields` | — | Comma-separated field values (e.g. `"hello,world"`) |

#### Other source steps
`PropertyInput` · `SystemInfo` · `GetFileNames` · `RowsFromResult` · `MappingInput`

---

### Sink steps

#### `TextFileOutput`
Writes rows to a delimited text file.

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Output file path |
| `writeHeader` | `"true"` | Write a header row |
| `separator` | `","` | Column delimiter |

#### `TableOutput`
Batch-inserts rows into a database table via JDBC (same JDBC params as `TableInput`).

| Param | Default | Description |
|-------|---------|-------------|
| `tableName` | *(required)* | Target table |
| `truncateFirst` | `"false"` | TRUNCATE table before inserting |
| `batchSize` | `"1000"` | JDBC batch size |
| *(JDBC params)* | — | Same as `TableInput` |

#### `ExcelOutput`
Creates a new `.xlsx` file.

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Output `.xlsx` path |
| `sheetName` | `"Sheet1"` | Sheet name |
| `writeHeader` | `"true"` | Write a header row |

#### `TypeExitExcelWriter`
Appends rows to an **existing** `.xlsx` file.

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Path to existing `.xlsx` |
| `sheetName` | first sheet | Sheet to append to (created if not found) |
| `writeHeader` | `"true"` | Prepend header before appended rows |

#### Other sink steps
`WriteToLog` · `RowsToResult` · `MappingOutput` · `DetectEmptyStream`

---

### Streaming transform steps

These steps process one row at a time and pass rows through unchanged or modified.

| Type | Purpose |
|------|---------|
| `SelectValues` | Retain, rename, or reorder columns |
| `Constant` | Add a fixed-value column to every row |
| `SetValueConstant` | Set a field to a constant value |
| `SetValueField` | Copy a field value to another field |
| `IfNull` | Replace null values with a default |
| `NullIf` | Set a field to null when it equals a value |
| `StringOperations` | Trim, pad, upper/lower-case |
| `ReplaceString` | Regex or literal string replace |
| `StringCut` | Substring extraction by position |
| `ConcatFields` | Concatenate fields into a new field |
| `Calculator` | Arithmetic expressions on numeric fields |
| `SetVariable` | Set a pipeline variable |
| `GetVariable` | Inject a variable value as a field |
| `ValueMapper` | Map field values via a lookup table |
| `NumberRange` | Bucket a numeric field into ranges |
| `CheckSum` | Compute MD5/SHA checksum of a row |
| `RegexEval` | Evaluate a regex and extract groups |
| `FieldSplitter` | Split one field into multiple fields |
| `CloneRow` | Emit N copies of each row |
| `Sequence` | Add an auto-increment sequence number |
| `Dummy` | No-op pass-through (useful for debugging) |
| `DetectLastRow` | Mark the last row in the stream |
| `FieldsChangeSequence` | Detect changes in field values |
| `Abort` | Throw an error and abort the pipeline |
| `ExecProcess` | Execute an OS command per row |
| `FileExists` | Add a boolean field: file exists? |
| `FileLocked` | Add a boolean field: file is locked? |
| `ProcessFiles` | Move, copy, or delete files |
| `ZipFile` | Create a zip archive |
| `GetFilesRowsCount` | Count rows in a file |
| `Validator` | Validate field values against rules |
| `Rest` | Make an HTTP REST call per row |
| `SplitFieldToRows3` | One row per split token |
| `InsertUpdate` | Upsert a row to a DB table (pass-through) |
| `Delete` | Delete a matching DB row (pass-through) |
| `ExecSQL` | Execute parameterized SQL per row (pass-through) |

**`InsertUpdate` params:**

| Param | Description |
|-------|-------------|
| `tableName` | Target table |
| `columns` | Comma-separated column names matching row fields |
| `keyFields` | Comma-separated key columns for lookup |
| `updateFields` | Columns to update (optional; defaults to all non-key columns) |
| *(JDBC params)* | Same as `TableInput` |

**`Delete` params:** `tableName`, `columns`, `keyFields`, JDBC params.

**`ExecSQL` params:**

| Param | Description |
|-------|-------------|
| `sql` | SQL with `?` placeholders |
| `paramFields` | Comma-separated 0-based field indices for `?` bindings |
| *(JDBC params)* | Same as `TableInput` |

---

### Blocking steps

| Type | Purpose |
|------|---------|
| `SortRows` | Disk-based external merge sort |
| `GroupBy` | Aggregate rows by key fields |
| `MemoryGroupBy` | In-memory group-by (faster for small groups) |
| `Unique` | Remove duplicate rows (sorted input required) |
| `UniqueRowsByHashSet` | Remove duplicates using a hash set |
| `BlockingStep` | Buffer entire stream before passing on |
| `Denormaliser` | Pivot rows into columns |
| `Normaliser` | Unpivot columns into rows |

**`SortRows` params:**

| Param | Default | Description |
|-------|---------|-------------|
| `columns` | *(required)* | Comma-separated column names or 0-based indices |
| `chunkSizeMb` | `"64"` | Max memory per sort chunk in MB |

---

### Fan-in steps

Merge multiple upstream iterators into one stream.

| Type | Purpose |
|------|---------|
| `Append` | Concatenate streams sequentially |
| `SortedMerge` | Merge pre-sorted streams |
| `MergeJoin` | Sorted merge join (like SQL JOIN) |
| `JoinRows` | Cartesian / nested-loop join |
| `MergeRows` | Compare two sorted streams (diff) |
| `MultiwayMergeJoin` | N-way sorted merge join |

---

### Routing steps (fan-out)

Route rows to named downstream branches.

**`FilterRows`**

| Param | Description |
|-------|-------------|
| `column` | Field name or 0-based index to test |
| `value` | Value to compare against (equality) |
| `trueStep` | Downstream step ID for matching rows |
| `falseStep` | Downstream step ID for non-matching rows |

**`SwitchCase`** — routes rows to different branches based on a field value.

---

### Sub-transformation steps

| Type | Purpose |
|------|---------|
| `Mapping` | Execute a named sub-transformation |
| `SimpleMapping` | Simplified sub-transformation with fixed input/output mapping |

---

### Job entry types

| Type | Purpose |
|------|---------|
| `Start` | Entry point (every job has exactly one) |
| `Success` | Marks successful completion |
| `Abort` | Marks failure / abort |
| `Dummy` | No-op |
| `RunTransformation` | Execute a transformation YAML |
| `WriteToLog` | Write a message to the log |
| `ExecSQL` | Execute SQL (params: `sql`) |
| `SetVariable` / `GetVariable` | Pipeline variable manipulation |
| `ExecProcess` | Run an OS command |
| `FileExists` | Branch on file existence |
| `Mail` | Send an email |

---

## Oracle Connection Examples

Oracle is supported through `JdbcUtil`'s `dbType` shorthand and URL builder. Add `com.oracle.database.jdbc:ojdbc11` to your runtime classpath first (available on Maven Central).

**SID format (legacy):**
```yaml
- id: read_orders
  type: TableInput
  params:
    dbType: oracle
    jdbcHost: prod-db.example.com
    jdbcPort: "1521"
    jdbcSid: PROD
    jdbcUser: appuser
    jdbcPassword: s3cr3t
    query: "SELECT * FROM ORDERS WHERE STATUS = 'ACTIVE'"
```
Builds: `jdbc:oracle:thin:@prod-db.example.com:1521:PROD`

**Service-name format (recommended for Oracle 12c+):**
```yaml
- id: read_orders
  type: TableInput
  params:
    dbType: oracle
    jdbcHost: cloud-db.example.com
    jdbcServiceName: myapp.prod.oracle.com
    jdbcUser: appuser
    jdbcPassword: s3cr3t
    query: "SELECT * FROM ORDERS"
```
Builds: `jdbc:oracle:thin:@//cloud-db.example.com:1521/myapp.prod.oracle.com`

**Explicit URL (full control):**
```yaml
    dbType: oracle          # resolves driver class; jdbcUrl overrides URL building
    jdbcUrl: jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=scan-host)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=myapp)))
    jdbcUser: appuser
    jdbcPassword: s3cr3t
```

**`dbType` driver map:**

| `dbType` | Driver class | Default port |
|----------|-------------|-------------|
| `oracle` | `oracle.jdbc.OracleDriver` | 1521 |
| `mysql` | `com.mysql.cj.jdbc.Driver` | 3306 |
| `postgresql` / `postgres` | `org.postgresql.Driver` | 5432 |
| `sqlserver` / `mssql` | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | 1433 |
| `h2` | `org.h2.Driver` | — |
| `db2` | `com.ibm.db2.jcc.DB2Driver` | 50000 |

---

## KJB/KTR Converter

The `converter` module converts Pentaho XML files to YAML without requiring a Pentaho installation.

### Programmatic use

```java
PentahoProjectConverter converter = new PentahoProjectConverter();

// Convert a single .ktr file
String yaml = converter.convertKtr(new FileInputStream("transform.ktr"));

// Convert a single .kjb file
String yaml = converter.convertKjb(new FileInputStream("job.kjb"));

// Convert a project zip (1 KJB + N KTRs) → zip of YAML files
converter.convert(Path.of("project.zip"), Path.of("converted.zip"));
```

### Via the REST API

```bash
curl -X POST http://localhost:8080/api/convert \
     -F "file=@project.zip" \
     --output converted.zip
```

The input zip contains `.kjb` and `.ktr` files in any directory structure. The output zip mirrors the structure with `.yaml` extensions.

### What gets converted

**Step type names** — 60+ Pentaho XML type names are normalised to registry keys (e.g. `"CSVInput"` → `"CsvInput"`, `"Dummy (do nothing)"` → `"Dummy"`).

**Parameters** — dedicated mappers for the most common step types:

| Pentaho step | Mapper output |
|---|---|
| `CSVInput` / `CsvInput` | `filePath`, `hasHeader`, `separator`, `enclosure` |
| `TextFileInput` | `filePath`, `hasHeader`, `separator` |
| `TextFileOutput` | `filePath`, `writeHeader`, `separator` |
| `SortRows` | `columns` (field names, comma-separated) |
| `FilterRows` | `column`, `value`, `trueStep`, `falseStep` |
| `TableInput` | `dbType`, `jdbcHost`, `jdbcPort`, `jdbcSid`, `jdbcUser`, `jdbcPassword`, `query` |
| `ExcelInput` | `filePath`, `sheetName`, `hasHeader` |
| `ExcelOutput` | `filePath`, `sheetName`, `writeHeader` |
| *(unknown type)* | All direct text child elements copied as-is (`DefaultStepXmlMapper`) |

**Job entries** — `SPECIAL` entries are resolved (`<start>Y` → type `Start`), `TRANS` entries emit `transformationPath` with `.ktr` → `.yaml` extension, hop evaluation flags map to `success` / `failure` / `unconditional`.

---

## REST API Reference

Base URL: `http://localhost:8080`

All requests and responses use JSON unless otherwise noted.

### Submit a transformation

```
POST /api/transformations/run
Content-Type: application/json
```

Request body: a `TransformationDefinition` object (see YAML format above, expressed as JSON).

Response `202 Accepted`:
```json
{
  "id": "3f2a1b4c-...",
  "type": "TRANSFORMATION",
  "status": "PENDING",
  "startedAt": "2026-04-08T09:00:00Z"
}
```

### Submit a job

```
POST /api/jobs/run
Content-Type: application/json
```

Request body: a `JobDefinition` object. Response: same shape as above with `"type": "JOB"`.

### Poll execution status

```
GET /api/executions/{id}
GET /api/transformations/{id}
GET /api/jobs/{id}
```

Response:
```json
{
  "id": "3f2a1b4c-...",
  "type": "TRANSFORMATION",
  "status": "COMPLETED",
  "startedAt": "2026-04-08T09:00:00Z",
  "completedAt": "2026-04-08T09:00:02Z",
  "durationMs": 2341
}
```

On failure:
```json
{
  "status": "FAILED",
  "errorMessage": "JDBC driver not found on classpath: oracle.jdbc.OracleDriver"
}
```

**Status values:** `PENDING` → `RUNNING` → `COMPLETED` | `FAILED`

### List all executions

```
GET /api/executions
```

Returns an array of all `ExecutionRecord` objects (in-memory; lost on restart).

### Convert Pentaho project

```
POST /api/convert
Content-Type: multipart/form-data
file: <zip file>
```

Response `200 OK` with `Content-Type: application/zip` — a zip of converted `.yaml` files.

### Health check

```
GET /actuator/health
```

---

## Configuration

`api/src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  application:
    name: pentaho-migration-api
  jackson:
    default-property-inclusion: non_null
    serialization:
      write-dates-as-timestamps: false

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

engine:
  temp-dir: ${java.io.tmpdir}   # temp dir for BroadcastBuffer and SortRows spill files
  execution-pool-size: 4         # max concurrent pipeline/job executions
```

All `engine.*` properties can be overridden via environment variables or `-D` flags:

```bash
java -jar migration-api.jar \
     --engine.temp-dir=/fast/ssd/tmp \
     --engine.execution-pool-size=8
```

---

## Extending with Custom Steps

1. Implement the appropriate base class:

```java
// Source — produces rows
public class MySourceStep extends AbstractSourceStep {
    private String filePath;

    @Override
    public void configure(Map<String, String> params) {
        this.filePath = params.get("filePath");
    }

    @Override
    protected Iterator<Row> readRows() throws Exception {
        // return rows
    }
}

// Streaming transform — one row at a time
public class MyTransformStep extends AbstractStreamingStep {
    @Override
    public void configure(Map<String, String> params) { ... }

    @Override
    protected Row processRow(Row row) {
        // mutate and return row, or return null to drop it
    }
}
```

2. Register with `StepRegistry`:

```java
StepRegistry registry = StepRegistry.withDefaults();
registry.register("MySource", MySourceStep.class);
TransformationExecutor executor = new TransformationExecutor(registry);
```

3. In the Spring API, add the registration in `EngineConfig.stepRegistry()`.

---

## Project Structure

```
pentaho-migration-ideas/
├── pom.xml                          ← parent POM (Java 21, Spring Boot 3.3.4 BOM)
│
├── framework/                       ← migration-framework
│   └── src/main/java/com/pentaho/migration/
│       ├── engine/
│       │   ├── TransformationExecutor.java   ← DAG execution engine
│       │   ├── JobExecutor.java              ← KJB job executor
│       │   └── BroadcastBuffer.java          ← fan-out materialisation
│       ├── model/                            ← TransformationDefinition, JobDefinition, …
│       ├── sort/                             ← ExternalMergeSort (MappedByteBuffer)
│       ├── step/                             ← Step interface, StepRegistry, base classes
│       └── step/impl/                        ← 85 step implementations
│
├── converter/                       ← migration-converter
│   └── src/main/java/com/pentaho/migration/converter/
│       ├── KtrParser.java                    ← KTR XML → TransformationDefinition
│       ├── KjbParser.java                    ← KJB XML → JobDefinition
│       ├── PentahoProjectConverter.java      ← zip-in → zip-out API
│       └── step/                             ← per-step XML param mappers
│
└── api/                             ← migration-api (Spring Boot fat JAR)
    └── src/main/java/com/pentaho/migration/api/
        ├── Application.java
        ├── config/EngineConfig.java          ← Spring bean wiring
        ├── controller/                       ← REST controllers
        ├── service/ExecutionService.java     ← async execution + status tracking
        └── model/ExecutionRecord.java        ← execution lifecycle model
```

---

## Test Summary

| Module | Test class | Tests | Coverage |
|--------|-----------|-------|---------|
| framework | `TransformationExecutorTest` | 8 | DAG execution patterns, fan-out, sub-transforms |
| framework | `ExternalMergeSortTest` | 12 | Sort correctness, large datasets, edge cases |
| framework | `JdbcStepsTest` | 11 | TableInput/Output, InsertUpdate, Delete, ExecSQL (H2) |
| framework | `JdbcUtilTest` | 22 | Driver resolution, Oracle/MySQL/PostgreSQL URL building |
| framework | `ExcelStepsTest` | 6 | ExcelInput/Output/TypeExitExcelWriter round-trips |
| converter | `KtrParserTest` | 9 | Type normalisation, param mapping, hop parsing |
| converter | `KjbParserTest` | 9 | Entry type resolution, hop evaluation flags |
| converter | `TableInputMapperTest` | 7 | Oracle SID/service-name, MySQL, PostgreSQL, unknown types |
| converter | `PentahoProjectConverterTest` | 4 | KTR/KJB round-trips, zip conversion |
| api | `ApplicationTest` | 6 | Spring context, all REST endpoints, actuator health |
| **Total** | | **94** | |
