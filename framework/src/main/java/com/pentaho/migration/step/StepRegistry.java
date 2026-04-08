package com.pentaho.migration.step;

import com.pentaho.migration.step.impl.blocking.*;
import com.pentaho.migration.step.impl.fanin.*;
import com.pentaho.migration.step.impl.routing.*;
import com.pentaho.migration.step.impl.sink.*;
import com.pentaho.migration.step.impl.source.*;
import com.pentaho.migration.step.impl.streaming.*;
import com.pentaho.migration.step.impl.subtransform.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry mapping Pentaho step type names (as they appear in YAML) to Java classes.
 *
 * <p>Register custom steps with {@link #register(String, Class)}.
 * Use {@link #withDefaults()} to get a registry pre-loaded with all built-in Phase 1 steps.
 */
public final class StepRegistry {

    private final Map<String, Class<? extends Step>> registry = new HashMap<>();

    public void register(String typeName, Class<? extends Step> clazz) {
        registry.put(typeName, clazz);
    }

    /**
     * Returns a registry pre-populated with all Phase 1 built-in step implementations.
     */
    public static StepRegistry withDefaults() {
        StepRegistry r = new StepRegistry();

        // --- Source steps ---
        r.register("CsvInput",        CsvInputStep.class);
        r.register("TextFileInput",    TextFileInputStep.class);
        r.register("PropertyInput",    PropertyInputStep.class);
        r.register("RowGenerator",     RowGeneratorStep.class);
        r.register("SystemInfo",       SystemInfoStep.class);
        r.register("GetFileNames",     GetFileNamesStep.class);
        r.register("RowsFromResult",   RowsFromResultStep.class);
        r.register("MappingInput",     MappingInputStep.class);
        r.register("TableInput",       TableInputStep.class);
        r.register("ExcelInput",       ExcelInputStep.class);

        // --- Sink steps ---
        r.register("TextFileOutput",   TextFileOutputStep.class);
        r.register("WriteToLog",       WriteToLogStep.class);
        r.register("RowsToResult",     RowsToResultStep.class);
        r.register("MappingOutput",    MappingOutputStep.class);
        r.register("DetectEmptyStream", DetectEmptyStreamStep.class);
        r.register("TableOutput",      TableOutputStep.class);
        r.register("ExcelOutput",      ExcelOutputStep.class);
        r.register("TypeExitExcelWriter", TypeExitExcelWriterStep.class);

        // --- Pattern A: streaming single-row transforms ---
        r.register("SelectValues",         SelectValuesStep.class);
        r.register("Constant",             ConstantStep.class);
        r.register("SetValueConstant",     SetValueConstantStep.class);
        r.register("SetValueField",        SetValueFieldStep.class);
        r.register("IfNull",               IfNullStep.class);
        r.register("NullIf",               NullIfStep.class);
        r.register("StringOperations",     StringOperationsStep.class);
        r.register("ReplaceString",        ReplaceStringStep.class);
        r.register("StringCut",            StringCutStep.class);
        r.register("ConcatFields",         ConcatFieldsStep.class);
        r.register("Calculator",           CalculatorStep.class);
        r.register("SetVariable",          SetVariableStep.class);
        r.register("GetVariable",          GetVariableStep.class);
        r.register("ValueMapper",          ValueMapperStep.class);
        r.register("NumberRange",          NumberRangeStep.class);
        r.register("CheckSum",             CheckSumStep.class);
        r.register("RegexEval",            RegexEvalStep.class);
        r.register("FieldSplitter",        FieldSplitterStep.class);
        r.register("CloneRow",             CloneRowStep.class);
        r.register("Sequence",             SequenceStep.class);
        r.register("Dummy",                DummyStep.class);
        r.register("DetectLastRow",        DetectLastRowStep.class);
        r.register("FieldsChangeSequence", FieldsChangeSequenceStep.class);
        r.register("WriteToLog",           WriteToLogStep.class);
        r.register("Abort",                AbortStep.class);
        r.register("ExecProcess",          ExecProcessStep.class);
        r.register("FileExists",           FileExistsStep.class);
        r.register("FileLocked",           FileLockedStep.class);
        r.register("ProcessFiles",         ProcessFilesStep.class);
        r.register("ZipFile",              ZipFileStep.class);
        r.register("GetFilesRowsCount",    GetFilesRowsCountStep.class);
        r.register("Validator",            ValidatorStep.class);
        r.register("Rest",                 RestStep.class);
        r.register("SplitFieldToRows3",    SplitFieldToRowsStep.class);
        r.register("InsertUpdate",         InsertUpdateStep.class);
        r.register("Delete",               DeleteStep.class);
        r.register("ExecSQL",              ExecSQLStep.class);

        // --- Pattern B: blocking steps ---
        r.register("SortRows",             SortRowsStep.class);
        r.register("GroupBy",              GroupByStep.class);
        r.register("MemoryGroupBy",        MemoryGroupByStep.class);
        r.register("Unique",               UniqueStep.class);
        r.register("UniqueRowsByHashSet",  UniqueRowsByHashSetStep.class);
        r.register("BlockingStep",         BlockingStepStep.class);
        r.register("Denormaliser",         DenormaliserStep.class);
        r.register("Normaliser",           NormaliserStep.class);

        // --- Pattern E: fan-in steps ---
        r.register("Append",               AppendStep.class);
        r.register("SortedMerge",          SortedMergeStep.class);
        r.register("MergeJoin",            MergeJoinStep.class);
        r.register("JoinRows",             JoinRowsStep.class);
        r.register("MergeRows",            MergeRowsStep.class);
        r.register("MultiwayMergeJoin",    MultiwayMergeJoinStep.class);

        // --- Pattern F: routing/conditional fan-out steps ---
        r.register("FilterRows",           FilterRowsStep.class);
        r.register("SwitchCase",           SwitchCaseStep.class);

        // --- Pattern G: sub-transformation execution ---
        r.register("Mapping",              MappingStep.class);
        r.register("SimpleMapping",        SimpleMappingStep.class);

        return r;
    }

    /**
     * Instantiate and configure a step by type name.
     *
     * @throws IllegalArgumentException if the type name is not registered
     * @throws RuntimeException         if instantiation fails
     */
    public Step instantiate(String typeName, Map<String, String> params) {
        Class<? extends Step> clazz = registry.get(typeName);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown step type: '" + typeName
                    + "'. Register it with StepRegistry.register() first.");
        }
        try {
            Step step = clazz.getDeclaredConstructor().newInstance();
            if (params != null) step.configure(params);
            return step;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to instantiate step type '" + typeName + "'", e);
        }
    }
}
