package br.com.banco.api.query.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Consumer Kafka reativo para o Read Side (CQRS).
 * Atualiza rm_conta_saldo e rm_extrato em tempo real (< 100ms) a partir de banco.conta.eventos.v1.
 */
@Component
@ConditionalOnProperty(name = "banco.messaging.consumer.enabled", havingValue = "true")
public class ContaKafkaConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContaKafkaConsumer.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ContaKafkaConsumer(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${banco.messaging.consumer.topic:banco.conta.eventos.v1}",
                   groupId = "${banco.messaging.consumer.group-id:banco-api-query-projections}")
    @Transactional
    public void onMessage(String payload) {
        try {
            Map<?, ?> map = objectMapper.readValue(payload, Map.class);
            String type = (String) map.get("type");
            UUID eventId = UUID.fromString((String) map.get("eventId"));
            int version = ((Number) map.get("streamVersion")).intValue();
            Instant occurredAt = Instant.parse((String) map.get("occurredAt"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) map.get("data");

            UUID contaId = UUID.fromString((String) data.get("contaId"));

            LOGGER.debug("Processando evento Kafka de leitura: tipo={}, conta={}, versao={}", type, contaId, version);

            switch (type) {
                case "ContaAberta" -> {
                    BigDecimal limite = new BigDecimal(data.get("limite").toString());
                    jdbc.update("""
                            INSERT INTO rm_conta_saldo (conta_id, saldo, limite, status, versao, atualizado_em)
                            VALUES (?, 0, ?, 'ATIVA', ?, ?)
                            ON CONFLICT (conta_id) DO NOTHING
                            """, contaId, limite, version, Timestamp.from(occurredAt));
                }
                case "ValorDepositado" -> {
                    BigDecimal valor = new BigDecimal(data.get("valor").toString());
                    movimentar(contaId, version, valor, "DEPOSITO", eventId, occurredAt);
                }
                case "ValorSacado" -> {
                    BigDecimal valor = new BigDecimal(data.get("valor").toString()).negate();
                    movimentar(contaId, version, valor, "SAQUE", eventId, occurredAt);
                }
                case "ValorEstornado" -> {
                    BigDecimal valor = new BigDecimal(data.get("valor").toString());
                    movimentar(contaId, version, valor, "ESTORNO", eventId, occurredAt);
                }
                case "LimiteDiarioConfigurado" -> {
                    Object novoLimiteObj = data.get("novoLimite");
                    BigDecimal novoLimite = novoLimiteObj != null ? new BigDecimal(novoLimiteObj.toString()) : null;
                    jdbc.update("""
                            UPDATE rm_conta_saldo
                            SET limite_diario = ?, versao = ?, atualizado_em = ?
                            WHERE conta_id = ? AND versao < ?
                            """, novoLimite, version, Timestamp.from(occurredAt), contaId, version);
                }
                case "ContaBloqueada" -> atualizarStatus(contaId, version, "BLOQUEADA", occurredAt);
                case "ContaDesbloqueada" -> atualizarStatus(contaId, version, "ATIVA", occurredAt);
                case "ContaEncerrada" -> atualizarStatus(contaId, version, "ENCERRADA", occurredAt);
                default -> LOGGER.warn("Tipo de evento desconhecido no consumer Kafka: {}", type);
            }
        } catch (Exception e) {
            LOGGER.error("Falha ao projetar evento Kafka na API Query: {}", payload, e);
            throw new RuntimeException("Falha ao projetar evento Kafka", e);
        }
    }

    private void movimentar(UUID contaId, int versao, BigDecimal delta, String tipo, UUID eventId, Instant occurredAt) {
        int atualizadas = jdbc.update("""
                UPDATE rm_conta_saldo
                SET saldo = saldo + ?, versao = ?, atualizado_em = ?
                WHERE conta_id = ? AND versao < ?
                """, delta, versao, Timestamp.from(occurredAt), contaId, versao);

        if (atualizadas > 0) {
            jdbc.update("""
                    INSERT INTO rm_extrato (conta_id, event_id, tipo, valor, saldo_apos, ocorrido_em)
                    SELECT conta_id, ?, ?, ?, saldo, ?
                    FROM rm_conta_saldo
                    WHERE conta_id = ?
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, tipo, delta.abs(), Timestamp.from(occurredAt), contaId);
        }
    }

    private void atualizarStatus(UUID contaId, int versao, String status, Instant occurredAt) {
        jdbc.update("""
                UPDATE rm_conta_saldo
                SET status = ?, versao = ?, atualizado_em = ?
                WHERE conta_id = ? AND versao < ?
                """, status, versao, Timestamp.from(occurredAt), contaId, versao);
    }
}
