package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.ProcessInstanceSequenceFlow;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class KpiFctHistory extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctHistory.class);

    private static final String RESULT_TYPE_STRING = "STRING";
    private static final String RESULT_TYPE_JSON = "JSON";
    private static final String TYPE_FILTER_EVENT = "EVENT";
    private static final String TYPE_FILTER_TASK = "TASK";
    private static final String TYPE_FILTER_ACTIVITY = "ACTIVITY";
    private static final String TYPE_FILTER_SEQUENCE = "SEQUENCE";
    private static final String TYPE_FILTER_GATEWAY = "GATEWAY";
    private static final String TYPE_FILTER_ALL = "ALL";
    private static final String TOPIC_ALL = "ALL";
    private static final int DEFAULT_SIZE = 100;
    private static final long DEFAULT_WAIT_MS = 0;

    private static final String PARAM_RESULT_TYPE = "resulttype";
    private static final String PARAM_TOPICS = "topics";
    private static final String PARAM_ACTIVITY_FILTER = "activityfilter";
    private static final String PARAM_SIZE = "size";
    private static final String PARAM_WAIT_MS = "waitms";

    private static final Set<String> KNOWN_PARAMETER_NAMES = Set.of(
            PARAM_RESULT_TYPE, PARAM_TOPICS, PARAM_ACTIVITY_FILTER, PARAM_SIZE, PARAM_WAIT_MS);

    /* "GATEWAY" is a convenience filter value covering every ElementInstanceType that represents a BPMN gateway,
     * since a caller typically wants "any gateway", not one specific kind. */
    private static final Set<ElementInstanceType> GATEWAY_ELEMENT_TYPES = EnumSet.of(
            ElementInstanceType.EXCLUSIVE_GATEWAY,
            ElementInstanceType.PARALLEL_GATEWAY,
            ElementInstanceType.INCLUSIVE_GATEWAY,
            ElementInstanceType.EVENT_BASED_GATEWAY);

    /* "EVENT" is a convenience filter value covering every ElementInstanceType that represents a BPMN event,
     * since a caller typically wants "any event", not one specific kind. */
    private static final Set<ElementInstanceType> EVENT_ELEMENT_TYPES = EnumSet.of(
            ElementInstanceType.START_EVENT,
            ElementInstanceType.INTERMEDIATE_CATCH_EVENT,
            ElementInstanceType.INTERMEDIATE_THROW_EVENT,
            ElementInstanceType.BOUNDARY_EVENT,
            ElementInstanceType.END_EVENT,
            ElementInstanceType.EVENT_SUB_PROCESS);

    /* "TASK" is a convenience filter value covering every ElementInstanceType that represents a task,
     * since a caller typically wants "any task", not one specific kind. */
    private static final Set<ElementInstanceType> TASK_ELEMENT_TYPES = EnumSet.of(
            ElementInstanceType.TASK,
            ElementInstanceType.SERVICE_TASK,
            ElementInstanceType.USER_TASK,
            ElementInstanceType.MANUAL_TASK,
            ElementInstanceType.RECEIVE_TASK,
            ElementInstanceType.SEND_TASK,
            ElementInstanceType.SCRIPT_TASK,
            ElementInstanceType.BUSINESS_RULE_TASK);

    /* "ACTIVITY" is a convenience filter value covering every task plus the elements that contain sub-flows,
     * since a caller typically wants "any activity", not one specific kind. It does not include CALL_ACTIVITY
     * itself - the history of what a call activity invokes is fetched separately (see fetchFullHistory()) and
     * is reported through the type of whatever ran inside the called process instance. */
    private static final Set<ElementInstanceType> ACTIVITY_ELEMENT_TYPES = EnumSet.of(
            ElementInstanceType.TASK,
            ElementInstanceType.SERVICE_TASK,
            ElementInstanceType.USER_TASK,
            ElementInstanceType.MANUAL_TASK,
            ElementInstanceType.RECEIVE_TASK,
            ElementInstanceType.SEND_TASK,
            ElementInstanceType.SCRIPT_TASK,
            ElementInstanceType.BUSINESS_RULE_TASK,
            ElementInstanceType.SUB_PROCESS,
            ElementInstanceType.AD_HOC_SUB_PROCESS,
            ElementInstanceType.MULTI_INSTANCE_BODY);

    /* "ALL" is a convenience filter value equivalent to "ACTIVITY|EVENT|SEQUENCE" - every task, sub-flow
     * container, event and sequence flow (but still not CALL_ACTIVITY itself - see ACTIVITY_ELEMENT_TYPES).
     * Sequence flows are not an ElementInstanceType match (see includeSequenceFlows in TypeFilter) - "ALL"
     * requests them too, via the separate decodeTypeFilter() flag. */
    private static final Set<ElementInstanceType> ALL_ELEMENT_TYPES;

    static {
        Set<ElementInstanceType> allTypes = EnumSet.copyOf(ACTIVITY_ELEMENT_TYPES);
        allTypes.addAll(EVENT_ELEMENT_TYPES);
        ALL_ELEMENT_TYPES = allTypes;
    }

    /* Canonical, ordered list of every topic history() can report - "ALL" expands to exactly this list.
     * sourceRef/targetRef only ever have a value for a sequence flow entry (see resolveSequenceFlowTopic()). */
    private static final List<String> ALL_TOPICS = List.of(
            "elementId", "elementName", "elementInstanceKey", "sourceRef", "targetRef", "processInstanceKey",
            "processDefinitionId", "processDefinitionName", "processDefinitionVersion", "type", "state",
            "startDate", "endDate", "assignee", "hasIncident", "incidentKey", "tenantId");

    private static final Set<String> KNOWN_TOPICS = ALL_TOPICS.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public String getName() {
        return "history";
    }

    @Override
    public String getLabel() {
        return "history(resultType:JSON, topics:ALL, activityFilter:ALL, size:100, waitMs:0)";
    }

    @Override
    public String getExplanation() {
        return "Return the process instance's execution history as a list of activities, most recently started last - "
                + "including, recursively, the history of every process instance spawned by a call activity. Every "
                + "parameter is optional, given as name:value, in any order, separated by commas: resultType "
                + "(STRING - a comma-separated list of activities, each a |-separated \"topic:value\" list - or JSON, "
                + "a list of objects; default JSON), topics (a |-separated list among elementId, elementName, "
                + "elementInstanceKey, sourceRef, targetRef, processInstanceKey, processDefinitionId, "
                + "processDefinitionName, processDefinitionVersion, type, state, startDate, endDate, assignee, "
                + "hasIncident, incidentKey, tenantId, or ALL for every topic; default ALL), activityFilter (a "
                + "|-separated list of BpmnElementType names, or the special values EVENT/TASK/ACTIVITY/GATEWAY/"
                + "SEQUENCE/ALL; default ALL), size (caps the number of activities returned, keeping the earliest "
                + "ones; default 100), waitMs (milliseconds to wait, once, before any function in the pilot runs - "
                + "e.g. to let Zeebe's exporter catch up; default 0). A topic with no value for a given activity "
                + "(e.g. sourceRef/targetRef on anything but a sequence flow, or state on a sequence flow) is "
                + "omitted entirely rather than reported as null. SEQUENCE (included in ALL) reports sequence flows "
                + "taken, fetched through a separate API since they are not part of the element instance history; a "
                + "sequence flow only ever reports elementId, sourceRef, targetRef, processInstanceKey, "
                + "processDefinitionId/Name/Version, type (SEQUENCE_FLOW), tenantId and a startDate approximated "
                + "from its target element's start, and it is omitted if its target cannot be resolved or has not "
                + "started yet. When a sequence flow's approximated startDate ties with another activity's, the "
                + "flow is ordered after its source and before its target. Every parameter name, topic and "
                + "activityFilter value is matched case-insensitively.";
    }

    /**
     * @param functionRecord           functionRecord.parameters is a list of "name:value" strings (resultType, topics,
     *                                  activityFilter, size, waitMs - all optional, any order, matched case-insensitively)
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's element instance history (recursively,
     *                                  through every call activity) and, if the assignee topic is requested, user
     *                                  task history
     * @return a comma-separated String, or a List of Maps, depending on resultType - see getExplanation()
     * @throws ConnectorException if a parameter name, or the value of resultType/topics/activityFilter/size, is not recognized
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        Map<String, String> paramsByName = decodeParamsByName(functionRecord);

        String resultType = paramsByName.getOrDefault(PARAM_RESULT_TYPE, RESULT_TYPE_JSON).trim().toUpperCase();
        if (!RESULT_TYPE_STRING.equals(resultType) && !RESULT_TYPE_JSON.equals(resultType)) {
            String message = "history() resultType [" + resultType + "] is unknown, expected STRING or JSON";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }

        List<String> topics = decodeTopics(functionRecord, paramsByName.getOrDefault(PARAM_TOPICS, TOPIC_ALL));

        TypeFilter typeFilter = decodeTypeFilter(functionRecord, paramsByName.getOrDefault(PARAM_ACTIVITY_FILTER, TYPE_FILTER_ALL));

        int size = paramsByName.containsKey(PARAM_SIZE)
                ? decodeSize(functionRecord, paramsByName.get(PARAM_SIZE))
                : DEFAULT_SIZE;

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        List<ElementInstance> fullHistory = fetchFullHistory(camundaClient, processInstanceKey);

        List<ElementInstance> matchingElementInstances = fullHistory.stream()
                .filter(elementInstance -> typeFilter.elementTypes().contains(elementInstance.getType()))
                .filter(elementInstance -> elementInstance.getStartDate() != null)
                .toList();

        Set<Long> processInstanceKeys = fullHistory.stream().map(ElementInstance::getProcessInstanceKey).collect(Collectors.toSet());

        Map<Long, String> assigneeByElementInstanceKey = containsTopic(topics, "assignee")
                ? processInstanceKeys.stream()
                        .flatMap(instanceKey -> searchAllUserTasks(camundaClient, instanceKey, null).stream())
                        .filter(userTask -> userTask.getElementInstanceKey() != null && userTask.getAssignee() != null)
                        .collect(Collectors.toMap(UserTask::getElementInstanceKey, UserTask::getAssignee, (first, second) -> second))
                : Map.of();

        Map<Long, ProcessInstance> processInstanceByKey = containsTopic(topics, "processdefinitionname") || containsTopic(topics, "processdefinitionversion")
                ? processInstanceKeys.stream()
                        .map(instanceKey -> searchProcessInstance(camundaClient, instanceKey))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(ProcessInstance::getProcessInstanceKey, processInstance -> processInstance))
                : Map.of();

        List<HistoryEntry> entries = new ArrayList<>();
        for (ElementInstance elementInstance : matchingElementInstances) {
            entries.add(HistoryEntry.forElementInstance(elementInstance));
        }
        if (typeFilter.includeSequenceFlows()) {
            entries.addAll(fetchSequenceFlowEntries(camundaClient, processInstanceKeys, fullHistory));
        }

        // A plain date comparison can't express "this flow belongs between its source and target" on its own -
        // that needs the topology (see rankByTopology()) - but once every entry has a rank, ordering really is
        // just one comparator: by date, then by rank.
        Map<HistoryEntry, Integer> rank = rankByTopology(entries);
        Comparator<HistoryEntry> byDateThenTopology = Comparator.comparing(HistoryEntry::getStartDate).thenComparingInt(rank::get);

        List<Map<String, Object>> activities = entries.stream()
                .sorted(byDateThenTopology)
                .limit(size)
                .map(entry -> entry.isSequenceFlow()
                        ? toSequenceFlowActivity(entry, topics, processInstanceByKey)
                        : toActivity(entry.elementInstance(), topics, assigneeByElementInstanceKey, processInstanceByKey))
                .toList();

        if (RESULT_TYPE_JSON.equals(resultType)) {
            return activities;
        }
        return activities.stream()
                .map(activity -> activity.entrySet().stream()
                        .map(entry -> entry.getKey() + ":" + entry.getValue())
                        .collect(Collectors.joining("|")))
                .collect(Collectors.joining(","));
    }

    /**
     * One row of the merged result, wrapping the single real object it came from - either an ElementInstance, or a
     * ProcessInstanceSequenceFlow together with the source/target ElementInstance it was resolved to (see
     * fetchSequenceFlowEntries()). Exactly one of elementInstance/sequenceFlow is set. getStartDate()/
     * getProcessInstanceKey() delegate to whichever is present - a sequence flow has no timestamp of its own, so
     * its startDate is its target's.
     */
    private record HistoryEntry(ElementInstance elementInstance, ProcessInstanceSequenceFlow sequenceFlow,
                                 ElementInstance sourceElementInstance, ElementInstance targetElementInstance) {

        static HistoryEntry forElementInstance(ElementInstance elementInstance) {
            return new HistoryEntry(elementInstance, null, null, null);
        }

        static HistoryEntry forSequenceFlow(ProcessInstanceSequenceFlow sequenceFlow, ElementInstance source, ElementInstance target) {
            return new HistoryEntry(null, sequenceFlow, source, target);
        }

        boolean isSequenceFlow() {
            return sequenceFlow != null;
        }

        OffsetDateTime getStartDate() {
            return elementInstance != null ? elementInstance.getStartDate() : targetElementInstance.getStartDate();
        }

        Long getProcessInstanceKey() {
            return elementInstance != null ? elementInstance.getProcessInstanceKey() : targetElementInstance.getProcessInstanceKey();
        }
    }

    /**
     * Assign every entry a rank such that sorting by (startDate, rank) puts a sequence flow after its source
     * activity and before its target activity, even across other unrelated entries sharing the same startDate -
     * a sequence flow's approximated startDate always ties with its target's (see fetchSequenceFlowEntries()),
     * sometimes also with its source's (e.g. a chain of automatic elements processed in the same instant), and a
     * flow may need to be threaded between activities several positions apart, not just its immediate neighbors.
     * Ranks only need to be locally meaningful within one identical-startDate group - resolved here via a
     * topological sort (Kahn's algorithm) per group, tie-broken by original position for unrelated entries.
     *
     * @param entries every entry to be sorted (any order)
     * @return each entry's rank, valid only for comparing entries that share the exact same startDate
     */
    private Map<HistoryEntry, Integer> rankByTopology(List<HistoryEntry> entries) {
        List<HistoryEntry> byDate = entries.stream().sorted(Comparator.comparing(HistoryEntry::getStartDate)).toList();
        Map<HistoryEntry, Integer> rank = new HashMap<>();
        int start = 0;
        while (start < byDate.size()) {
            int end = start;
            while (end + 1 < byDate.size() && byDate.get(end + 1).getStartDate().isEqual(byDate.get(start).getStartDate())) {
                end++;
            }
            List<HistoryEntry> ordered = topologicalSortGroup(byDate.subList(start, end + 1));
            for (int i = 0; i < ordered.size(); i++) {
                rank.put(ordered.get(i), i);
            }
            start = end + 1;
        }
        return rank;
    }

    /**
     * Topologically sort one same-startDate group: every sequence flow entry whose source and/or target activity
     * is also in this group must come after that source and before that target; entries with no such constraint
     * keep their relative position. Ties among equally-ready entries are broken by original position (Kahn's
     * algorithm, always picking the lowest-index ready node), so unrelated activities stay in their original order.
     * Matching is by elementInstanceKey - the source/target ElementInstance objects were already resolved to a
     * specific occurrence when the flow entry was built (see fetchSequenceFlowEntries()).
     *
     * @param group entries sharing one identical startDate, in their pre-sort (arbitrary) relative order
     * @return group, reordered to satisfy every source-before-flow-before-target constraint found within it
     */
    private List<HistoryEntry> topologicalSortGroup(List<HistoryEntry> group) {
        int size = group.size();
        Map<Long, Integer> indexByElementInstanceKey = new HashMap<>();
        for (int i = 0; i < size; i++) {
            HistoryEntry entry = group.get(i);
            if (!entry.isSequenceFlow()) {
                indexByElementInstanceKey.put(entry.elementInstance().getElementInstanceKey(), i);
            }
        }

        List<Set<Integer>> successors = new ArrayList<>(size);
        int[] inDegree = new int[size];
        for (int i = 0; i < size; i++) {
            successors.add(new HashSet<>());
        }
        for (int i = 0; i < size; i++) {
            HistoryEntry entry = group.get(i);
            if (!entry.isSequenceFlow()) {
                continue;
            }
            if (entry.sourceElementInstance() != null) {
                Integer sourceIndex = indexByElementInstanceKey.get(entry.sourceElementInstance().getElementInstanceKey());
                if (sourceIndex != null && successors.get(sourceIndex).add(i)) {
                    inDegree[i]++;
                }
            }
            Integer targetIndex = indexByElementInstanceKey.get(entry.targetElementInstance().getElementInstanceKey());
            if (targetIndex != null && successors.get(i).add(targetIndex)) {
                inDegree[targetIndex]++;
            }
        }

        boolean[] emitted = new boolean[size];
        List<HistoryEntry> ordered = new ArrayList<>(size);
        for (int emittedCount = 0; emittedCount < size; emittedCount++) {
            int next = -1;
            for (int i = 0; i < size; i++) {
                if (!emitted[i] && inDegree[i] == 0) {
                    next = i;
                    break;
                }
            }
            if (next == -1) {
                // A cycle would mean a flow's own target is (transitively) its own source within one instant -
                // not possible for a real BPMN model. Fall back to original order for whatever remains.
                for (int i = 0; i < size; i++) {
                    if (!emitted[i]) {
                        ordered.add(group.get(i));
                        emitted[i] = true;
                    }
                }
                break;
            }
            ordered.add(group.get(next));
            emitted[next] = true;
            for (int successor : successors.get(next)) {
                inDegree[successor]--;
            }
        }
        return ordered;
    }

    private boolean containsTopic(List<String> topics, String topic) {
        return topics.stream().anyMatch(topic::equalsIgnoreCase);
    }

    /**
     * @param functionRecord functionRecord.parameters is a list of "name:value" strings; only waitMs is consulted here
     * @return the waitMs parameter's value in milliseconds, or 0 if not given
     * @throws ConnectorException if a parameter name is unknown, or waitMs is not a non-negative integer
     */
    @Override
    public long peekWaitMs(FunctionRecord functionRecord) throws ConnectorException {
        Map<String, String> paramsByName = decodeParamsByName(functionRecord);
        return paramsByName.containsKey(PARAM_WAIT_MS)
                ? decodeWaitMs(functionRecord, paramsByName.get(PARAM_WAIT_MS))
                : DEFAULT_WAIT_MS;
    }

    /**
     * Decode functionRecord.parameters ("name:value" strings, e.g. "resultType:JSON") into a name -> value map,
     * matching names case-insensitively. Every name must be one of KNOWN_PARAMETER_NAMES.
     */
    private Map<String, String> decodeParamsByName(FunctionRecord functionRecord) throws ConnectorException {
        Map<String, String> paramsByName = new LinkedHashMap<>();
        if (functionRecord.parameters == null) {
            return paramsByName;
        }
        for (String rawParam : functionRecord.parameters) {
            int colon = rawParam.indexOf(':');
            if (colon <= 0) {
                String message = "history() parameter [" + rawParam + "] is not of the form name:value";
                logger.error("{}: {}", getSignature(functionRecord), message);
                throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
            }
            String name = rawParam.substring(0, colon).trim().toLowerCase();
            String value = rawParam.substring(colon + 1).trim();
            if (!KNOWN_PARAMETER_NAMES.contains(name)) {
                String message = "history() parameter name [" + rawParam.substring(0, colon).trim() + "] is unknown, expected one of "
                        + "resultType, topics, activityFilter, size, waitMs";
                logger.error("{}: {}", getSignature(functionRecord), message);
                throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
            }
            paramsByName.put(name, value);
        }
        return paramsByName;
    }

    /**
     * Fetch the full execution history of processInstanceKey, including - recursively - the history of every
     * process instance spawned by a CALL_ACTIVITY found along the way (a call activity that itself calls another
     * process, which calls another, etc.), since the goal is the process instance's complete history and a called
     * process instance's activities are part of that history.
     *
     * @param camundaClient      used to search element instances and, for each CALL_ACTIVITY found, the process
     *                           instance it spawned
     * @param processInstanceKey the process instance whose history (including nested called instances) is fetched
     * @return every element instance reached, flattened into a single list (not sorted)
     */
    private List<ElementInstance> fetchFullHistory(CamundaClient camundaClient, long processInstanceKey) {
        List<ElementInstance> history = new ArrayList<>(searchElementInstances(camundaClient, processInstanceKey, null));

        for (ElementInstance elementInstance : List.copyOf(history)) {
            if (elementInstance.getType() != ElementInstanceType.CALL_ACTIVITY) {
                continue;
            }
            for (Long calledProcessInstanceKey : searchCalledProcessInstanceKeys(camundaClient, elementInstance.getElementInstanceKey())) {
                history.addAll(fetchFullHistory(camundaClient, calledProcessInstanceKey));
            }
        }
        return history;
    }

    /**
     * Find the process instance(s) a CALL_ACTIVITY element instance spawned. A multi-instance call activity spawns
     * one process instance per occurrence, but each occurrence is its own element instance (with its own
     * elementInstanceKey), so this normally returns at most one process instance per call.
     *
     * @param camundaClient             used to search process instances
     * @param callActivityElementInstanceKey the calling CALL_ACTIVITY's element instance key
     * @return the process instance key(s) whose parentElementInstanceKey is callActivityElementInstanceKey
     */
    private List<Long> searchCalledProcessInstanceKeys(CamundaClient camundaClient, long callActivityElementInstanceKey) {
        List<Long> processInstanceKeys = new ArrayList<>();
        String afterCursor = null;
        boolean historyExhausted = false;

        while (!historyExhausted) {
            String cursor = afterCursor;
            SearchResponse<ProcessInstance> response = camundaClient.newProcessInstanceSearchRequest()
                    .filter(f -> f.parentElementInstanceKey(callActivityElementInstanceKey))
                    .page(p -> {
                        p.limit(PAGE_SIZE);
                        if (cursor != null) {
                            p.after(cursor);
                        }
                    })
                    .send()
                    .join();

            for (ProcessInstance processInstance : response.items()) {
                processInstanceKeys.add(processInstance.getProcessInstanceKey());
            }

            afterCursor = response.page().endCursor();
            historyExhausted = afterCursor == null || response.items().size() < PAGE_SIZE;
        }
        return processInstanceKeys;
    }

    /**
     * Look up the ProcessInstance record for processInstanceKey - needed for processDefinitionName/Version,
     * which ElementInstance does not expose (unlike processDefinitionKey/processDefinitionId, which it does).
     *
     * @param camundaClient      used to search process instances
     * @param processInstanceKey the process instance to look up
     * @return the matching ProcessInstance, or null if none is found (should not normally happen)
     */
    private ProcessInstance searchProcessInstance(CamundaClient camundaClient, long processInstanceKey) {
        SearchResponse<ProcessInstance> response = camundaClient.newProcessInstanceSearchRequest()
                .filter(f -> f.processInstanceKey(processInstanceKey))
                .page(p -> p.limit(1))
                .send()
                .join();
        return response.items().isEmpty() ? null : response.items().getFirst();
    }

    /**
     * Fetch the sequence flows taken across every process instance in processInstanceKeys (the root instance and
     * any nested ones reached through a call activity), and turn each into a HistoryEntry - the dedicated sequence
     * flows API (ProcessInstanceSequenceFlow) carries no source/target/timestamp of its own, so this resolves both:
     * the source/target element ids by parsing the process definition's BPMN XML for that flow's element id, then
     * the actual ElementInstance each refers to (target: its earliest occurrence, since that's closest to the
     * moment this flow was taken; source: its latest occurrence at or before the target started, to pick the
     * right iteration in a loop). A flow whose target can't be resolved (BPMN parse issue, or hasn't started yet)
     * is dropped, since it cannot be honestly placed in a startDate-ordered result.
     *
     * @param camundaClient       used to fetch sequence flows and, per distinct process definition, its BPMN XML
     * @param processInstanceKeys every process instance (root + nested) whose taken sequence flows are fetched
     * @param fullHistory         the full (unfiltered) element instance history, used to resolve each flow's source/target
     * @return one HistoryEntry per sequence flow whose target could be resolved
     */
    private List<HistoryEntry> fetchSequenceFlowEntries(CamundaClient camundaClient, Set<Long> processInstanceKeys, List<ElementInstance> fullHistory) {
        List<HistoryEntry> entries = new ArrayList<>();
        Map<Long, Map<String, SequenceFlowRefs>> topologyByProcessDefinitionKey = new HashMap<>();

        for (Long instanceKey : processInstanceKeys) {
            for (ProcessInstanceSequenceFlow flow : camundaClient.newProcessInstanceSequenceFlowsRequest(instanceKey).send().join()) {
                Long flowProcessDefinitionKey = parseLongOrNull(flow.getProcessDefinitionKey());
                if (flowProcessDefinitionKey == null) {
                    continue;
                }
                Map<String, SequenceFlowRefs> topologyBySequenceFlowId = topologyByProcessDefinitionKey.computeIfAbsent(
                        flowProcessDefinitionKey, key -> fetchSequenceFlowTopology(camundaClient, key));
                SequenceFlowRefs refs = topologyBySequenceFlowId.get(flow.getElementId());
                if (refs == null || refs.targetRef() == null) {
                    continue;
                }

                ElementInstance target = fullHistory.stream()
                        .filter(elementInstance -> instanceKey.equals(elementInstance.getProcessInstanceKey()))
                        .filter(elementInstance -> refs.targetRef().equals(elementInstance.getElementId()))
                        .filter(elementInstance -> elementInstance.getStartDate() != null)
                        .min(Comparator.comparing(ElementInstance::getStartDate))
                        .orElse(null);
                if (target == null) {
                    continue;
                }

                ElementInstance source = refs.sourceRef() == null ? null : fullHistory.stream()
                        .filter(elementInstance -> instanceKey.equals(elementInstance.getProcessInstanceKey()))
                        .filter(elementInstance -> refs.sourceRef().equals(elementInstance.getElementId()))
                        .filter(elementInstance -> elementInstance.getStartDate() != null)
                        .filter(elementInstance -> !elementInstance.getStartDate().isAfter(target.getStartDate()))
                        .max(Comparator.comparing(ElementInstance::getStartDate))
                        .orElse(null);

                entries.add(HistoryEntry.forSequenceFlow(flow, source, target));
            }
        }
        return entries;
    }

    /**
     * A sequence flow's source and target element ids, parsed from its process definition's BPMN XML - the only
     * way to resolve either, since ProcessInstanceSequenceFlow exposes neither a source/target nor a timestamp.
     */
    private record SequenceFlowRefs(String sourceRef, String targetRef) {
    }

    /**
     * Parse processDefinitionKey's BPMN XML to map each sequence flow's own element id to its source/target refs.
     *
     * @param camundaClient        used to fetch the process definition's BPMN XML
     * @param processDefinitionKey the process definition whose sequence flow topology is resolved
     * @return sequence flow element id -> its source/target refs; empty if the XML could not be fetched or parsed
     */
    private Map<String, SequenceFlowRefs> fetchSequenceFlowTopology(CamundaClient camundaClient, long processDefinitionKey) {
        String xml;
        try {
            xml = camundaClient.newProcessDefinitionGetXmlRequest(processDefinitionKey).send().join();
        } catch (Exception e) {
            logger.warn("history(): could not fetch BPMN XML for process definition {} to resolve sequence flow topology: {}",
                    processDefinitionKey, e.getMessage());
            return Map.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xml)));

            Map<String, SequenceFlowRefs> topologyBySequenceFlowId = new HashMap<>();
            NodeList sequenceFlows = document.getElementsByTagNameNS("*", "sequenceFlow");
            for (int i = 0; i < sequenceFlows.getLength(); i++) {
                Element sequenceFlow = (Element) sequenceFlows.item(i);
                String id = sequenceFlow.getAttribute("id");
                String sourceRef = sequenceFlow.getAttribute("sourceRef");
                String targetRef = sequenceFlow.getAttribute("targetRef");
                if (!id.isEmpty()) {
                    topologyBySequenceFlowId.put(id, new SequenceFlowRefs(
                            sourceRef.isEmpty() ? null : sourceRef,
                            targetRef.isEmpty() ? null : targetRef));
                }
            }
            return topologyBySequenceFlowId;
        } catch (Exception e) {
            logger.warn("history(): could not parse BPMN XML for process definition {} to resolve sequence flow topology: {}",
                    processDefinitionKey, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> toSequenceFlowActivity(HistoryEntry entry, List<String> topics, Map<Long, ProcessInstance> processInstanceByKey) {
        Map<String, Object> activity = new LinkedHashMap<>();
        for (String topic : topics) {
            Object value = resolveSequenceFlowTopic(entry, topic, processInstanceByKey);
            if (value != null) {
                activity.put(topic, value);
            }
        }
        return activity;
    }

    /**
     * A sequence flow only ever reports elementId, sourceRef, targetRef, processInstanceKey,
     * processDefinitionId/Name/Version, type (always SEQUENCE_FLOW), tenantId and its approximated startDate -
     * elementName, elementInstanceKey, endDate, state, assignee, hasIncident and incidentKey are all null (and so
     * omitted by toSequenceFlowActivity()), since the sequence flows API exposes none of them.
     */
    private Object resolveSequenceFlowTopic(HistoryEntry entry, String topic, Map<Long, ProcessInstance> processInstanceByKey) {
        ProcessInstanceSequenceFlow flow = entry.sequenceFlow();
        return switch (topic.toLowerCase()) {
            case "elementid" -> flow.getElementId();
            case "sourceref" -> entry.sourceElementInstance() == null ? null : entry.sourceElementInstance().getElementId();
            case "targetref" -> entry.targetElementInstance().getElementId();
            case "processinstancekey" -> entry.getProcessInstanceKey();
            case "processdefinitionid" -> flow.getProcessDefinitionId();
            case "processdefinitionname" -> {
                ProcessInstance processInstance = processInstanceByKey.get(entry.getProcessInstanceKey());
                yield processInstance == null ? null : processInstance.getProcessDefinitionName();
            }
            case "processdefinitionversion" -> {
                ProcessInstance processInstance = processInstanceByKey.get(entry.getProcessInstanceKey());
                yield processInstance == null ? null : processInstance.getProcessDefinitionVersion();
            }
            case "type" -> ElementInstanceType.SEQUENCE_FLOW.name();
            case "startdate" -> entry.getStartDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            case "tenantid" -> flow.getTenantId();
            default -> null;
        };
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> decodeTopics(FunctionRecord functionRecord, String rawTopicsParam) throws ConnectorException {
        List<String> rawTopics = Arrays.stream(rawTopicsParam.split("\\|"))
                .map(String::trim)
                .filter(topic -> !topic.isEmpty())
                .toList();
        if (rawTopics.isEmpty()) {
            String message = "history() requires at least one topic";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }

        List<String> topics = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawTopic : rawTopics) {
            if (TOPIC_ALL.equalsIgnoreCase(rawTopic)) {
                for (String topic : ALL_TOPICS) {
                    if (seen.add(topic.toLowerCase())) {
                        topics.add(topic);
                    }
                }
                continue;
            }
            if (!KNOWN_TOPICS.contains(rawTopic.toLowerCase())) {
                String message = "history() topic [" + rawTopic + "] is unknown, expected one of " + KNOWN_TOPICS + ", or ALL";
                logger.error("{}: {}", getSignature(functionRecord), message);
                throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
            }
            if (seen.add(rawTopic.toLowerCase())) {
                topics.add(rawTopic);
            }
        }
        return topics;
    }

    /**
     * elementTypes drives the ElementInstance-based filter (see fetchFullHistory()); includeSequenceFlows is a
     * separate flag since sequence flows are never returned by the element-instance search API at all - they
     * are fetched through a dedicated API (see fetchSequenceFlowEntries()).
     */
    private record TypeFilter(Set<ElementInstanceType> elementTypes, boolean includeSequenceFlows) {
    }

    private TypeFilter decodeTypeFilter(FunctionRecord functionRecord, String rawFilter) throws ConnectorException {
        Set<ElementInstanceType> types = EnumSet.noneOf(ElementInstanceType.class);
        boolean includeSequenceFlows = false;
        for (String rawType : rawFilter.split("\\|")) {
            String type = rawType.trim().toUpperCase();
            if (type.isEmpty()) {
                continue;
            }
            if (TYPE_FILTER_EVENT.equals(type)) {
                types.addAll(EVENT_ELEMENT_TYPES);
                continue;
            }
            if (TYPE_FILTER_TASK.equals(type)) {
                types.addAll(TASK_ELEMENT_TYPES);
                continue;
            }
            if (TYPE_FILTER_ACTIVITY.equals(type)) {
                types.addAll(ACTIVITY_ELEMENT_TYPES);
                continue;
            }
            if (TYPE_FILTER_SEQUENCE.equals(type)) {
                includeSequenceFlows = true;
                continue;
            }
            if (TYPE_FILTER_GATEWAY.equals(type)) {
                types.addAll(GATEWAY_ELEMENT_TYPES);
                continue;
            }
            if (TYPE_FILTER_ALL.equals(type)) {
                types.addAll(ALL_ELEMENT_TYPES);
                includeSequenceFlows = true;
                continue;
            }
            try {
                types.add(ElementInstanceType.valueOf(type));
            } catch (IllegalArgumentException e) {
                String message = "history() activity filter [" + type + "] is unknown";
                logger.error("{}: {}", getSignature(functionRecord), message);
                throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
            }
        }
        if (types.isEmpty() && !includeSequenceFlows) {
            String message = "history() requires at least one activityFilter value";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        return new TypeFilter(types, includeSequenceFlows);
    }

    private int decodeSize(FunctionRecord functionRecord, String rawSize) throws ConnectorException {
        try {
            int size = Integer.parseInt(rawSize.trim());
            if (size <= 0) {
                throw new NumberFormatException("size must be positive");
            }
            return size;
        } catch (NumberFormatException e) {
            String message = "history() size [" + rawSize + "] is not a positive integer";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }
    }

    private long decodeWaitMs(FunctionRecord functionRecord, String rawWaitMs) throws ConnectorException {
        try {
            long waitMs = Long.parseLong(rawWaitMs.trim());
            if (waitMs < 0) {
                throw new NumberFormatException("waitMs must not be negative");
            }
            return waitMs;
        } catch (NumberFormatException e) {
            String message = "history() waitMs [" + rawWaitMs + "] is not a non-negative integer";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }
    }

    private Map<String, Object> toActivity(ElementInstance elementInstance, List<String> topics, Map<Long, String> assigneeByElementInstanceKey,
                                            Map<Long, ProcessInstance> processInstanceByKey) {
        Map<String, Object> activity = new LinkedHashMap<>();
        for (String topic : topics) {
            Object value = resolveTopic(elementInstance, topic, assigneeByElementInstanceKey, processInstanceByKey);
            if (value != null) {
                activity.put(topic, value);
            }
        }
        return activity;
    }

    private Object resolveTopic(ElementInstance elementInstance, String topic, Map<Long, String> assigneeByElementInstanceKey,
                                 Map<Long, ProcessInstance> processInstanceByKey) {
        return switch (topic.toLowerCase()) {
            case "elementid" -> elementInstance.getElementId();
            case "elementname" -> elementInstance.getElementName();
            case "elementinstancekey" -> elementInstance.getElementInstanceKey();
            case "processinstancekey" -> elementInstance.getProcessInstanceKey();
            case "processdefinitionid" -> elementInstance.getProcessDefinitionId();
            case "processdefinitionname" -> {
                ProcessInstance processInstance = processInstanceByKey.get(elementInstance.getProcessInstanceKey());
                yield processInstance == null ? null : processInstance.getProcessDefinitionName();
            }
            case "processdefinitionversion" -> {
                ProcessInstance processInstance = processInstanceByKey.get(elementInstance.getProcessInstanceKey());
                yield processInstance == null ? null : processInstance.getProcessDefinitionVersion();
            }
            case "type" -> elementInstance.getType() == null ? null : elementInstance.getType().name();
            case "state" -> elementInstance.getState() == null ? null : elementInstance.getState().name();
            case "startdate" -> elementInstance.getStartDate() == null ? null : elementInstance.getStartDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            case "enddate" -> elementInstance.getEndDate() == null ? null : elementInstance.getEndDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            case "assignee" -> assigneeByElementInstanceKey.get(elementInstance.getElementInstanceKey());
            case "hasincident" -> elementInstance.getIncident();
            case "incidentkey" -> elementInstance.getIncidentKey();
            case "tenantid" -> elementInstance.getTenantId();
            default -> null;
        };
    }
}
