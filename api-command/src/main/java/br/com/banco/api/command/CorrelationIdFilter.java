package br.com.banco.api.command;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;

@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private final ObservationRegistry observations;

    public CorrelationIdFilter(ObservationRegistry observations) {
        this.observations = observations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = request.getHeader(HEADER);
        UUID correlationId;
        try {
            correlationId = value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, HEADER + " deve conter um UUID válido.");
            return;
        }

        String normalized = correlationId.toString();
        response.setHeader(HEADER, normalized);
        MDC.put(MDC_KEY, normalized);
        Observation observation = observations.getCurrentObservation();
        if (observation != null) observation.highCardinalityKeyValue(MDC_KEY, normalized);
        try {
            chain.doFilter(new CorrelationRequest(request, normalized), response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static final class CorrelationRequest extends HttpServletRequestWrapper {
        private final String correlationId;

        private CorrelationRequest(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? correlationId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(Collections.singleton(correlationId))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            var names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) names.add(HEADER);
            return Collections.enumeration(names);
        }
    }
}
