package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class KpiFctBucket extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctBucket.class);

    @Override
    public String getName() {
        return "bucket";
    }

    @Override
    public String getLabel() {
        return "bucket(variableName, classification)";
    }

    @Override
    public String getExplanation() {
        return "Classify process variable variable's numeric value using classification, a comma-separated list of "
                + "\"threshold:label\" pairs (e.g. \"0:low,100:medium,1000:high\"); returns the label of the highest threshold not greater than the value.";
    }

    /**
     * classification looks like a comma-separated list itself ("0:low,100:medium,1000:high"), but the KPI pilot
     * descriptor's own comma-splitting has already broken it into functionRecord.parameters.get(1) onward - so
     * every parameter from index 1 is rejoined with "," to recover the original classification string.
     *
     * @param functionRecord           functionRecord.parameters.getFirst() is the process variable name,
     *                                 functionRecord.parameters subList(1, end) rejoined with "," is the classification
     * @param outboundConnectorContext used to read the current job's variables
     * @param camundaClient            unused, the value comes from the job context, not from a cluster query
     * @return the label of the highest threshold that is <= the variable's value, or null if the value is below every threshold
     * @throws Exception if a parameter is missing, the variable is not set/numeric, or classification cannot be parsed
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.size() < 2) {
            String message = "bucket() requires two parameters: the variable name and the classification";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String variableName = functionRecord.parameters.getFirst();
        String classification = String.join(",", functionRecord.parameters.subList(1, functionRecord.parameters.size()));

        Object rawValue = getVariablesAsMap(outboundConnectorContext).get(variableName);
        if (rawValue == null) {
            return null;
        }
        double value = parseNumber(functionRecord, rawValue, "variable [" + variableName + "]");

        List<Threshold> thresholds = parseClassification(functionRecord, classification);

        String label = null;
        for (Threshold threshold : thresholds) {
            if (value >= threshold.value()) {
                label = threshold.label();
            }
        }
        return label;
    }

    private double parseNumber(FunctionRecord functionRecord, Object candidate, String context) throws ConnectorException {
        if (candidate instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(candidate.toString().trim());
        } catch (NumberFormatException e) {
            String message = "bucket() " + context + " is not numeric: [" + candidate + "]";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
        }
    }

    private List<Threshold> parseClassification(FunctionRecord functionRecord, String classification) throws ConnectorException {
        List<Threshold> thresholds = new ArrayList<>();
        for (String entry : classification.split(",")) {
            String[] parts = entry.split(":", 2);
            if (parts.length != 2) {
                String message = "bucket() classification entry [" + entry + "] is not \"threshold:label\"";
                logger.error("{}: {}", getSignature(functionRecord), message);
                throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
            }
            try {
                thresholds.add(new Threshold(Double.parseDouble(parts[0].trim()), parts[1].trim()));
            } catch (NumberFormatException e) {
                String message = "bucket() classification threshold [" + parts[0] + "] is not numeric";
                logger.error("{}: {}", getSignature(functionRecord), message);
                throw new ConnectorException(KpiError.BAD_INPUTPARAMETER, message);
            }
        }
        thresholds.sort(Comparator.comparingDouble(Threshold::value));
        return thresholds;
    }

    private record Threshold(double value, String label) {
    }
}
