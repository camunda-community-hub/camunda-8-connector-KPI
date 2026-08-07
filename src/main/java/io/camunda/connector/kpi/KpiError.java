package io.camunda.connector.kpi;

public class KpiError {


    public static final String BAD_INPUTPARAMETER = "BAD_INPUTPARAMETER";
    public static final String BAD_INPUTPARAMETER_EXPLANATION = "During the bind, some input does not have the expected type";

    public static final String OPERATION_EXECUTION = "OPERATION_EXECUTION";
    public static final String OPERATION_EXECUTION_EXPLANATION = "Error during an operation";

    public static final String DATE_PARSING_ERROR = "DATE_PARSING_ERROR";
    public static final String DATE_PARSING_ERROR_EXPLANATION = "A date parameter cannot be parsed. Dates must be ISO-8601, e.g. \"2026-07-22T10:00:00-07:00\" or \"2026-07-22\". The message gives the source parameter (startDate, endDate, startDateOccurrence)";

    public static final String UNKNOWN_FUNCTION = "UNKNOWN_FUNCTION";
    public static final String UNKNOWN_FUNCTION_EXPLANATION = "The function is unknown";


    public static final String INCOMPLETE_PARAMETERS = "INCOMPLETE_PARAMETERS";
    public static final String INCOMPLETE_PARAMETERS_EXPLANATION = "Parameter is incomplete";

    public static final String SQL_ERROR = "SQL_ERROR";
    public static final String SQL_ERROR_EXPLANATION = "Error during insertion in the database";


    public static final String DATABASE_NOT_MATCH = "DATABASE_NOT_MATCH";
    public static final String DATABASE_NOT_MATCH_EXPLANATION = "The table does not have any columns matching the KPI Record";


}
