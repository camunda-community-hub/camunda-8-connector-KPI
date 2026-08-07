package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KpiFctPath extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctPath.class);

    private static final String MODE_ANY = "ANY";
    private static final String MODE_ALL = "ALL";
    private static final String MODE_NOTHING = "NOTHING";

    @Override
    public String getName() {
        return "path";
    }

    @Override
    public String getLabel() {
        return "path(mode, transitionId, ...)";
    }

    @Override
    public String getExplanation() {
        return "Check which of the given transition names were executed. mode is ALL (every one), ANY (at least one) "
                + "or NOTHING (none of them).";
    }

    /**
     * At least two parameters expected.
     * First one is the mode: ANY, ALL or NOTHING.
     * Following ones are transition names to look for in the process instance history.
     *
     * @param functionRecord           functionRecord.parameters.getFirst() is the mode (ANY/ALL/NOTHING),
     *                                 the remaining parameters are the transition names to check
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's element instance history, page by page
     * @return Boolean.TRUE or Boolean.FALSE depending on the mode: ALL requires every transition to have been executed,
     * ANY requires at least one, NOTHING requires none of them
     * @throws Exception if the mode or the transition name parameters are missing, or the mode is not ANY/ALL/NOTHING
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.size() < 2) {
            String message = "path() requires a mode (ANY/ALL/NOTHING) followed by at least one transition name";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String mode = functionRecord.parameters.getFirst().trim().toUpperCase();
        if (!MODE_ANY.equals(mode) && !MODE_ALL.equals(mode) && !MODE_NOTHING.equals(mode)) {
            String message = "path() mode [" + mode + "] is unknown, expected ANY, ALL or NOTHING";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }

        Map<String, Boolean> transitionTag = new LinkedHashMap<>();
        for (int i = 1; i < functionRecord.parameters.size(); i++) {
            transitionTag.put(functionRecord.parameters.get(i), Boolean.FALSE);
        }

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        searchTransitions(camundaClient, processInstanceKey, transitionTag);

        return switch (mode) {
            case MODE_ALL -> transitionTag.values().stream().allMatch(Boolean.TRUE::equals);
            case MODE_ANY -> transitionTag.values().stream().anyMatch(Boolean.TRUE::equals);
            default -> transitionTag.values().stream().noneMatch(Boolean.TRUE::equals);
        };
    }

    /**
     * Search the process instance's element instance history (via KpiFct.searchElementInstances, filtered
     * server-side to just the requested transition names) and tag each transition found.
     *
     * @param camundaClient      used to search the element instance history
     * @param processInstanceKey the process instance whose history is scanned
     * @param transitionTag      map of transition name to Boolean, updated in place to TRUE when found
     */
    private void searchTransitions(CamundaClient camundaClient, long processInstanceKey, Map<String, Boolean> transitionTag) {
        FilterElementId filter = new FilterElementId().addElementIds(new ArrayList<>(transitionTag.keySet()));
        List<ElementInstance> elementInstances = searchElementInstances(camundaClient, processInstanceKey, filter);

        for (ElementInstance elementInstance : elementInstances) {
            if (transitionTag.containsKey(elementInstance.getElementId())) {
                transitionTag.put(elementInstance.getElementId(), Boolean.TRUE);
            }
        }
    }
}
