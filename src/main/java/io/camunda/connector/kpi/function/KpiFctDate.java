package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

public class KpiFctDate extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctDate.class);

    private static String roundingNames() {
        return Arrays.stream(Rounding.values()).map(Rounding::name).collect(Collectors.joining(", "));
    }

    private static String typeNames() {
        return Arrays.stream(DateType.values()).map(DateType::name).collect(Collectors.joining(", "));
    }

    @Override
    public String getName() {
        return "date";
    }

    @Override
    public String getLabel() {
        return "date(rounding, type, zone)";
    }

    @Override
    public String getExplanation() {
        return "Return the current date/time, rounded to rounding (" + roundingNames()
                + ", full default), as type (" + typeNames()
                + ", LocalDateTime default; STRING returns an ISO-8601 string), in zone (UTC default, local, an offset, or a zone id).";
    }

    /**
     * first parameter (optional):
     * - full (default) : return the full date, java.util.date
     * - second: return the date, round to the previous second
     * - minute: return the date, rond to the previous minutes
     * - hour : round to hour
     * - day: round to day
     * - month : round to month + year
     * <p>
     * second parameter (optional) : type - one of DateType (LocalDateTime is the default); STRING returns an
     * ISO-8601 string instead of a java.time object
     * third parameter (optional)
     * - UTC return the date in UTC (LocalTime or string)
     * - +07:00 or any format like this: try to get the Zone from that string, maybe "anmerica" or whatever
     * - "local" : use the time zone of the java machine
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present, is the rounding: full (default), second, minute, hour, day or month;
     *                                 functionRecord.parameters.get(1), if present, is the type: one of DateType (LocalDateTime is the default);
     *                                 functionRecord.parameters.get(2), if present, is the zone: UTC (default), local (JVM default zone), a fixed offset
     *                                 like +07:00, or a zone id like America/New_York
     * @param outboundConnectorContext unused, this function reads the current time, not a job/process variable
     * @param camundaClient            unused, this function does not query the cluster
     * @return the current date/time, rounded as requested and expressed in the requested zone, as a LocalDate, LocalTime,
     * LocalDateTime, OffsetDateTime, Instant or an ISO-8601 string, depending on type
     * @throws Exception if the rounding, type or zone parameter is not one of the supported values
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String roundingParameter = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst().trim() : "FULL";
        Rounding rounding = Rounding.fromString(roundingParameter);
        if (rounding == null) {
            String message = "date() rounding [" + roundingParameter + "] is unknown, expected one of " + roundingNames();
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }

        String typeParameter = functionRecord.parameters != null && functionRecord.parameters.size() > 1
                ? functionRecord.parameters.get(1).trim() : "LocalDateTime";
        DateType type = DateType.fromString(typeParameter);
        if (type == null) {
            String message = "date() type [" + typeParameter + "] is unknown, expected one of " + typeNames();
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }
        String zoneParameter = functionRecord.parameters != null && functionRecord.parameters.size() > 2
                ? functionRecord.parameters.get(2).trim() : "UTC";
        ZoneId zoneId = resolveZone(functionRecord, zoneParameter);

        ZonedDateTime dateTime = round(Instant.now().atZone(zoneId), rounding);

        return switch (type) {
            case LOCALDATE -> dateTime.toLocalDate();
            case LOCALTIME -> dateTime.toLocalTime();
            case OFFSETDATETIME -> dateTime.toOffsetDateTime();
            case INSTANT -> dateTime.toInstant();
            case STRING -> dateTime.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            case LOCALDATETIME -> dateTime.toLocalDateTime();
        };
    }

    /**
     * @param zoneParameter UTC, local, a fixed offset like +07:00, or a zone id like America/New_York
     * @return the resolved ZoneId
     * @throws ConnectorException if zoneParameter cannot be resolved to a zone
     */
    private ZoneId resolveZone(FunctionRecord functionRecord, String zoneParameter) throws ConnectorException {
        if ("UTC".equalsIgnoreCase(zoneParameter)) {
            return ZoneOffset.UTC;
        }
        if ("local".equalsIgnoreCase(zoneParameter)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zoneParameter);
        } catch (DateTimeException e) {
            String message = "date() zone [" + zoneParameter + "] is unknown, expected UTC, local, an offset like +07:00, or a zone id like America/New_York";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }
    }

    /**
     * @param dateTime the date/time to round
     * @param rounding FULL (no rounding), SECOND, MINUTE, HOUR, DAY or MONTH
     * @return dateTime truncated to the requested precision, keeping its original zone
     */
    private ZonedDateTime round(ZonedDateTime dateTime, Rounding rounding) {
        return switch (rounding) {
            case SECOND -> dateTime.withNano(0);
            case MINUTE -> dateTime.withSecond(0).withNano(0);
            case HOUR -> dateTime.withMinute(0).withSecond(0).withNano(0);
            case DAY -> dateTime.toLocalDate().atStartOfDay(dateTime.getZone());
            case MONTH -> dateTime.toLocalDate().withDayOfMonth(1).atStartOfDay(dateTime.getZone());
            case FULL -> dateTime;
        };
    }

    private enum Rounding {
        FULL, SECOND, MINUTE, HOUR, DAY, MONTH;

        static Rounding fromString(String value) {
            return Arrays.stream(values())
                    .filter(rounding -> rounding.name().equalsIgnoreCase(value))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Mapping
     * | Java type        | PostgreSQL type                            |
     * | ---------------- | ------------------------------------------ |
     * | `LocalDate`      | `DATE`                                     |
     * | `LocalTime`      | `TIME`                                     |
     * | `LocalDateTime`  | `TIMESTAMP` (`WITHOUT TIME ZONE`)          |
     * | `OffsetDateTime` | `TIMESTAMP WITH TIME ZONE` (`TIMESTAMPTZ`) |
     * | `Instant`        | `TIMESTAMPTZ`                              |
     */
    private enum DateType {
        LOCALDATE, LOCALTIME, LOCALDATETIME, OFFSETDATETIME, INSTANT, STRING;

        static DateType fromString(String value) {
            return Arrays.stream(values())
                    .filter(type -> type.name().equalsIgnoreCase(value))
                    .findFirst()
                    .orElse(null);
        }
    }
}
