package edu.eci.arep.sngrd.cloud;

/** Injected fault: the batch was committed but the acknowledgement is "lost" (HTTP 503). */
public class AckLostException extends RuntimeException {

    public AckLostException() {
        super("simulated lost acknowledgement");
    }
}
