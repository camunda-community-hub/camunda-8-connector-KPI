package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

public class KpiFctConstant extends KpiFct {

    public static final String NAME = "constant";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getLabel() {
        return "<value>";
    }

    @Override
    public String getExplanation() {
        return "Not a real function call: any KPI pilot value that is not \"name(params)\" is used as-is.";
    }

    /**
     * A constant carries no function call, just a literal value decoded straight from the KPI pilot descriptor.
     *
     * @param functionRecord           the decoded value; only functionRecord.value is used
     * @param outboundConnectorContext unused, a constant does not need to read the job/process context
     * @param camundaClient            unused, a constant does not need to query the cluster
     * @return functionRecord.value as-is
     * @throws Exception never thrown by this implementation
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        return functionRecord.value;
    }
}
