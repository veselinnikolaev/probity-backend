package me.veselin.probity.bff.security.rate_limit.store;

import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import me.veselin.probity.bff.security.rate_limit.RateLimit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component("redisBucketStore")
@ConditionalOnBean(RedisClient.class)
@ConditionalOnMissingBean(BucketStore.class)
public class RedisBucketStore implements BucketStore {

    private final ProxyManager<String> proxyManager;

    public RedisBucketStore(RedisClient lettuceClient) {
        StatefulRedisConnection<String, byte[]> connection =
                lettuceClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        this.proxyManager = LettuceBasedProxyManager.builderFor(connection)
                .build();
    }

    @Override
    public Bucket getOrCreate(String key, RateLimit annotation) {
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(annotation.requests())
                        .refillGreedy(annotation.requests(), Duration.ofSeconds(annotation.seconds()))
                        .build())
                .build();

        return proxyManager.builder().build(key, () -> config);
    }
}