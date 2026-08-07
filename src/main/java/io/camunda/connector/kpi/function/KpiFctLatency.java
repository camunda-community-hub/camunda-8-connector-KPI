package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public class KpiFctLatency extends KpiFct {
    private final Logger logger = LoggerFactory.getLogger(KpiFctLatency.class.getName());

    @Override
    public String getName() {
        return "latency";
    }

    @Override
    public String getLabel() {
        return "latency(elementId)";
    }

    @Override
    public String getExplanation() {
        return "Return, in milliseconds, the gap between elementId completing and whatever ran next starting "
                + "(queueing delay, distinct from processing duration); without elementId, the sum of every such gap in the instance.";
    }

    /**
     * Latency here means the gap between an element finishing and the next element (by start date) beginning -
     * i.e. the time the process instance spent NOT actively processing anything, as opposed to duration() which
     * measures an element's own start-to-end processing time.
     * <p>
     * With elementId: the gap right after that specific element completes.
     * Without it: the sum of every such gap across the whole instance (total queueing time).
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the element name/id to measure the gap after
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's element instance history
     * @return the latency in milliseconds (a long)
     * @throws Exception if elementId is given but does not exist, or has not completed yet, in the process instance history
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String elementFilter = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        List<ElementInstance> sortedByStart = searchElementInstances(camundaClient, processInstanceKey, null).stream()
                .filter(elementInstance -> elementInstance.getStartDate() != null)
                .sorted(Comparator.comparing(ElementInstance::getStartDate))
                .toList();

        if (elementFilter == null || elementFilter.isBlank()) {
            long totalMs = 0;
            for (int i = 0; i < sortedByStart.size() - 1; i++) {
                ElementInstance current = sortedByStart.get(i);
                ElementInstance next = sortedByStart.get(i + 1);
                if (current.getEndDate() == null) {
                    continue;
                }
                long gapMs = Duration.between(current.getEndDate(), next.getStartDate()).toMillis();
                if (gapMs > 0) {
                    totalMs += gapMs;
                }
            }
            return totalMs;
        }

        ElementInstance target = sortedByStart.stream()
                .filter(elementInstance -> elementFilter.equals(elementInstance.getElementId()))
                .findFirst()
                .orElse(null);

        if (target == null || target.getEndDate() == null) {
            logger.info("{}: No end target [{}] found", getSignature(functionRecord), elementFilter);
            return null;
        }

        return sortedByStart.stream()
                .filter(elementInstance -> !elementInstance.getElementInstanceKey().equals(target.getElementInstanceKey()))
                .filter(elementInstance -> !elementInstance.getStartDate().isBefore(target.getEndDate()))
                .min(Comparator.comparing(ElementInstance::getStartDate))
                .map(next -> Duration.between(target.getEndDate(), next.getStartDate()).toMillis())
                .orElse(0L);
    }
}
