package br.com.banco.infrastructure.eventstore;

public record IntegrityVerification(boolean valid, long verifiedEvents, long firstInvalidPosition) {}
