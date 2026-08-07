package io.camunda.connector.kpi.function;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.CorrelatedMessageSubscription;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

public class KpiFctMessageCount extends KpiFct {

    @Override
    public String getName() {
        return "messageCount";
    }

    @Override
    public String getLabel() {
        return "messageCount(messageName)";
    }

    @Override
    public String getExplanation() {
        return "Count messages correlated to (received by) the process instance; if messageName is given, count only messages with that name.";
    }

    /**
     * Uses newCorrelatedMessageSubscriptionSearchRequest(), which returns subscriptions that were actually
     * correlated (a message arrived and matched) - as opposed to newMessageSubscriptionSearchRequest(), which
     * would also include subscriptions still open and waiting.
     *
     * @param functionRecord           functionRecord.parameters.getFirst(), if present and not blank, is the message name filter
     * @param outboundConnectorContext used to read the current job's process instance key
     * @param camundaClient            used to search the process instance's correlated message subscriptions, page by page
     * @return the number of matching correlated messages
     * @throws Exception if the search fails
     */
    @Override
    public Object execute(FunctionRecord functionRecord, OutboundConnectorContext outboundConnectorContext, CamundaClient camundaClient) throws Exception {
        String messageNameFilter = functionRecord.parameters != null && !functionRecord.parameters.isEmpty()
                ? functionRecord.parameters.getFirst() : null;

        long processInstanceKey = outboundConnectorContext.getJobContext().getProcessInstanceKey();

        long count = 0;
        String afterCursor = null;
        boolean historyExhausted = false;

        while (!historyExhausted) {
            String cursor = afterCursor;
            SearchResponse<CorrelatedMessageSubscription> response = camundaClient.newCorrelatedMessageSubscriptionSearchRequest()
                    .filter(f -> {
                        f.processInstanceKey(processInstanceKey);
                        if (messageNameFilter != null && !messageNameFilter.isBlank()) {
                            f.messageName(messageNameFilter);
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

            count += response.items().size();
            afterCursor = response.page().endCursor();
            historyExhausted = afterCursor == null || response.items().size() < PAGE_SIZE;
        }
        return count;
    }
}
