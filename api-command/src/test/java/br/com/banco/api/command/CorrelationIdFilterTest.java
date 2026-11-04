package br.com.banco.api.command;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter(ObservationRegistry.create());

    @Test
    void criaEPropagaCorrelationIdQuandoAusente() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var forwarded = new AtomicReference<String>();

        filter.doFilter(request, response, (wrapped, ignored) ->
                forwarded.set(((HttpServletRequest) wrapped).getHeader(CorrelationIdFilter.HEADER)));

        String correlationId = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId);
        assertThat(forwarded.get()).isEqualTo(correlationId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void rejeitaCorrelationIdMalformado() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "not-a-uuid");
        var response = new MockHttpServletResponse();
        var chainReached = new boolean[1];

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainReached[0] = true);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chainReached[0]).isFalse();
    }
}
