package br.com.banco.application;

import java.util.UUID;

/** Resultado de um comando; {@code versao} permite ao cliente aguardar a projeção (?minVersion). */
public record CommandResult(UUID contaId, int versao) {}

