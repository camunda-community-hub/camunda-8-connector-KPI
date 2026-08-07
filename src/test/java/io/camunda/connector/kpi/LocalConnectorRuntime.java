package io.camunda.connector.kpi;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.Deployment;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.exception.CamundaError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@SpringBootApplication
@Deployment(resources = {"classpath:connector-KPI.bpmn", "classpath:GetRiskLevel.dmn"})

public class LocalConnectorRuntime {

    private static final Logger logger = LoggerFactory.getLogger(LocalConnectorRuntime.class);

    private static final List<String> WATCHED_CITIES = List.of("Paris", "London", "Berlin");

    @Autowired
    private CamundaClient camundaClient;

    private Random random = new Random();

    public static void main(String[] args) {
        SpringApplication.run(LocalConnectorRuntime.class, args);
    }

    /**
     * Fired once the Spring context is fully up (the local Zeebe broker connection is ready too): starts one
     * "connector-KPI" instance as a smoke test, seeded to exercise the BAD_AMOUNT path without triggering
     * BAD_ACCOUNT.
     * <p>
     * amount is set above the validation() worker's 5000 threshold; city/country are seeded too (not just left
     * unset) so getAmount's "generate only if not already present" logic doesn't leave city null, which would
     * otherwise break the getWeather task's URL further down the process. "tag" is a variable the kpiPilot reads
     * back via variable(tag), demonstrating that a value seeded at process start is retrievable through the pilot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent applicationReadyEvent) {
        Thread thread = new Thread(() -> {
            startSmokeTestInstance();
        });
        thread.start();
    }

    public void startSmokeTestInstance() {


        createApplication(Map.of(
                "explanation", "NoUserTask",
                "pathValidation", "PINK",
                "amount", 6000,
                "city", "Rome",
                "country", "Italy"), "KPI-2026-0001", "badAccount");

        createApplication(Map.of(
                "explanation", "NoUserTask",
                "pathValidation", "ORANGE",
                "amount", 6000,
                "city", "Rome",
                "country", "Italy"), "KPI-2026-0002", "badAccount");
        createApplication(Map.of(
                "explanation", "BadAmount",
                "pathValidation", "PINK",
                "amount", 30000,
                "city", "Rome",
                "country", "Italy"
                ), "KPI-2026-0003", "badAccount");
        createApplication(Map.of(
                "explanation", "BadAccount",
                "pathValidation", "PINK",
                "amount", 30000,
                "city", "Paris",
                "country", "Italy"
        ), "KPI-2026-0004", "badAccount");

    }

    private void createApplication(Map<String, Object> variables, String businessKey, String tag) {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        int applicationId = (int) (System.currentTimeMillis() / 1000 % 10000);

        Map<String, Object> variablesImproved = new HashMap<>(variables);
        variablesImproved.put("applicationId", applicationId);
        ProcessInstanceEvent processInstance = camundaClient.newCreateInstanceCommand()
                .bpmnProcessId("connector-KPI")
                .latestVersion()
                .tags("badge", tag)
                .businessId(businessKey)       // Business key equivalent
                .variables(variablesImproved)

                .send()
                .join();
        int nbMessages = random.nextInt(3) + 1;
        logger.info("Start ProcessInstance ID {} . msg:{}  PI {}", applicationId, nbMessages, processInstance.getProcessInstanceKey());


        for (int i = 0; i < nbMessages; i++) {
            camundaClient.newCorrelateMessageCommand()
                    .messageName("KpiStatus")
                    .correlationKey(String.valueOf(applicationId))
                    .send().join();
        }
        logger.info("Started smoke-test instance of connector-KPI with variables {}", variables);
    }

    /**
     * Backs the "validation" service task. Purely a business-outcome check (per camunda-job-workers guidance):
     * amount over 5000 raises BAD_AMOUNT, a watched city raises BAD_ACCOUNT - both are BPMN errors caught by the
     * error-specific boundary events already attached to "validation" in the BPMN, not incidents.
     *
     * @param amount the "amount" process variable
     * @param city   the "city" process variable
     * @return no output variables; this worker only validates
     */
    @JobWorker(type = "validation")
    public Map<String, Object> validation(@Variable Number amount, @Variable String city) {
        if (amount != null && amount.doubleValue() > 25000) {
            logger.warn("SmokeTestInstance validation failed for amount {} throw [BAD_AMOUNT]", amount);
            throw CamundaError.bpmnError("BAD_AMOUNT", "Amount " + amount + " exceeds 5000");
        }
        if (city != null && WATCHED_CITIES.contains(city)) {
            logger.warn("SmokeTestInstance validation failed for city {} throw [BAD_ACCOUNT]", city);
            throw CamundaError.bpmnError("BAD_ACCOUNT", "City " + city + " is in the watch list " + WATCHED_CITIES);
        }
        return Map.of();
    }


    @JobWorker(type = "amountstatusrequested")
    public Map<String, Object> amountStatusRequested(@Variable String applicationId) {
        logger.info("Amount status requested for Id {}", applicationId);
        return Map.of();
    }
    /**
     *
     CREATE TABLE kpiAmount (
     explanation            VARCHAR(50),
     id                     SERIAL PRIMARY KEY,
     dateStringUTC          VARCHAR(50),
     dateLocalDateUTC       DATE,
     dateLocalTimeUTC       TIME,
     dateLocalDateTimeUTC   TIMESTAMP,
     dateOffsetDateTimeUTC  TIMESTAMP WITH TIME ZONE,
     dateInstantUTC         TIMESTAMPTZ,
     dateSFO                VARCHAR(50),
     blueExecution          BIGINT,
     slaLightCheck          BIGINT,
     pathValidation         VARCHAR(50),
     city                   VARCHAR(100),
     amount                 BIGINT,
     flowValidationTaken    BOOLEAN,
     isHighValue            BOOLEAN,
     reviewAssignee         VARCHAR(200),
     reviewSLA              BOOLEAN,
     reviewSLADescription   TEXT,
     kpiLabel               VARCHAR(100),
     incidentCount          BIGINT,
     errorCount             BIGINT,
     processDefinitionId    VARCHAR(200),
     latencyAfterWeather    BIGINT,
     retryCountTotal        BIGINT,
     demoTag                VARCHAR(200),
     instanceKey            BIGINT,
     businessKey            VARCHAR(200),
     amountChangedCount     BIGINT,
     amountBucket           VARCHAR(50),
     decisionOutcome        TEXT,
     createdAt              TIMESTAMP DEFAULT now()
     );
     */
}
