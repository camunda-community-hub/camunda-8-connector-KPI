package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

public class KpiFctDuration extends KpiFct {

    private static final Logger logger = LoggerFactory.getLogger(KpiFctDuration.class);

    /**
     * @param reference a <Id>[:START|END] reference
     * @return the <Id> part, without the :START/:END suffix
     */
    private static String elementId(String reference) {
        int separatorIndex = reference.indexOf(':');
        return separatorIndex < 0 ? reference : reference.substring(0, separatorIndex).trim();
    }

    @Override
    public String getName() {
        return "duration";
    }

    @Override
    public String getLabel() {
        return "duration(fromElementId[:START|:END], toElementId[:START|:END], unit)";
    }

    @Override
    public String getExplanation() {
        return "Return the delay between fromElement and toElement (each <Name>[:START|:END], END by default), in unit "
                + "(MS default, Second, Minute, Hour, Day, Week).";
    }

    /**
     * Two parameters expected.
     * First one is the task or event / start list to search. Format is <Name>[:START|END]
     * Second one is the task or event (same format)
     * Third (optional) is the unit : Mollisecond or MS (the default), Second, Minute, Hour, Day, Week
     * The history of the process instance is accessible via camundaClient. Search it, search each StartTask and EndTask and collect the time. return the delay between the two tasks (start or end) in the unit
     *
     * @param functionRecord           functionRecord.parameters.getFirst() is the "from" element (name, optionally suffixed by :START or :END, END is used when omitted),
     *                                 functionRecord.parameters.get(1) is the "to" element with the same format,
     *                                 functionRecord.parameters.get(2), if present, is the unit (MS/Millisecond, Second, Minute, Hour, Day, Week - MS is the default)
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's element instance history, page by page, to locate the two elements' start/end dates
     * @return the duration between the "from" and the "to" element, expressed in the requested unit
     * @throws Exception if the two elements parameters are not provided, or one of the referenced elements has not reached the requested state
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        if (functionRecord.parameters == null || functionRecord.parameters.size() < 2) {
            String message = "duration() requires two parameters: the from and the to element";
            logger.error("{}: {}", getSignature(functionRecord), message);
            throw new ConnectorException(KpiError.INCOMPLETE_PARAMETERS, message);
        }
        String fromReference = functionRecord.parameters.getFirst();
        String toReference = functionRecord.parameters.get(1);
        String unit = functionRecord.parameters.size() > 2 ? functionRecord.parameters.get(2) : "MS";

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();
        OffsetDateTime[] times = searchStartEndTimes(camundaClient, processInstanceKey, fromReference, toReference);
        OffsetDateTime fromTime = times[0];
        OffsetDateTime toTime = times[1];

        if (fromTime == null) {
            logger.info("{}: element [{}] does not exist, or has not reached the requested state, in the process instance history", getSignature(functionRecord), fromReference);
            return null;
        }
        if (toTime == null) {
            logger.info("{}: element [{}] does not exist, or has not reached the requested state, in the process instance history", getSignature(functionRecord), toReference);
            return null;
        }

        return convert(Duration.between(fromTime, toTime), unit);
    }

    /**
     * Search the process instance's element instance history (via KpiFct.searchElementInstances, filtered
     * server-side to just the from/to element names) and resolve both references against it.
     *
     * @param camundaClient      used to search the element instance history
     * @param processInstanceKey the process instance whose history is scanned
     * @param fromReference      the "from" <Name>[:START|END] reference
     * @param toReference        the "to" <Name>[:START|END] reference
     * @return a 2-element array [fromTime, toTime]; either entry is null if never resolved in the history
     */
    private OffsetDateTime[] searchStartEndTimes(CamundaClient camundaClient, long processInstanceKey,
                                                 String fromReference, String toReference) {
        FilterElementId filter = new FilterElementId().addElementIds(List.of(elementId(fromReference), elementId(toReference)));
        List<ElementInstance> elementInstances = searchElementInstances(camundaClient, processInstanceKey, filter);

        OffsetDateTime fromTime = null;
        OffsetDateTime toTime = null;
        for (ElementInstance elementInstance : elementInstances) {
            if (fromTime == null) {
                fromTime = matchTime(elementInstance, fromReference);
            }
            if (toTime == null) {
                toTime = matchTime(elementInstance, toReference);
            }
        }
        return new OffsetDateTime[]{fromTime, toTime};
    }

    /**
     * @param elementInstance one element instance from the history
     * @param reference       a <Name>[:START|END] reference - END is used when the suffix is omitted
     * @return the start or end date of elementInstance if its name/id matches reference, null if it does not
     * match or the requested date is not set yet
     */
    private OffsetDateTime matchTime(ElementInstance elementInstance, String reference) {
        int separatorIndex = reference.indexOf(':');
        String elementId = elementId(reference);
        boolean wantStart = separatorIndex >= 0 && "START".equalsIgnoreCase(reference.substring(separatorIndex + 1).trim());

        if (!elementId.equals(elementInstance.getElementId()) && !elementId.equals(elementInstance.getElementId())) {
            return null;
        }
        return wantStart ? elementInstance.getStartDate() : elementInstance.getEndDate();
    }

    /**
     * Convert a duration to the requested unit.
     *
     * @param duration the duration to convert
     * @param unit     MS/Millisecond (default), Second, Minute, Hour, Day or Week
     * @return the duration expressed as a number of the requested unit
     */
    private long convert(Duration duration, String unit) {
        return switch (unit.trim().toUpperCase()) {
            case "SECOND" -> duration.toSeconds();
            case "MINUTE" -> duration.toMinutes();
            case "HOUR" -> duration.toHours();
            case "DAY" -> duration.toDays();
            case "WEEK" -> duration.toDays() / 7;
            default -> duration.toMillis();
        };
    }
}
