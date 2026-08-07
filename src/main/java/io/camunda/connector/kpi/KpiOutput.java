package io.camunda.connector.kpi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.camunda.connector.cherrytemplate.CherryOutput;
import io.camunda.connector.cherrytemplate.RunnerParameter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KpiOutput implements CherryOutput {

    public static final String KPIRECORD = "kpiRecord";
    public static final RunnerParameter parameterKpiRecord = new RunnerParameter(
            KPIRECORD, "Kpi Record", Map.class, RunnerParameter.Level.OPTIONAL,
            "Record build for the KPI");


    /* ------------------------------------------------------------------ */
    /*  RunnerParameters                                                   */
    /* ------------------------------------------------------------------ */
    /**
     * ID of the created/updated/deleted event (master series ID for recurring events).
     */
    public Map<String, Object> kpiRecord = new HashMap<>();
    /* ------------------------------------------------------------------ */
    /*  Parameter list                                                     */
    /* ------------------------------------------------------------------ */
    public List<RunnerParameter> outputParametersList = List.of(
            parameterKpiRecord);

    public Map<String, Object> getKpiRecord() {
        return kpiRecord;
    }


    @JsonIgnore
    @Override
    public List<Map<String, Object>> getOutputParameters() {
        return outputParametersList.stream().map(t -> t.toMap(null)).toList();
    }

    /* ------------------------------------------------------------------ */
    /*  Populate from CalendarStatus                                       */
    /* ------------------------------------------------------------------ */

}
