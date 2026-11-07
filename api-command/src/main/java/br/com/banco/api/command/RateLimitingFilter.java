package br.com.banco.api.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Filtro de proteção e resiliência contra abusos e DoS em operações financeiras críticas.
 * Aplica algoritmo de Token Bucket por titular autenticado ou IP de origem.
 */
@Component
@Order(10)
public class RateLimitingFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final int capacity;
    private final int windowSeconds;
    private final ConcurrentHashMap<String, WindowCounter> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(
            @Value("${banco.rate-limiting.enabled:true}") boolean enabled,
            @Value("${banco.rate-limiting.capacity:60}") int capacity,
            @Value("${banco.rate-limiting.window-seconds:60}") int windowSeconds,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.windowSeconds = windowSeconds;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || !isProtectedPath(request)) {
            chain.doFilter(request, response);
            return;
        }

        String clientKey = resolveClientKey(request);
        long currentWindow = System.currentTimeMillis() / (windowSeconds * 1000L);

        WindowCounter counter = buckets.compute(clientKey, (k, old) -> {
            if (old == null || old.window.get() != currentWindow) {
                return new WindowCounter(currentWindow, 1);
            }
            old.count.incrementAndGet();
            return old;
        });

        if (counter.count.get() > capacity) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(windowSeconds));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Limite de requisições financeiras excedido. Tente novamente em instantes.");
            problem.setType(URI.create("urn:banco:error:TAXA_LIMITE_EXCEDIDA"));

            response.getWriter().write(objectMapper.writeValueAsString(problem));
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isProtectedPath(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        String path = request.getRequestURI();
        return path.contains("/saques") || path.contains("/depositos") || path.endsWith("/transferencias");
    }

    private String resolveClientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return "user:" + jwt.getSubject();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static class WindowCounter {
        final AtomicLong window;
        final AtomicInteger count;

        WindowCounter(long window, int initialCount) {
            this.window = new AtomicLong(window);
            this.count = new AtomicInteger(initialCount);
        }
    }
}
