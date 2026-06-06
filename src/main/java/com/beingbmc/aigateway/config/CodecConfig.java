package com.beingbmc.aigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Raises the in-memory buffer limit used when decoding request bodies.
 *
 * <p>JSON callers (e.g. DateSense) embed base64 images directly in the request body, so the whole
 * payload must be buffered in memory before Jackson can decode it. The {@code
 * spring.codec.max-in-memory-size} property is not reliably applied to the reactive server's
 * {@code @RequestBody} decoder on its own, leaving it at the framework default of 256&nbsp;KB and
 * causing large JSON payloads to fail. Setting it explicitly here guarantees the limit takes effect.
 */
@Configuration
public class CodecConfig implements WebFluxConfigurer {

    private final int maxInMemoryBytes;

    public CodecConfig(@Value("${spring.codec.max-in-memory-size:32MB}") DataSize maxInMemorySize) {
        this.maxInMemoryBytes = (int) maxInMemorySize.toBytes();
    }

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(maxInMemoryBytes);
    }
}
