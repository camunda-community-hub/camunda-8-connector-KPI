package io.camunda.connector.kpi;

/* ******************************************************************** */
/*                                                                      */
/*  FunctionRecord                                                         */
/*                                                                      */
/*  This connector is the main connector, doing the distribution on     */
/*  specific function                                                   */
/* ******************************************************************** */

import io.camunda.client.CamundaClient;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.cherrytemplate.CherryConnector;
import io.camunda.connector.kpi.database.JdbcConnectionFactory;
import io.camunda.connector.kpi.database.SaveDatabase;
import io.camunda.connector.kpi.function.FunctionRecord;
import io.camunda.connector.kpi.function.KpiFct;
import io.camunda.connector.kpi.function.KpiFctFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Do not register any InputVariables: we want to get all
 */
@Component
@OutboundConnector(name = KpiInput.KPIFUNCTION, type = "c-kpi-function")

public class KpiFunction implements OutboundConnectorFunction, CherryConnector {

    private static final String WORKER_LOGO = "data:image/png;base64,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";
    private static final Pattern FUNCTION_CALL_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*$");
    private final Logger logger = LoggerFactory.getLogger(KpiFunction.class.getName());
    @Autowired
    CamundaClient camundaClient;

    @Override
    public KpiOutput execute(OutboundConnectorContext outboundConnectorContext) throws ConnectorException {
        long beginTime = System.currentTimeMillis();
        KpiInput kpiInput;
        try {
            kpiInput = outboundConnectorContext.bindVariables(KpiInput.class);
        } catch (ConnectorException ce) {
            logger.error("Bind input error ", ce);
            throw ce;
        } catch (Exception e) {
            logger.error("Bind input error ", e);
            throw new ConnectorException(KpiError.BAD_INPUTPARAMETER,
                    "Kpi can't bind variables: " + e.getMessage());
        }

        try {
            KpiOutput kpiOutput = new KpiOutput();
            String log = "";
            for (Map.Entry<String, Object> pilotEntry : kpiInput.getKpiPilot().entrySet()) {
                FunctionRecord functionRecord = decodeFunction(pilotEntry.getKey(), pilotEntry.getValue());
                KpiFct kpiFct = KpiFctFactory.getInstance().getKpiFct(functionRecord);
                if (kpiFct != null) {
                    long beginFct = System.currentTimeMillis();
                    Object valueFct = kpiFct.execute(functionRecord, outboundConnectorContext, camundaClient);
                    logger.debug("KpiFct {} param: {} value:[{}]",
                            kpiFct.getName(),
                            functionRecord.parameters.stream().collect(Collectors.joining(",")),
                            valueFct);
                    kpiOutput.kpiRecord.put(pilotEntry.getKey(), valueFct);
                    log += pilotEntry.getKey() + "=[" + kpiFct.getName() + "] " + (System.currentTimeMillis() - beginFct) + " ms,";
                }
            }
            logger.info("KpiPilot execution: {} . kpiRecord: {}", log, kpiOutput.kpiRecord);
            if (KpiInput.SAVEDATABASE_V_YES.equals(kpiInput.getSaveDatabase())) {
                if (kpiInput.getJdbcString() == null || kpiInput.getJdbcString().isBlank()) {
                    throw new ConnectorException(KpiError.BAD_INPUTPARAMETER,
                            "jdbcString is required when saveDatabase is Yes");
                }
                if (kpiInput.getTableName() == null || kpiInput.getTableName().isBlank()) {
                    throw new ConnectorException(KpiError.BAD_INPUTPARAMETER,
                            "tableName is required when saveDatabase is Yes");
                }
                SaveDatabase saveDatabase = new SaveDatabase();
                saveDatabase.save(kpiInput.getJdbcString(), kpiInput.getTableName(), kpiOutput.kpiRecord);
            }

            logger.info("Kpi : record in {} ms", kpiOutput.kpiRecord, System.currentTimeMillis() - beginTime);
            return kpiOutput;
        } catch (ConnectorException ce) {
            throw ce;
        } catch (Exception e) {
            logger.error("Kpi execution error ", e.getMessage(), e);

            throw new ConnectorException(KpiError.OPERATION_EXECUTION,
                    "Error executing : " + e.getMessage());
        } finally {
            JdbcConnectionFactory.getInstance().checkConnection(Duration.ofHours(1));
        }

    }


    @Override
    public String getDescription() {
        return "Kpi calculation";
    }

    @Override
    public String getLogo() {
        return WORKER_LOGO;
    }

    @Override
    public String getCollectionName() {
        return "";
    }

    @Override
    public Map<String, String> getListBpmnErrors() {
        return Map.of(KpiError.BAD_INPUTPARAMETER, KpiError.BAD_INPUTPARAMETER_EXPLANATION,
                KpiError.OPERATION_EXECUTION, KpiError.OPERATION_EXECUTION_EXPLANATION,
                KpiError.DATE_PARSING_ERROR, KpiError.DATE_PARSING_ERROR_EXPLANATION,
                KpiError.UNKNOWN_FUNCTION, KpiError.UNKNOWN_FUNCTION_EXPLANATION,
                KpiError.INCOMPLETE_PARAMETERS, KpiError.INCOMPLETE_PARAMETERS_EXPLANATION,
                KpiError.SQL_ERROR, KpiError.SQL_ERROR_EXPLANATION,
                KpiError.DATABASE_NOT_MATCH, KpiError.DATABASE_NOT_MATCH_EXPLANATION);
    }

    @Override
    public Class<?> getInputParameterClass() {
        return KpiInput.class;
    }

    @Override
    public Class<?> getOutputParameterClass() {
        return KpiOutput.class;
    }

    /**
     * Only task at this moment (no InboundConnector)
     *
     * @return list of items where the function applies
     */
    @Override
    public List<String> getAppliesTo() {
        return List.of("bpmn:Task", "bpmn:ServiceTask");
    }

    @Override
    public String getElementType() {
        return "bpmn:ServiceTask";
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public String getRelease() {
        try (InputStream is = KpiFunction.class.getResourceAsStream(
                "/META-INF/maven/io.camunda.connector/kpi-function/pom.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                String version = props.getProperty("version");
                if (version != null)
                    return version;
            }
        } catch (Exception e) {
            // fall through
        }
        return "";
    }

    private FunctionRecord decodeFunction(String key, Object value) {
        FunctionRecord functionRecord = new FunctionRecord();

        if (value instanceof String stringValue) {
            Matcher matcher = FUNCTION_CALL_PATTERN.matcher(stringValue);
            if (matcher.matches()) {
                functionRecord.name = matcher.group(1);
                String rawParameters = matcher.group(2).trim();
                functionRecord.parameters = rawParameters.isEmpty()
                        ? List.of()
                        : Arrays.stream(rawParameters.split(","))
                        .map(String::trim)
                        .toList();
                return functionRecord;
            }
        }

        functionRecord.value = value;
        return functionRecord;
    }

}
