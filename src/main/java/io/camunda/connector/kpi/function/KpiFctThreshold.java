package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

public class KpiFctThreshold extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctThreshold.class);

    @Override
    public String getName() {
        return "threshold";
    }

    @Override
    public String getLabel() {
        return "threshold(variableName, operator, value)";
    }

    @Override
    public String getExplanation() {
        return "Compare process variable variableName against value using operator (=, >, <, >=, <=, before, after); "
                + "before/after compare ISO-8601 dates.";
    }

    /**
     * 3 parameters:
     * - First is the process variable name
     * - second is the operator : =, >, <, >=, <=, before, after
     * - third is the threshold
     * Return Boolean.True or Boolean.False according if the operator work
     * Example amount >= 2000 : compare the amount (process variable, Integer, Long, Double expected) with 2000
     * before, after compare date. Threshold must be a date ISO, same format as FEEL date
     *
     * @param functionRecord           functionRecord.parameters.getFirst() is the process variable name,
     *                                 functionRecord.parameters.get(1) is the operator (=, &gt;, &lt;, &gt;=, &lt;=, before, after),
     *                                 functionRecord.parameters.get(2) is the threshold: a number for =/&gt;/&lt;/&gt;=/&lt;=,
     *                                 an ISO-8601 date/date-time for before/after
     * @param outboundConnectorContext used to read the current job's variables
     * @param camundaClient            unused, the value being compared comes from the job context, not from a cluster query
     * @return Boolean.TRUE if "variable operator threshold" holds, Boolean.FALSE otherwise
     * @throws Exception if a parameter is missing, the variable is not set, the operator is unknown,
     *                   or the variable/threshold cannot be parsed as required by the operator (a number, or an ISO date/date-time)
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.size() < 3) {
            String message = "threshold() requires three parameters: the variable name, the operator and the threshold";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String variableName = functionRecord.parameters.getFirst();
        String operatorParameter = functionRecord.parameters.get(1).trim();
        String thresholdParameter = functionRecord.parameters.get(2);

        Operator operator = Operator.fromString(operatorParameter);
        if (operator == null) {
            String message = "threshold() operator [" + operatorParameter + "] is unknown, expected one of =, >, <, >=, <=, before, after";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }

        Object variableValue = getVariablesAsMap(outboundConnectorContext).get(variableName);
        if (variableValue == null) {
            return null;
        }

        if (operator.isDateOperator()) {
            Instant variableInstant = parseTemporal(functionRecord, String.valueOf(variableValue), "variable [" + variableName + "]");
            Instant thresholdInstant = parseTemporal(functionRecord, thresholdParameter, "threshold");
            return operator == Operator.BEFORE
                    ? variableInstant.isBefore(thresholdInstant)
                    : variableInstant.isAfter(thresholdInstant);
        }

        double value = parseNumber(functionRecord, variableValue, "variable [" + variableName + "]");
        double threshold = parseNumber(functionRecord, thresholdParameter, "threshold");
        return switch (operator) {
            case EQ -> value == threshold;
            case GT -> value > threshold;
            case LT -> value < threshold;
            case GE -> value >= threshold;
            case LE -> value <= threshold;
            case BEFORE, AFTER -> false;
        };
    }

    /**
     * @param candidate the raw variable/threshold value
     * @param label     used in the error message if parsing fails
     * @return candidate parsed as a double
     * @throws ConnectorException if candidate is not numeric
     */
    private double parseNumber(FunctionRecord functionRecord, Object candidate, String label) throws ConnectorException {
        if (candidate instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(candidate.toString().trim());
        } catch (NumberFormatException e) {
            String message = "threshold() " + label + " is not numeric: [" + candidate + "]";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }
    }

    /**
     * Parse an ISO-8601 date, date-time or offset date-time (FEEL date/date-time format) into a comparable Instant.
     * A date-only value is normalized to midnight UTC and a local date-time to UTC, so before/after comparisons stay
     * consistent even if the variable and the threshold are not expressed with the same precision.
     *
     * @param raw   the ISO-8601 text to parse
     * @param label used in the error message if parsing fails
     * @return raw parsed as an Instant
     * @throws ConnectorException if raw is not a valid ISO-8601 date, date-time or offset date-time
     */
    private Instant parseTemporal(FunctionRecord functionRecord, String raw, String label) throws ConnectorException {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            // not an offset date-time, try the next format
        }
        try {
            return LocalDateTime.parse(raw).atZone(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            // not a local date-time, try the next format
        }
        try {
            return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            String message = "threshold() " + label + " is not a valid ISO-8601 date/date-time: [" + raw + "]";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.DATE_PARSING_ERROR, message);
        }
    }

    private enum Operator {
        EQ("="), GT(">"), LT("<"), GE(">="), LE("<="), BEFORE("before"), AFTER("after");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        static Operator fromString(String value) {
            return Arrays.stream(values())
                    .filter(operator -> operator.symbol.equalsIgnoreCase(value))
                    .findFirst()
                    .orElse(null);
        }

        boolean isDateOperator() {
            return this == BEFORE || this == AFTER;
        }
    }
}
