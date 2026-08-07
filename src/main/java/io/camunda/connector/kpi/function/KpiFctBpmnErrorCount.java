package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.Job;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

import java.util.List;

public class KpiFctBpmnErrorCount extends KpiFct {

    @Override
    public String getName() {
        return "bpmnErrorCount";
    }

    @Override
    public String getLabel() {
        return "bpmnErrorCount([errorCode])";
    }

    @Override
    public String getExplanation() {
        return "Count BPMN errors thrown in the process instance; if errorCode is given, count only jobs whose "
                + "thrown errorCode matches it.";
    }

    /**
     * Uses the job search API's getErrorCode(): a job's errorCode is the exact code passed to
     * CamundaError.bpmnError(errorCode, message) when it was thrown, recorded on the job regardless of which
     * boundary event (a specific errorRef, or a generic/catch-all one) ends up catching it - unlike an
     * ElementInstance, which has no error-code field at all.
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the errorCode filter
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's job history
     * @return the number of jobs that threw a BPMN error, matching errorCode when given
     * @throws Exception if the job search fails
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String errorCode = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        FilterJob filter = new FilterJob().addErrorCode(errorCode != null && !errorCode.isBlank() ? errorCode : null);

        List<Job> jobs = searchJob(camundaClient, processInstanceKey, filter);

        return jobs.stream()
                .filter(job -> job.getErrorCode() != null && !job.getErrorCode().isBlank())
                .count();
    }
}
