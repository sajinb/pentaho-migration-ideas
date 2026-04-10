# Copilot Instructions — Pentaho Migration Engine

## What this project does

Migrates Pentaho ETL jobs (`.kjb` orchestration files + `.ktr` transformation files) to a
declarative Java engine. The migration has two stages:

1. **Convert** — KJB/KTR XML is parsed and emitted as YAML job definitions
   (`converter` module).
2. **Execute** — The YAML is read at runtime, step/entry classes are looked up in a
   registry, wired by the DAG engine, and executed (`framework` module).
3. **Serve** — A Spring Boot REST API exposes upload, conversion, and execution
   (`api` module).

---

## Module map

```
pentaho-migration-ideas/
├── framework/          Core engine: model POJOs, step/entry impls, DAG executors
│   └── src/main/java/com/pentaho/migration/
│       ├── model/          TransformationDefinition, JobDefinition, Step/EntryDefinition, Hop*
│       ├── sort/           ExternalMergeSort (MappedByteBuffer, disk-spill sort)
│       ├── step/           Step interface, base classes, StepRegistry, 85 step impls
│       │   └── impl/
│       │       ├── source/     AbstractSourceStep subclasses (CsvInput, TableInput, Excel…)
│       │       ├── sink/       AbstractSinkStep subclasses (TextFileOutput, TableOutput…)
│       │       ├── streaming/  AbstractStreamingStep subclasses (SelectValues, Calculator…)
│       │       ├── blocking/   AbstractBlockingStep subclasses (SortRows, GroupBy…)
│       │       ├── fanin/      Multi-input steps (MergeJoin, Append, SortedMerge…)
│       │       ├── routing/    RoutingStep impls (FilterRows, SwitchCase)
│       │       └── subtransform/ MappingStep, SimpleMappingStep
│       ├── entry/          JobEntry interface, JobEntryRegistry, 12 entry impls
│       │   └── impl/       StartEntry, RunTransformationEntry, MailEntry…
│       └── engine/         TransformationExecutor (KTR DAG), JobExecutor (KJB DAG),
│                           BroadcastBuffer (fan-out materialisation)
│
├── converter/          XML → YAML converter (no Spring, no HTTP)
│   └── src/main/java/com/pentaho/migration/converter/
│       ├── KtrParser.java          KTR XML → TransformationDefinition
│       ├── KjbParser.java          KJB XML → JobDefinition
│       ├── PentahoProjectConverter.java  Zip-in / zip-out batch converter
│       └── step/
│           ├── StepXmlMapper.java       Interface: Element → Map<String,String>
│           ├── StepXmlMapperRegistry.java
│           ├── DefaultStepXmlMapper.java  Generic fallback (copies text child elements)
│           └── *Mapper.java             Per-step mappers (CsvInputMapper, SortRowsMapper…)
│
└── api/                Spring Boot REST wrapper
    └── src/main/java/com/pentaho/migration/api/
        ├── controller/  ConverterController, ExecutionController, ProjectController…
        └── service/     ProjectService, ExecutionService
```

---

## Data flow

```
.kjb / .ktr XML
      │
      ▼ converter module
KjbParser / KtrParser
      │  (StepXmlMapper per step type)
      ▼
JobDefinition / TransformationDefinition  (YAML files on disk)
      │
      ▼ framework module
JobExecutor / TransformationExecutor
      │  (StepRegistry / JobEntryRegistry — type name → class)
      ▼
Iterator<Row> pipeline runs; output written to files / DB / etc.
```

---

## How to add a new KTR step

Every new step type requires **three** touch points:

### 1. Implement the step class (`framework`)

Pick the right base class:

| Pattern | Base class | Use when |
|---|---|---|
| Transform each row independently | `AbstractStreamingStep` | SelectValues, Calculator, IfNull… |
| Must see all rows before emitting any | `AbstractBlockingStep` | Sort, GroupBy, Unique… |
| Reads from an external source | `AbstractSourceStep` | CsvInput, TableInput… |
| Writes to an external sink | `AbstractSinkStep` | TextFileOutput, ExcelOutput… |
| Multiple inputs (fan-in) | Implement `Step` directly | MergeJoin, Append… |
| Conditional routing (fan-out) | Implement `RoutingStep` | FilterRows, SwitchCase |

```java
// Example: streaming step
package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;
import java.util.Map;

public class MyNewStep extends AbstractStreamingStep {

    private int columnIndex;
    private String someParam;

    @Override
    public void configure(Map<String, String> params) {
        columnIndex = Integer.parseInt(params.getOrDefault("columnIndex", "0"));
        someParam   = params.get("someParam");
    }

    @Override
    protected Row transform(Row row) {
        // transform and return the row; return null to drop it
        return row;
    }
}
```

