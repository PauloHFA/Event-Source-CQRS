package br.com.banco.api.command;

import br.com.banco.domain.ContaEvent;
import br.com.banco.domain.DomainEvent;
import br.com.banco.domain.TransferenciaEvent;
import br.com.banco.infrastructure.eventstore.EventSerializer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Component
public class ContaOwnershipAuthorization {

    private final JdbcTemplate jdbc;
    private final EventSerializer serializer;

    public ContaOwnershipAuthorization(JdbcTemplate jdbc, EventSerializer serializer) {
        this.jdbc = jdbc;
        this.serializer = serializer;
    }

    public void requireOwner(UUID accountId, String subject) {
        DomainEvent firstEvent = firstEvent("conta-" + accountId);
        if (!(firstEvent instanceof ContaEvent.ContaAberta opened)) {
            throw new IllegalStateException("O primeiro evento da conta não é ContaAberta.");
        }
        if (!opened.titularRef().equals(subject)) {
            throw notFound();
        }
    }

    public void requireTransferOwner(UUID transferId, String subject) {
        DomainEvent firstEvent = firstEvent("transfer-" + transferId);
        if (!(firstEvent instanceof TransferenciaEvent.TransferenciaSolicitada requested)) {
            throw new IllegalStateException("O primeiro evento da transferência não é TransferenciaSolicitada.");
        }
        requireOwner(requested.origem(), subject);
    }

    private DomainEvent firstEvent(String streamId) {
        List<DomainEvent> events = jdbc.query("""
                        SELECT event_type, schema_version, payload::text AS payload
                        FROM events
                        WHERE stream_id = ? AND version = 1
                        """,
                (rs, row) -> serializer.deserializeAny(streamId, rs.getString("event_type"),
                        rs.getInt("schema_version"), rs.getString("payload")),
                streamId);
        if (events.isEmpty()) {
            throw notFound();
        }
        return events.getFirst();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
