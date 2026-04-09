# Pentaho Migration Framework

A declarative Java execution engine for migrating 1 500+ Pentaho jobs (KJB) and transformations (KTR) to plain Java — without static code generation.

Pentaho XML is converted to intermediate YAML. At runtime the engine reads the YAML, resolves step types through a registry, instantiates and wires Java components dynamically, and executes the pipeline. The same 85 step implementations handle every job; there is nothing per-job to maintain.

---

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `framework/` | `migration-framework` | Core engine, all 85 step implementations, external merge sort |
| `converter/` | `migration-converter` | Converts Pentaho KJB/KTR XML files to YAML job definitions |
| `api/` | `migration-api` | Spring Boot REST API for submitting and monitoring executions |
| `ui/` | `pentaho-migration-ui` | React 18 web UI for uploading, converting, and running projects |

**Dependency graph:**
```
api  →  framework
api  →  converter
converter  →  framework
ui  →  api  (HTTP/REST)
```

---

## Requirements

- Java 21
- Maven 3.9+
- Node.js 18+ (UI only)

---

## Build & Run

```bash
# Build all Java modules
mvn clean package -Dmaven.artifact.threads=1

# Start the REST API (port 8080)
java -jar api/target/migration-api-1.0-SNAPSHOT.jar

# Start the UI dev server (port 5173)
cd ui
npm install
npm run dev
```

Running tests only:
```bash
mvn test -Dmaven.artifact.threads=1
# ~100 tests across 10 test classes — all pass with no external dependencies
```

---

## Web UI

The `ui/` module is a React 18 single-page application (Vite 5, React Router 6, Axios).

### Starting the UI

```bash
cd ui
npm install      # first time only
npm run dev      # http://localhost:5173
npm run build    # production build → ui/dist/
npm run preview  # serve production build locally
```

The UI proxies all `/api` requests to `http://localhost:8080` (configured in `vite.config.js`).

### Pages

#### Projects list — `/`

Shows all projects in a table:

- **Name**, **Status**, **Files** (uploaded count), **YAMLs** (converted count), **Latest Execution**, **Created**
- **+ New Project** button opens an upload modal where you enter a project name, select one `.kjb` file and one or more `.ktr` files

#### Project detail — `/projects/:id`

Three sections, top-to-bottom:

1. **Uploaded Files** — table of every `.kjb` / `.ktr` file with type badge and size
2. **YAML Generation** — click **⚙ Generate YAML** to run the converter; each resulting YAML is listed by filename with a collapsible preview
3. **Job Execution** — click **▶ Run Job** to execute; execution history table shows ID, status, start time, duration, and error message; the page auto-polls every 2 s while any execution is `PENDING` or `RUNNING`

### Project lifecycle

```
UPLOADED → (convert) → CONVERTED → (execute) → RUNNING → COMPLETED
                 ↓                                           ↓
          CONVERSION_FAILED                              FAILED
```

---

## Orchestration — How the Engine Executes a Job

When the UI clicks **▶ Run Job**, here is the full flow:

```
UI (React)
  │  POST /api/projects/{id}/execute
  ▼
ProjectController
  │  projectService.execute(id)
  ▼
ProjectService.runJobAsync()
  │  1. Load all YamlDefinition rows from DB
  │  2. Write each YAML to a temp directory
  │  3. Find the JOB-type YAML (contains "entries:", not "steps:")
  │  4. Deserialize JobDefinition with Jackson/SnakeYAML
  │  5. Put tempDir path into context map as "basePath"
  │
  ├─► JobExecutor.execute(jobDef, context)
  │     │
  │     │  Kahn topological sort of entries + hops
  │     │  Walk from Start → RunTransformation → Success
  │     │
  │     └─► RunTransformationEntry.execute(context)
  │               │
  │               │  resolvedPath() = basePath / "read_filter_write.yaml"
  │               │  Load & deserialize TransformationDefinition
  │               │
  │               └─► TransformationExecutor.execute(transformationDef)
  │                         (see DAG execution below)
  │
  └─► ExecutionRecord status → COMPLETED or FAILED
         │
         ▼
      DB (JobExecution table) — UI polls GET /api/projects/{id}/executions
```

### DAG Execution Inside TransformationExecutor

Example pipeline: **CsvInput → FilterRows → TextFileOutput**

