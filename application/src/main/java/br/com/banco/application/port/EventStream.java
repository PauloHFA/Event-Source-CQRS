package br.com.banco.application.port;

import br.com.banco.domain.ContaEvent;

import java.util.List;

/** Eventos de um stream e a última versão persistida (0 se vazio). */
public record EventStream(String id, int version, List<ContaEvent> events) {}