Key rules:
- `configure()` receives a `Map<String,String>` — all values are strings.
- `Row.getString(index)` returns `null` for SQL NULL / blank cells. Guard accordingly.
- Checked exceptions from `transform()` must be wrapped in `RuntimeException` (streaming
  steps cannot declare checked exceptions in the interface).
- Source steps (`AbstractSourceStep`) override `readRows()` not `apply()`.
- Sink steps (`AbstractSinkStep`) override `open()`, `writeRow()`, `close()`.

### 2. Register the step (`framework/step/StepRegistry.java`)

Add one line inside `withDefaults()`:

```java
r.register("MyNewStep", MyNewStep.class);
```

The key must **exactly** match the type name the converter emits (see step 3).

### 3. Add a converter mapper (`converter/step/`)

Create `MyNewStepMapper.java` implementing `StepXmlMapper`:

```java
package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import java.util.HashMap;
import java.util.Map;
import static com.pentaho.migration.converter.step.XmlHelper.*;

public final class MyNewStepMapper implements StepXmlMapper {
    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();
        put(p, "someParam",    child(e, "xml_element_name"));
        put(p, "columnIndex",  child(e, "column_idx", "0"));
        return p;
    }
    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }
}
```

Use `XmlHelper.child(element, tagName)` to read text from child elements.
Use `XmlHelper.yesNo(value)` to convert Pentaho Y/N flags to `"true"`/`"false"`.
Use `XmlHelper.child(element, tagName, defaultValue)` for optional elements.

Register in `StepXmlMapperRegistry.withDefaults()`:

```java
r.register("MyNewStep", new MyNewStepMapper());
```

### 4. Add type normalisation if needed (`converter/KtrParser.java`)

If Pentaho's `<type>` XML value differs from your registry key, add an entry in
`normalizeStepType()`:

```java
case "PentahoXmlTypeName" -> "MyNewStep";
```

Steps whose XML type name already matches the registry key need no entry here.

### 5. Write a test

Add a test in `converter/src/test/.../KtrParserTest.java` using an inline XML string:

```java
@Test
void myNewStep_paramsExtracted() throws Exception {
    String ktr = """
        <?xml version="1.0"?>
        <transformation>
          <info><name>test</name></info>
          <step>
            <name>s1</name>
            <type>MyNewStep</type>
            <xml_element_name>someValue</xml_element_name>
          </step>
          <order/>
        </transformation>
        """;
    TransformationDefinition def = parser.parse(...);
    StepDefinition s = def.steps.get(0);
    assertEquals("MyNewStep", s.type);
    assertEquals("someValue", s.params.get("someParam"));
}
```

---

## How to add a new KJB entry type

Every new entry type requires **three** touch points:

### 1. Implement the entry class (`framework/entry/impl/`)

```java
package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

public class MyNewEntry implements JobEntry {

    private String param;

    @Override
    public void configure(Map<String, String> params) {
        param = params.get("param");
    }

    @Override
    public boolean execute(Map<String, String> context) throws Exception {
        // return true = success, false = failure
        // context is a shared mutable map for job-level variables
        return true;
    }
}
```

### 2. Register and map in the converter (`converter/KjbParser.java`)

Add the Pentaho XML type → our type name mapping in `normalizeEntryType()`:

```java
case "PENTAHO_XML_TYPE" -> "MyNewEntry";
```

Add param extraction in `buildEntryParams()`:

```java
case "MyNewEntry" -> {
    String val = text(el, "xml_element");
    if (val != null) params.put("param", val);
}
```

Register in `JobEntryRegistry.withDefaults()`:

```java
r.register("MyNewEntry", MyNewEntry.class);
```

### 3. Write tests

- `KjbParserTest.java` — verify XML → type name and params extraction.
- `TransformationExecutorTest.java` — use `registry.registerFactory()` to inject
  a lambda for integration tests without real file/network I/O:

```java
registry.registerFactory("MyNewEntry", params -> ctx -> {
    // assert params, update ctx, return true/false
    return true;
});
```

---

## Engine behaviour — critical rules

### TransformationExecutor (KTR)

