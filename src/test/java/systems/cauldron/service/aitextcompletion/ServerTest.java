package systems.cauldron.service.aitextcompletion;

import io.helidon.webclient.WebClient;
import io.helidon.webserver.WebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ServerTest {

    private static WebServer webServer;
    private static WebClient webClient;

    @BeforeAll
    public static void setup() throws Exception {
        String apiSecret = System.getenv("API_SECRET");
        if (apiSecret == null) {
            fail("missing required environment variable: API_SECRET");
        }
        webServer = Server.start();
        long timeout = 2000;
        long now = System.currentTimeMillis();
        while (!webServer.isRunning()) {
            Thread.sleep(100);
            if ((System.currentTimeMillis() - now) > timeout) {
                fail("failed to start webserver");
            }
        }
        webClient = WebClient.builder()
                .baseUri("http://localhost:" + webServer.port())
                .build();
    }

    @AfterAll
    public static void cleanup() throws Exception {
        if (webServer != null) {
            webServer.shutdown()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testHealth() throws Exception {
        webClient.get()
                .path("/health")
                .request()
                .thenAccept(response -> assertEquals(200, response.status().code()))
                .toCompletableFuture()
                .get(10L, TimeUnit.SECONDS);
    }

    @Test
    public void testMetrics() throws Exception {
        webClient.get()
                .path("/metrics")
                .request()
                .thenAccept(response -> assertEquals(200, response.status().code()))
                .toCompletableFuture()
                .get(10L, TimeUnit.SECONDS);
    }

    @Disabled
    @Test
    public void testCompletion() throws Exception {
        byte[] body = buildValidRequestBody();
        byte[] responseBody = webClient.post()
                .path("/api/v1/complete")
                .addHeader("Authorization", System.getenv("API_SECRET"))
                .addHeader("Content-Type", "application/json")
                .submit(body)
                .thenCompose(response -> {
                    assertEquals(200, response.status().code());
                    return response.content().as(byte[].class);
                })
                .toCompletableFuture()
                .get(10L, TimeUnit.SECONDS);
        assertNotNull(responseBody);
        String completion = new String(responseBody, StandardCharsets.UTF_8);
        System.out.println(completion);
        String normalizedFinalResult = completion.toLowerCase(Locale.ROOT);
        assertTrue(normalizedFinalResult.contains("world"));
        assertTrue(completion.length() > 0);
    }

    @Test
    public void testBadAuth() throws Exception {
        byte[] body = buildValidRequestBody();
        byte[] responseBody = webClient.post()
                .path("/api/v1/complete")
                .addHeader("Authorization", "wrong")
                .addHeader("Content-Type", "application/json")
                .submit(body)
                .thenCompose(response -> {
                    assertEquals(401, response.status().code());
                    return response.content().as(byte[].class);
                })
                .toCompletableFuture()
                .get(10L, TimeUnit.SECONDS);
        assertEquals(0, responseBody.length);
    }

    @Test
    public void testNoAuth() throws Exception {
        byte[] body = buildValidRequestBody();
        byte[] responseBody = webClient.post()
                .path("/api/v1/complete")
                .addHeader("Content-Type", "application/json")
                .submit(body)
                .thenCompose(response -> {
                    assertEquals(401, response.status().code());
                    return response.content().as(byte[].class);
                })
                .toCompletableFuture()
                .get(10L, TimeUnit.SECONDS);
        assertEquals(0, responseBody.length);
    }

    @Test
    public void testBadRequest() throws Exception {
        byte[] body = buildInvalidRequestBody();
        byte[] responseBody = webClient.post()
                .path("/api/v1/complete")
                .addHeader("Authorization", System.getenv("API_SECRET"))
                .addHeader("Content-Type", "application/json")
                .submit(body)
                .thenCompose(response -> {
                    assertEquals(400, response.status().code());
                    return response.content().as(byte[].class);
                })
                .toCompletableFuture()
                .get(10L, TimeUnit.SECONDS);
        assertEquals(0, responseBody.length);
    }

    private byte[] buildValidRequestBody() {
        JsonObject jsonObject = Json.createObjectBuilder()
                .add("provider", "OPENAI_DAVINCI")
                .add("prompt", "His first program simply printed 'Hello")
                .add("maxTokens", 3)
                .add("temperature", 0.1)
                .build();
        return buildRequestBody(jsonObject);
    }

    private byte[] buildInvalidRequestBody() {
        JsonObject jsonObject = Json.createObjectBuilder()
                .add("provider", "OPENAI_DAVINCI")
                .add("prompt", "His first program simply printed 'Hello")
                .add("maxToken", 3)
                .add("temperature", 0.1)
                .build();
        return buildRequestBody(jsonObject);
    }

    private byte[] buildRequestBody(JsonObject jsonObject) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (JsonWriter writer = Json.createWriter(os)) {
            writer.write(jsonObject);
        }
        return os.toByteArray();
    }
}