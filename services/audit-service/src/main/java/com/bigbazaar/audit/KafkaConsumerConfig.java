package com.bigbazaar.audit;

import com.bigbazaar.common.events.Topics;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Retry then dead-letter (SRS 9.4). Transient failures (database blip) are retried a few times;
 * messages that can never succeed skip retries. Either way the message lands on {@code <topic>.DLT}
 * with the original plus exception headers, and the partition keeps moving.
 */
@Configuration
class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template,
                                     @Value("${bigbazaar.audit.retry-attempts:3}") long attempts,
                                     @Value("${bigbazaar.audit.retry-interval:1s}") Duration interval) {
        var recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            log.error("Sending to DLT topic={} partition={} offset={} reason={}", record.topic(), record.partition(),
                    record.offset(), org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
            return new TopicPartition(Topics.deadLetter(record.topic()), -1);
        });
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(interval.toMillis(), attempts));
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }

    // Declared here too so the consumer starts cleanly whichever service boots first.
    @Bean
    NewTopic identityEvents() {
        return TopicBuilder.name(Topics.IDENTITY_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic identityEventsDlt() {
        return TopicBuilder.name(Topics.deadLetter(Topics.IDENTITY_EVENTS)).partitions(1).replicas(1).build();
    }
}
