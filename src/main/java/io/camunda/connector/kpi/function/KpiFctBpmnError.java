package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.Job;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

import java.util.Comparator;
import java.util.List;

public class KpiFctBpmnError extends KpiFct {

    @Override
    public String getName() {
        return "bpmnError";
    }

    @Override
    public String getLabel() {
        return "bpmnError(elementId)";
    }

    @Override
    public String getExplanation() {
        return "Return the errorCode of the most recent BPMN error thrown by elementId (the job-backed task or "
                + "event that threw it, not the catching boundary event); if elementId is omitted, the most recent "
                + "BPMN error thrown anywhere in the process instance.";
    }

    /**
     * Uses the job search API's getErrorCode(): the exact code passed to CamundaError.bpmnError(errorCode, message)
     * when the job was thrown, recorded on the job itself regardless of which boundary event (a specific errorRef,
     * or a generic/catch-all one) ends up catching it - unlike an ElementInstance, which has no error-code field.
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the element id that threw the error
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's job history
     * @return the errorCode of the matching, most recently thrown BPMN error, or null if none was thrown
     * @throws Exception if the job search fails
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String elementFilter = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        FilterJob filter = new FilterJob()
                .addElementId(elementFilter != null && !elementFilter.isBlank() ? elementFilter : null);

        List<Job> jobs = searchJob(camundaClient, processInstanceKey, filter);

        return jobs.stream()
                .filter(job -> job.getErrorCode() != null && !job.getErrorCode().isBlank())
                .max(Comparator.comparing(Job::getLastUpdateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Job::getErrorCode)
                .orElse(null);
    }
}
