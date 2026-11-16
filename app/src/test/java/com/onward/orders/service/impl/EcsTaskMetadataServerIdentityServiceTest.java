package com.onward.orders.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.onward.orders.config.IdentityProperties;
import com.onward.orders.model.ServerIdentity;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EcsTaskMetadataServerIdentityServiceTest {
    private static final String TASK_JSON = """
            {"Cluster":"onward-dev-orders-cluster",
             "TaskARN":"arn:aws:ecs:eu-west-1:111122223333:task/onward-dev-orders-cluster/3f2a9c41b7d84e0f9a1b2c3d4e5f6a7b",
             "Family":"onward-dev-orders","AvailabilityZone":"eu-west-1b","LaunchType":"FARGATE"}
            """;
    private static final String CONTAINER_JSON = """
            {"Name":"orders","Networks":[{"NetworkMode":"awsvpc","IPv4Addresses":["10.0.11.42"]}]}
            """;

    private HttpServer fakeMetadata;

    @AfterEach
    void stop() {
        if (fakeMetadata != null) {
            fakeMetadata.stop(0);
        }
    }

    @Test
    void readsIdentityFromEcsTaskMetadataV4() throws Exception {
        String endpoint = startFakeMetadata(Map.of("/v4/abc/task", TASK_JSON, "/v4/abc", CONTAINER_JSON)) + "/v4/abc";

        ServerIdentity identity = new EcsTaskMetadataServerIdentityService(
                new IdentityProperties(true, endpoint, Duration.ofSeconds(2))).current();

        assertThat(identity).isEqualTo(new ServerIdentity("3f2a9c41", "eu-west-1b", "10.0.11.42"));
        assertThat(identity.label()).isEqualTo("3f2a9c41 · eu-west-1b");
    }

    @Test
    void fallsBackToHostNameOffEcs() {
        ServerIdentity identity = new EcsTaskMetadataServerIdentityService(
                new IdentityProperties(true, "", Duration.ofMillis(300))).current();

        assertThat(identity.availabilityZone()).isEqualTo("local");
        assertThat(identity.serverId()).isNotBlank();
    }

    @Test
    void fallsBackToHostNameWhenMetadataIsUnreachable() {
        ServerIdentity identity = new EcsTaskMetadataServerIdentityService(
                new IdentityProperties(true, "http://127.0.0.1:9/v4/abc", Duration.ofMillis(300))).current();

        assertThat(identity.availabilityZone()).isEqualTo("local");
    }

    @Test
    void shortensTaskArnToEightCharacters() {
        assertThat(EcsTaskMetadataServerIdentityService.shortTaskId("arn:aws:ecs:r:1:task/c/0123456789abcdef"))
                .isEqualTo("01234567");
    }

    /** Minimal ECS task metadata endpoint v4: fixed JSON per path. */
    private String startFakeMetadata(Map<String, String> responses) throws Exception {
        fakeMetadata = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeMetadata.createContext("/", exchange -> {
            String body = responses.get(exchange.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        fakeMetadata.start();
        return "http://127.0.0.1:" + fakeMetadata.getAddress().getPort();
    }
}
