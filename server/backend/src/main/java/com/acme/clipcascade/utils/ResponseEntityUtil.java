package com.acme.clipcascade.utils;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

public class ResponseEntityUtil {

    private static final Logger logger = LoggerFactory.getLogger(ResponseEntityUtil.class);

    public static ResponseEntity<String> buildResponse(boolean condition, String successMessage, String errorMessage) {
        return condition ? ResponseEntity.ok(successMessage) : ResponseEntity.badRequest().body(errorMessage);
    }

    @SuppressWarnings("unchecked")
    public static <T> ResponseEntity<T> executeWithResponse(Supplier<T> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (Exception e) {
            // Log the detailed cause server-side, but never leak raw exception
            // messages (which may expose internals) to the client.
            logger.warn("Request handling failed", e);
            return ResponseEntity.badRequest().body((T) "Request failed");
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> ResponseEntity<T> conditionalExecuteOrError(boolean condition,
            Supplier<ResponseEntity<T>> successAction,
            String errorMessage) {

        return condition
                ? successAction.get()
                : ResponseEntity.badRequest().body((T) errorMessage);
    }
}
