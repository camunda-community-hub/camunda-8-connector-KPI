package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KpiFctVariableChangedCount extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctVariableChangedCount.class);

    @Override
    public String getName() {
        return "variableChangedCount";
    }

    @Override
    public String getLabel() {
        return "variableChangedCount(variableName)";
    }

    @Override
    public String getExplanation() {
        return "Return the number of distinct scopes that hold a value for variableName in this process instance "
                + "(an approximation of how many times it was set: the search API only exposes current values, not a change history).";
    }

    /**
     * The variable search API only exposes each variable's current value, scoped by scopeKey (the process
     * instance, or a sub-scope such as one iteration of a multi-instance body) - there is no per-update history
     * to count real value changes against. As a best-effort proxy, this counts how many scopes currently hold a
     * value for variableName, which at least reflects re-declarations across parallel/looped scopes.
     *
     * @param functionRecord           functionRecord.parameters.getFirst() is the process variable name
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's variables
     * @return the number of scopes holding a value for variableName
     * @throws Exception if the variableName parameter is missing
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.isEmpty()) {
            String message = getSignature(functionRecord) + " requires the variableName parameter";
            logger.error("{}", message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String variableName = functionRecord.parameters.getFirst();
        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();

        return (long) camundaClient.newVariableSearchRequest()
                .filter(f -> {
                    f.processInstanceKey(processInstanceKey);
                    f.name(variableName);
                })
                .page(p -> p.limit(10000))
                .send()
                .join()
                .items()
                .size();
    }
}
