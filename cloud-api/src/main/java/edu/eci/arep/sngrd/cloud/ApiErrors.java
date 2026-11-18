package edu.eci.arep.sngrd.cloud;

import edu.eci.arep.sngrd.shared.model.ContractViolation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Error responses with the FastAPI shape: {@code {"detail": ...}}. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(ContractViolation.class)
    public ResponseEntity<Map<String, Object>> contract(ContractViolation e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.errors()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("detail", List.of(error("json_invalid", List.of("body"), "JSON decode error", null))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missing(MissingServletRequestParameterException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail",
                List.of(error("missing", List.of("query", e.getParameterName()), "Field required", null))));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> mismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail",
                List.of(error("float_parsing", List.of("query", e.getName()),
                        "Input should be a valid number, unable to parse string as a number", e.getValue()))));
    }

    @ExceptionHandler(AckLostException.class)
    public ResponseEntity<Map<String, Object>> ackLost(AckLostException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }

    private static Map<String, Object> error(String type, List<Object> loc, String msg, Object input) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("loc", loc);
        m.put("msg", msg);
        m.put("input", input);
        return m;
    }
}
