package me.veselin.probity.bff.security.rate_limit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // key = "IP:ClassName:methodName"
    private final Map<String, Bucket> buckets = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > 10_000;
                }
            }
    );

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod method)) return true;

        RateLimit annotation = method.getMethodAnnotation(RateLimit.class);
        if (annotation == null) return true; // no annotation = no limit

        String key = request.getRemoteAddr()
                + ":" + method.getBeanType().getSimpleName()
                + ":" + method.getMethod().getName();

        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.computeIfAbsent(key, k ->
                    Bucket.builder()
                            .addLimit(Bandwidth.builder()
                                    .capacity(annotation.requests())
                                    .refillGreedy(annotation.requests(),
                                            Duration.ofSeconds(annotation.seconds())
                                    ).build())
                            .build()
            );
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-RateLimit-Limit", String.valueOf(annotation.requests()));
            response.addHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return true;
        }

        long retryAfter = probe.getNanosToWaitForRefill() / 1_000_000_000;
        response.addHeader("X-RateLimit-Retry-After", String.valueOf(retryAfter));
        response.setStatus(429);
        response.setContentType("text/plain");
        response.getWriter().write("Rate limit exceeded for this endpoint");
        return false;
    }
}
