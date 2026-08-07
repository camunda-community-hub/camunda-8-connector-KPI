package io.camunda.connector.kpi.function;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Registry of all known KpiFct implementations.
 * No Spring/classpath scanning: new functions must be added by hand to KNOWN_FUNCTIONS.
 */
public class KpiFctFactory {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctFactory.class);

    private static final List<KpiFct> KNOWN_FUNCTIONS = List.of(
            new KpiFctConstant(),
            new KpiFctProcessVariable(),
            new KpiFctDuration(),
            new KpiFctPath(),
            new KpiFctThreshold(),
            new KpiFctUserTaskAssignee(),
            new KpiFctUserTaskSLA(),
            new KpiFctExecutionCount(),
            new KpiFctUserTaskSLADescription(),
            new KpiFctDate(),
            new KpiFctIncidentCount(),
            new KpiFctProcessProperty(),
            new KpiFctLatency(),
            new KpiFctRetryCount(),
            new KpiFctTag(),
            new KpiFctProcessInstanceKey(),
            new KpiFctBusinessKey(),
            new KpiFctVariableChangedCount(),
            new KpiFctBucket(),
            new KpiFctDecisionResult(),
            new KpiFctMessageCount(),
            new KpiFctBpmErrorCount(),
            new KpiFctBpmnError(),
            new KpiFctBpmnErrorCount());

    public static KpiFctFactory getInstance() {
        return new KpiFctFactory();
    }

    /**
     * @return every known function's getLabel(), separated by "," - used to self-document the KPI pilot parameter
     */
    public static String getFctLabels() {
        return KNOWN_FUNCTIONS.stream()
                .map(KpiFct::getLabel)
                .collect(Collectors.joining(","));
    }

    /**
     * Find the KpiFct matching functionRecord.name (a FunctionRecord with no name is a constant).
     *
     * @param functionRecord record to resolve
     * @return the matching KpiFct
     * @throws ConnectorException if no known function matches the name
     */
    public KpiFct getKpiFct(FunctionRecord functionRecord) throws ConnectorException {
        String functionName = functionRecord.name != null ? functionRecord.name : KpiFctConstant.NAME;

        for (KpiFct kpiFct : KNOWN_FUNCTIONS) {
            if (functionName.equalsIgnoreCase(kpiFct.getName())) {
                return kpiFct;
            }
        }
        String parameters = functionRecord.parameters == null ? "" : String.join(",", functionRecord.parameters);
        String message = "Function [" + functionName + "] is unknown";
        logger.error("{} : {}: {}", functionName, parameters, message);
        throw new ConnectorException(KpiError.UNKNOWN_FUNCTION, message);
    }
}
