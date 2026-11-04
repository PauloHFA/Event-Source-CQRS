package br.com.banco.application.port;

import br.com.banco.domain.TransferenciaEvent;

import java.util.List;

/** Eventos do stream da saga e a última versão persistida (0 se vazio). */
public record TransferenciaStream(String id, int version, List<TransferenciaEvent> events) {}

