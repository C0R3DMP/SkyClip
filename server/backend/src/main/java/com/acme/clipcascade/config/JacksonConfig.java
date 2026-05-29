package com.acme.clipcascade.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(prefix = "app.p2p", name = "enabled", havingValue = "false", matchIfMissing = false)
public class JacksonConfig {
    private final AppProperties appProperties;

    public JacksonConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // Increase the Maximum String(JSON) Length in Jackson
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints
                        .builder()
                        .maxStringLength(
                                (int) appProperties.getOverheadMaxMessageSizeInBytes()) // default: 20000000
                        .build());
        return objectMapper;
    }
}