```
Input file                KTR / YAML                     Engine execution
──────────                ──────────                     ────────────────
id,name,age,city          steps:
1,Alice,25,Pune             - id: Read CSV               1. Topological sort:
2,Bob,17,Mumbai               type: TextFileInput           [Read CSV, Filter Rows, Write CSV]
3,Charlie,32,Bangalore        params:
4,David,15,Delhi                filePath: in.csv         2. Read CSV instantiated
5,Eva,45,Chennai                fieldNames: id,name,age,city   → Iterator<Row> (lazy CSV reader)
                                fieldTypes: Integer,String,Integer,String
                                                         3. Filter Rows instantiated
                          - id: Filter Rows                column = 2  (index of "age")
                            type: FilterRows               operator = GT
                            params:                        value = "18"
                              column: "2"                  trueStep = "Write CSV"
                              operator: GT                → route() called with upstream iterator
                              value: "18"                  returns { "Write CSV": matchingIter }
                              trueStep: Write CSV
                                                         4. Write CSV instantiated
                          - id: Write CSV                  drains matchingIter → out.csv
                            type: TextFileOutput
                            params:               Output file:
                              filePath: out.csv   id,name,age,city
                                                  1,Alice,25,Pune
                          hops:                   3,Charlie,32,Bangalore
                            - from: Read CSV      5,Eva,45,Chennai
                              to: Filter Rows
                            - from: Filter Rows
                              to: Write CSV
```

### Step execution patterns in detail

```
┌─────────────────────────────────────────────────────────────────────┐
│  TransformationExecutor — topological walk                          │
│                                                                     │
│  Source step           Streaming step          Sink step            │
│  ┌──────────┐          ┌──────────────┐        ┌──────────────┐     │
│  │ CsvInput │ ──iter──►│ FilterRows   │─iter──►│ TextFileOut  │     │
│  │          │          │ (RoutingStep)│        │              │     │
│  │ readRows()│          │ route()      │        │ writeRow()   │     │
│  └──────────┘          └──────┬───────┘        └──────────────┘     │
│                               │ falseStep iter → (discarded)        │
│                                                                     │
│  Fan-out (BroadcastBuffer)    Fan-in (multi-input)                  │
│  ┌──────────┐                 ┌──────────┐                          │
│  │ SortRows │──► BroadBuffer  │CsvInput A│──►┐                      │
│  └──────────┘    │            └──────────┘   │  ┌─────────┐         │
│                  ├──reader0──►  CsvOutput    ├──►│ Append  │──►...  │
│                  └──reader1──►  FilterRows   │  └─────────┘         │
│                                ┌──────────┐  │                      │
│                                │CsvInput B│──►┘                     │
│                                └──────────┘                         │
└─────────────────────────────────────────────────────────────────────┘
```

**Key rules:**
- Linear hop → iterator passed directly (zero copy, lazy evaluation)
- Fan-out (1 step → N steps) → `BroadcastBuffer` materialises to a temp file; N independent readers
- Fan-in (N steps → 1 step) → all upstream iterators collected into `List<Iterator<Row>>`, passed to step
- `RoutingStep` (FilterRows, SwitchCase) → `route()` returns `Map<stepId, Iterator>` instead of a single iterator; executor stores each entry separately

---

## KJB/KTR Converter

The `converter` module converts Pentaho XML files to YAML without requiring a Pentaho installation.

### How conversion works

```
project.zip
├── daily_job.kjb           KjbParser
├── read_filter.ktr    ──►  KtrParser  ──►  YAML strings  ──►  converted.zip
└── write_output.ktr        KtrParser                          ├── daily_job.yaml
                                                               ├── read_filter.yaml
                                                               └── write_output.yaml
```

**KtrParser two-pass processing:**
1. First pass: scan every `<step>/<fields>` section → build `stepName → [fieldName, ...]` schema map; scan all `<hop>` elements → build upstream and downstream maps
2. Second pass: for each step, delegate XML → params to a dedicated `StepXmlMapper`; for `FilterRows` and `SortRows` steps, resolve column names to 0-based indices by walking upstream until a step with a field schema is found; inject `trueStep`/`falseStep` from hop topology when `<send_true_to>` is absent

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

### What gets converted

**Step type names** — 60+ Pentaho XML type names normalised to registry keys (e.g. `"CSVInput"` → `"CsvInput"`, `"Dummy (do nothing)"` → `"Dummy"`).

