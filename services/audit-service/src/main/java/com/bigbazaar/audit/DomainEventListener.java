package com.bigbazaar.audit;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.common.web.Headers;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records every domain event. Later phases add their topics to the listener; the mapping is generic.
 * Failures are retried, then parked on {@code <topic>.DLT} by {@link KafkaConsumerConfig}.
 */
@Component
class DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final AuditRecorder recorder;
    private final JsonMapper jsonMapper;

    DomainEventListener(AuditRecorder recorder, JsonMapper jsonMapper) {
        this.recorder = recorder;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.IDENTITY_EVENTS, groupId = AuditRecorder.CONSUMER)
    void onEvent(ConsumerRecord<String, String> record) {
        JsonNode envelope;
        try {
            envelope = jsonMapper.readTree(record.value());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MalformedEventException("Not JSON at " + record.topic() + "-" + record.partition() + "@"
                    + record.offset(), e);
        }
        AuditEntry entry = AuditEntry.from(envelope);
        if (entry.correlationId() != null) {
            MDC.put(Headers.MDC_CORRELATION_ID, entry.correlationId().toString());
        }
        try {
            if (recorder.record(entry)) {
                log.info("Audited eventId={} eventType={} entity={}:{}", entry.eventId(), entry.eventType(),
                        entry.entityType(), entry.entityId());
            }
        } finally {
            MDC.remove(Headers.MDC_CORRELATION_ID);
        }
    }
}
