package com.loadflex.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BailianClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesTimedOutRequestWithoutDuplicatingCallerWork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            int requestNumber = requests.incrementAndGet();
            if (requestNumber == 1) {
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] response = ("{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}")
                    .getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (java.io.IOException ignored) {
                // The first client request has already timed out and closed its connection.
            } finally {
                exchange.close();
            }
        });
        server.start();

        BailianClient client = new BailianClient(
                new ObjectMapper(),
                "test-model",
                "test-key",
                "",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                Path.of("target", "missing-local-secrets").toString(),
                1000L,
                2,
                0L);

        BailianClient.Completion completion = client.completeText("system", "user");

        assertEquals("ok", completion.content());
        assertEquals(2, requests.get());
        assertEquals(3, completion.inputTokens());
        assertEquals(1, completion.outputTokens());
    }
}
