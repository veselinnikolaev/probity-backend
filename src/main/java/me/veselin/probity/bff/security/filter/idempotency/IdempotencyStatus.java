package me.veselin.probity.bff.security.filter.idempotency;

/**
 * Enum representing the state of an idempotent request.
 * <p>
 * Used by {@link IdempotencyFilter} to track request state across Redis.
 */
public enum IdempotencyStatus {
    /**
     * Request is currently being processed.
     * Subsequent requests with the same key will receive 409 Conflict.
     */
    PENDING,

    /**
     * Request completed successfully.
     * Subsequent requests with the same key will receive the cached response.
     */
    COMPLETED
}
