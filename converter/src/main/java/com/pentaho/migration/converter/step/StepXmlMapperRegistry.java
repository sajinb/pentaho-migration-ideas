package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry mapping normalised Pentaho step type names to their {@link StepXmlMapper}.
 * Falls back to {@link DefaultStepXmlMapper} for unknown types.
 */
public final class StepXmlMapperRegistry {

    private final Map<String, StepXmlMapper> mappers = new HashMap<>();
    private final StepXmlMapper fallback = new DefaultStepXmlMapper();

    public void register(String typeName, StepXmlMapper mapper) {
        mappers.put(typeName, mapper);
    }

    /** Returns the mapper for {@code typeName}, or the default mapper if none registered. */
    public StepXmlMapper get(String typeName) {
        return mappers.getOrDefault(typeName, fallback);
    }

    /** Returns a registry pre-populated with all built-in step mappers. */
    public static StepXmlMapperRegistry withDefaults() {
        StepXmlMapperRegistry r = new StepXmlMapperRegistry();
        r.register("CsvInput",           new CsvInputMapper());
        r.register("TextFileInput",       new TextFileInputMapper());
        r.register("TextFileOutput",      new TextFileOutputMapper());
        r.register("SortRows",            new SortRowsMapper());
        r.register("FilterRows",          new FilterRowsMapper());
        r.register("TableInput",          new TableInputMapper());
        r.register("ExcelInput",          new ExcelInputMapper());
        r.register("ExcelOutput",         new ExcelOutputMapper());
        r.register("MergeJoin",           new MergeJoinMapper());
        r.register("GroupBy",             new GroupByMapper());
        r.register("MemoryGroupBy",       new GroupByMapper());
        r.register("Formula",             new FormulaMapper());
        r.register("Calculator",          new CalculatorMapper());
        r.register("ScriptValueMod",      new ScriptValueModMapper());
        r.register("SwitchCase",          new SwitchCaseMapper());
        r.register("Mapping",             new MappingMapper());
        r.register("SimpleMapping",       new MappingMapper());
        return r;
    }
}
