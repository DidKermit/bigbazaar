package com.bigbazaar.identity.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox and publishes to Kafka with at-least-once delivery. Consumers deduplicate on
 * eventId. Rows that keep failing are parked as FAILED (our dead-letter state) for investigation.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${bigbazaar.outbox.batch-size:100}") int batchSize,
                       @Value("${bigbazaar.outbox.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${bigbazaar.outbox.poll-interval:1s}")
    @Transactional
    public void publishPending() {
        for (OutboxEvent event : repository.lockPendingBatch(batchSize)) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(event.getTopic(), event.getAggregateId(), event.getEnvelope());
            record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
            try {
                kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
                event.markPublished();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                event.markAttemptFailed(e.toString(), maxAttempts);
                log.warn("Outbox publish failed eventId={} eventType={} attempt={} status={}",
                        event.getId(), event.getEventType(), event.getAttempts(), event.getStatus(), e);
                // Keep order per aggregate: stop this batch and retry on the next poll.
                break;
            }
        }
    }
}
