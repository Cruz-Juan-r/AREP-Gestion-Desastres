package edu.eci.arep.sngrd.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code application.yml}, which maps CLOUD_DB and FAULT_ACK_LOSS. */
@ConfigurationProperties(prefix = "cloud")
public record CloudProperties(String db, double faultAckLoss) {
}
