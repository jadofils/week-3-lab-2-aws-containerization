package com.onward.orders.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the app discovers which container it is running in, bound from {@code app.identity.*}.
 *
 * <p>{@code metadataEndpoint} is the ECS task metadata endpoint v4. ECS injects it into every
 * Fargate container as {@code ECS_CONTAINER_METADATA_URI_V4}; it is empty off ECS.
 */
@ConfigurationProperties(prefix = "app.identity")
public record IdentityProperties(
        @DefaultValue("true") boolean ecsMetadataEnabled,
        @DefaultValue("") String metadataEndpoint,
        @DefaultValue("500ms") Duration metadataTimeout) {
}
