package io.camunda.connector.kpi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.camunda.connector.cherrytemplate.CherryInput;
import io.camunda.connector.cherrytemplate.RunnerParameter;
import io.camunda.connector.kpi.function.KpiFctFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * the JsonIgnoreProperties is mandatory: the template may contain additional widget to help the designer, especially on the OPTIONAL parameters
 * This avoids the MAPPING Exception
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KpiInput implements CherryInput {
    /**
     * Attention, each Input here must be added in the FunctionRecord, list of InputVariables
     */
    public static final String KPIFUNCTION = "Kpi Outbound Connector";

    public static final String KPIPILOT = "kpiPilot";

    /* ------------------------------------------------------------------ */
    /*  Function                                                           */
    /* ------------------------------------------------------------------ */
    public static final RunnerParameter parameterFunction = new RunnerParameter(
            KPIPILOT,
            "Pilot to build the KPI",
            String.class,
            RunnerParameter.Level.REQUIRED,
            "Record to build the KPI. Set of 'name':'function'. Available functions: " + KpiFctFactory.getFctLabels());

    /* ------------------------------------------------------------------ */
    /*  Database save (optional)                                           */
    /* ------------------------------------------------------------------ */
    public static final String SAVEDATABASE = "saveDatabase";
    public static final String SAVEDATABASE_V_YES = "Yes";
    public static final String SAVEDATABASE_V_NO = "No";
    public static final RunnerParameter parameterSaveDatabase = new RunnerParameter(
            SAVEDATABASE,
            "Save in database", String.class, RunnerParameter.Level.REQUIRED,
            "Save the KPI in a database")
            .addChoice(SAVEDATABASE_V_YES, "Yes")
            .addChoice(SAVEDATABASE_V_NO, "No");
    public static final String JDBCSTRING = "jdbcString";
    public static final RunnerParameter parameterJdbcString = new RunnerParameter(
            JDBCSTRING,
            "Jdbc connection String", String.class, RunnerParameter.Level.OPTIONAL,
            "JDBC connection string used to save the KPI record in a database, e.g. jdbc:postgresql://host:5432/mydb?user=foo&password=bar. When not set, the KPI record is not saved to any database.")
            .addCondition(SAVEDATABASE, List.of("Yes"))
            .setVisibleInTemplate();

    public static final String TABLENAME = "tableName";
    public static final RunnerParameter parameterTableName = new RunnerParameter(
            TABLENAME,
            "Table name", String.class, RunnerParameter.Level.OPTIONAL,
            "Name of the database table where the KPI record is saved. Required when jdbcString is set.")
            .addCondition(SAVEDATABASE, List.of("Yes"))
            .setVisibleInTemplate();



    /* ------------------------------------------------------------------ */
    /*  Shared: eventId / calendarId                                       */
    /* ------------------------------------------------------------------ */
    // -- shared --

    @JsonIgnore
    private final Logger logger = LoggerFactory.getLogger(KpiInput.class.getName());
    @JsonIgnore
    public List<RunnerParameter> inputParametersList = List.of(
            parameterFunction,
            parameterSaveDatabase,
            parameterJdbcString,
            parameterTableName
    );
    private Map<String, Object> kpiPilot;
    private String saveDatabase;
    private String jdbcString;
    private String tableName;


    // {dateUTC: "date(full, string, UTC)", dateSFO: "date(full, string, America/Los_Angeles)", blueExecution: "duration(beginMarkBlue, endMarkBlue, second)", city: "variable(city)", amount: "variable(amount)", flowValidationTaken: "path(ANY, Manual validation)", isHighValue: "threshold(amount, >, 5000)", reviewAssignee: "userTaskExecution(Review, LAST)", reviewSLA: "usertaskSLA(Review, PT1H)", reviewSLADescription: "usertaskSLADescription(Review, PT1H, hour)", kpiLabel: "kpi-demo"}
    public Map<String, Object> getKpiPilot() {
        return kpiPilot;
    }

    public String getSaveDatabase() {
        return saveDatabase;
    }

    public String getJdbcString() {
        return jdbcString;
    }

    public String getTableName() {
        return tableName;
    }

    /* ------------------------------------------------------------------ */
    /*  Build complete objects                                             */
    /* ------------------------------------------------------------------ */

    @JsonIgnore
    @Override
    public List<Map<String, Object>> getInputParameters() {
        return inputParametersList.stream().map(t -> t.toMap(null)).toList();
    }
}
