# AWS SQS Implementation for Remote Job Message Handler

This document describes how the existing **ActiveMQ (JMS)** and **Kafka** remote job message handlers are implemented and specifies the design and code changes required to add an **AWS SQS** implementation for Spring Batch remote partitioning (e.g. COB). It also clarifies credential strategy by comparing with existing S3 usage in Fineract.

---

## 1. Current design: ActiveMQ (JMS) and Kafka

### 1.1 “Exactly one” handler enforcement

- **Location:** `fineract-core/.../condition/FineractRemoteJobMessageHandlerCondition.java`
- **Behaviour:** When the instance is a batch instance (manager and/or worker), **exactly one** message handler must be enabled. If Spring Events is enabled, the instance must be both batch manager and batch worker.
- **Logic (after SQS implementation):** The condition enforces **exactly one** of Spring Events, JMS, Kafka, or SQS. If Spring Events is enabled, the instance must be both batch manager and batch worker (unchanged). Enabling more than one handler fails startup with “exactly one Message Handler must be enabled.”

### 1.2 Shared flow (all handlers)

- **Manager:** `ManagerConfig` (when `fineract.mode.batch-manager-enabled=true`) creates:
  - `DirectChannel outboundRequests`
  - `OutputChannelInterceptor outputInterceptor`
- **Worker:** `WorkerConfig` (when `fineract.mode.batch-worker-enabled=true`) creates:
  - `QueueChannel inboundRequests` (used only by Spring Events flow)
  - `InputChannelInterceptor inputInterceptor`
- **COB:** `LoanCOBManagerConfiguration` sends partition requests to `outboundRequests`. The active message-handler config consumes from `outboundRequests` and sends to JMS queue / Kafka topic / Spring event. Workers either receive from JMS/Kafka/SQS and call `StepExecutionRequestHandler.handle()`, or (Spring Events) receive via `inboundRequests`.

Message payload is **ContextualMessage** (serializable), containing:
- `StepExecutionRequest` (jobExecutionId, stepExecutionId, stepName)
- `FineractContext` (tenant, context type, etc.)

`OutputChannelInterceptor` wraps `StepExecutionRequest` in `ContextualMessage` and sets `ThreadLocalContextUtil.getContext()` into it. `InputChannelInterceptor.beforeHandleMessage()` restores that context and returns the `StepExecutionRequest` for the handler.

### 1.3 JMS (ActiveMQ) implementation

| Concern | Implementation |
|--------|----------------|
| **Config** | `fineract.remote-job-message-handler.jms.*` in `application.properties` (env overrides: `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_JMS_*`). Properties: `enabled`, `request-queue-name`, `broker-url`, `broker-username`, `broker-password`. |
| **Properties class** | `FineractProperties.FineractRemoteJobMessageHandlerJmsProperties` in `fineract-core`. |
| **Manager** | `JmsManagerConfig` (condition: `JmsManagerCondition` = batch-manager-enabled **and** jms.enabled). Imports `JmsBrokerConfiguration`. Defines `IntegrationFlow outboundFlow`: `from(outboundRequests)` → intercept → log → `Jms.outboundAdapter(connectionFactory).destination(requestQueueName)`. |
| **Worker** | `JmsWorkerConfig` (condition: `JmsWorkerCondition` = batch-worker-enabled **and** jms.enabled). Uses same `JmsBrokerConfiguration`. Registers `JmsBatchWorkerMessageListener` on `DefaultMessageListenerContainer` for `requestQueueName`. Listener: convert JMS message → `Message<ContextualMessage>`, `inputInterceptor.beforeHandleMessage(msg)`, `stepExecutionRequestHandler.handle(request)`, then `message.acknowledge()`. |
| **Broker** | `JmsBrokerConfiguration` (conditional on jms.enabled): builds `ActiveMQConnectionFactory` from broker URL and optional username/password; `setTrustAllPackages(true)`; prefetch = 1. |
| **Conditions** | `conditions/jms/JmsManagerCondition.java`, `JmsWorkerCondition.java` (AllNestedConditions: manager/worker + `fineract.remote-job-message-handler.jms.enabled=true`). |

### 1.4 Kafka implementation

