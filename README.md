[![Community badge: Stable](https://img.shields.io/badge/Lifecycle-Stable-brightgreen)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#stable-)
[![Community extension badge](https://img.shields.io/badge/Community%20Extension-An%20open%20source%20community%20maintained%20project-FF4700)](https://github.com/camunda-community-hub/community)
![Compatible with: Camunda Platform 8](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-0072Ce)

# camunda-8-connector-KPI


![kpi.png](kpi.png)

<a href="https://www.flaticon.com/free-icons/kpi" title="kpi icons">Kpi icons created by zero_wing - Flaticon</a>

## Description

This is a Camunda 8 outbound connector for building and collecting KPIs (Key Performance Indicators) out of a running
process instance, without writing a job worker for each metric.

Attach the connector to a service task anywhere in the process (typically right before an end event, or on a
completion path). Give it a **KPI pilot**: a JSON object where each key is the name of a KPI to compute, and each
value is a small function call describing how to compute it - e.g. `"duration(start, end, second)"`,
`"threshold(amount, >, 5000)"`, `"variable(city)"`. The connector evaluates every entry against the current job and
process instance (variables, element instance history, incidents, user tasks, DMN evaluations, correlated
messages...) and produces a flat `kpiRecord` map as output - one value per pilot key.

Optionally, the connector can also persist that `kpiRecord` as a row in a relational database table, so KPIs
accumulate across many process instances into something queryable/reportable (see
[Save the KPI record to a database](#save-the-kpi-record-to-a-database) below).

No functions are added via classpath scanning or plugins: every available function is a hand-written, hand-registered
Java class (see [Available functions](#available-functions)), so what you see in this README is exactly what you can
call - nothing more, nothing hidden behind reflection.

# Use as an archiver

The connector is not only for computing KPIs - the same mechanism (pilot in, `kpiRecord` out, optionally saved to a
database) works equally well as a lightweight **business data archiver**: a way to take a snapshot of whatever
business information matters from a process instance and persist it as one row, without writing a dedicated job
worker or database-access code.

The pilot describes what to collect and under what column name to store it; each pilot key becomes one column of the
archived row (see [Save the KPI record to a database](#save-the-kpi-record-to-a-database) for how column matching
works).

For example, say you want to archive:

| What to save                     | Source                                       | Column in the database |
|----------------------------------|-----------------------------------------------|-------------------------|
| The calculated risk level        | process variable `riskLevel`                  | `riskCalculated`        |
| Who reviewed the application     | the assignee of user task `Activity_Review`   | `reviewer`               |
| The full address, as one string  | process variables `address`, `city`, `country` | `fullAddress`         |

This pilot does exactly that:

```
{
  riskCalculated: riskLevel,
  reviewer:       "userTaskAssignee(Activity_Review, LAST)",
  fullAddress:    address + " " + city + " " + country
}
```

`riskCalculated` and `fullAddress` are raw FEEL expressions (see [FEEL expression](#feel-expression) below) - the
engine resolves them before the connector ever sees the pilot, so the connector just treats the resulting value as a
constant. `reviewer` is a real KPI function call (see [userTaskAssignee](#usertaskexecutiontaskfilter-mode)),
resolved by the connector itself. Both styles can be freely mixed in the same pilot, and both end up as plain columns
of the same archived row.


# Connector


## Inputs
| Name         | Description            | Class            | Level    |
|--------------|------------------------|------------------|----------|
| kpiPilot     | Pilot to build the KPI | java.lang.String | REQUIRED |
| saveDatabase | Save in database       | java.lang.String | REQUIRED |
| jdbcString   | Jdbc connection String | java.lang.String | OPTIONAL |
| tableName    | Table name             | java.lang.String | OPTIONAL |



## Outputs
| Name      | Description | Class         | Level    |
|-----------|-------------|---------------|----------|
| kpiRecord | Kpi Record  | java.util.Map | OPTIONAL |



## Errors
| Name                  | Explanation                                                                                                                                                                                   |
|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| INCOMPLETE_PARAMETERS | Parameter is incomplete                                                                                                                                                                       |
| UNKNOWN_FUNCTION      | The function is unknown                                                                                                                                                                       |
| DATE_PARSING_ERROR    | A date parameter cannot be parsed. Dates must be ISO-8601, e.g. "2026-07-22T10:00:00-07:00" or "2026-07-22". The message gives the source parameter (startDate, endDate, startDateOccurrence) |
| OPERATION_EXECUTION   | Error during an operation                                                                                                                                                                     |
| BAD_INPUTPARAMETER    | During the bind, some input does not have the expected type                                                                                                                                   |
| DATABASE_NOT_MATCH    | saveDatabase is "Yes" but no column of tableName matches any key of the KPI record                                                                                                            |
| SQL_ERROR             | Error during insertion in the database (e.g. the table does not exist, or a value does not fit its column type)                                                                              |

## Catching an error from a boundary event

To let a BPMN error boundary event catch one of the errors above, the connector task needs an `errorExpression`
task header that calls the FEEL `bpmnError(...)` function - there is no built-in variable (e.g. no
`thrownErrorCode`/`thrownErrorMessage`) that a boundary event can read automatically:

```
if is defined(error) then bpmnError(error.code, error.message, {connectorError: {error: error.code, message: error.message}}) else null
```

`error.code`/`error.message` only exist transiently while evaluating this expression - they are not process
variables. `bpmnError`'s **third, optional parameter** is a context whose entries *do* become real process variables
once the error is thrown and caught, so the boundary event can map that whole object onward, e.g. via
`zeebe:ioMapping`:

```xml
<zeebe:output source="=connectorError" target="kpiError" />
```

giving downstream elements a single `kpiError` variable with two fields, `kpiError.error` (the error code) and
`kpiError.message`. See `src/test/resources/connector-KPI.bpmn` for a working example.

**Is the `variables` parameter mandatory to throw the error?** No. `bpmnError(errorCode, errorMessage)` (two
arguments) throws the BPMN error exactly the same way - the boundary event still catches it, retries/incidents behave
identically. The third `variables` parameter is purely a way to also carry data into the catching scope; omitting it
only means no extra variables come along with the error, nothing about the throw itself changes.

# Kpi Record

Build the KPI record from the pilot. Multiple function are available to build the record.

Here an example of pilot:
```
{
  dateStringUTC:           "date(second, string, UTC)",
  dateLocalDateUTC:        "date(second, LocalDate, UTC)",
  dateLocalTimeUTC:        "date(second, LocalTime, UTC)",
  dateLocalDateTimeUTC:    "date(second, LocalDateTime, UTC)",
  dateOffsetDateTimeUTC:   "date(second, OffsetDateTime, UTC)",
  dateInstantUTC:          "date(second, Instant, UTC)",

  dateSFO:                 "date(minute, string, America/Los_Angeles)",

  pathValidation:          pathValidation,
  blueExecution:           "duration(beginMarkBlue, endMarkBlue, second)",
  explanation:             "variable(explanation)",
  city:                    "variable(city)",
  amount:                  "variable(amount)",
  orangePath:     		  "path(ANY, Flow_orange_validation)",
  slaLightCheck:           "executionCount(Event_SLA_LighCheck)",
  isHighValue:             "threshold(amount, >, 5000)",
  reviewAssignee:          "userTaskExecution(Review, LAST)",
  reviewSLA:               "userTaskSLA(Review, PT1H)",
  reviewSLADescription:    "userTaskSLADescription(Review, PT1H, hour)",
  kpiLabel:                "kpi-demo",
  incidentCount:           "incidentCount()",
  processDefinitionId:     "processProperty(processDefinition)",
  latencyAfterWeather:     "latency(getWeather)",
  retryCountTotal:         "retryCount()",
  demoTag:                 "tag(kpi-connector-demo)",
  instanceKey:             "processInstanceKey()",
  businessKey:             "businessKey()",
  amountChangedCount:      "variableChangedCount(amount)",
  amountBucket:            "bucket(amount, 0:low,1000:medium,5000:high)",
  requestStatus:           "messageCount(KpiStatus)",
  decisionOutcome:         "decisionResult(kpiDecision)" 
}
```

## Available functions

Any pilot value that is not `name(param1, param2, ...)` is used as a constant, as-is (e.g. `kpiLabel: "kpi-demo"` above).
Every real function below is called the same way: `<key>: "<functionName>(<parameters>)"`.

### FEEL expression

Given a direct value works

```
  pathValidation:          concat("path:",myPathVariable),
```

FEEL resolve the expression, and the connector will consider this  value as a constant.

### variable(variableName)

Return the current value of process variable `variableName`.

| Parameter    | Required | Description                           |
|--------------|----------|---------------------------------------|
| variableName | Yes      | Name of the process variable to read  |

Returns `null` if the variable is not set.

### duration(fromElementId, toElementId, unit)

Return the delay between `fromElement` and `toElement` completing, in `unit`.

| Parameter     | Required | Description                                                                                                       |
|---------------|----------|-------------------------------------------------------------------------------------------------------------------|
| fromElementId | Yes      | `<ElementId>` or `<ElementId>:START`/`<Name>:END` (element name or id); `END` is used when no suffix is given |
| toElementId   | Yes      | Same format as fromElement                                                                                        |
| unit          | No       | `MS` (default), `Second`, `Minute`, `Hour`, `Day`, `Week`                                                         |

Returns `null` if either element does not exist, or has not reached the requested state, in the process instance history (not an error).

### path(mode, transitionId, ...)

Check which of the given transition (element) names were executed.

| Parameter     | Required | Description                                                        |
|---------------|----------|--------------------------------------------------------------------|
| mode          | Yes      | `ALL` (every one executed), `ANY` (at least one), `NOTHING` (none) |
| transitionId  | Yes (≥1) | One or more element ids to check; repeat as extra parameters       |

Returns `Boolean.TRUE`/`Boolean.FALSE` depending on mode.

### history(resultType:JSON, topics:ALL, activityFilter:ALL, size:100, waitMs:0)

Return the process instance's execution history as a list of activities, ordered by start date (earliest first) -
including, recursively, the history of every process instance spawned by a call activity (a call activity that
itself calls another process, which calls another, etc.), since a called process instance's activities are part of
the calling process instance's history.

Unlike every other function, `history` takes **named** parameters, not positional ones: each parameter is
`name:value`, parameters are comma-separated, and any parameter can be omitted (its default applies) or given in any
order. For example, `history(activityFilter:TASK, resultType:STRING)` is valid and uses the defaults for
`topics`, `size` and `waitMs`.

| Parameter      | Required | Description                                                                                                              |
|----------------|----------|----------------------------------------------------------------------------------------------------------------------------|
| resultType     | No       | `STRING` (a comma-separated list of activities, each a `\|`-separated `topic:value` list) or `JSON` (a list of objects); default `JSON` |
| topics         | No       | `\|`-separated list of topics to report per activity - see below; default `ALL`                                          |
| activityFilter | No       | `\|`-separated list of `BpmnElementType` names (or the special values `EVENT`, `TASK`, `ACTIVITY`, `GATEWAY`, `SEQUENCE`, `ALL` - see below); default `ALL` |
| size           | No       | Maximum number of activities returned, keeping the earliest ones; default `100`                                          |
| waitMs         | No       | Milliseconds to wait, once, before *any* function in the pilot runs (see below); default `0`                             |

Every parameter name, topic and `activityFilter` value is matched **case-insensitively** - `resultType`, `RESULTTYPE`
and `resulttype` are all equivalent, and so are `startDate`, `STARTDATE` and `startdate`.

`size` exists because the underlying history search has no way to return an unbounded number of activities in one
shot - if a process instance's history is larger than `size`, only the first `size` activities (earliest by start
date) are returned; the rest are silently dropped. Raise `size` if you need more, e.g. `history(size:500)`.

`waitMs` exists because Zeebe exports process instance history asynchronously - right after an activity completes,
it may not be queryable yet through the search API. If `waitMs` is set, `history` (and, by extension, the whole
KPI pilot) waits before querying anything, giving the exporter time to catch up. If the same pilot has more than one
`history(...)` call with different `waitMs` values, the connector waits **once**, for the largest value across
all of them, before running *any* function in the pilot (not just `history`) - not once per call, and not the
sum of them.

Available topics: `elementId`, `elementName`, `elementInstanceKey`, `sourceRef`, `targetRef`, `processInstanceKey`,
`processDefinitionId`, `processDefinitionName`, `processDefinitionVersion`, `type`, `state`, `startDate`, `endDate`,
`assignee` (user tasks only, from the matching user task's history), `hasIncident`, `incidentKey`, `tenantId`, plus
the special value `ALL`, which reports every topic above (equivalent to spelling out
`elementId|elementName|elementInstanceKey|sourceRef|targetRef|processInstanceKey|processDefinitionId|processDefinitionName|processDefinitionVersion|type|state|startDate|endDate|assignee|hasIncident|incidentKey|tenantId`).
`startDate`/`endDate` are formatted as ISO-8601 (e.g. `2026-08-10T17:21:08Z`). `processInstanceKey`/
`processDefinitionId`/`processDefinitionName`/`processDefinitionVersion` identify which process instance/definition
an activity belongs to - the top-level one, or a nested one spawned by a call activity (see below). `sourceRef`/
`targetRef` only ever have a value on a sequence flow entry (see [Sequence flows](#sequence-flows-sequence) below).

**A topic with no value for a given activity is omitted from that activity entirely, contextually** - it is not
reported as `null`/empty. For example `sourceRef`/`targetRef` never appear on anything but a sequence flow entry, and
`state`/`endDate`/`assignee` never appear on a sequence flow entry (see below); requesting `topics:ALL` therefore
returns a different, smaller set of keys per entry depending on what kind of activity it is.

`activityFilter` accepts any of: `TASK`, `SERVICE_TASK`, `USER_TASK`, `MANUAL_TASK`, `RECEIVE_TASK`, `SEND_TASK`,
`SCRIPT_TASK`, `BUSINESS_RULE_TASK`, `CALL_ACTIVITY`, `SUB_PROCESS`, `AD_HOC_SUB_PROCESS`, `EXCLUSIVE_GATEWAY`,
`PARALLEL_GATEWAY`, `INCLUSIVE_GATEWAY`, `EVENT_BASED_GATEWAY`, `START_EVENT`, `INTERMEDIATE_CATCH_EVENT`,
`INTERMEDIATE_THROW_EVENT`, `BOUNDARY_EVENT`, `END_EVENT`, `EVENT_SUB_PROCESS`, `MULTI_INSTANCE_BODY`, `PROCESS`,
`SEQUENCE_FLOW`, `UNSPECIFIED`, plus five convenience values, each expanding to a whole family of types:

| Convenience value | Expands to                                                                                                          |
|--------------------|----------------------------------------------------------------------------------------------------------------------|
| `EVENT`            | `START_EVENT`, `INTERMEDIATE_CATCH_EVENT`, `INTERMEDIATE_THROW_EVENT`, `BOUNDARY_EVENT`, `END_EVENT`, `EVENT_SUB_PROCESS` |
| `TASK`             | `TASK`, `SERVICE_TASK`, `USER_TASK`, `MANUAL_TASK`, `RECEIVE_TASK`, `SEND_TASK`, `SCRIPT_TASK`, `BUSINESS_RULE_TASK`  |
| `ACTIVITY`         | Every type in `TASK` above, plus `SUB_PROCESS`, `AD_HOC_SUB_PROCESS`, `MULTI_INSTANCE_BODY`                          |
| `GATEWAY`          | `EXCLUSIVE_GATEWAY`, `PARALLEL_GATEWAY`, `INCLUSIVE_GATEWAY`, `EVENT_BASED_GATEWAY`                                 |
| `ALL`              | `ACTIVITY\|EVENT\|SEQUENCE` - every activity, sub-flow container, event and sequence flow (does **not** include `GATEWAY` - add it explicitly, e.g. `ALL|GATEWAY`, if you want gateways too) |

`ACTIVITY` (and therefore `ALL`) does not include `CALL_ACTIVITY` itself - filter on `CALL_ACTIVITY` explicitly if you
want the call activity element to appear in the result. Either way, what runs *inside* a called process instance is
always part of the history (see the recursive call activity note above) and is reported through its own real type (e.g. a
`USER_TASK` inside the called process instance still matches `TASK`/`ACTIVITY`/`USER_TASK`).

### Sequence flows (`SEQUENCE`)

`SEQUENCE_FLOW` is not part of the element instance history at all - Zeebe never returns it from the element
instance search API (`GATEWAY`, `TASK`, etc. all come from there; sequence flows genuinely don't). Requesting
`SEQUENCE` in `activityFilter` (or `ALL`, which includes it) makes `history` fetch taken sequence flows through
Camunda's separate, dedicated API for this instead, and merge them into the result.

That API is much thinner than element instances, though: a taken sequence flow only ever exposes its own element
id, process instance/definition identifiers, tenant id - **no timestamp, no name, no state, no source/target**. To
place a sequence flow in the startDate-ordered result at all, and to report `sourceRef`/`targetRef`, `history`
parses the process definition's BPMN XML to resolve that flow's `sourceRef`/`targetRef`, then uses the target
element's earliest start as an approximated `startDate`. Consequences:

- A sequence flow entry only ever reports `elementId` (its own), `sourceRef`, `targetRef`, `processInstanceKey`,
  `processDefinitionId`/`Name`/`Version`, `type` (always `SEQUENCE_FLOW`), `tenantId` and its approximated
  `startDate` - every other topic (`elementName`, `elementInstanceKey`, `endDate`, `state`, `assignee`,
  `hasIncident`, `incidentKey`) is omitted for it, per the contextual-omission rule above.
- A sequence flow whose target hasn't started yet, or whose target/source can't be resolved from the BPMN XML, is
  silently omitted - it cannot be honestly placed in a startDate-ordered list without a date.
- **Ordering ties are broken by topology, not just by date.** Since a flow's approximated `startDate` is always
  identical to its target's real `startDate` (and sometimes also to its source's, e.g. a chain of automatic
  elements processed in the same instant), `history` resolves same-`startDate` ties so that a flow always sorts
  **after its source activity and before its target activity** - e.g. if `needValidation` has outbound sequence
  flow `Flow_0baismk`, the result orders them `needValidation`, `Flow_0baismk`, then whatever `Flow_0baismk` leads
  to, even though all three may share the exact same `startDate`.

Example: `history(resultType:JSON, topics:elementId|elementName|type|startDate|endDate|processInstanceKey|processDefinitionName|processDefinitionVersion|state, activityFilter:ACTIVITY|EVENT|SEQUENCE, size:500)` returns

```json
[
  {
    "elementId": "StartEvent_1",
    "elementName": "StartEvent_1",
    "type": "START_EVENT",
    "startDate": "2026-08-10T19:08:27.224Z",
    "endDate": "2026-08-10T19:08:27.224Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Activity_0kjf9pe",
    "elementName": "needValidation",
    "type": "SCRIPT_TASK",
    "startDate": "2026-08-10T19:08:27.224Z",
    "endDate": "2026-08-10T19:08:27.224Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Activity_1goiwt2",
    "elementName": "getAmount",
    "type": "SCRIPT_TASK",
    "startDate": "2026-08-10T19:08:27.224Z",
    "endDate": "2026-08-10T19:08:27.224Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Activity_0tm81e6",
    "elementName": "getRiskLevel",
    "type": "BUSINESS_RULE_TASK",
    "startDate": "2026-08-10T19:08:27.224Z",
    "endDate": "2026-08-10T19:08:27.224Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Activity_0j72crs",
    "elementName": "validation",
    "type": "SCRIPT_TASK",
    "startDate": "2026-08-10T19:08:27.224Z",
    "endDate": "2026-08-10T19:08:27.243Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "TERMINATED"
  },
  {
    "elementId": "Event_1y6el57",
    "elementName": "badAccount",
    "type": "BOUNDARY_EVENT",
    "startDate": "2026-08-10T19:08:27.243Z",
    "endDate": "2026-08-10T19:08:27.243Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Activity_1i24ow1",
    "elementName": "Light check",
    "type": "USER_TASK",
    "startDate": "2026-08-10T19:08:27.243Z",
    "endDate": "2026-08-10T19:08:37.505Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "TERMINATED"
  },
  {
    "elementId": "Event_SLA_LighCheck",
    "elementName": "10s",
    "type": "BOUNDARY_EVENT",
    "startDate": "2026-08-10T19:08:37.505Z",
    "endDate": "2026-08-10T19:08:37.505Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Event_0pw76i1",
    "elementName": "Automatic acceptance",
    "type": "INTERMEDIATE_THROW_EVENT",
    "startDate": "2026-08-10T19:08:37.505Z",
    "endDate": "2026-08-10T19:08:37.505Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "beginMarkBlue",
    "elementName": "begin Blue",
    "type": "INTERMEDIATE_THROW_EVENT",
    "startDate": "2026-08-10T19:08:37.505Z",
    "endDate": "2026-08-10T19:08:37.505Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Activity_1emt58t",
    "elementName": "Calculate time to wait (5=>40s)",
    "type": "SCRIPT_TASK",
    "startDate": "2026-08-10T19:08:37.505Z",
    "endDate": "2026-08-10T19:08:37.505Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Event_1mwrdvu",
    "elementName": "Wait x seconds",
    "type": "INTERMEDIATE_CATCH_EVENT",
    "startDate": "2026-08-10T19:08:37.505Z",
    "endDate": "2026-08-10T19:08:57.857Z",
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  },
  {
    "elementId": "Activity_getWeather",
    "elementName": "getWeather",
    "type": "SERVICE_TASK",
    "startDate": "2026-08-10T19:08:57.857Z",
    "endDate": null,
    "processInstanceKey": 2251799814572129,
    "processDefinitionName": "connector-KPI",
    "processDefinitionVersion": 33,
    "state": "COMPLETED"
  }
]
```

and `history(resultType:STRING, topics:elementId|elementName|startDate, activityFilter:TASK|EVENT)` returns the same activities as

```
elementId:StartEvent_1|elementName:Receive application|startDate:2026-08-10T17:21:08Z,elementId:GetScore|elementName:Get score|startDate:2026-08-10T17:22:03Z
```

### threshold(variableName, operator, value)

Compare process variable `variableName` against `value` using `operator`.

| Parameter    | Required | Description                                                                                     |
|--------------|----------|---------------------------------------------------------------------------------------------------|
| variableName | Yes      | Name of the process variable to compare                                                          |
| operator     | Yes      | `=`, `>`, `<`, `>=`, `<=` (numeric comparison) or `before`, `after` (ISO-8601 date/date-time comparison) |
| value        | Yes      | A number for `=`/`>`/`<`/`>=`/`<=`; an ISO-8601 date/date-time for `before`/`after`               |

Returns `Boolean.TRUE`/`Boolean.FALSE`, or `null` if the variable is not set (not an error).

### userTaskAssignee(taskFilter, mode)

Return the assignee of the user task(s) matching `taskFilter`.

| Parameter  | Required | Description                                                                 |
|------------|----------|-------------------------------------------------------------------------------|
| taskFilter | No       | User task name or element id filter; blank/omitted matches every user task   |
| mode       | No       | `FIRST` (default), `LAST`, or `ALL` (every matching assignee, comma-joined)   |

Returns the matching assignee(s) as a String, or `""` if none of the matching user tasks has an assignee.

### userTaskSLA(taskIdFilter, limit)

Return `TRUE` if every completed user task matching `taskFilter` finished within `limit`.

| Parameter    | Required | Description                                         |
|--------------|----------|-----------------------------------------------------|
| taskIdFilter | Yes      | User task Id filter; blank matches every user task  |
| limit        | Yes      | SLA limit, an ISO-8601 duration (e.g. `PT2H`)       |

User tasks that have not completed yet are ignored (their final duration is not known). Returns `Boolean.TRUE`/`Boolean.FALSE`.

### userTaskSLADescription(taskIdFilter, limit, unit)

Return `"taskId"-delay-OK` or `"taskId"-delay-OVER` for every completed user task matching `taskFilter`.

| Parameter    | Required | Description                                                                    |
|--------------|----------|--------------------------------------------------------------------------------|
| taskIdFilter | Yes      | User task name or element id filter; blank matches every user task             |
| limit        | Yes      | SLA limit, an ISO-8601 duration (e.g. `PT2H`)                                  |
| unit         | No       | Unit used to format the delay: `minute`, `hour` (default), `day`               |

Example (no filter, limit 15h, unit hour): `"Review Contract"-14h-OK, "Validate Contract"-19h-OVER`.

### date(rounding, type, zone)

Return the current date/time, rounded and formatted as requested.

| Parameter | Required | Description                                                                                                          |
|-----------|----------|------------------------------------------------------------------------------------------------------------------------|
| rounding  | No       | `FULL` (default, no rounding), `SECOND`, `MINUTE`, `HOUR`, `DAY`, `MONTH`                                             |
| type      | No       | `LocalDateTime` (default), `LocalDate`, `LocalTime`, `OffsetDateTime`, `Instant`, or `STRING` (ISO-8601 text)          |
| zone      | No       | `UTC` (default), `local` (JVM default zone), a fixed offset like `+07:00`, or a zone id like `America/New_York`      |

All parameter matching is case-insensitive. Java type → PostgreSQL column type mapping, if saving the result to a database:

| Java type        | PostgreSQL type                            |
|-------------------|---------------------------------------------|
| `LocalDate`       | `DATE`                                       |
| `LocalTime`       | `TIME`                                       |
| `LocalDateTime`   | `TIMESTAMP` (`WITHOUT TIME ZONE`)            |
| `OffsetDateTime`  | `TIMESTAMP WITH TIME ZONE` (`TIMESTAMPTZ`)   |
| `Instant`         | `TIMESTAMPTZ`                                |

### incidentCount([elementId])

Count incidents raised on the process instance.

| Parameter  | Required | Description                                                               |
|------------|----------|---------------------------------------------------------------------------|
| elementId  | No       | Element name or id filter; omitted counts every incident of the instance  |

### processProperty(processDefinition\|processDefinitionVersion\|tenantId)

Return a property of the current job.

| Parameter | Required | Description                                                                                    |
|-----------|----------|----------------------------------------------------------------------------------------------------|
| property  | Yes      | One of `processDefinition` (BPMN process id), `processDefinitionVersion`, or `tenantId`           |

### latency(elementId)

Return, in milliseconds, the gap between `elementId` completing and whatever ran next starting (queueing delay, distinct from processing duration).

| Parameter   | Required | Description                                                                                 |
|-------------|----------|-------------------------------------------------------------------------------------------------|
| elementId | No       | Element name or id; omitted sums every such gap across the whole process instance              |

Returns `null` if elementId is given but does not exist, or has not completed yet, in the process instance history (not an error).

### retryCount(elementId)

Count how many extra times `elementId`'s element instance was (re-)executed, beyond its first execution.

| Parameter  | Required | Description                                                                                 |
|------------|----------|---------------------------------------------------------------------------------------------|
| elementId  | No       | Element name or id; omitted sums extra executions across every element already executed     |

### executionCount(elementId)

Count the number of times an element (activity, event, transition) was executed in this process instance - unlike
`retryCount(elementId)`, this returns the raw execution count (not the count minus its first execution), and
unlike `path(...)`, it returns a number rather than a boolean.

| Parameter  | Required | Description                                       |
|------------|----------|---------------------------------------------------|
| elementId  | Yes      | Element name or id to count executions for        |

### tag(tagName)

Return `tagName` as-is: a simple, explicit way to attach a fixed label to the KPI record.

| Parameter | Required | Description                  |
|-----------|----------|-------------------------------|
| tagName   | Yes      | The literal value to return  |

### processInstanceKey()

Return the current job's process instance key. No parameters.

### businessKey()

Return the current process instance's business ID (Zeebe's native `businessId` field, set via `newCreateInstanceCommand().businessId(...)`, Camunda 8.9+). No parameters.

### variableChangedCount(variableName)

Return the number of distinct scopes that hold a value for `variableName` in this process instance - an approximation of how many times it was set, since the search API only exposes current values, not a change history.

| Parameter    | Required | Description                        |
|--------------|----------|--------------------------------------|
| variableName | Yes      | Name of the process variable to check |

### bucket(variableName, classification)

Classify process variable `variableName`'s numeric value using `classification`.

| Parameter      | Required | Description                                                                                                       |
|----------------|----------|-----------------------------------------------------------------------------------------------------------------------|
| variableName   | Yes      | Name of the process variable to classify                                                                          |
| classification | Yes      | Comma-separated list of `threshold:label` pairs, e.g. `0:low,100:medium,1000:high`                                |

Returns the label of the highest threshold not greater than the value, or `null` if the variable is not set or its value is below every threshold (not an error).

### decisionResult(decisionId)

Return the output (as a JSON string) of the most recent evaluation of DMN decision `decisionId` in this process instance.

| Parameter  | Required | Description                                   |
|------------|----------|--------------------------------------------------|
| decisionId | Yes      | The DMN decision id (decisionDefinitionId)      |

Returns `null` if the decision was never evaluated in this instance.

### messageCount(messageName)

Count messages correlated to (received by) the process instance.

| Parameter   | Required | Description                                                              |
|-------------|----------|--------------------------------------------------------------------------|
| messageName | No       | Message name filter; omitted counts every correlated message             |

### bpmErrorCount(errorCode, elementId)

Count BPMN errors caught by a boundary event matching `errorCode` and/or `elementId`.

| Parameter   | Required | Description                                                                                            |
|-------------|----------|--------------------------------------------------------------------------------------------------------|
| errorCode   | No       | BPMN error code filter (matched against the catching boundary event's name/id)                         |
| elementId | No       | Element name/id filter                                                                                 |

With neither parameter given, counts every BPMN error caught anywhere in the process instance. Relies on the
convention that each error-catching boundary event is named/identified after the BPMN error code it catches - this
convention silently breaks for a **generic/catch-all** boundary error event (no specific `errorRef`), since such an
event isn't named after any one error code. Prefer `bpmnErrorCount([errorCode])` below when that matters.

### bpmnError(elementId)

Return the `errorCode` of the most recent BPMN error thrown by `elementId` - the job-backed task or event that
**threw** the error (via `CamundaError.bpmnError(errorCode, message)`), not the boundary event that caught it.

| Parameter | Required | Description                                                                |
|-----------|----------|-------------------------------------------------------------------------------|
| elementId | No       | Id of the throwing element; omitted returns the most recent BPMN error thrown anywhere in the instance |

Returns `null` if no matching BPMN error was thrown. Backed by the job search API's `errorCode` field, so - unlike
`bpmErrorCount(errorCode, elementId)` - this correctly reports the real error code even when it was caught by a
generic/catch-all boundary event.

### bpmnErrorCount([errorCode])

Count BPMN errors actually thrown in the process instance, optionally filtered to a specific `errorCode`.

| Parameter | Required | Description                                                                          |
|-----------|----------|------------------------------------------------------------------------------------------|
| errorCode | No       | BPMN error code filter; omitted counts every BPMN error thrown anywhere in the instance  |

Like `bpmnError(elementId)`, this reads each job's real `errorCode` (the value passed to
`CamundaError.bpmnError(errorCode, message)`) rather than inferring it from a catching boundary event's name/id - so
it correctly counts errors caught by a generic/catch-all boundary event too, unlike `bpmErrorCount(errorCode, elementId)`.


# Save the KPI record to a database

The KPI record can optionally be saved as one row in a database table, in addition to (or instead of) being returned
as `kpiRecord`. This only ever **inserts** a new row - it never updates or upserts an existing one. Every call that
saves produces one more row.

## Inputs

| Name         | Description                                                | Class            | Level    |
|--------------|--------------------------------------------------------------|------------------|----------|
| saveDatabase | `Yes` or `No` - whether to save the KPI record to a database | java.lang.String | REQUIRED |
| jdbcString   | The JDBC connection string                                    | java.lang.String | Required when saveDatabase is `Yes` |
| tableName    | The table to insert the KPI record into                       | java.lang.String | Required when saveDatabase is `Yes` |

`jdbcString` typically references a secret rather than a literal connection string, so credentials never appear in
the BPMN file itself:

```
jdbcString: "{{secrets.kpi_jdbc}}"
```

resolved from a Spring property/environment variable named `SECRET_kpi_jdbc` (see `EnvironmentSecretProvider`), e.g.:

```
SECRET_kpi_jdbc: jdbc:postgresql://localhost:5432/c8-kpi?user=postgres&password=postgres
```

The corresponding JDBC driver (e.g. `org.postgresql:postgresql`) must be a dependency of the connector so it is
available to `java.sql.DriverManager` at runtime.

## How the insert is built

1. **Table introspection.** The connector reads the target table's columns via `DatabaseMetaData.getColumns(...)`,
   trying `tableName` as given, then upper-cased, then lower-cased - whichever candidate actually matches a table in
   the catalog wins. This exists because different databases (and different creation styles - quoted vs. unquoted
   identifiers) store table names with different casing; see the case-sensitivity note below for why this matters.
2. **Column matching.** Each key of the KPI record is matched against the table's columns **case-insensitively**.
   A KPI record key with no matching column is dropped from the insert. A table column with no matching KPI record
   key is simply left out of the `INSERT` statement, so it keeps whatever default the table defines (typically
   `NULL`, unless the column has a `DEFAULT`).
3. **Insert.** A single parameterized `INSERT INTO ... (...) VALUES (...)` is built from the matched columns and
   executed via `PreparedStatement`. The resolved table name and every matched column name are wrapped in double
   quotes, using the *exact* casing the catalog reported during introspection - see below for why.

If none of the KPI record's keys match any column of the table, the insert is skipped and a `DATABASE_NOT_MATCH`
error is raised. If the table cannot be found at all (under any of the three candidate names), or the insert itself
fails (wrong type, constraint violation, connection lost, ...), the connector raises `SQL_ERROR`.

## Identifier case-sensitivity (a common trap)

PostgreSQL (and most SQL databases) fold **unquoted** identifiers to lowercase, but preserve exact case for
**quoted** ones. That means:

- `CREATE TABLE kpiAmount (...)` (unquoted) actually creates a table named `kpiamount` (all lowercase).
- `CREATE TABLE "kpiAmount" (...)` (quoted) creates a table whose name is exactly `kpiAmount` (mixed case), and it
  stays case-sensitive forever after - only `"kpiAmount"`, quoted with that exact case, will ever match it again.
  An unquoted `INSERT INTO kpiAmount (...)` against a quoted-created table fails with
  `relation "kpiamount" does not exist`, because the unquoted reference gets folded to lowercase before lookup.

The connector protects against this automatically: because it always quotes the table/column names it resolved
during introspection (step 1 above) with their real catalog-stored casing, it works correctly regardless of whether
the table/columns were created quoted or unquoted. This only matters if you are testing manually with a SQL client:
if you created the table quoted, remember to quote it (with the same exact case) in every manual query too.

## Connection handling

Connections are cached per distinct `jdbcString` (so repeated executions against the same database reuse one open
connection instead of opening a new one every time) and are automatically closed after being idle for more than one
hour. No connection pooling library is used - this is a plain, synchronized in-memory cache suitable for a single
connector instance.

## Example

Given the table:

```sql
CREATE TABLE kpiAmount (
    id                     SERIAL PRIMARY KEY,
    dateUTC                VARCHAR(50),
    dateSFO                VARCHAR(50),
    blueExecution          BIGINT,
    city                   VARCHAR(100),
    amount                 BIGINT,
    flowValidationTaken    BOOLEAN,
    isHighValue            BOOLEAN,
    reviewAssignee         VARCHAR(200),
    reviewSLA              BOOLEAN,
    reviewSLADescription   TEXT,
    kpiLabel               VARCHAR(100),
    incidentCount          BIGINT,
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
```

and the connector's inputs set to:

```
saveDatabase: "Yes"
jdbcString:   "{{secrets.kpi_jdbc}}"
tableName:    "kpiAmount"
```

every execution of the KPI pilot from the [example above](#kpi-record) inserts one new row into `kpiAmount`, with
`id` and `createdAt` left to their column defaults since neither is a key of the pilot.
