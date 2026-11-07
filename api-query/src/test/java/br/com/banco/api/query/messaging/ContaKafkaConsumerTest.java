package br.com.banco.api.query.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContaKafkaConsumerTest {

    private JdbcTemplate jdbc;
    private ContaKafkaConsumer consumer;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        consumer = new ContaKafkaConsumer(jdbc, new ObjectMapper());
    }

    @Test
    void processaContaAberta() {
        UUID accountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "type": "ContaAberta",
                  "streamId": "conta-%s",
                  "streamVersion": 1,
                  "occurredAt": "2026-01-01T10:00:00Z",
                  "data": {
                    "contaId": "%s",
                    "limite": "500.00"
                  }
                }
                """.formatted(eventId, accountId, accountId);

        consumer.onMessage(json);

        verify(jdbc).update(contains("INSERT INTO rm_conta_saldo"),
                eq(accountId), eq(new BigDecimal("500.00")), eq(1), eq(Timestamp.from(Instant.parse("2026-01-01T10:00:00Z"))));
    }

    @Test
    void processaValorDepositado() {
        UUID accountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "type": "ValorDepositado",
                  "streamId": "conta-%s",
                  "streamVersion": 2,
                  "occurredAt": "2026-01-01T10:05:00Z",
                  "data": {
                    "contaId": "%s",
                    "valor": "100.00",
                    "origem": "CAIXA"
                  }
                }
                """.formatted(eventId, accountId, accountId);

        when(jdbc.update(contains("UPDATE rm_conta_saldo"), any(), any(), any(), any(), any())).thenReturn(1);

        consumer.onMessage(json);

        verify(jdbc).update(contains("UPDATE rm_conta_saldo"),
                eq(new BigDecimal("100.00")), eq(2), any(Timestamp.class), eq(accountId), eq(2));
        verify(jdbc).update(contains("INSERT INTO rm_extrato"),
                eq(eventId), eq("DEPOSITO"), eq(new BigDecimal("100.00")), any(Timestamp.class), eq(accountId));
    }
}
