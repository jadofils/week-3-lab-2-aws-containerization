package com.onward.orders.service.impl;

import com.onward.orders.config.IdentityProperties;
import com.onward.orders.model.ServerIdentity;
import com.onward.orders.service.ServerIdentityService;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the task ID, Availability Zone and private IP from the ECS task metadata endpoint v4
 * ({@code ECS_CONTAINER_METADATA_URI_V4}), which ECS injects into every Fargate container.
 * Off ECS (for example on a laptop) it falls back to the host name. The identity never
 * changes for a running task, so it is looked up once.
 *
 * <ul>
 *   <li>{@code GET ${endpoint}/task}: {@code TaskARN} and {@code AvailabilityZone}</li>
 *   <li>{@code GET ${endpoint}}: this container's {@code Networks[0].IPv4Addresses[0]}</li>
 * </ul>
 */
@Service
public class EcsTaskMetadataServerIdentityService implements ServerIdentityService {
    private static final Logger log = LoggerFactory.getLogger(EcsTaskMetadataServerIdentityService.class);

    private final IdentityProperties properties;
    private final ObjectMapper json = JsonMapper.builder().build();
    private volatile ServerIdentity cached;

    public EcsTaskMetadataServerIdentityService(IdentityProperties properties) {
        this.properties = properties;
    }

    @Override
    public ServerIdentity current() {
        ServerIdentity identity = cached;
        if (identity == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = resolve();
                }
                identity = cached;
            }
        }
        return identity;
    }

    private ServerIdentity resolve() {
        String endpoint = properties.metadataEndpoint();
        if (properties.ecsMetadataEnabled() && endpoint != null && !endpoint.isBlank()) {
            try {
                return fromTaskMetadata(endpoint.replaceAll("/+$", ""));
            } catch (Exception ex) {
                log.info("ECS task metadata not available ({}); using host name", ex.getMessage());
            }
        }
        return fromHost();
    }

    private ServerIdentity fromTaskMetadata(String endpoint) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.metadataTimeout()).build();

        JsonNode task = get(client, endpoint + "/task");
        JsonNode container = get(client, endpoint);

        String taskArn = required(task.path("TaskARN").asString(""), "TaskARN");
        String zone = required(task.path("AvailabilityZone").asString(""), "AvailabilityZone");
        String ip = container.path("Networks").path(0).path("IPv4Addresses").path(0).asString("unknown");

        return new ServerIdentity(shortTaskId(taskArn), zone, ip);
    }

    /** "arn:aws:ecs:eu-west-1:123:task/cluster/3f2a9c41..." becomes "3f2a9c41". */
    static String shortTaskId(String taskArn) {
        String id = taskArn.substring(taskArn.lastIndexOf('/') + 1);
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private JsonNode get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.metadataTimeout())
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().isBlank()) {
            throw new IllegalStateException("task metadata returned HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("task metadata has no " + field);
        }
        return value;
    }

    private static ServerIdentity fromHost() {
        try {
            InetAddress local = InetAddress.getLocalHost();
            return new ServerIdentity(local.getHostName(), "local", local.getHostAddress());
        } catch (Exception ignored) {
            return new ServerIdentity("unknown-server", "unknown", "unknown");
        }
    }
}
