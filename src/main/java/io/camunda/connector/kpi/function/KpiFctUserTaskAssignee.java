package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class KpiFctUserTaskAssignee extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctUserTaskAssignee.class);

    @Override
    public String getName() {
        return "userTaskAssignee";
    }

    @Override
    public String getLabel() {
        return "userTaskAssignee(taskFilter, mode)";
    }

    @Override
    public String getExplanation() {
        return "Return the assignee of the user task(s) matching taskFilter (blank = all). mode is FIRST (default), "
                + "LAST, or ALL (comma-joined).";
    }

    /**
     * First parameter is the user task name filter. if empty, all user task are studied.
     * Second parameter (optional): FIRST (default), LAST, ALL. If ALL, then all users are returned, separated by a comma.
     * All history are search. If a user task filter is provided, only user task with that ID is take into account.
     * According to the second filter, one or multiple name can be returned.
     * the user who executed the task is returned.
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the user task name or element id filter,
     *                                 functionRecord.parameters.get(1), if present, is the mode: FIRST (default), LAST or ALL
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's user task history, page by page
     * @return the assignee of the matching user task(s): the first one, the last one, or all of them joined by a comma
     * depending on the mode; null if no matching user task has an assignee
     * @throws Exception if the mode is not FIRST, LAST or ALL
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String taskFilter = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;
        String modeParameter = functionRecord.parameters != null && functionRecord.parameters.size() > 1
                ? functionRecord.parameters.get(1).trim() : "FIRST";
        Mode mode = Mode.fromString(modeParameter);
        if (mode == null) {
            String message = "userTaskAssignee() mode [" + modeParameter + "] is unknown, expected FIRST, LAST or ALL";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        List<UserTask> userTasks = searchAllUserTasks(camundaClient, processInstanceKey, taskFilter);

        List<String> assignees = userTasks.stream()
                .sorted(Comparator.comparing(UserTask::getCreationDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(UserTask::getAssignee)
                .filter(Objects::nonNull)
                .toList();

        if (assignees.isEmpty()) {
            return "";
        }
        return switch (mode) {
            case LAST -> assignees.getLast();
            case FIRST -> assignees.getFirst();
            case ALL -> String.join(",", assignees);
        };
    }

    private enum Mode {
        FIRST, LAST, ALL;

        static Mode fromString(String value) {
            return Arrays.stream(values())
                    .filter(mode -> mode.name().equalsIgnoreCase(value))
                    .findFirst()
                    .orElse(null);
        }
    }
}
