package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

public class KpiFctProcessVariable extends KpiFct {

    @Override
    public String getName() {
        return "variable";
    }

    @Override
    public String getLabel() {
        return "variable(variableName)";
    }

    @Override
    public String getExplanation() {
        return "Return the current value of process variable variableName.";
    }

    /**
     * The single parameter is the process variable name; its current value is read from the outboundConnectorContext.
     *
     * @param functionRecord           functionRecord.parameters.getFirst() is the process variable name to read
     * @param outboundConnectorContext used to read the current job's variables
     * @param camundaClient            unused, the value comes from the job context, not from a cluster query
     * @return the value of the process variable, or null if it is not set
     * @throws Exception if functionRecord.parameters is empty
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String variableName = functionRecord.parameters.getFirst();
        return getVariablesAsMap(outboundConnectorContext).get(variableName);
    }
}
