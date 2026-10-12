package com.bigbazaar.identity.outbox;

import com.bigbazaar.common.events.EventEnvelope;
import com.bigbazaar.identity.common.CorrelationIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves an event in the caller's transaction, so the event exists if and only if the business
 * change committed. {@link OutboxRelay} publishes it to Kafka afterwards.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final String sourceService;

    public OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper,
                        @Value("${spring.application.name}") String sourceService) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.sourceService = sourceService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String aggregateType, String aggregateId, String eventType, Object payload) {
        EventEnvelope<Object> envelope =
                EventEnvelope.of(eventType, 1, sourceService, CorrelationIds.current(), payload);
        String json = jsonMapper.writeValueAsString(envelope);
        repository.save(new OutboxEvent(envelope.eventId(), aggregateType, aggregateId, eventType, topic, json));
    }
}
