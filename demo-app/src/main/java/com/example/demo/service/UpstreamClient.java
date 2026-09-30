package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;

/**
 * Simulated downstream dependency client.
 * <p>
 * Represents a call to an external payment service (payment-service) that the
 * order flow depends on. In a real deployment this would be an HTTP client
 * (OpenFeign / WebClient) with its own timeout and retry config; here the
 * behavior is simulated so fault injection can demonstrate a classic
 * cross-service failure mode: <b>symptom visible in this service, root cause
 * in the upstream dependency</b>.
 * <p>
 * When the downstream fault is enabled, the call blocks for the configured
 * timeout and then fails with a {@link SocketTimeoutException} — the same
 * signature an on-call engineer sees when a dependency stops responding.
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    /** Simulated HTTP read timeout, mirroring a real Feign/OkHttp read timeout. */
    private static final long TIMEOUT_MS = 3000L;

    /**
     * Charges a simulated payment through the downstream service.
     *
     * @param orderId    the order being paid for
     * @param amountUsd  the amount to charge
     * @throws SocketTimeoutException when the downstream fault is active and the
     *                                simulated call exceeds its read timeout
     */
    public void charge(long orderId, double amountUsd) throws SocketTimeoutException {
        log.info("Calling payment-service: charge orderId={}, amount=${}", orderId, amountUsd);
        try {
            Thread.sleep(TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.error("payment-service request timed out after {}ms: orderId={}, amount=${} (SocketTimeoutException)",
                TIMEOUT_MS, orderId, amountUsd);
        throw new SocketTimeoutException(
                "Read timed out after " + TIMEOUT_MS + "ms calling payment-service /v1/charges");
    }
}
