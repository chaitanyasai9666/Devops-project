package com.example.nlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class MetricsTests {

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metricsAreOnTheManagementPortOnly() throws Exception {
        get(port, "/health"); // creates a request metric

        var metrics = get(managementPort, "/actuator/prometheus");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("http_server_requests_seconds_count"));
        assertTrue(metrics.body().contains("jvm_memory_used_bytes"));

        assertEquals(404, get(port, "/actuator/prometheus").statusCode());
    }
}