**Parameters** — dedicated mappers for common step types:

| Pentaho step | Emitted params |
|---|---|
| `CSVInput` / `CsvInput` | `filePath`, `hasHeader`, `separator`, `enclosure` |
| `TextFileInput` | `filePath`, `hasHeader`, `separator`, `fieldNames`, `fieldTypes` |
| `TextFileOutput` | `filePath`, `writeHeader`, `separator` |
| `SortRows` | `columns` (field names, comma-separated) |
| `FilterRows` | `column` (0-based index), `operator` (GT/LT/GTE/LTE/EQ/NEQ/CONTAINS), `value`, `trueStep`, `falseStep` |
| `TableInput` | `dbType`, `jdbcHost`, `jdbcPort`, `jdbcSid`, `jdbcUser`, `jdbcPassword`, `query` |
| `ExcelInput` | `filePath`, `sheetName`, `hasHeader` |
| `ExcelOutput` | `filePath`, `sheetName`, `writeHeader` |
| *(unknown type)* | All direct text child elements copied as-is (`DefaultStepXmlMapper`) |

**Column name resolution:** When a `FilterRows` or `SortRows` step references a field by name (e.g. `age`), the converter walks backward through the hop graph to find the nearest upstream step with a `<fields>` schema declaration (e.g. `TextFileInput`), then replaces the name with the 0-based column index (`age` → `2`).

**trueStep/falseStep inference:** Pentaho omits `<send_true_to>` when a `FilterRows` step has only one outgoing hop. The converter injects `trueStep` automatically from the hop topology.

**Job entries** — `SPECIAL` entries are resolved (`<start>Y` → type `Start`), `TRANS` entries emit `transformationPath` with `.ktr` → `.yaml` extension, hop evaluation flags map to `success` / `failure` / `unconditional`.

**Windows paths in KJB files:** Real Pentaho KJBs store absolute Windows paths like `C:\jobs\my_transform.ktr`. The engine's `RunTransformationEntry` automatically strips the directory prefix and resolves against `basePath` (the temp directory where YAML files are written at execution time), so no manual path editing is needed.

---

## REST API Reference

Base URL: `http://localhost:8080`

### Projects API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/projects` | Upload KJB + KTR files, create project |
| `GET` | `/api/projects` | List all projects |
| `GET` | `/api/projects/{id}` | Get project detail (files, YAMLs, executions) |
| `POST` | `/api/projects/{id}/convert` | Generate YAML from uploaded files |
| `POST` | `/api/projects/{id}/execute` | Run the job (async, 202 Accepted) |
| `GET` | `/api/projects/{id}/executions` | List all executions for a project |
| `GET` | `/api/projects/{id}/executions/{eid}` | Get a single execution |

**Upload a project:**
```bash
curl -X POST http://localhost:8080/api/projects \
     -F "name=My ETL Job" \
     -F "kjb=@daily_job.kjb" \
     -F "ktrs=@read_csv.ktr" \
     -F "ktrs=@write_csv.ktr"
```

**Convert and execute:**
```bash
PROJECT_ID="<uuid from upload response>"
curl -X POST http://localhost:8080/api/projects/$PROJECT_ID/convert
curl -X POST http://localhost:8080/api/projects/$PROJECT_ID/execute
curl http://localhost:8080/api/projects/$PROJECT_ID/executions
```

### Low-level execution API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/transformations/run` | Run a TransformationDefinition (JSON body) |
| `POST` | `/api/jobs/run` | Run a JobDefinition (JSON body) |
| `GET` | `/api/executions` | List all in-memory executions |
| `GET` | `/api/executions/{id}` | Poll a single execution |
| `POST` | `/api/convert` | Convert project zip → yaml zip (multipart) |
| `GET` | `/actuator/health` | Health check |

**Execution status values:** `PENDING` → `RUNNING` → `COMPLETED` \| `FAILED`

---

## YAML Job Definition Format

### Transformation (KTR equivalent)

