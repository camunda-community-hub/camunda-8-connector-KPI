package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class KpiFctRetryCount extends KpiFct {

    @Override
    public String getName() {
        return "retryCount";
    }

    @Override
    public String getLabel() {
        return "retryCount(elementId)";
    }

    @Override
    public String getExplanation() {
        return "Count how many extra times elementId's element instance was (re-)executed, beyond its first "
                + "execution; without elementId, the total extra executions summed across every element already executed.";
    }

    /**
     * There is no direct "retries" field on the element instance history: each (re-)activation of an element -
     * whether from a BPMN loop or a retry after an incident was resolved - creates its own ElementInstance record
     * sharing the same elementId. So the count of extra executions beyond the first is used as the retry count.
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the element name/id to count retries for
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's element instance history
     * @return the number of extra executions (0 if the element executed only once, or never)
     * @throws Exception if the element instance search fails
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String elementFilter = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();

        if (elementFilter != null && !elementFilter.isBlank()) {
            FilterElementId filter = new FilterElementId().addElementIds(List.of(elementFilter));
            long occurrences = searchElementInstances(camundaClient, processInstanceKey, filter).size();
            return Math.max(0, occurrences - 1);
        }

        List<ElementInstance> all = searchElementInstances(camundaClient, processInstanceKey, null);
        Map<String, Long> occurrencesByElementId = all.stream()
                .collect(Collectors.groupingBy(ElementInstance::getElementId, Collectors.counting()));

        return occurrencesByElementId.values().stream()
                .mapToLong(occurrences -> Math.max(0, occurrences - 1))
                .sum();
    }
}
