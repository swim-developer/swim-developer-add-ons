package com.github.swim_developer.artemis.plugin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.core.message.impl.CoreMessage;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.core.server.plugin.impl.LoggingActiveMQServerPlugin;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public class ACKMonitorPlugin extends LoggingActiveMQServerPlugin {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final long serialVersionUID = 1L;
    private static final Pattern MESSAGE_FILTER_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+-[a-zA-Z0-9._-]+-[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$");
    private static final String ACK_MONITOR_TOPIC = "ACK_MONITOR";
    private static final long MESSAGE_EXPIRY_DAYS = 7;
    private static final long MESSAGE_EXPIRY_MS = MESSAGE_EXPIRY_DAYS * 24 * 60 * 60 * 1000L;
    private static final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private final AtomicReference<ActiveMQServer> serverAtomicReference = new AtomicReference<>();
    private final AtomicBoolean topicCreated = new AtomicBoolean(false);

    @Override
    public void registered(ActiveMQServer server) {
        this.serverAtomicReference.set(server);
        logger.info("{\"event\":\"PLUGIN_REGISTERED\",\"plugin\":\"ACKMonitorPlugin\"}");
    }

    @Override
    public void unregistered(ActiveMQServer server) {
        this.serverAtomicReference.set(null);
        logger.info("{\"event\":\"PLUGIN_UNREGISTERED\",\"plugin\":\"ACKMonitorPlugin\"}");
    }

    @Override
    public void messageAcknowledged(final Transaction tx, final MessageReference ref, final AckReason reason, final ServerConsumer consumer) throws ActiveMQException {
        if (consumer == null) {
            super.messageAcknowledged(tx, ref, reason, null);
            return;
        }

        final Message message = ref.getMessage();
        final SimpleString messageAddressSimple = message.getAddressSimpleString();
        final Queue queue = consumer.getQueue();
        final String queueName = queue == null ? "" : queue.getName().toString();
        final boolean filterMatched = MESSAGE_FILTER_PATTERN.matcher(queueName).matches();

        super.messageAcknowledged(tx, ref, reason, consumer);

        if (!filterMatched) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            processMessageACK(consumer, message, messageAddressSimple);
        }, executor).exceptionally(ex -> {
            logger.error("{\"event\":\"ACK_TASK_FAILED\",\"error\":\"{}\"}", ex.getMessage(), ex);
            return null;
        });
    }

    private void ensureTopicExists(ActiveMQServer server) {
        if (topicCreated.get()) return;

        try {
            if (server.getAddressInfo(SimpleString.toSimpleString(ACK_MONITOR_TOPIC)) == null) {
                server.addAddressInfo(new org.apache.activemq.artemis.core.server.impl.AddressInfo(
                        SimpleString.toSimpleString(ACK_MONITOR_TOPIC),
                        RoutingType.MULTICAST
                ));
                logger.info("{\"event\":\"TOPIC_CREATED\",\"topic\":\"{}\"}", ACK_MONITOR_TOPIC);
            }
            topicCreated.set(true);
        } catch (Exception e) {
            logger.error("{\"event\":\"TOPIC_CREATION_FAILED\",\"topic\":\"{}\",\"error\":\"{}\"}", ACK_MONITOR_TOPIC, e.getMessage(), e);
        }
    }

    private void processMessageACK(ServerConsumer consumer, Message message, SimpleString addressSimpleString) {
        final ActiveMQServer server = serverAtomicReference.get();
        if (server == null) {
            logger.warn("{\"event\":\"ACK_SKIPPED\",\"reason\":\"server_not_available\"}");
            return;
        }

        String correlationId = String.valueOf(message.getCorrelationID());
        long messageID = message.getMessageID();
        long consumerID = consumer.getID();
        String connectionClientID = consumer.getConnectionClientID();
        String connectionRemoteAddress = consumer.getConnectionRemoteAddress();
        long consumerCreationTime = consumer.getCreationTime();

        String traceId = extractTraceId(message);
        Queue q = consumer.getQueue();
        String queueNameStr = q == null ? "" : q.getName().toString();
        logger.info("{\"event\":\"ACK_RECEIVED\",\"messageId\":{},\"correlationId\":\"{}\",\"consumerId\":{},\"clientId\":\"{}\",\"remoteAddress\":\"{}\",\"messageAddress\":\"{}\",\"queueName\":\"{}\",\"traceId\":\"{}\"}",
                messageID, correlationId, consumerID, connectionClientID, connectionRemoteAddress, addressSimpleString, queueNameStr, traceId);

        try {
            ensureTopicExists(server);

            ACKMessage ackMessage = new ACKMessage(
                    messageID,
                    correlationId,
                    consumerID,
                    connectionClientID,
                    connectionRemoteAddress,
                    new Date(consumerCreationTime),
                    new Date(),
                    addressSimpleString.toString(),
                    queueNameStr
            );

            String json = mapper.writeValueAsString(ackMessage);

            CoreMessage coreMessage = new CoreMessage(
                    server.getStorageManager().generateID(),
                    1024
            );
            coreMessage.setAddress(ACK_MONITOR_TOPIC);
            coreMessage.setDurable(true);
            coreMessage.setExpiration(System.currentTimeMillis() + MESSAGE_EXPIRY_MS);
            coreMessage.getBodyBuffer().writeBytes(json.getBytes(StandardCharsets.UTF_8));
            coreMessage.setCorrelationID(correlationId);
            coreMessage.putStringProperty("_AMQ_ORIG_ADDRESS", addressSimpleString.toString());
            coreMessage.putStringProperty("_AMQ_QUEUE_NAME", queueNameStr);
            coreMessage.putLongProperty("_AMQ_ORIG_MESSAGE_ID", messageID);

            server.getPostOffice().route(coreMessage, false);

            logger.debug("{\"event\":\"ACK_AUDIT_SENT\",\"topic\":\"{}\"}", ACK_MONITOR_TOPIC);
        } catch (Exception e) {
            logger.error("{\"event\":\"ACK_AUDIT_FAILED\",\"error\":\"{}\"}", e.getMessage(), e);
        }
    }

    private String extractTraceId(Message message) {
        Object traceparent = message.getObjectProperty("traceparent");
        if (traceparent != null) {
            String tp = traceparent.toString();
            String[] parts = tp.split("-");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        Object traceId = message.getObjectProperty("traceId");
        if (traceId != null) {
            return traceId.toString();
        }
        return "";
    }

    public record ACKMessage(
            long messageId,
            String correlationId,
            long consumerID,
            String connectionID,
            String connectionRemoteAddress,
            Date connectionCreationTime,
            Date timestamp,
            String messageAddress,
            String queueName
    ) implements Serializable {
    }
}
