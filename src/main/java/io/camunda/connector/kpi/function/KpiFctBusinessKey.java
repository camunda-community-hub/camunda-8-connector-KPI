package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

public class KpiFctBusinessKey extends KpiFct {

    @Override
    public String getName() {
        return "businessKey";
    }

    @Override
    public String getLabel() {
        return "businessKey()";
    }

    @Override
    public String getExplanation() {
        return "Return the current process instance's business ID (Zeebe's native businessId field, set via "
                + "newCreateInstanceCommand().businessId(...)).";
    }

    /**
     * @param functionRecord           unused, no parameters
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to fetch the process instance and its businessId
     * @return the process instance's businessId, or null if it is not set
     * @throws Exception never thrown by this implementation
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        return camundaClient.newProcessInstanceGetRequest(processInstanceKey).send().join().getBusinessId();
    }
}
