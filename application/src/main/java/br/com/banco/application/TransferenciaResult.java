package br.com.banco.application;

import br.com.banco.domain.Transferencia;

import java.util.UUID;

public record TransferenciaResult(UUID transferId, Transferencia.Status status) {}