```yaml
name: customer_filter
steps:
  - id: Read CSV
    type: TextFileInput
    params:
      filePath: /data/customers.csv
      hasHeader: "true"
      separator: ","
      fieldNames: id,name,age,city
      fieldTypes: Integer,String,Integer,String

  - id: Filter Rows
    type: FilterRows
    params:
      column: "2"          # 0-based index of "age"
      operator: GT
      value: "18"
      trueStep: Write CSV

  - id: Write CSV
    type: TextFileOutput
    params:
      filePath: /data/adults.csv
      writeHeader: "true"

hops:
  - from: Read CSV
    to: Filter Rows
  - from: Filter Rows
    to: Write CSV
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
      transformationPath: customer_filter.yaml

  - id: SUCCESS
    type: Success

hops:
  - from: START
    to: load_customers
    evaluation: unconditional
  - from: load_customers
    to: SUCCESS
    evaluation: success
```

**Hop `evaluation` values:** `unconditional` · `success` · `failure`

---

## Step Reference

### Source steps

#### `CsvInput` / `TextFileInput`

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Path to the file |
| `hasHeader` | `"true"` | Skip first row as header |
| `separator` | `","` | Column separator |
| `enclosure` | `"\""` | Quote character |
| `fieldNames` | — | Comma-separated field names (emitted by converter) |
| `fieldTypes` | — | Comma-separated field types (emitted by converter) |

#### `TableInput`

| Param | Default | Description |
|-------|---------|-------------|
| `query` | — | Raw SQL SELECT |
| `tableName` | — | Table to `SELECT *` from |
| `dbType` | — | `oracle`, `mysql`, `postgresql`, `sqlserver`, `h2`, `db2` |
| `jdbcUrl` | — | Explicit JDBC URL (overrides URL building) |
| `jdbcHost` | `localhost` | Database host |
| `jdbcPort` | *(per dbType)* | Database port |
| `jdbcUser` | `""` | Username |
| `jdbcPassword` | `""` | Password |

#### `ExcelInput`

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Path to `.xls` / `.xlsx` |
| `sheetName` | first sheet | Sheet to read |
| `hasHeader` | `"true"` | Skip first row |

### Sink steps

#### `TextFileOutput`

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Output file path |
| `writeHeader` | `"true"` | Write a header row |
| `separator` | `","` | Delimiter |

#### `TableOutput`

| Param | Default | Description |
|-------|---------|-------------|
| `tableName` | *(required)* | Target table |
| `truncateFirst` | `"false"` | DELETE all rows before inserting |
| `batchSize` | `"1000"` | JDBC batch size |
| *(JDBC params)* | — | Same as `TableInput` |

#### `ExcelOutput` / `TypeExitExcelWriter`

| Param | Default | Description |
|-------|---------|-------------|
| `filePath` | *(required)* | Output `.xlsx` path |
| `sheetName` | `"Sheet1"` | Sheet name |
| `writeHeader` | `"true"` | Write a header row |

`TypeExitExcelWriter` opens an **existing** file and appends rows; `ExcelOutput` creates a new file.

### Routing steps

#### `FilterRows`

| Param | Default | Description |
|-------|---------|-------------|
| `column` | `"0"` | 0-based field index to test |
| `operator` | `EQ` | `EQ`, `NEQ`, `GT`, `LT`, `GTE`, `LTE`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH` |
| `value` | — | Value to compare against |
| `trueStep` | — | Downstream step ID for matching rows |
| `falseStep` | — | Downstream step ID for non-matching rows |

Numeric fields use numeric comparison (via `Double.parseDouble`); string fields use lexicographic comparison.

#### `SwitchCase`
Routes rows to different branches based on a field value.

### Blocking steps

| Type | Description |
|------|-------------|
| `SortRows` | External merge sort (disk-backed, handles datasets larger than heap) |
| `GroupBy` | Aggregate rows by key fields (requires sorted input) |
| `MemoryGroupBy` | In-memory group-by |
| `Unique` | Remove consecutive duplicates (requires sorted input) |
| `UniqueRowsByHashSet` | Remove duplicates via hash set |
| `Denormaliser` | Pivot rows → columns |
| `Normaliser` | Unpivot columns → rows |

**`SortRows` params:**

| Param | Default | Description |
|-------|---------|-------------|
| `columns` | *(required)* | Comma-separated column names or 0-based indices |
| `chunkSizeMb` | `"64"` | Max in-memory sort chunk |

### Fan-in steps

| Type | Description |
|------|-------------|
| `Append` | Concatenate N streams sequentially |
| `SortedMerge` | Merge N pre-sorted streams |
| `MergeJoin` | Sorted merge join |
| `JoinRows` | Cartesian join |
| `MergeRows` | Compare two sorted streams (diff) |

### Streaming transform steps (selection)

`SelectValues` · `Constant` · `Calculator` · `StringOperations` · `ReplaceString` · `ConcatFields` · `IfNull` · `NullIf` · `ValueMapper` · `RegexEval` · `SetVariable` · `GetVariable` · `Sequence` · `Dummy` · `InsertUpdate` · `Delete` · `ExecSQL` · `WriteToLog` · `Abort` · `ExecProcess` · `Rest` — and 20+ more.

### Job entry types

| Type | Description |
|------|-------------|
| `Start` | Entry point |
| `Success` | Marks successful completion |
| `Abort` | Marks failure |
| `RunTransformation` | Execute a transformation YAML |
| `Dummy` | No-op |
| `WriteToLog` | Log a message |
| `SetVariable` / `GetVariable` | Pipeline variable manipulation |
| `ExecProcess` | Run an OS command |
| `FileExists` | Branch on file existence |
| `Mail` | Send an email |

---

## Configuration

`api/src/main/resources/application.yml`:

```yaml
server:
  port: 8080

