package cr.web;

import cr.Config;
import cr.TestFrames;
import cr.core.RegisterBank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void servesMultipleAirConditionersOnlyOverAuthenticatedHttps() throws Exception {
        int port = availablePort();
        RegisterBank living = readyBank();
        RegisterBank bedroom = readyBank();
        List<String> audit = Collections.synchronizedList(new ArrayList<>());
        Logger logger = Logger.getLogger(WebServer.class.getName());
        Handler auditCapture = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().startsWith("web-audit ")) audit.add(record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(auditCapture);

        try (WebServer server = new WebServer(config(port), Map.of("living", living, "bedroom", bedroom));
             HttpClient client = testClient()) {
            server.start();
            URI home = URI.create("https://127.0.0.1:" + port + "/?ac=bedroom");

            HttpResponse<String> unauthenticated = client.send(
                    HttpRequest.newBuilder(home).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthenticated.statusCode());
            assertTrue(unauthenticated.headers().firstValue("WWW-Authenticate").orElseThrow()
                    .startsWith("Basic "));
            HttpResponse<String> wrongPassword = client.send(
                    HttpRequest.newBuilder(home)
                            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                                    "admin:wrong".getBytes(StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrongPassword.statusCode());

            HttpResponse<String> page = client.send(
                    request(home).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Bedroom status"));
            assertTrue(page.body().contains("Modbus unit"));
            assertTrue(page.body().contains("value=\"bedroom\" selected"));
            assertTrue(page.body().contains("<form method=\"post\" action=\"/control\">"));
            assertFalse(page.body().contains("<script"));
            assertEquals("max-age=31536000",
                    page.headers().firstValue("Strict-Transport-Security").orElseThrow());

            String form = "ac=bedroom&power=1&mode=1&fan=2&setpoint=25&turbo=0&quiet=1";
            HttpResponse<String> submitted = client.send(
                    request(URI.create("https://127.0.0.1:" + port + "/control"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(303, submitted.statusCode());
            assertEquals("/?ac=bedroom&result=applied",
                    submitted.headers().firstValue("Location").orElseThrow());
            assertArrayEquals(new int[]{1, 1, 2, 25, 0, 1}, bedroom.readHolding(0, 6));
            assertEquals(21, living.readHolding(3, 1)[0]);
        } finally {
            logger.removeHandler(auditCapture);
        }

        assertTrue(audit.stream().anyMatch(message -> message.contains("user=\"unauthenticated\"")
                && message.contains("status=401") && message.contains("authentication failed")));
        assertTrue(audit.stream().anyMatch(message -> message.contains("user=\"admin\"")
                && message.contains("method=GET") && message.contains("viewed air conditioner id=bedroom")));
        assertTrue(audit.stream().anyMatch(message -> message.contains("user=\"admin\"")
                && message.contains("method=POST") && message.contains("updated air conditioner id=bedroom")
                && message.contains("setpoint=25°C") && message.contains("quiet=1")));
        assertTrue(audit.stream().noneMatch(message -> message.contains("test-password")
                || message.contains("admin:wrong")));
    }

    private static RegisterBank readyBank() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        return bank;
    }

    private static HttpRequest.Builder request(URI uri) {
        String credentials = Base64.getEncoder().encodeToString(
                "admin:test-password".getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri).header("Authorization", "Basic " + credentials);
    }

    private static HttpClient testClient() throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trust, new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(ssl)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Config config(int port) throws Exception {
        Path certificate = copyResource("/cr/web/test-certificate.pem", "certificate.pem");
        Path key = copyResource("/cr/web/test-private-key.pem", "private-key.pem");
        Config.Serial modbus = new Config.Serial("/dev/test-modbus", 9600, 8, 1, 0);
        return new Config(
                List.of(
                        new Config.AirConditioner("living", "Living room",
                                new Config.Serial("/dev/test-living", 9600, 8, 1, 0),
                                1, new byte[6], Duration.ofSeconds(30)),
                        new Config.AirConditioner("bedroom", "Bedroom",
                                new Config.Serial("/dev/test-bedroom", 9600, 8, 1, 0),
                                2, new byte[6], Duration.ofSeconds(30))),
                modbus,
                new Config.Web(port, certificate, key, "", Map.of("admin", "test-password")),
                Path.of("/tmp/cool-raspberries-web-test.log"),
                65_536,
                1);
    }

    private Path copyResource(String resource, String filename) throws Exception {
        Path destination = temporaryDirectory.resolve(filename);
        try (var input = WebServerTest.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            Files.copy(input, destination);
        }
        return destination;
    }
}