| Concern | Implementation |
|--------|----------------|
| **Config** | `fineract.remote-job-message-handler.kafka.*`: `enabled`, `bootstrap-servers`, `topic.*` (name, partitions, replicas, auto-create), `consumer.group-id`, consumer/producer/admin `extra-properties`. |
| **Properties class** | `FineractRemoteJobMessageHandlerKafkaProperties` (and nested topic/consumer/producer/admin) in `FineractProperties`. |
| **Manager** | `KafkaManagerConfig` (condition: `KafkaManagerCondition`). Builds `ProducerFactory` / `KafkaTemplate` from bootstrap servers and producer extra properties. `IntegrationFlow outboundFlow`: from `outboundRequests` → `KafkaProducerMessageHandler` with topic name and partition id = `stepExecutionId % topic.partitions`. |
| **Worker** | `KafkaWorkerConfig` (condition: `KafkaWorkerCondition`): `ConsumerFactory`, `ConcurrentKafkaListenerContainerFactory` (manual ack). `KafkaRemoteMessageListener` (component, same condition): `@KafkaListener(topics = "${fineract.remote-job-message-handler.kafka.topic.name}")`, receives `ContextualMessage`, calls `inputInterceptor.beforeHandleMessage(contextualMessage)`, `stepExecutionRequestHandler.handle(stepExecutionRequest)`, then `acknowledgment.acknowledge()`. |
| **Topic** | `KafkaJobTopicConfig` (condition: `KafkaRemoteJobTopicAutoCreateCondition`): creates `KafkaAdmin` and `NewTopic` from topic name/partitions/replicas. |
| **Conditions** | `conditions/kafka/KafkaManagerCondition`, `KafkaWorkerCondition`, `KafkaRemoteJobTopicAutoCreateCondition`. |

### 1.5 Spring Events (in-process)

- Manager: `SpringEventManagerConfig` → `ApplicationEventPublishingMessageHandler` (publishes to Spring’s `ApplicationEventPublisher`).
- Worker: `SpringEventWorkerConfig` → subscribes to the same event and sends to `inboundRequests`; worker step reads from `inboundRequests`.

---

## 2. S3 and AWS credentials in Fineract

Fineract uses **two different patterns** for AWS/S3:

1. **Content S3** (`fineract.content.s3.*`):
   - **Config:** `application.properties` with env overrides: `enabled`, `bucketName`, `accessKey`, `secretKey`, `region`, `endpoint`, `path-style-addressing-enabled`.
   - **Credentials:** Implemented in `ContentS3Config` (provider): if `accessKey` and `secretKey` are set, use `StaticCredentialsProvider`; otherwise use `DefaultCredentialsProvider.create()` (env vars, instance profile, etc.).
   - **Location:** `fineract-provider/.../config/ContentS3Config.java`, `FineractProperties.FineractContentS3Properties` in fineract-core.

2. **Report export S3** (`fineract.report.export.s3.*`):
   - **Config:** `enabled`, `bucket` (bucket name). No access key/secret in these properties.
   - **Credentials:** Uses **default chain only**: `DefaultCredentialsProvider` and `DefaultAwsRegionProviderChain` in `AmazonS3Config` (provider). Condition `AmazonS3ConfigCondition` requires default credentials to resolve.
   - **Location:** `fineract-provider/.../s3/AmazonS3Config.java`, `AmazonS3ConfigCondition.java`.

**Recommendation for SQS:** Use the **same pattern as Content S3** (application.properties with optional explicit credentials), with IAM role as the default in ECS:

- All remote-job-message-handler config (JMS, Kafka, and SQS) lives under `application.properties` with env overrides; SQS should be consistent.
- In ECS, prefer **task IAM role** (no static keys). Keep `accessKey`/`secretKey` optional for local development or special cases only.
- If static keys are required, do not hardcode them; inject from **AWS Secrets Manager** (or SSM Parameter Store) into task env vars and map those env vars to the same `application.properties` placeholders.
- Content S3 is the more flexible, user-facing pattern; report export S3 is default-chain-only and less flexible.

So: **SQS config and credentials should follow `fineract-provider` / `application.properties` and the Content S3 style** (optional explicit credentials, fallback to default chain), with **IAM role as production default** and static keys from secrets only when truly needed.

---

## 3. AWS SQS implementation – what to build

### 3.1 Behavioural summary

