# activemq-log-plugins

Artemis broker plugin that intercepts message acknowledgments on SWIM subscription queues and publishes structured delivery audit records to an internal `ACK_MONITOR` topic.

This gives you server-side, broker-level proof that a message was actually delivered to a client, something the application layer cannot provide on its own.

## The problem it solves

A SWIM Consumer persists the events it processes. But that only tells you what the consumer received from the application's point of view. If you need to prove delivery for CP1 audit, SLA disputes, or troubleshooting, you need the broker to confirm that the message was ACKed, with exactly who consumed it, from which queue, and when.

This plugin hooks directly into Artemis's `messageAcknowledged` callback and captures that evidence at the source.

## How it works

1. The plugin registers itself with the Artemis server on startup.
2. On every `messageAcknowledged` call, it checks whether the consumer's queue matches the SWIM subscription naming pattern.
3. If it matches, it fires an async task that builds an `ACKMessage` record and:
   - Logs it as structured JSON (Loki/Splunk-friendly)
   - Routes it as a durable AMQP message to the `ACK_MONITOR` topic (MULTICAST, 7-day expiry)

The async dispatch uses a fixed thread pool (`2 × available CPUs`) so the ACK processing path on the broker is not blocked.

## Queue name filter

The plugin only acts on queues whose name matches the SWIM subscription queue format: `PREFIX-userId-UUID`.

```
^[A-Za-z0-9_-]+-[a-zA-Z0-9._-]+-[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$
```

The last segment must be a full UUID (8-4-4-4-12 hex groups with hyphens).

| Queue name | Matches |
|------------|---------|
| `DNOTAM-marcelo-550e8400-e29b-41d4-a716-446655440000` | ✓ |
| `ed254-john-85e0fc55-1234-abcd-ef01-23456789abcd` | ✓ |
| `DLQ` | ✗ |
| `DNOTAM-marcelo-short` | ✗ (last segment is not a UUID) |

This avoids polluting the `ACK_MONITOR` topic with internal broker activity (DLQs, management queues, etc).

## ACK record structure

```json
{
  "messageId": 1234,
  "correlationId": "abc-123",
  "consumerID": 42,
  "connectionID": "client-01",
  "connectionRemoteAddress": "10.0.0.5:51234",
  "connectionCreationTime": "2026-01-15T10:30:00.000Z",
  "timestamp": "2026-01-15T10:30:00.123Z",
  "messageAddress": "DNOTAM",
  "queueName": "DNOTAM-marcelo-550e8400-e29b-41d4-a716-446655440000"
}
```

If the message carries a W3C `traceparent` header, the trace ID is extracted and included in the log for correlation with Tempo/Jaeger.

## Artemis: address vs queue

- **Address** (`messageAddress`): the routing name where the message was published.
- **Queue** (`queueName`): the physical queue the consumer is reading from.

In a MULTICAST address (SWIM pub/sub pattern), the address is the topic name (e.g. `DNOTAM`) and the queue is the per-subscription queue (e.g. `DNOTAM.marcelo.85e0fc55`). They are different things. The filter is applied to the **queue name**, which is what the Subscription Manager controls.

## Build

```bash
./mvnw clean package -DskipTests
```

Output: `target/activemq-log-plugin.jar`

## Local install test

Build the JAR first, then start a local Artemis broker with the plugin loaded:

```bash
./mvnw clean package -DskipTests
podman compose up -d
```

The `compose.yml` mounts the built JAR and the `post-config.sh` into the broker image. The script copies the JAR into the broker's `lib/` and injects the `<broker-plugin>` entry into `broker.xml` before Artemis starts.

**Verify the plugin is active:**

1. Open the Artemis console at http://localhost:8161 (admin / admin)
2. Navigate to the broker → Addresses
3. After any message ACK on a queue matching the pattern `SERVICE.user.subscriptionId`, the `ACK_MONITOR` address appears automatically

To simulate an ACK, send and consume a message on any queue whose name follows the pattern (e.g. `DNOTAM.testuser.85e0fc55`).

## Deployment on OpenShift (AMQ Broker Operator)

The plugin is deployed via a custom init container image that copies the JAR into the broker's lib directory before startup.

### 1. Build the init image

```bash
cd src/main/openshift
./build-init-image.sh
```

| Variable | Default | Description |
|----------|---------|-------------|
| `IMAGE_REGISTRY` | `quay.io` | Container registry |
| `IMAGE_NAMESPACE` | `swim-developer` | Registry namespace |
| `IMAGE_NAME` | `amq-broker-init-swim` | Image name |
| `IMAGE_TAG` | `7.13.2` | Image tag (should match AMQ Broker version) |

### 2. Push the image

```bash
podman push quay.io/swim-developer/amq-broker-init-swim:7.13.2
```

### 3. Reference the init image in the ActiveMQArtemis CR

```yaml
apiVersion: broker.amq.io/v1beta1
kind: ActiveMQArtemis
metadata:
  name: swim-broker
spec:
  deploymentPlan:
    initImage: quay.io/swim-developer/amq-broker-init-swim:7.13.2
```

The `post-config.sh` script inside the init image:
1. Copies `activemq-log-plugin.jar` into the broker's `lib/` directory.
2. Injects the `<broker-plugin class-name="...ACKMonitorPlugin"/>` entry into `broker.xml` if not already present.

## References

- [ArtemisCloud custom init container](https://artemiscloud.io/docs/tutorials/initcontainer/)
- [Apache Artemis Broker Plugins](https://activemq.apache.org/components/artemis/documentation/latest/broker-plugins.html)
