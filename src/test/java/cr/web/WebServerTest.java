package cr.web;

import cr.Config;
import cr.TestFrames;
import cr.core.RegisterBank;
import cr.modbus.ModbusTraffic;
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

        ModbusTraffic traffic = new ModbusTraffic();
        traffic.recordRequest(11);
        traffic.recordResponse(11);
        traffic.recordRequest(7);
        traffic.recordRequest(7);
        traffic.recordResponse(7);
        traffic.recordCrcError();
        try (WebServer server = new WebServer(config(port), Map.of("living", living, "bedroom", bedroom), traffic);
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
            assertTrue(page.body().contains("Modbus register guide"));
            assertTrue(page.body().contains("0 / 40001"));
            assertTrue(page.body().contains("1 / 30002"));
            assertTrue(page.body().contains("value=\"bedroom\" selected"));
            assertTrue(page.body().contains("HTTPS is observation only"));
            assertTrue(page.body().contains("Observed Modbus devices"));
            assertTrue(page.body().contains("<td>7</td><td>2</td><td>1</td>"));
            assertTrue(page.body().contains("<td>11</td><td>1</td><td>1</td>"));
            assertTrue(page.body().contains("CRC errors"));
            assertTrue(page.body().contains("class=\"status-value\">1</td>"));
            assertTrue(page.body().contains("AC requests sent"));
            assertTrue(page.body().contains("AC responses received"));
            assertTrue(page.body().contains("AC CRC errors"));
            assertTrue(page.body().contains("Valid AC state frames"));
            assertTrue(page.body().contains("Last valid AC frame"));
            assertFalse(page.body().contains("<form method=\"post\""));
            assertFalse(page.body().contains("<script"));
            assertEquals("max-age=31536000",
                    page.headers().firstValue("Strict-Transport-Security").orElseThrow());

            String form = "ac=bedroom&power=1&temperature=25&sleepTimer=90";
            HttpResponse<String> submitted = client.send(
                    request(URI.create("https://127.0.0.1:" + port + "/control"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(404, submitted.statusCode());
            assertArrayEquals(new int[]{0, 24, 0}, bedroom.readHolding(0, 3));
            assertEquals(24, living.readHolding(1, 1)[0]);
        } finally {
            logger.removeHandler(auditCapture);
        }

        assertTrue(audit.stream().anyMatch(message -> message.contains("user=\"unauthenticated\"")
                && message.contains("status=401") && message.contains("authentication failed")));
        assertTrue(audit.stream().anyMatch(message -> message.contains("user=\"admin\"")
                && message.contains("method=GET") && message.contains("viewed air conditioner id=bedroom")));
        assertTrue(audit.stream().noneMatch(message -> message.contains("updated air conditioner")));
        assertTrue(audit.stream().noneMatch(message -> message.contains("test-password")
                || message.contains("admin:wrong")));
    }

    @Test
    void servesAnAuthenticatedEmptyStateWithNoAirConditioners() throws Exception {
        int port = availablePort();
        Config populated = config(port);
        Config empty = new Config(
                List.of(),
                populated.modbusSerial(),
                populated.web(),
                populated.logPath(),
                populated.logLimitBytes(),
                populated.logFiles());

        try (WebServer server = new WebServer(empty, Map.of()); HttpClient client = testClient()) {
            server.start();

            HttpResponse<String> page = client.send(
                    request(URI.create("https://127.0.0.1:" + port + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> health = client.send(
                    request(URI.create("https://127.0.0.1:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("No air conditioners configured"));
            assertTrue(page.body().contains("Modbus register guide"));
            assertFalse(page.body().contains("action=\"/control\""));
            assertEquals(200, health.statusCode());
            assertEquals("ok\n", health.body());
        }
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