- **Manager:** Same as JMS/Kafka: consume from `outboundRequests`, serialize `ContextualMessage` (e.g. JSON), send to an SQS queue (one message per partition request).
- **Worker:** Poll SQS (or use long polling), receive messages, deserialize to `ContextualMessage`, run `inputInterceptor.beforeHandleMessage()` and `stepExecutionRequestHandler.handle()`, then delete the message (or use visibility timeout for at-least-once behaviour and idempotent step execution).
- **Exactly one handler:** Extend `FineractRemoteJobMessageHandlerCondition` so that exactly one of Spring Events, JMS, Kafka, or SQS is enabled (and when Spring Events is enabled, manager and worker must both be enabled as today).

### 3.2 Configuration (application.properties and FineractProperties)

Add under `fineract.remote-job-message-handler.sqs.*` (with env overrides `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_*`):

- `enabled` (boolean)
- `queue-url` (required; standard queue URL)
- `region` (e.g. `us-east-1`)
- `access-key` (optional, empty = use default chain)
- `secret-key` (optional)
- `visibility-timeout-seconds` (applied per-receive on `ReceiveMessageRequest`, clamped to the SQS cap of 43200s; also the fallback driver of the orphaned-partition takeover threshold when `orphaned-partition-threshold-seconds` is unset, default 3600)
- `orphaned-partition-threshold-seconds` (staleness age after which a STARTED partition may be taken over; 0/unset = 2x the effective visibility timeout)
- `wait-time-seconds` (long polling, e.g. 20)
- `max-number-of-messages` per receive (e.g. 1)
- `concurrency` (consumer threads per instance, default 1)

Add to `FineractProperties.FineractRemoteJobMessageHandlerProperties`:

- `private FineractRemoteJobMessageHandlerSqsProperties sqs;`

New nested class (e.g. in `FineractProperties`):

- `FineractRemoteJobMessageHandlerSqsProperties`: enabled, queueUrl, region, accessKey, secretKey, visibilityTimeoutSeconds, waitTimeSeconds, maxNumberOfMessages, concurrency.

### 3.3 Condition (exactly one handler)

**File:** `fineract-core/.../condition/FineractRemoteJobMessageHandlerCondition.java`

- In `isOnlyOneMessageHandlerEnabled`: count how many of `springEvents`, `jms`, `kafka`, `sqs` are enabled; return true iff exactly one is true. (Replace current `isSpringEventsEnabled ^ isJmsEnabled`.)
- In `isAnyMessageHandlerConfigured`: return true if any of the four is enabled.
- Ensure Spring Events rule unchanged: if Spring Events is enabled, batch instance must be both manager and worker.

**Note:** Today Kafka is not part of this condition; adding SQS is the right time to fix that so only one of Spring | JMS | Kafka | SQS is allowed.

### 3.4 Manager: SQS outbound

- **New config class:** e.g. `SqsManagerConfig` in `messagehandler/sqs/`.
- **Condition:** `SqsManagerCondition` (in `conditions/sqs/`): `batch-manager-enabled` **and** `fineract.remote-job-message-handler.sqs.enabled=true`.
- **Behaviour:**
  - Inject `DirectChannel outboundRequests`, `OutputChannelInterceptor outputInterceptor`, `FineractProperties`.
  - Build an `IntegrationFlow.from(outboundRequests).intercept(outputInterceptor).log(...).handle(...)`.
  - The handler must serialize each `ContextualMessage` to JSON and call SQS `SendMessage` (using `SqsClient`). Queue URL from properties. No need for FIFO unless you explicitly want FIFO ordering; standard queue is sufficient for partition distribution.
- **SqsClient bean:** Create a dedicated SQS client bean (or reuse a shared AWS config). Prefer building `SqsClient` in an `SqsClientConfiguration` (or similar) conditional on `sqs.enabled`, using the same credential logic as Content S3: if `accessKey`/`secretKey` set then `StaticCredentialsProvider`, else `DefaultCredentialsProvider`. Region from `fineract.remote-job-message-handler.sqs.region`.
- **ECS production guidance:** Keep `accessKey` and `secretKey` blank in production and rely on task role credentials from `DefaultCredentialsProvider`.

### 3.5 Worker: SQS consumer and handler

