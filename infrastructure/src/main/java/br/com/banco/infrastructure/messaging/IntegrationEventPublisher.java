package br.com.banco.infrastructure.messaging;

public interface IntegrationEventPublisher {
    void publish(String topic, String key, IntegrationEvent event);
}
