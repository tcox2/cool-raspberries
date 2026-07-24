package cr.web;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cr.Config;
import cr.core.RegisterBank;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final String BIND_ADDRESS = "0.0.0.0";
    private final Config config;
    private final RegisterBank registers;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Template homeTemplate;

    public WebServer(Config config, RegisterBank registers) throws IOException {
        this.config = config;
        this.registers = registers;
        this.homeTemplate = loadTemplate("/cr/web/home.mustache");
        this.server = HttpServer.create(new InetSocketAddress(BIND_ADDRESS, config.webPort()), 32);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::home);
        server.createContext("/control", this::formControl);
        server.createContext("/health", this::health);
    }

    public void start() {
        server.start();
        LOG.info(() -> "web server listening on http://" + BIND_ADDRESS + ":" + config.webPort());
    }

    private void home(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            notFound(exchange);
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        boolean applied = "result=applied".equals(exchange.getRequestURI().getRawQuery());
        noStore(exchange.getResponseHeaders());
        send(exchange, 200, "text/html; charset=utf-8",
                homeHtml(applied ? "Control request accepted." : "", false));
    }

    private void formControl(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/control")) {
            notFound(exchange);
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                send(exchange, 413, "text/plain; charset=utf-8", "Request too large\n");
                return;
            }
            Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
            int[] values = {
                    integer(form, "power"),
                    integer(form, "mode"),
                    integer(form, "fan"),
                    integer(form, "setpoint"),
                    integer(form, "turbo"),
                    integer(form, "quiet")
            };
            registers.writeHolding(0, values);
            redirect(exchange, "/?result=applied");
        } catch (IllegalStateException notReady) {
            noStore(exchange.getResponseHeaders());
            send(exchange, 409, "text/html; charset=utf-8", homeHtml(notReady.getMessage(), true));
        } catch (IllegalArgumentException error) {
            noStore(exchange.getResponseHeaders());
            send(exchange, 400, "text/html; charset=utf-8", homeHtml(error.getMessage(), true));
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "web form control request failed", error);
            noStore(exchange.getResponseHeaders());
            send(exchange, 500, "text/html; charset=utf-8", homeHtml("Internal error", true));
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/health")) {
            notFound(exchange);
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        boolean online = registers.readInput(RegisterBank.STATUS_AC_ONLINE, 1)[0] == 1;
        send(exchange, online ? 200 : 503, "text/plain; charset=utf-8", online ? "ok\n" : "stale\n");
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> result = new HashMap<>();
        if (body.isBlank()) return result;
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int integer(Map<String, String> form, String key) {
        return Integer.parseInt(required(form, key));
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed\n");
    }

    private static void notFound(HttpExchange exchange) throws IOException {
        send(exchange, 404, "text/plain; charset=utf-8", "Not found\n");
    }

    private static void noStore(Headers headers) {
        headers.set("Cache-Control", "no-store");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'; form-action 'self'");
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(2);
        executor.shutdown();
    }

    private String homeHtml(String message, boolean error) {
        int[] input = registers.readInput(0, RegisterBank.REGISTER_COUNT);
        int[] holding = registers.readHolding(0, RegisterBank.REGISTER_COUNT);
        boolean online = input[RegisterBank.STATUS_AC_ONLINE] == 1;
        String connection = online ? "AC online"
                : "AC status stale (" + input[RegisterBank.STATUS_LAST_FRAME_AGE_SECONDS] + "s)";

        Map<String, Object> context = new HashMap<>();
        context.put("hasMessage", !message.isBlank());
        context.put("message", message);
        context.put("messageClass", error ? "error" : "success");
        context.put("connectionClass", online ? "online" : "offline");
        context.put("connection", connection);
        context.put("temperature", String.format(Locale.ROOT, "%.1f °C",
                input[RegisterBank.STATUS_RETURN_AIR_TENTHS_C] / 10.0));
        context.put("powerStatus", input[RegisterBank.STATUS_POWER] == 1 ? "On" : "Off");
        context.put("modeStatus", modeName(input[RegisterBank.STATUS_MODE]));
        context.put("fanStatus", input[RegisterBank.STATUS_FAN]);
        context.put("powerOff", holding[RegisterBank.POWER] == 0);
        context.put("powerOn", holding[RegisterBank.POWER] == 1);
        context.put("modeAuto", holding[RegisterBank.MODE] == 0);
        context.put("modeCool", holding[RegisterBank.MODE] == 1);
        context.put("modeDry", holding[RegisterBank.MODE] == 2);
        context.put("modeFan", holding[RegisterBank.MODE] == 3);
        context.put("modeHeat", holding[RegisterBank.MODE] == 4);
        context.put("fanValue", holding[RegisterBank.FAN]);
        context.put("setpointValue", holding[RegisterBank.SETPOINT_C]);
        context.put("turboOff", holding[RegisterBank.TURBO] == 0);
        context.put("turboOn", holding[RegisterBank.TURBO] == 1);
        context.put("quietOff", holding[RegisterBank.QUIET] == 0);
        context.put("quietOn", holding[RegisterBank.QUIET] == 1);
        return homeTemplate.execute(context);
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case 0 -> "Auto";
            case 1 -> "Cool";
            case 2 -> "Dry";
            case 3 -> "Fan";
            case 4 -> "Heat";
            default -> Integer.toString(mode);
        };
    }

    private static Template loadTemplate(String resource) throws IOException {
        try (InputStream stream = WebServer.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing web template: " + resource);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Mustache.compiler().escapeHTML(true).compile(source);
        }
    }
}