- **New config class:** `SqsWorkerConfig` (condition: `SqsWorkerCondition` = batch-worker-enabled and sqs.enabled).
- **New listener/handler class:** e.g. `SqsBatchWorkerMessageListener` or `SqsRemoteMessageListener` (component, conditional on `SqsWorkerCondition`):
  - Uses a polling loop or Spring integration / AWS SDK v2 SQS consumer to receive messages (long polling recommended: `waitTimeSeconds` up to 20).
  - For each message: deserialize body to `ContextualMessage` (JSON), call `inputInterceptor.beforeHandleMessage(contextualMessage)`, then `stepExecutionRequestHandler.handle(stepExecutionRequest)`, which returns a `HandleOutcome`.
  - Outcome `PROCESSED` or `DISCARD`: delete the message. Outcome `IN_FLIGHT` (partition running on another worker, or its metadata not readable): keep the message — it is the crash-recovery trigger and is re-evaluated after the next visibility expiry. On exception: do not delete; the message retries after the visibility timeout and the queue redrive policy dead-letters it after `maxReceiveCount`.
- **Concurrency:** Configurable via `fineract.remote-job-message-handler.sqs.concurrency` (default 1, same as JMS single consumer). That many threads each run the SQS long-poll loop; SQS distributes messages across workers. Each partition is processed by one worker; standard queue gives at-least-once delivery.
- **Visibility timeout:** Applied **per-receive** via `ReceiveMessageRequest.visibilityTimeout` from `visibility-timeout-seconds` (clamped to the SQS hard cap of 43200s), so the configured value is authoritative and does not fall back to the queue attribute. Size it comfortably above the p99 partition duration.
- **DLQ (REQUIRED — queue redrive policy):** The main queue **must** have a redrive policy to a DLQ with a suitable `maxReceiveCount`. The worker never sends to a DLQ itself and never deletes a failed message; a message that keeps failing (e.g. a poison payload that cannot be deserialized) reappears after each visibility timeout and is moved to the DLQ by SQS after `maxReceiveCount` receives. **Without a redrive policy a poison message retries forever.** Note that `IN_FLIGHT` keep-cycles also count toward `maxReceiveCount`, so choose it above `max-partition-runtime / visibility-timeout` (e.g. 5+) and alarm on DLQ depth.
- **Shutdown and drain:** The listener implements `SmartLifecycle` (default phase, so it stops in the **first** shutdown phase — before the web graceful-shutdown phase, before `cobTaskExecutor`'s lifecycle stop, and before any bean destruction; JMS/Kafka workers get the same behaviour from their lifecycle-managed containers). On stop: intake stops immediately (a message returned by a long poll already in flight is **released** with `ChangeMessageVisibility(0)` so another worker picks it up right away, not processed), an in-flight partition gets the drain window (`shutdown-timeout-seconds`, default 25) to finish and delete its message, and idle workers are force-stopped immediately. The value is one total budget: the last `min(5, value/2)` seconds are reserved for the post-interrupt wind-down (status persistence, an in-flight SQS delete/release), so the in-flight wait proper is the remainder — e.g. default 25 = 20s wait + up to 5s wind-down. Stopping twice is safe: a second `stop()` (the `@PreDestroy` safety net after a lifecycle stop) returns immediately instead of re-running the drain. The async `stop(Runnable)` form means the wait is additionally bounded by `spring.lifecycle.timeout-per-shutdown-phase` (`FINERACT_TIMEOUT_PER_SHUTDOWN`, default 30s); the worker logs a startup WARN when the drain budget exceeds that phase timeout, since the misconfiguration silently voids the drain guarantee. The SQS delete/release calls run with the thread's interrupt flag temporarily cleared (the AWS SDK aborts calls on interrupted threads). Two known trade-offs: (1) force-stopping an **idle** worker aborts its in-flight long poll, so a message SQS had already dispatched for that receive cannot be released and stays invisible for the full visibility timeout; (2) on a **combined web+worker instance** the SQS drain runs *before* the web graceful-shutdown phase and competes with it for the orchestrator's stop window — deliberate (a lost partition costs hours of recovery, HTTP clients retry), but size the stop window for both phases or run dedicated workers. **A partition interrupted at the drain deadline either persists STOPPED/FAILED — its redelivered message is discarded and it needs a manual job restart — or, if the interrupted thread cannot persist the status, stays STARTED and is auto-taken-over after the orphan threshold (`orphaned-partition-threshold-seconds`, default 2x visibility timeout).** The drain window is what makes either outcome rare. This section is the canonical reference for the shutdown contract (javadoc and log messages point here). Follow-up worth tracking: spring-cloud-aws's `SqsMessageListenerContainer` (version-managed by the already-imported BOM) provides equivalent lifecycle/ack machinery upstream and could replace this hand-rolled listener (needs the async `SqsAsyncClient` and MANUAL ack mode).
- **Multitenant:** `ContextualMessage` carries `FineractContext` (tenant, auth, business dates). The worker calls `inputInterceptor.beforeHandleMessage(contextualMessage)`, which runs `ThreadLocalContextUtil.init(contextualMessage.getContext())` and `setActionContext(ActionContext.COB)` before `stepExecutionRequestHandler.handle()`, so each partition runs with the correct tenant context. No change to existing JMS/Kafka/Spring behaviour.

**Logging and error handling (SQS vs JMS/Kafka):** Worker threads use `ExecutorService.execute(Runnable)` (not `submit`), matching patterns like `SendMessageToSmsGatewayTasklet` and avoiding ignored `Future` return values. Each runnable wraps the poll loop in try-catch and logs `"SQS worker thread failed (worker {}), stopping poll loop"` on exception. `InterruptedException` is handled separately (interrupt flag restored, loop exits). Per-message: debug `"Received SQS partition message"` / `"SQS message {} deleted (outcome {})"`; on failure, error `"Exception while processing SQS message"` (message kept for retry/redrive); on an in-flight duplicate, info `"Keeping SQS message {} on the queue"`. Kafka logs processing exceptions but still commits the offset; JMS acknowledges except on an `IN_FLIGHT` outcome (best-effort keep). SQS never deletes on failure. `Throwable`s that are not `Exception` (e.g. `OutOfMemoryError`) are not caught and will terminate that worker thread; monitor logs and thread count.

### 3.6 Serialization

- **SqsMessageSerializer** (component, conditional on `sqs.enabled`) uses the application’s existing **ObjectMapper** bean from `JerseyJacksonConverterConfig` (same as REST/Jersey JSON). **No additional JARs:** Jackson is already on the classpath via Spring Boot; SQS uses only `ObjectMapper.writeValueAsString` / `readValue`. Kafka uses Spring Kafka’s `JsonSerializer`/`JsonDeserializer` (also Jackson-based, from spring-kafka); SQS reuses the app bean so config (e.g. `JacksonLocalDateArrayModule`, `FAIL_ON_UNKNOWN_PROPERTIES`) is consistent. Single place for SQS payload (de)serialization.

### 3.7 File/class checklist

| Area | File(s) / change |
|------|-------------------|
| **Core condition** | `fineract-core/.../condition/FineractRemoteJobMessageHandlerCondition.java` – add sqs to “exactly one” and “any configured”; include kafka in the same logic. |
| **Properties** | `fineract-core/.../config/FineractProperties.java` – add `FineractRemoteJobMessageHandlerSqsProperties` and `getSqs()` in remote handler properties. |
| **application.properties** | `fineract-provider/src/main/resources/application.properties` – add `fineract.remote-job-message-handler.sqs.*` with env var placeholders. |
| **SQS client** | New config (e.g. `SqsClientConfiguration` or `SqsBrokerConfiguration`) in provider: build `SqsClient` with region and credentials (Content S3 style). Condition: sqs.enabled. |
| **Manager** | New `messagehandler/sqs/SqsManagerConfig.java` + `conditions/sqs/SqsManagerCondition.java`. |
| **Worker** | New `messagehandler/sqs/SqsWorkerConfig.java`, `SqsBatchWorkerMessageListener`, `conditions/sqs/SqsWorkerCondition.java`. |
| **Serialization** | New `messagehandler/sqs/SqsMessageSerializer.java` (uses application `ObjectMapper`; conditional on sqs.enabled). |
| **Dependencies** | Add AWS SDK v2 SQS (and possibly SQS-specific dependencies) in `fineract-provider/build.gradle` if not already present (S3/content may already bring AWS SDK). |

### 3.8 Queue type decision

- **Standard queue:** Simpler, good for partition distribution; at-least-once; no ordering guarantee per partition (acceptable if step execution is idempotent and partition keys are independent).
- **FIFO queue:** Use only if you need strict ordering of partition requests; requires `.fifo` suffix and message group id (e.g. by job execution id). Document that FIFO adds complexity and may reduce throughput.

Decision: **standard queue only** for this implementation.

---

## 4. Confirmed decisions

1. **Queue type:** Standard SQS queue only.
2. **Queue config:** Use fixed `queue-url` in configuration.
3. **Credentials in ECS:** IAM task role is configured and is the production default.
4. **Network/account:** Same AWS account and same VPC as existing components.
5. **Fallback credential properties:** Keep optional `access-key` / `secret-key` properties; when used, values come from Secrets Manager/SSM injected as env vars.

Credential handling recommendation based on these decisions:
- Primary: IAM role + default credential chain.
- Secondary fallback: optional static keys (`access-key` / `secret-key`) for non-ECS local/dev or exceptional scenarios.
- If fallback keys are used, source them from Secrets Manager/SSM and inject as env vars; do not store plaintext in source control.

---

## 5. Production deployment

### 5.1 Deployment topology

- **Manager instance:** One Fineract instance with `fineract.mode.batch-manager-enabled=true` and SQS enabled. It receives COB (and other remote job) triggers, creates partitions, and sends one SQS message per partition to the queue.
- **Worker instances:** One or more Fineract instances with `fineract.mode.batch-worker-enabled=true` and SQS enabled. Each worker polls the same SQS queue, processes messages (e.g. loan COB steps), and deletes messages on success.
- **Same queue:** Manager and workers use the same `queue-url`. Standard SQS distributes messages to consumers; scale workers by adding more instances or increasing `concurrency` per instance.
- **Exactly one message handler:** Ensure only SQS is enabled (not JMS, Kafka, or Spring Events) for manager and workers when using SQS in production.

### 5.2 Credentials: IAM role (recommended for production)

Use IAM roles so the application uses the **default credential chain** and never stores long-lived keys.

**Behaviour:** If neither `access-key` nor `secret-key` is set (or only one is set), the SQS client uses `DefaultCredentialsProvider`. That chain uses, in order: env vars (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`), system properties, Web Identity Token, EC2/ECS instance profile (task role), etc.

**Production setup:**

1. **Do not set** `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_ACCESS_KEY` or `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_SECRET_KEY` (leave them unset or empty).
2. Attach an **IAM role** to the run environment so the default chain resolves:
   - **ECS:** Use a **task IAM role** (not task execution role). Attach it to the task definition; the container gets credentials via the metadata endpoint.
   - **EC2:** Use an **instance profile** (instance IAM role).
   - **EKS / Kubernetes:** Use IRSA (IAM Roles for Service Accounts) or an equivalent mechanism so the pod has credentials.
3. Grant the role the minimum SQS permissions needed for the queue (and optional DLQ):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage"
      ],
      "Resource": "arn:aws:sqs:REGION:ACCOUNT_ID:QUEUE_NAME"
    },
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:ChangeMessageVisibility"
      ],
      "Resource": "arn:aws:sqs:REGION:ACCOUNT_ID:QUEUE_NAME"
    }
  ]
}
```

The worker never sends to the DLQ itself (SQS's redrive policy moves failed messages), so no DLQ permission is needed for the application. Replace `REGION`, `ACCOUNT_ID`, and `QUEUE_NAME` with your values.

### 5.3 Credentials: Static access key and secret (optional)

Use static credentials only when IAM roles are not available (e.g. some on-prem or non-AWS environments, or local development).

**Behaviour:** The application uses static credentials **only when both** `access-key` and `secret-key` are set (see `isAccessKeyProtected()` in `FineractRemoteJobMessageHandlerSqsProperties`). If only one is set, the default credential chain is used.

**Production-safe approach:**

1. **Do not** put keys in `application.properties` or in source control.
2. Store the secret in **AWS Secrets Manager** (or SSM Parameter Store, or your vault).
3. Inject into the process as environment variables, e.g.:
   - `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_ACCESS_KEY`
   - `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_SECRET_KEY`
4. In ECS: use **secrets** in the task definition (e.g. `secrets` from Secrets Manager). In Kubernetes: use a Secret resource and envFrom or env valueFrom.

**Local development:** Set the two env vars in your shell or `.env` file; the application will use `StaticCredentialsProvider` when both are non-empty.

### 5.4 Production environment variables (reference)

| Variable | Required | Description | Production note |
|----------|----------|-------------|-----------------|
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_ENABLED` | Yes | `true` to use SQS | Set to `true` for manager and workers. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_QUEUE_URL` | Yes | Full SQS queue URL | e.g. `https://sqs.ap-south-1.amazonaws.com/123456789012/my-cob-queue` |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_REGION` | Yes | AWS region | e.g. `ap-south-1`; must match queue region. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_ACCESS_KEY` | No | AWS access key | **Leave unset** when using IAM role. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_SECRET_KEY` | No | AWS secret key | **Leave unset** when using IAM role. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_ENDPOINT` | No | Override SQS endpoint | **Leave unset** for real AWS; set only for LocalStack or custom endpoints. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_WAIT_TIME_SECONDS` | No | Long poll wait (max 20) | Default 20. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_VISIBILITY_TIMEOUT_SECONDS` | No | Visibility timeout, applied per-receive | Default 3600; clamped to the SQS cap 43200. **Always applied per-receive — the queue attribute never governs** (an unset/non-positive value applies the 3600 default, keeping redelivery cadence and takeover math on the same value). Sets the redelivery (recovery-check) interval; also the fallback driver of the orphan-takeover threshold (2x this value) when the dedicated threshold below is unset. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_ORPHANED_PARTITION_THRESHOLD_SECONDS` | No | Staleness age before a STARTED partition may be taken over | Default 0 = unset: threshold is 2x the effective visibility timeout (the pre-knob behavior). Decouples takeover speed from the redelivery interval. Sizing: must comfortably exceed the longest single chunk (`lastUpdated` advances at every chunk commit), or a live worker's slow partition gets stolen — a value below the visibility timeout logs a startup WARN stating exactly that. Values above 86400 (2x the SQS visibility cap) are clamped with a WARN (a larger value — e.g. a milliseconds units mistake — would refuse takeover on every redelivery until the recovery message dead-letters). Takeover is only *evaluated* at redeliveries (~multiples of the visibility timeout after dispatch), so recovery latency is always a multiple of VT regardless of this value: the knob can only move the takeover from the 2nd redelivery (2x VT, the default) to the 1st (set it at or below VT) — it halves the VT coupling, it cannot remove it, and every value in (1x VT, 2x VT] is the default with extra steps. Genuine decoupling (minute-scale recovery) needs a time-driven staleness check that fires independently of receive events — the same infrastructure as the liveness heartbeat; do not expect this knob alone to deliver it. Receive budget: refusals burn receives, so `maxReceiveCount >= ceil(threshold / VT) + 3` — a threshold above 2x the visibility timeout logs a startup WARN with the computed receive count (the cap alone cannot guarantee takeover beats the DLQ; the budget depends on the actual VT). **Precondition:** staleness compares `LocalDateTime` values written by different JVMs in their system zones — all containers must run the same timezone (the jib image pins UTC); small thresholds amplify any zone skew into instant steals or never-takeovers, and a non-UTC system zone logs a startup WARN. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_MAX_NUMBER_OF_MESSAGES` | No | Messages per ReceiveMessage call | Default 1. Keep at 1: a buffered second message's visibility clock runs while the first processes. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_CONCURRENCY` | No | Consumer threads per instance | Default 1; increase to process more messages in parallel per instance. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_SHUTDOWN_TIMEOUT_SECONDS` | No | Total drain budget on SIGTERM | Default 25 (negative values fall back to the default; 0 disables the drain; the last `min(5, value/2)` seconds are the wind-down reserve, so e.g. 25 = 20s in-flight wait + 5s wind-down). Also bounded by `FINERACT_TIMEOUT_PER_SHUTDOWN` (default 30s), so raise the two together, inside the orchestrator's stop window — which must additionally cover the web-drain phase on combined web+worker instances. Size `maxReceiveCount` to also absorb ~1 extra receive per dying replica per deploy wave (shutdown releases re-deliver the message immediately). **Fargate long-drain recipe:** ECS `stopTimeout=120` (the Fargate cap), `FINERACT_TIMEOUT_PER_SHUTDOWN=100s`, this var `=90`. Recovery semantics of a drain-deadline interrupt: see the shutdown-and-drain bullet in section 3.5 (canonical). Note: draining only saves partitions that can finish inside this window; protecting longer partitions from scale-in needs ECS scale-in protection or a scheduled minimum-capacity floor across the COB window. |

Manager and worker mode are controlled by:

- `fineract.mode.batch-manager-enabled` / `FINERACT_MODE_BATCH_MANAGER_ENABLED`
- `fineract.mode.batch-worker-enabled` / `FINERACT_MODE_BATCH_WORKER_ENABLED`

### 5.5 AWS setup checklist

1. Create a **standard SQS queue** in the same region as your Fineract deployment.
2. **(REQUIRED)** Create a **DLQ** and set the main queue’s **redrive policy** to send messages to the DLQ after a suitable `maxReceiveCount` (5+; must exceed `max-partition-runtime / visibility-timeout` since in-flight keep-cycles also count). Without a redrive policy a poison message retries forever. Alarm on DLQ depth, and set the DLQ `MessageRetentionPeriod` high enough (e.g. 14 days) to investigate before evidence expires.
3. The application applies `visibility-timeout-seconds` per-receive, overriding the queue attribute; set it above your longest partition step.
4. For production, attach an **IAM role** (task role / instance profile / IRSA) with the SQS permissions above; leave access key and secret key unset.
5. Configure **VPC and security**: workers and manager must be able to reach the SQS endpoint (same region and VPC or public endpoint with correct IAM).

### 5.6 SQS message size limit

**AWS SQS maximum message size is 1 MiB (1,048,576 bytes)** per message body. See [Amazon SQS message quotas](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html) (Message size). For payloads larger than 1 MiB, AWS recommends the [Amazon SQS Extended Client Library](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-s3-messages.html) (message payload in S3).

**What is sent:** The manager publishes a **ContextualMessage** (JSON-serialized via `SqsMessageSerializer`), containing:

- **StepExecutionRequest:** `jobExecutionId` (long), `stepExecutionId` (long), `stepName` (string, e.g. `"loanCOBWorkerStep:partition_0"`).
- **FineractContext:** `contextHolder`, tenant (id, identifier, name, timezone, connection), `authTokenContext` (e.g. JWT), `businessDateContext` (small map), `actionContext`.

**Typical size:** A few hundred bytes to a few KB (step names and tenant identifiers are short; the main variable is `authTokenContext`, which is usually 1–4 KB for a JWT). Even with a large token and tenant metadata, the payload stays well under 50 KB.

**Conclusion:** There is **no realistic chance** of exceeding the 1 MiB limit with the current partition payload. For COB and existing remote job partitioning, no change is required.

---

## 6. Summary

- **JMS:** Manager sends to a single request queue; workers listen on that queue and call `StepExecutionRequestHandler.handle()`; config and credentials via application.properties (broker URL and optional username/password).
- **Kafka:** Manager sends to a topic with partition key; workers consume via `@KafkaListener` and call the same handler; config via application.properties.
- **SQS (to add):** Manager sends to an SQS standard queue; workers poll (long polling), deserialize, call the same handler, then delete. Config uses `queue-url` in application.properties. Credentials follow Content S3 style (optional explicit accessKey/secretKey, fallback to default credential chain) with IAM task role as production default. Condition must be updated so exactly one of Spring Events, JMS, Kafka, or SQS is enabled, with Kafka included in the check.

This design keeps the same manager/worker contract (`ContextualMessage` + `StepExecutionRequestHandler`) and integrates SQS in a way consistent with existing JMS/Kafka and with Fineract’s application.properties and S3 credential approach.

---

## 7. Backward compatibility

- **No changes to existing handlers:** JMS, Kafka, and Spring Events code paths are unchanged. No edits to `JmsManagerConfig`, `JmsWorkerConfig`, `JmsBrokerConfiguration`, `KafkaManagerConfig`, `KafkaWorkerConfig`, `KafkaRemoteMessageListener`, `SpringEventManagerConfig`, `SpringEventWorkerConfig`, `ManagerConfig`, or `WorkerConfig`.
- **Condition:** Only the “exactly one handler” logic was extended: the count now includes Kafka and SQS. When only Spring Events or only JMS is enabled, behaviour is unchanged (same XOR outcome). New failure mode: enabling more than one of Spring | JMS | Kafka | SQS fails startup with the same error message.
- **Properties:** All new keys are under `fineract.remote-job-message-handler.sqs.*` with `sqs.enabled` default `false`. No existing property names or defaults were changed.
- **Beans:** SQS beans (`SqsBrokerConfiguration`, `SqsManagerConfig`, `SqsWorkerConfig`, `SqsBatchWorkerMessageListener`, `SqsMessageSerializer`) are conditional on `sqs.enabled` or SQS manager/worker conditions, so they are not created when SQS is disabled. No impact on existing JMS/Kafka/Spring-only deployments.
