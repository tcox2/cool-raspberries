package cr.web;

import cr.Config;
import cr.TestFrames;
import cr.core.RegisterBank;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @Test
    void servesAndProcessesPlainHtmlFormsWithoutJavaScript() throws Exception {
        int port = availablePort();
        RegisterBank registers = new RegisterBank();
        registers.updateFromA3(TestFrames.sampleA3());

        try (WebServer server = new WebServer(config(port), registers);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("<h2>Status</h2>"));
            assertTrue(page.body().contains("<form method=\"get\" action=\"/\""));
            assertTrue(page.body().contains("<form method=\"post\" action=\"/control\">"));
            assertFalse(page.body().contains("<script"));
            assertFalse(page.body().contains("Sweep L/R"));
            assertFalse(page.body().contains("Sweep U/D"));
            assertEquals(404, client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/control"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            String form = "power=1&mode=1&fan=2&setpoint=22&turbo=0&quiet=1";
            HttpResponse<String> submitted = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/control"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(303, submitted.statusCode());
            assertEquals("/", submitted.headers().firstValue("Location").orElseThrow());
            assertArrayEquals(new int[]{1, 1, 2, 22, 0, 1}, registers.readHolding(0, 6));
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Config config(int port) {
        return new Config(
                new Config.Serial("/dev/test-ac", 9600, 8, 1, 0),
                new Config.Serial("/dev/test-modbus", 9600, 8, 1, 0),
                1,
                port,
                Path.of("/tmp/cool-raspberries-web-test.log"),
                65_536,
                1,
                new byte[6],
                Duration.ofSeconds(30));
    }
}
