package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KpiFctProcessProperty extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctProcessProperty.class);

    private static final String PROCESS_DEFINITION = "processDefinition";
    private static final String PROCESS_DEFINITION_VERSION = "processDefinitionVersion";
    private static final String TENANT_ID = "tenantId";

    @Override
    public String getName() {
        return "processProperty";
    }

    @Override
    public String getLabel() {
        return "processProperty(processDefinition|processDefinitionVersion|tenantId)";
    }

    @Override
    public String getExplanation() {
        return "Return the current job's processDefinition (BPMN process id), processDefinitionVersion, or tenantId.";
    }

    /**
     * The single parameter selects which property of the current job to return.
     *
     * @param functionRecord           functionRecord.parameters.getFirst() is the property name: processDefinition, processDefinitionVersion or tenantId
     * @param outboundConnectorContext used to read the current job's context
     * @param camundaClient            unused, the value comes from the job context, not from a cluster query
     * @return the requested property: a String for processDefinition/tenantId, an int for processDefinitionVersion
     * @throws Exception if the property parameter is missing or is not one of the supported values
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.isEmpty()) {
            String message = "processProperty() requires the property name: processDefinition, processDefinitionVersion or tenantId";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String property = functionRecord.parameters.getFirst().trim();

        return switch (property) {
            case PROCESS_DEFINITION -> outboundConnectorContext.getJobContext().getBpmnProcessId();
            case PROCESS_DEFINITION_VERSION -> outboundConnectorContext.getJobContext().getProcessDefinitionVersion();
            case TENANT_ID -> outboundConnectorContext.getJobContext().getTenantId();
            default -> {
                String message = "processProperty() property [" + property + "] is unknown, expected processDefinition, processDefinitionVersion or tenantId";
                logger.error("{}: {}", getSignature(functionRecord), message);
                throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
            }
        };
    }
}
