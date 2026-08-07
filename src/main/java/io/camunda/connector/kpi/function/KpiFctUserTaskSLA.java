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
import java.util.List;

public class KpiFctUserTaskSLA extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctUserTaskSLA.class);

    @Override
    public String getName() {
        return "userTaskSLA";
    }

    @Override
    public String getLabel() {
        return "userTaskSLA(taskFilter, limit)";
    }

    @Override
    public String getExplanation() {
        return "Return TRUE if every completed user task matching taskFilter (blank = all) finished within limit "
                + "(an ISO-8601 duration, e.g. PT2H), FALSE otherwise.";
    }


    /**
     * First parameter is the user task filter. If empty, then all tasks are considered
     * Second parameters is the limit, given in ISO format: PT12S for 12 second, or PT3H
     * For all user task which match the filter, compare the startDate and endDate. They must be uinder the SLA
     * Return Boolean.True if al tasks respect the SLA, else return FALSE
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the user task name or element id filter,
     *                                 functionRecord.parameters.get(1) is the SLA limit, an ISO-8601 duration (e.g. PT12S, PT3H)
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's user task history, page by page
     * @return Boolean.TRUE if every matching, completed user task's duration (completionDate - creationDate) is
     * within the limit, Boolean.FALSE as soon as one exceeds it. User tasks that have not completed yet
     * are ignored, since their final duration is not known.
     * @throws Exception if the limit parameter is missing or is not a valid ISO-8601 duration
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.size() < 2) {
            String message = "userTaskSLA() requires the task filter and the SLA limit (ISO-8601 duration)";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String taskIdFilter = functionRecord.parameters.getFirst();
        String limitParameter = functionRecord.parameters.get(1);
        Duration limit;
        try {
            limit = Duration.parse(limitParameter);
        } catch (DateTimeParseException e) {
            String message = "userTaskSLA() limit [" + limitParameter + "] is not a valid ISO-8601 duration";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.DATE_PARSING_ERROR, message);
        }

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        FilterElementId filter = new FilterElementId()
                .addElementIds(taskIdFilter == null || taskIdFilter.isBlank() ? null : List.of(taskIdFilter))
                .addType(ElementInstanceType.USER_TASK);
        List<ElementInstance> userTasks = searchElementInstances(camundaClient, processInstanceKey, filter);

        for (ElementInstance userTask : userTasks) {
            if (userTask.getStartDate() == null || userTask.getEndDate() == null) {
                continue;
            }
            Duration actual = Duration.between(userTask.getStartDate(), userTask.getEndDate());
            if (actual.compareTo(limit) > 0) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }
}
