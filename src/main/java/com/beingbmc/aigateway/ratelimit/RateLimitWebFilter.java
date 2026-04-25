package com.beingbmc.aigateway.ratelimit;

import com.beingbmc.aigateway.config.AiGatewayProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reactive per-IP token-bucket rate limiter (Bucket4j, in-memory).
 * <p>
 * Applied only to {@code /api/**} so health & actuator probes are never throttled.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RateLimitWebFilter implements WebFilter {

    private final AiGatewayProperties props;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path == null || !path.startsWith("/api/") || !props.getRateLimit().isEnabled()) {
            return chain.filter(exchange);
        }
        String ip = clientIp(exchange.getRequest());
        Bucket bucket = buckets.computeIfAbsent(ip, k -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            exchange.getResponse().getHeaders()
                    .add("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return chain.filter(exchange);
        }
        return reject(exchange.getResponse(), probe);
    }

    private Mono<Void> reject(ServerHttpResponse response, ConsumptionProbe probe) {
        long waitSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add(HttpHeaders.RETRY_AFTER, String.valueOf(waitSeconds));
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        byte[] body = "{\"error\":\"rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private Bucket newBucket() {
        AiGatewayProperties.RateLimit rl = props.getRateLimit();
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(rl.getCapacity())
                        .refillGreedy(rl.getRefillTokens(), Duration.ofSeconds(rl.getRefillPeriodSeconds())))
                .build();
    }

    private String clientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeaders().getFirst("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        InetSocketAddress addr = request.getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }
}