- Executes steps in **topological order** (Kahn's algorithm).
- **Linear**: iterator is passed directly — zero materialisation.
- **Fan-out** (one step → N downstreams): output is materialised to disk via
  `BroadcastBuffer` (FileChannel, ref-counted delete). N independent readers
  are created from the same file.
- **Fan-in** (N steps → one step): all upstream iterators collected into
  `List<Iterator<Row>>` and passed to `step.apply(inputs)`.
- **Routing steps** (`RoutingStep`): `route()` returns `Map<targetStepId, Iterator<Row>>`
  instead of a single iterator. The engine maps each key to the correct downstream step.

### JobExecutor (KJB)

- Builds `CompletableFuture<Optional<Boolean>>` per entry, wired by `allOf()` for fan-in.
- `Optional.of(true/false)` = entry ran (success/failure).
- `Optional.empty()` = entry was **skipped** (its incoming hop was not satisfied).
- **Fan-in rule**: ALL non-skipped predecessors must satisfy their hop. Skipped
  predecessors are ignored. This handles mutually-exclusive branches (e.g. a SUCCESS
  terminal reachable from two alternate paths) without deadlock or false triggers.
- Hop evaluation values: `"unconditional"`, `"success"`, `"failure"`.
- Do **not** change the `shouldFollow()` / `Optional` logic without running the
  `mutuallyExclusiveBranches_terminalRunsOnce` and `nonLinearJob_parallelFanOutAndFanIn`
  engine tests.

---

## Converter — parsing rules

### KtrParser

- Entry point: `KtrParser.parse(InputStream)` → `TransformationDefinition`.
- Step type is normalised via `normalizeStepType()` before the mapper is called.
- Column-name → index resolution: blocking steps (SortRows, GroupBy, FilterRows) may
  receive column **names** from the converter; `computeGroupByOutputSchema()` tracks
  which column names each step exposes downstream for index resolution.
- Hops with `<enabled>N</enabled>` are skipped.

### KjbParser

- Entry point: `KjbParser.parse(InputStream)` → `JobDefinition`.
- Supports both `<entries><entry>` wrapper and bare `<entry>` children of `<job>`
  (real-world KJBs vary).
- Supports both `<hops><hop>` wrapper and bare `<hop>` children.
- Supports both `<jobentry>` and `<entry>` tag names.
- `SPECIAL` entries: `<start>Y>` → Start, `<success>Y>` → Success, `<abort>Y>` → Abort,
  `<dummy>Y>` → Dummy, no flag → Success (Pentaho omits `<success>Y>` in many exports).
- Hop evaluation: `<unconditional>Y</unconditional>` → `"unconditional"`;
  `<evaluation>Y/true</evaluation>` → `"success"`;
  `<evaluation>N/false</evaluation>` → `"failure"`.
- Entry types currently mapped to `Dummy` (no implementation):
  `EVAL_FILES_METRICS`, `DELETE_FILE`, `EVAL`, `MOVE_FILES`, `BLOCKUNTILSTEPSFINISH`.
  Replace with real implementations as needed.

---

## Known gaps / TODO

| Type | Location | Notes |
|---|---|---|
| `EVAL_FILES_METRICS` | KjbParser → Dummy | Check file size/count condition; needs FileEntryBase |
| `DELETE_FILE` | KjbParser → Dummy | Delete a file path |
| `EVAL` | KjbParser → Dummy | JavaScript expression evaluator |
| `MOVE_FILES` | KjbParser → Dummy | Move/rename files |
| `Mail` SMTP config | MailEntry | `host` and `from` are required but KJBs often omit them |
| Repository-based KTRs | KjbParser | Entries with empty `<filename/>` need path injection |
| Malformed `<to>name</</to>` | KjbParser | Literal `<` in XML text → SAXParseException upstream |
| `UserDefinedJavaClassStep` | StepRegistry | Registered but compiles inline Java; untested at scale |
| `FormulaStep`, `ScriptValueModStep` | StepRegistry | Stubs; need Janino/Groovy wiring |

---

## Build and test commands

```bash
# Build everything (skip tests)
mvn package -DskipTests -Dmaven.artifact.threads=1 -q

# Run all tests
mvn test -Dmaven.artifact.threads=1

# Run only converter tests (fast — pure XML, no Spring)
mvn test -pl converter -Dmaven.artifact.threads=1

# Run only framework engine tests
mvn test -pl framework -Dmaven.artifact.threads=1 -Dtest=TransformationExecutorTest

# Run the API tests
mvn test -pl api -Dmaven.artifact.threads=1
```

Tests use JUnit 5. All `KtrParserTest` and `KjbParserTest` tests are pure in-memory
(no files, no Spring context) — prefer this style for new parser tests.

---

## Code style rules

- No Spring annotations in `framework` or `converter` modules. Only `api` uses Spring.
- `Row.getString(i)` can return `null` — never call `.trim()` or `.equals()` without
  a null check.
- Checked exceptions from `AbstractStreamingStep.transform()` must be wrapped in
  `RuntimeException`.
- Do not add params for values the engine doesn't use. Params map is `Map<String,String>`;
  all values are strings — parse them in `configure()`, not in `apply()`.
- Tests should use inline XML strings (text blocks), not files on disk, for parser tests.
- The `DefaultStepXmlMapper` provides a generic fallback — it copies all direct text
  child elements as params. Rely on it for simple steps with no structural mapping needs.
