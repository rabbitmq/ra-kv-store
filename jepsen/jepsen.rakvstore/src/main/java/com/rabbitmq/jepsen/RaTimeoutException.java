package com.rabbitmq.jepsen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 *
 */
public class RaTimeoutException extends RuntimeException {

    private final Map<String, String> headers;

    public RaTimeoutException() {
        this(new LinkedHashMap<>());
    }

    public RaTimeoutException(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
