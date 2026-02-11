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
- `visibility-timeout-seconds` (queue-level in AWS; used for documentation and optional ChangeMessageVisibility)
- `wait-time-seconds` (long polling, e.g. 20)
- `max-number-of-messages` per receive (e.g. 1)
- `concurrency` (consumer threads per instance, default 1)
- `dlq-queue-url` (optional; for redrive policy reference or explicit send on failure)

Add to `FineractProperties.FineractRemoteJobMessageHandlerProperties`:

- `private FineractRemoteJobMessageHandlerSqsProperties sqs;`

New nested class (e.g. in `FineractProperties`):

- `FineractRemoteJobMessageHandlerSqsProperties`: enabled, queueUrl, region, accessKey, secretKey, visibilityTimeoutSeconds, waitTimeSeconds, maxNumberOfMessages, concurrency, dlqQueueUrl.

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
  - For each message: deserialize body to `ContextualMessage` (JSON), call `inputInterceptor.beforeHandleMessage(contextualMessage)`, then `stepExecutionRequestHandler.handle(stepExecutionRequest)`.
  - On success: delete the message. On failure: do not delete (or use visibility timeout and optional DLQ); document at-least-once and idempotent step behaviour.
- **Concurrency:** Configurable via `fineract.remote-job-message-handler.sqs.concurrency` (default 1, same as JMS single consumer). That many threads each run the SQS long-poll loop; SQS distributes messages across workers. Each partition is processed by one worker; standard queue gives at-least-once delivery.
- **Visibility timeout:** Configured at **queue level** in AWS (not in `ReceiveMessageRequest`). Set the SQS queue’s default visibility timeout (e.g. to match `visibility-timeout-seconds` in config). Use AWS `ChangeMessageVisibility` if processing may exceed that (e.g. extend visibility in the worker for long-running steps).
- **DLQ:** Optional `dlq-queue-url`. Two patterns supported: (1) **Queue redrive policy:** Configure the main queue in AWS with a redrive policy to the DLQ after `maxReceiveCount`; on failure the worker does not delete the message, so it reappears after visibility timeout and is eventually moved to DLQ by SQS. (2) **Explicit send on failure:** When `dlq-queue-url` is set and processing throws, the worker sends the message body to the DLQ and deletes it from the main queue so it is not retried indefinitely.
- **Multitenant:** `ContextualMessage` carries `FineractContext` (tenant, auth, business dates). The worker calls `inputInterceptor.beforeHandleMessage(contextualMessage)`, which runs `ThreadLocalContextUtil.init(contextualMessage.getContext())` and `setActionContext(ActionContext.COB)` before `stepExecutionRequestHandler.handle()`, so each partition runs with the correct tenant context. No change to existing JMS/Kafka/Spring behaviour.

**Logging and error handling (SQS vs JMS/Kafka):** Worker threads use `ExecutorService.execute(Runnable)` (not `submit`), matching patterns like `SendMessageToSmsGatewayTasklet` and avoiding ignored `Future` return values. Each runnable wraps the poll loop in try-catch and logs `"SQS worker thread failed (worker {}), stopping poll loop"` on exception. `InterruptedException` is handled separately (interrupt flag restored, loop exits). Per-message: debug `"Received SQS partition message"` / `"SQS message deleted after successful processing"`; on failure, error `"Exception while processing SQS message"` and optionally `"Moved failed SQS message to DLQ"`. JMS/Kafka both log processing exceptions but still acknowledge (message removed); SQS does not delete on failure unless DLQ is configured. `Throwable`s that are not `Exception` (e.g. `OutOfMemoryError`) are not caught and will terminate that worker thread; monitor logs and thread count.

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

If you use a DLQ and the worker sends failed messages to it explicitly, add:

```json
{
  "Effect": "Allow",
  "Action": ["sqs:SendMessage"],
  "Resource": "arn:aws:sqs:REGION:ACCOUNT_ID:DLQ_QUEUE_NAME"
}
```

Replace `REGION`, `ACCOUNT_ID`, `QUEUE_NAME`, and `DLQ_QUEUE_NAME` with your values.

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
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_VISIBILITY_TIMEOUT_SECONDS` | No | Visibility timeout (queue-level in AWS) | Match your queue’s visibility timeout; used for docs and optional ChangeMessageVisibility. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_MAX_NUMBER_OF_MESSAGES` | No | Messages per ReceiveMessage call | Default 1. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_CONCURRENCY` | No | Consumer threads per instance | Default 1; increase to process more messages in parallel per instance. |
| `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_DLQ_QUEUE_URL` | No | Dead-letter queue URL | Optional; set if you use explicit send-to-DLQ on failure. |

Manager and worker mode are controlled by:

- `fineract.mode.batch-manager-enabled` / `FINERACT_MODE_BATCH_MANAGER_ENABLED`
- `fineract.mode.batch-worker-enabled` / `FINERACT_MODE_BATCH_WORKER_ENABLED`

### 5.5 AWS setup checklist

1. Create a **standard SQS queue** in the same region as your Fineract deployment.
2. (Optional) Create a **DLQ** and set the main queue’s **redrive policy** to send messages to the DLQ after a suitable `maxReceiveCount`; or configure `FINERACT_REMOTE_JOB_MESSAGE_HANDLER_SQS_DLQ_QUEUE_URL` and use explicit send-on-failure.
3. Set the queue **visibility timeout** (e.g. 60–300 seconds) to be at least as long as your longest partition step; workers can extend it via `ChangeMessageVisibility` if needed.
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
