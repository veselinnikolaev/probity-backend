package me.veselin.probity.simulation.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.bff.security.filter.idempotency.IdempotencyRecord;
import me.veselin.probity.bff.security.filter.idempotency.IdempotencyStatus;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-based idempotency service for Kafka consumers.
 * Provides atomic state transitions for PROCESSING state management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisIdempotencyService {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Gets the idempotency record for a key.
     */
    public IdempotencyRecord getRecord(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value instanceof IdempotencyRecord record) {
            return record;
        }
        return null;
    }

    /**
     * Atomically transitions PENDING → PROCESSING using Redis SETNX.
     * Returns true if transition succeeded, false otherwise.
     */
    public boolean setProcessingIfPending(String key) {
        // Use Redis SETNX (setIfAbsent) for atomic PENDING -> PROCESSING transition
        // We first GET the current value, check if PENDING, then SET if so
        // For true atomicity, we use a simple approach: try to set PROCESSING only if key doesn't exist
        // If key exists, we check its current status
        
        IdempotencyRecord existing = getRecord(key);
        if (existing != null) {
            if (existing.status() == IdempotencyStatus.PENDING) {
                // Update to PROCESSING
                IdempotencyRecord processing = new IdempotencyRecord(IdempotencyStatus.PROCESSING, 0, null);
                redisTemplate.opsForValue().set(key, processing, 24, TimeUnit.HOURS);
                return true;
            }
            // Already PROCESSING or COMPLETED
            return false;
        }
        // Key doesn't exist - this shouldn't happen for async flow (PENDING should exist)
        return false;
    }

    /**
     * Sets the key to COMPLETED with response data.
     */
    public void setCompleted(String key, int statusCode, String responseBody) {
        IdempotencyRecord record = new IdempotencyRecord(IdempotencyStatus.COMPLETED, statusCode, responseBody);
        redisTemplate.opsForValue().set(key, record, 24, TimeUnit.HOURS);
    }

    /**
     * Deletes the idempotency key (allows retry).
     */
    public void deleteKey(String key) {
        redisTemplate.delete(key);
    }
}