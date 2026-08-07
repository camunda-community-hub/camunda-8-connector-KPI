package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

public class KpiFctProcessInstanceKey extends KpiFct {

    @Override
    public String getName() {
        return "processInstanceKey";
    }

    @Override
    public String getLabel() {
        return "processInstanceKey()";
    }

    @Override
    public String getExplanation() {
        return "Return the current job's process instance key.";
    }

    /**
     * @param functionRecord           unused, no parameters
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            unused
     * @return the current process instance key
     * @throws Exception never thrown by this implementation
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        return outboundConnectorContext.getJobContext().getProcessInstanceKey();
    }
}
