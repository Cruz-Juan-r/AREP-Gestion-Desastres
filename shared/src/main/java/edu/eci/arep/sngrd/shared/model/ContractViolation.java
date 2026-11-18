package edu.eci.arep.sngrd.shared.model;

import java.util.List;
import java.util.Map;

/**
 * Raised when a message does not satisfy its contract. {@link #errors()} follows the
 * shape of FastAPI/Pydantic validation errors: {@code {type, loc, msg, input}}.
 */
public class ContractViolation extends RuntimeException {

    private final transient List<Map<String, Object>> errors;

    public ContractViolation(List<Map<String, Object>> errors) {
        super(errors.isEmpty() ? "invalid message" : String.valueOf(errors.get(0).get("msg")));
        this.errors = List.copyOf(errors);
    }

    public List<Map<String, Object>> errors() {
        return errors;
    }
}
