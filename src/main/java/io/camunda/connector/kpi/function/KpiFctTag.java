package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KpiFctTag extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctTag.class);

    @Override
    public String getName() {
        return "tag";
    }

    @Override
    public String getLabel() {
        return "tag(tagName)";
    }

    @Override
    public String getExplanation() {
        return "Return tagName as-is: a simple, explicit way to attach a fixed label to the KPI record.";
    }

    /**
     * @param functionRecord           functionRecord.parameters.getFirst() is the tag name to return
     * @param outboundConnectorContext unused
     * @param camundaClient            unused
     * @return tagName as-is
     * @throws Exception if the tagName parameter is missing
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.isEmpty()) {
            String message = "tag() requires the tag name parameter";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        return functionRecord.parameters.getFirst();
    }
}
