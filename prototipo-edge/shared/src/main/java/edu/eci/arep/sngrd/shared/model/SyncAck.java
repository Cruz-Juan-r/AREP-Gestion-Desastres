package edu.eci.arep.sngrd.shared.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Cloud acknowledgement of a sync batch: what was accepted and what was a duplicate. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder({"batch_id", "events_accepted", "events_duplicated", "alerts_accepted", "alerts_duplicated"})
public record SyncAck(
        String batchId,
        int eventsAccepted,
        int eventsDuplicated,
        int alertsAccepted,
        int alertsDuplicated) {
}
