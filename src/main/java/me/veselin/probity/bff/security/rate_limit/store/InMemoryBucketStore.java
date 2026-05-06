package me.veselin.probity.bff.security.rate_limit.store;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import me.veselin.probity.bff.security.rate_limit.RateLimit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnMissingBean(name = "redisBucketStore")
public class InMemoryBucketStore implements BucketStore {

    private final Map<String, Bucket> buckets = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > 10_000;
                }
            }
    );

    @Override
    public Bucket getOrCreate(String key, RateLimit annotation) {
        return buckets.computeIfAbsent(key, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(annotation.requests())
                                .refillGreedy(annotation.requests(), Duration.ofSeconds(annotation.seconds()))
                                .build())
                        .build()
        );
    }

    public void clear() {
        buckets.clear();
    }
}
