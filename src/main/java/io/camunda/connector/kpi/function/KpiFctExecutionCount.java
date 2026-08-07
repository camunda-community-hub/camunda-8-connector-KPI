package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class KpiFctExecutionCount extends KpiFct {
    private static final Logger logger = LoggerFactory.getLogger(KpiFctExecutionCount.class);


    @Override
    public String getName() {
        return "executionCount";
    }

    @Override
    public String getLabel() {
        return "executionCount(elementId)";
    }

    @Override
    public String getExplanation() {
        return "Count the number of time this element (activity, event,transition) was executed.";
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
        if (functionRecord.parameters == null || functionRecord.parameters.size() != 1) {
            String message = "executionCount() requires a element name";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }

        FilterElementId filter = new FilterElementId().addElementIds(List.of(functionRecord.parameters.getFirst()));
        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();

        List<ElementInstance> elementInstances = searchElementInstances(camundaClient, processInstanceKey, filter);
        return elementInstances.size();
    }


}
