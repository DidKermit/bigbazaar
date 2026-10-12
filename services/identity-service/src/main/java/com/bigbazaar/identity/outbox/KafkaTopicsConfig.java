package com.bigbazaar.identity.outbox;

import com.bigbazaar.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class KafkaTopicsConfig {

    @Bean
    NewTopic identityEventsTopic() {
        return TopicBuilder.name(Topics.IDENTITY_EVENTS).partitions(3).replicas(1).build();
    }
}
