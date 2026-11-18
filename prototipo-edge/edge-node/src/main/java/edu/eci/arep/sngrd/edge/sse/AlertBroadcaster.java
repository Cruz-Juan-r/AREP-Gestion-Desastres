package edu.eci.arep.sngrd.edge.sse;

import edu.eci.arep.sngrd.edge.node.EdgeNode;
import edu.eci.arep.sngrd.shared.json.Json;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events: pushes every new alert to the local dashboard. Like the Python
 * stream, each subscriber first receives {@code retry: 2000}, then {@code alert}
 * events, and a {@code status} event (current metrics) after 15 s without alerts.
 * Sends run on a dedicated thread so a slow browser never blocks MQTT ingestion.
 */
@Component
public class AlertBroadcaster {

    static final long STATUS_EVERY_MS = 15_000;

    private final EdgeNode node;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-sender");
        t.setDaemon(true);
        return t;
    });

    private static final class Subscriber {
        final SseEmitter emitter = new SseEmitter(0L);   // no timeout
        volatile long lastSentMs = System.currentTimeMillis();
    }

    public AlertBroadcaster(EdgeNode node) {
        this.node = node;
        sender.scheduleAtFixedRate(this::statusTick, 1, 1, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe() {
        Subscriber s = new Subscriber();
        s.emitter.onCompletion(() -> subscribers.remove(s));
        s.emitter.onTimeout(() -> subscribers.remove(s));
        s.emitter.onError(e -> subscribers.remove(s));
        subscribers.add(s);
        sender.execute(() -> send(s, SseEmitter.event().reconnectTime(2000)));
        return s.emitter;
    }

    public void broadcast(String alertJson) {
        sender.execute(() -> subscribers.forEach(s -> send(s, SseEmitter.event().name("alert").data(alertJson))));
    }

    private void statusTick() {
        long now = System.currentTimeMillis();
        String status = null;
        for (Subscriber s : subscribers) {
            if (now - s.lastSentMs >= STATUS_EVERY_MS) {
                if (status == null) {
                    status = Json.write(node.metrics());
                }
                send(s, SseEmitter.event().name("status").data(status));
            }
        }
    }

    private void send(Subscriber s, SseEmitter.SseEventBuilder event) {
        try {
            s.emitter.send(event);
            s.lastSentMs = System.currentTimeMillis();
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(s);
            s.emitter.completeWithError(e);
        }
    }

    int subscriberCount() {
        return subscribers.size();
    }

    @PreDestroy
    public void close() {
        subscribers.forEach(s -> s.emitter.complete());
        sender.shutdownNow();
    }
}
