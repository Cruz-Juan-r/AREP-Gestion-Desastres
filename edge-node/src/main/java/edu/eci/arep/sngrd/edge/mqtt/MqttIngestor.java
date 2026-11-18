package edu.eci.arep.sngrd.edge.mqtt;

import edu.eci.arep.sngrd.edge.config.EdgeProperties;
import edu.eci.arep.sngrd.edge.node.EdgeNode;
import edu.eci.arep.sngrd.edge.sse.AlertBroadcaster;
import edu.eci.arep.sngrd.shared.json.Json;
import edu.eci.arep.sngrd.shared.model.Alert;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MQTT side of the edge node (Eclipse Paho, MQTT 3.1.1). Subscribes to
 * {@code sensors/#} with QoS 1 on a persistent session, hands every message to
 * {@link EdgeNode} and publishes alerts on {@code alerts/{severity}/{device_id}}
 * (QoS 1) and to the SSE dashboard. The PUBACK is sent after the reading has been
 * committed locally, so an acknowledged message is never lost.
 */
@Component
public class MqttIngestor implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestor.class);

    private final EdgeNode node;
    private final AlertBroadcaster broadcaster;
    private final EdgeProperties props;
    private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connect");
        t.setDaemon(true);
        return t;
    });
    private MqttAsyncClient client;
    private volatile boolean stopping;

    private final boolean autostart;

    public MqttIngestor(EdgeNode node, AlertBroadcaster broadcaster, EdgeProperties props,
                        @Value("${edge.autostart:true}") boolean autostart) {
        this.node = node;
        this.broadcaster = broadcaster;
        this.props = props;
        this.autostart = autostart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws MqttException {
        if (!autostart) {
            return;
        }
        String uri = "tcp://" + props.mqttHost() + ":" + props.mqttPort();
        client = new MqttAsyncClient(uri, "edge-" + props.nodeId(), new MemoryPersistence());
        client.setCallback(this);
        connect();
    }

    private void connect() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(false);
        opts.setKeepAliveInterval(30);
        opts.setAutomaticReconnect(true);
        opts.setMaxReconnectDelay(5_000);
        opts.setMaxInflight(1_000);
        opts.setConnectionTimeout(10);
        try {
            client.connect(opts, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    // subscription happens in connectComplete
                }

                @Override
                public void onFailure(IMqttToken token, Throwable e) {
                    scheduleRetry(e);
                }
            });
        } catch (MqttException e) {
            scheduleRetry(e);
        }
    }

    /** The first connection is retried here; later ones by Paho's automatic reconnect. */
    private void scheduleRetry(Throwable e) {
        if (!stopping) {
            log.warn("MQTT broker not reachable ({}), retrying in 2 s", e.getMessage());
            retry.schedule(this::connect, 2, TimeUnit.SECONDS);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        log.info("MQTT connected to {} (reconnect={})", serverUri, reconnect);
        node.setMqttConnected(true);
        try {
            client.subscribe("sensors/#", 1);
        } catch (MqttException e) {
            log.error("could not subscribe to sensors/#", e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        node.setMqttConnected(false);
        log.warn("MQTT connection lost: {}", cause == null ? "?" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        long t0 = System.nanoTime();
        try {
            EdgeNode.Ingested r = node.ingest(message.getPayload());
            if (r.status() != EdgeNode.Ingested.Status.STORED) {
                return;
            }
            Alert alert = r.alert();
            if (alert != null) {
                String payload = Json.write(alert);
                publish("alerts/" + alert.severity() + "/" + alert.deviceId(), payload);
                broadcaster.broadcast(payload);
            }
            node.recordProcessingMs((System.nanoTime() - t0) / 1e6);
        } catch (RuntimeException e) {
            // Never let an exception reach Paho: it would drop the connection.
            log.error("failed to process message on {}", topic, e);
        }
    }

    private void publish(String topic, String payload) {
        try {
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
        } catch (MqttException e) {
            log.warn("could not publish alert on {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1 alert delivered to the broker; nothing else to do
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        retry.shutdownNow();
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect(1_000).waitForCompletion(2_000);
                }
                client.close();
            } catch (MqttException e) {
                log.debug("MQTT close: {}", e.getMessage());
            }
        }
    }
}
