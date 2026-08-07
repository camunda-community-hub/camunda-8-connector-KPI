package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.DecisionInstance;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;

public class KpiFctDecisionResult extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctDecisionResult.class);

    @Override
    public String getName() {
        return "decisionResult";
    }

    @Override
    public String getLabel() {
        return "decisionResult(decisionId)";
    }

    @Override
    public String getExplanation() {
        return "Return the output (as a JSON string) of the most recent evaluation of decision decisionId in this process instance.";
    }

    /**
     * @param functionRecord           functionRecord.parameters.getFirst() is the DMN decision id (decisionDefinitionId)
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's decision evaluation history
     * @return the result (JSON string) of the most recent evaluation of decisionId, or null if it was never evaluated in this instance
     * @throws Exception if the decisionId parameter is missing
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.isEmpty()) {
            String message = "decisionResult() requires the decisionId parameter";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String decisionId = functionRecord.parameters.getFirst();
        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();

        return camundaClient.newDecisionInstanceSearchRequest()
                .filter(f -> {
                    f.processInstanceKey(processInstanceKey);
                    f.decisionDefinitionId(decisionId);
                })
                .page(p -> p.limit(1000))
                .send()
                .join()
                .items()
                .stream()
                .max(Comparator.comparing(DecisionInstance::getEvaluationDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(DecisionInstance::getResult)
                .orElse(null);
    }
}
