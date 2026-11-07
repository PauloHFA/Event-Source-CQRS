package br.com.banco.api.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitingFilterTest {

    private RateLimitingFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setup() {
        filter = new RateLimitingFilter(true, 2, 60, new ObjectMapper());
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void requisicoesAbaixoDoLimitePassam() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/contas/123/saques");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void requisicoesAcimaDoLimiteRetornam429() throws ServletException, IOException {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), Map.of("sub", "alice"));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/transferencias");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        MockHttpServletResponse res3 = new MockHttpServletResponse();

        filter.doFilter(req, res1, chain);
        filter.doFilter(req, res2, chain);
        filter.doFilter(req, res3, chain);

        assertThat(res1.getStatus()).isEqualTo(200);
        assertThat(res2.getStatus()).isEqualTo(200);
        assertThat(res3.getStatus()).isEqualTo(429);
        assertThat(res3.getHeader("Retry-After")).isEqualTo("60");
        assertThat(res3.getContentAsString()).contains("TAXA_LIMITE_EXCEDIDA");
    }

    @Test
    void rotasNaoProtegidasIgnoramRateLimit() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/contas/123/historico/estado");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }
}
