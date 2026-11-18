package edu.eci.arep.sngrd.edge.store;

import java.util.List;

/** Rows of the outbox not yet acknowledged by the cloud (ids plus stored JSON payloads). */
public record Pending(List<String> eventIds, List<String> eventPayloads,
                     List<String> alertIds, List<String> alertPayloads) {

    public boolean isEmpty() {
        return eventIds.isEmpty() && alertIds.isEmpty();
    }
}
