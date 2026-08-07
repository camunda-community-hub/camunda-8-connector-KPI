package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class KpiFct {

    /**
     * Page size used by every subclass that pages through a process instance's history (element instances,
     * user tasks...) so a single query never artificially caps how much history gets scanned.
     */
    protected static final int PAGE_SIZE = 100;
    private static final Logger logger = LoggerFactory.getLogger(KpiFct.class);

    /**
     * Fetch the user tasks of a process instance, paging PAGE_SIZE items at a time until the whole
     * matching history has been scanned (there is no artificial cap on the total number of user tasks).
     * The name filter is applied server-side, so a process with a lot of history does not need to be
     * fetched in full just to keep a handful of matching tasks.
     *
     * @param camundaClient      used to search the user task history
     * @param processInstanceKey the process instance whose user tasks are fetched
     * @param userTaskIdFilder   a user task name to filter on server-side, or null/blank to fetch every user task
     * @return the matching user tasks of the process instance, in the order the search API returns them
     */
    protected static List<UserTask> searchAllUserTasks(CamundaClient camundaClient, long processInstanceKey, String userTaskIdFilder) {
        List<UserTask> userTasks = new ArrayList<>();
        String afterCursor = null;
        boolean historyExhausted = false;

        while (!historyExhausted) {
            String cursor = afterCursor;
            SearchResponse<UserTask> response = camundaClient.newUserTaskSearchRequest()
                    .filter(f -> {
                        f.processInstanceKey(processInstanceKey);
                        if (userTaskIdFilder != null && !userTaskIdFilder.isBlank()) {
                            f.elementId(userTaskIdFilder);
                        }
                    })
                    .page(p -> {
                        p.limit(PAGE_SIZE);
                        if (cursor != null) {
                            p.after(cursor);
                        }
                    })
                    .send()
                    .join();

            userTasks.addAll(response.items());
            afterCursor = response.page().endCursor();
            historyExhausted = afterCursor == null || response.items().size() < PAGE_SIZE;
        }
        return userTasks;
    }

    /**
     * Fetch the element instances of a process instance matching filter, paging PAGE_SIZE items at a
     * time until the whole matching history has been scanned (there is no artificial cap on the total
     * number of element instances).
     * <p>
     * filter.type, when set, is applied server-side (e.g. only USER_TASK elements) - this is the main
     * saving, since it lets the search skip every non-matching element entirely. filter.names is only
     * pushed server-side when it holds a single entry (the search API's elementId filter accepts one
     * exact value, not a list); with two or more names (e.g. KpiFctDuration's from/to elements, or
     * KpiFctPath's list of transitions) it is instead matched client-side, page by page, against each
     * element's name or id.
     *
     * @param camundaClient      used to search the element instance history
     * @param processInstanceKey the process instance whose element instances are fetched
     * @param filter             names/type to filter on; null, or a filter with no names and no type, matches every element
     * @return the matching element instances of the process instance, in the order the search API returns them
     */
    protected static List<ElementInstance> searchElementInstances(CamundaClient camundaClient, long processInstanceKey, FilterElementId filter) {
        List<ElementInstance> elementInstances = new ArrayList<>();
        String afterCursor = null;
        boolean historyExhausted = false;
        String singleElementId = filter != null && filter.elementIds != null && filter.elementIds.size() == 1 ? filter.elementIds.getFirst() : null;

        while (!historyExhausted) {
            String cursor = afterCursor;
            SearchResponse<ElementInstance> response = camundaClient.newElementInstanceSearchRequest()
                    .filter(f -> {
                        f.processInstanceKey(processInstanceKey);
                        if (filter != null && filter.type != null) {
                            f.type(filter.type);
                        }
                        if (singleElementId != null) {
                            f.elementId(singleElementId);
                        }
                    })
                    .page(p -> {
                        p.limit(PAGE_SIZE);
                        if (cursor != null) {
                            p.after(cursor);
                        }
                    })
                    .send()
                    .join();

            for (ElementInstance elementInstance : response.items()) {
                if (matchesFilterElementId(elementInstance, filter)) {
                    elementInstances.add(elementInstance);
                }
            }

            afterCursor = response.page().endCursor();
            historyExhausted = afterCursor == null || response.items().size() < PAGE_SIZE;
        }
        return elementInstances;
    }

    private static boolean matchesFilterElementId(ElementInstance elementInstance, FilterElementId filter) {
        if (filter == null || filter.elementIds == null || filter.elementIds.isEmpty()) {
            return true;
        }
        return filter.elementIds.contains(elementInstance.getElementId()) || filter.elementIds.contains(elementInstance.getElementId());
    }

    /**
     * Fetch the jobs of a process instance matching filter, paging PAGE_SIZE items at a time until the whole
     * matching history has been scanned. A job's getErrorCode()/getErrorMessage() record the exact BPMN error
     * thrown via CamundaError.bpmnError(errorCode, message) - unlike an element instance, which has no error-code
     * field at all, this is the actual runtime-recorded value, independent of which boundary event (specific or
     * generic/catch-all) ends up catching it.
     *
     * @param camundaClient      used to search the job history
     * @param processInstanceKey the process instance whose jobs are fetched
     * @param filter             errorCode/elementId to filter on server-side; null, or a filter with neither set, matches every job
     * @return the matching jobs of the process instance, in the order the search API returns them
     */
    protected static List<Job> searchJob(CamundaClient camundaClient, long processInstanceKey, FilterJob filter) {
        List<Job> jobs = new ArrayList<>();
        String afterCursor = null;
        boolean historyExhausted = false;

        while (!historyExhausted) {
            String cursor = afterCursor;
            SearchResponse<Job> response = camundaClient.newJobSearchRequest()
                    .filter(f -> {
                        f.processInstanceKey(processInstanceKey);
                        if (filter != null && filter.errorCode != null && !filter.errorCode.isBlank()) {
                            f.errorCode(filter.errorCode);
                        }
                        if (filter != null && filter.elementId != null && !filter.elementId.isBlank()) {
                            f.elementId(filter.elementId);
                        }
                    })
                    .page(p -> {
                        p.limit(PAGE_SIZE);
                        if (cursor != null) {
                            p.after(cursor);
                        }
                    })
                    .send()
                    .join();

            jobs.addAll(response.items());
            afterCursor = response.page().endCursor();
            historyExhausted = afterCursor == null || response.items().size() < PAGE_SIZE;
        }
        return jobs;
    }

    /**
     * Read the current job's process variables as a Map. JobContext only exposes variables as a JSON
     * string (there is no getVariablesAsMap() on JobContext, in 8.8 or 8.9 - that method only exists on
     * the job-worker SDK's ActivatedJob, a different class), so this parses that JSON with Jackson.
     *
     * @param outboundConnectorContext the connector execution context
     * @return the current job's variables
     * @throws ConnectorException if the variables JSON cannot be parsed
     */
    protected static Map<String, Object> getVariablesAsMap(OutboundConnectorContext outboundConnectorContext) throws ConnectorException {
        try {
            return (Map<String, Object>) outboundConnectorContext.bindVariables(Map.class);
        } catch (Exception e) {
            String message = "Cannot parse process variables as JSON: " + e.getMessage();
            logger.error(message, e);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }
    }

    /**
     * @return the function name, as used in the KPI pilot descriptor "name(param1, param2, ...)"
     */
    public abstract String getName();

    /**
     * @return the short calling syntax of this function, e.g. "tag(tagName)" - collected by
     * KpiFctFactory.getFctLabels() to self-document the KPI pilot parameter
     */
    public abstract String getLabel();

    /**
     * @return a short, human-readable explanation of what this function computes and its parameters
     */
    public abstract String getExplanation();

    /**
     * Compute the value of this KPI function.
     *
     * @param functionRecord           the decoded function call (name and parameters), or the raw value when it is a constant
     * @param outboundConnectorContext the connector execution context, giving access to the current job and its process variables
     * @param camundaClient            client used to query the Camunda 8 cluster (history, variables, incidents...) when the function needs it
     * @return the computed KPI value
     * @throws Exception if the function cannot be evaluated, e.g. a referenced element or variable does not exist
     */
    public abstract Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception;

    public String getSignature(FunctionRecord functionRecord) {
        String parameters = functionRecord.parameters == null ? "" : String.join(",", functionRecord.parameters);
        return getName() + " : " + parameters;
    }

    public static class FilterElementId {

        public List<String> elementIds = null;
        public ElementInstanceType type = null;

        public FilterElementId() {
        }

        public FilterElementId addElementIds(List<String> elementIds) {
            this.elementIds = elementIds;
            return this;
        }

        public FilterElementId addType(ElementInstanceType type) {
            this.type = type;
            return this;
        }

    }

    /**
     * Filter for searchJob(): errorCode and elementId are each applied server-side (JobFilter.errorCode(...)/
     * elementId(...) both accept a single exact value, not a list) - either, both, or neither may be set.
     */
    public static class FilterJob {

        public String errorCode = null;
        public String elementId = null;

        public FilterJob() {
        }

        public FilterJob addErrorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public FilterJob addElementId(String elementId) {
            this.elementId = elementId;
            return this;
        }
    }
}