engine:
  temp-dir: ${java.io.tmpdir}   # temp dir for BroadcastBuffer and SortRows spill files
  execution-pool-size: 4         # max concurrent executions
```

Override via environment or `-D` flags:
```bash
java -jar migration-api.jar --engine.temp-dir=/fast/ssd/tmp --engine.execution-pool-size=8
```

---

## Project Structure

```
pentaho-migration-ideas/
├── pom.xml                              ← parent POM (Java 21, Spring Boot 3.3.4 BOM)
│
├── framework/                           ← migration-framework
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
├── converter/                           ← migration-converter
│   └── src/main/java/com/pentaho/migration/converter/
│       ├── KtrParser.java                    ← KTR XML → TransformationDefinition
│       ├── KjbParser.java                    ← KJB XML → JobDefinition
│       ├── PentahoProjectConverter.java      ← zip-in → zip-out API
│       └── step/                             ← per-step XML param mappers
│
├── api/                                 ← migration-api (Spring Boot fat JAR)
│   └── src/main/java/com/pentaho/migration/api/
│       ├── controller/ProjectController.java ← /api/projects CRUD + execute
│       ├── service/ProjectService.java       ← project lifecycle + async execution
│       └── service/ExecutionService.java     ← low-level execution + status tracking
│
└── ui/                                  ← React 18 SPA (Vite 5)
    ├── package.json
    ├── vite.config.js                   ← proxy /api → localhost:8080
    └── src/
        ├── App.jsx                      ← Router setup
        ├── api.js                       ← Axios wrappers for all API calls
        ├── pages/
        │   ├── ProjectsPage.jsx         ← / (project list + upload modal)
        │   └── ProjectDetail.jsx        ← /projects/:id (files, YAML, executions)
        └── components/
            ├── StatusBadge.jsx          ← coloured status pill
            └── UploadModal.jsx          ← project upload form
```

---

## Test Summary

| Module | Test class | Tests | What is covered |
|--------|-----------|-------|----------------|
| framework | `TransformationExecutorTest` | 8 | DAG execution, fan-out, fan-in, routing, sub-transforms |
| framework | `ExternalMergeSortTest` | 12 | Sort correctness, large datasets, edge cases |
| framework | `JdbcStepsTest` | 11 | TableInput/Output, InsertUpdate, Delete, ExecSQL (H2) |
| framework | `JdbcUtilTest` | 22 | Driver resolution, Oracle/MySQL/PostgreSQL URL building |
| framework | `ExcelStepsTest` | 6 | ExcelInput/Output/TypeExitExcelWriter round-trips |
| converter | `KtrParserTest` | 11 | Type normalisation, param mapping, column resolution, FilterRows routing |
| converter | `KjbParserTest` | 9 | Entry type resolution, hop evaluation flags |
| converter | `TableInputMapperTest` | 7 | Oracle SID/service-name, MySQL, PostgreSQL |
| converter | `PentahoProjectConverterTest` | 4 | KTR/KJB round-trips, zip conversion |
| api | `ApplicationTest` | 6 | Spring context, REST endpoints, actuator health |
| **Total** | | **~96** | |
