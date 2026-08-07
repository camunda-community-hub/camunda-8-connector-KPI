package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Incident;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class KpiFctIncidentCount extends KpiFct {

    @Override
    public String getName() {
        return "incidentCount";
    }

    @Override
    public String getLabel() {
        return "incidentCount([elementId])";
    }

    @Override
    public String getExplanation() {
        return "Count incidents raised on the process instance; if elementId is given, count only incidents raised on that element.";
    }

    /**
     * The optional single parameter is an element name (or id) to restrict the count to. Without it,
     * every incident of the process instance is counted.
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the element name/id filter
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's incidents (and, when filtering by element, its element instance history)
     * @return the number of matching incidents
     * @throws Exception if the incident or element instance search fails
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String elementFilter = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        List<Incident> incidents = camundaClient.newIncidentsByProcessInstanceSearchRequest(processInstanceKey)
                .send()
                .join()
                .items();

        if (elementFilter == null || elementFilter.isBlank()) {
            return (long) incidents.size();
        }

        FilterElementId filter = new FilterElementId().addElementIds(List.of(elementFilter));
        Set<String> matchingElementIds = searchElementInstances(camundaClient, processInstanceKey, filter).stream()
                .map(ElementInstance::getElementId)
                .collect(Collectors.toSet());

        return incidents.stream()
                .filter(incident -> matchingElementIds.contains(incident.getElementId()))
                .count();
    }
}
