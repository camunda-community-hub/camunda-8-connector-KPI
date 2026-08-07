package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KpiFctUserTaskSLADescription extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctUserTaskSLADescription.class);

    @Override
    public String getName() {
        return "userTaskSLADescription";
    }

    @Override
    public String getLabel() {
        return "userTaskSLADescription(taskFilter, limit, unit)";
    }

    @Override
    public String getExplanation() {
        return "Return \"taskName\"-delay-OK/OVER for every completed user task matching taskFilter (blank = all), "
                + "comparing its duration against limit (ISO-8601 duration); unit formats the delay (minute, hour default, day).";
    }

    /**
     * First parameter is the user task filter. If empty, then all tasks are considered
     * Second parameters is the limit, given in ISO format: PT12S for 12 second, or PT3H
     * Third parameter (optional) is the unit: minute, hour(default), day
     * For all user task which match the filter, compare the startDate and endDate.
     * Return a String, which is a list of <taskName>-<delay>-OK or <<taskName>-<delay>-OVER
     * For example, no taskfilter, limit is 15h, unit is hour:
     * "review Contract"-14h-OK, "validate Contract"-19h-OVER
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the user task name or element id filter,
     *                                 functionRecord.parameters.get(1) is the SLA limit, an ISO-8601 duration (e.g. PT12S, PT3H),
     *                                 functionRecord.parameters.get(2), if present, is the unit used to format the delay: minute, hour (default) or day
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's user task history, page by page
     * @return a comma-separated list of "taskName"-delay-OK or "taskName"-delay-OVER, one entry per matching,
     * completed user task; user tasks that have not completed yet are skipped since their delay is not known
     * @throws Exception if the limit parameter is missing, not a valid ISO-8601 duration, or the unit is not minute/hour/day
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.size() < 2) {
            String message = "userTaskSLADescription() requires the task filter and the SLA limit (ISO-8601 duration)";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String taskIdFilter = functionRecord.parameters.getFirst();
        String limitParameter = functionRecord.parameters.get(1);
        String unitParameter = functionRecord.parameters.size() > 2 ? functionRecord.parameters.get(2).trim() : "HOUR";
        Unit unit = Unit.fromString(unitParameter);
        if (unit == null) {
            String message = "userTaskSLADescription() unit [" + unitParameter + "] is unknown, expected minute, hour or day";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }

        Duration limit;
        try {
            limit = Duration.parse(limitParameter);
        } catch (DateTimeParseException e) {
            String message = "userTaskSLADescription() limit [" + limitParameter + "] is not a valid ISO-8601 duration";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.DATE_PARSING_ERROR, message);
        }

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        FilterElementId filter = new FilterElementId()
                .addElementIds(taskIdFilter == null || taskIdFilter.isBlank() ? null : List.of(taskIdFilter))
                .addType(ElementInstanceType.USER_TASK);
        List<ElementInstance> userTasks = searchElementInstances(camundaClient, processInstanceKey, filter);

        List<String> descriptions = new ArrayList<>();
        for (ElementInstance userTask : userTasks) {
            if (userTask.getStartDate() == null || userTask.getEndDate() == null) {
                continue;
            }
            Duration actual = Duration.between(userTask.getStartDate(), userTask.getEndDate());
            long delay = unit.toUnit(actual);
            String status = actual.compareTo(limit) > 0 ? "OVER" : "OK";
            descriptions.add("\"" + userTask.getElementId() + "\"-" + delay + unit.abbreviation + "-" + status);
        }
        return String.join(", ", descriptions);
    }

    private enum Unit {
        MINUTE("mn") {
            long toUnit(Duration duration) {
                return duration.toMinutes();
            }
        },
        HOUR("h") {
            long toUnit(Duration duration) {
                return duration.toHours();
            }
        },
        DAY("d") {
            long toUnit(Duration duration) {
                return duration.toDays();
            }
        };

        private final String abbreviation;

        Unit(String abbreviation) {
            this.abbreviation = abbreviation;
        }

        static Unit fromString(String value) {
            return Arrays.stream(values())
                    .filter(unit -> unit.name().equalsIgnoreCase(value))
                    .findFirst()
                    .orElse(null);
        }

        abstract long toUnit(Duration duration);
    }
}
