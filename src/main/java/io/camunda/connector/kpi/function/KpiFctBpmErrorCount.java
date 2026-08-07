package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

public class KpiFctBpmErrorCount extends KpiFct {

    @Override
    public String getName() {
        return "bpmErrorCount";
    }

    @Override
    public String getLabel() {
        return "bpmErrorCount(errorCode, elementId)";
    }

    @Override
    public String getExplanation() {
        return "Count BPMN errors caught by a boundary event matching errorCode and/or elementId; "
                + "with neither given, count every BPMN error caught anywhere in the process instance.";
    }

    /**
     * A caught BPMN error leaves no incident behind (that only happens for unhandled errors) - the only observable
     * trace is the boundary error event's own element instance. This convention ids each error-catching
     * boundary event after the BPMN error code it catches (e.g. id "BAD_AMOUNT"), so counting boundary event
     * instances whose name or id matches errorCode/elementId is how "how many times did this error occur" is
     * answered here.
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the error code filter;
     *                                 functionRecord.parameters.get(1), if present and not blank, is an element name/id filter
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's element instance history for BOUNDARY_EVENT elements
     * @return the number of matching boundary error event activations
     * @throws Exception if the element instance search fails
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String errorCode = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;
        String elementId = functionRecord.parameters != null && functionRecord.parameters.size() > 1
                ? functionRecord.parameters.get(1) : null;


        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        FilterJob filter = new FilterJob()
                .addErrorCode(errorCode)
                .addElementId(elementId);

        return (long) searchJob(camundaClient, processInstanceKey, filter).size();
    }
}
