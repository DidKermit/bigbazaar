package com.bigbazaar.identity.kyc;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(KycProperties.class)
class KycConfig {

    @Bean
    KycProvider kycProvider(KycProperties properties, JsonMapper json, Clock clock) {
        return switch (properties.provider()) {
            case "mock" -> new MockKycProvider();
            case "sandbox" -> new SandboxKycProvider(properties.sandbox() == null
                    ? new KycProperties.Sandbox(null, null, null, null, null) : properties.sandbox(), json, clock);
            default -> throw new IllegalStateException("Unknown bigbazaar.kyc.provider: " + properties.provider());
        };
    }
}
