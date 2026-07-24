package cr.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cr.Config;
import cr.core.RegisterBank;

import java.io.IOException;
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

    public WebServer(Config config, RegisterBank registers) throws IOException {
        this.config = config;
        this.registers = registers;
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
        String flash = message.isBlank() ? ""
                : "<p class=\"message " + (error ? "error" : "success") + "\">"
                + htmlEscape(message) + "</p>";

        return HOME_TEMPLATE
                .replace("{{FLASH}}", flash)
                .replace("{{CONNECTION_CLASS}}", online ? "online" : "offline")
                .replace("{{CONNECTION}}", connection)
                .replace("{{TEMPERATURE}}", String.format(Locale.ROOT, "%.1f °C",
                        input[RegisterBank.STATUS_RETURN_AIR_TENTHS_C] / 10.0))
                .replace("{{POWER_STATUS}}", input[RegisterBank.STATUS_POWER] == 1 ? "On" : "Off")
                .replace("{{MODE_STATUS}}", modeName(input[RegisterBank.STATUS_MODE]))
                .replace("{{FAN_STATUS}}", Integer.toString(input[RegisterBank.STATUS_FAN]))
                .replace("{{POWER_OFF}}", selected(holding[RegisterBank.POWER], 0))
                .replace("{{POWER_ON}}", selected(holding[RegisterBank.POWER], 1))
                .replace("{{MODE_AUTO}}", selected(holding[RegisterBank.MODE], 0))
                .replace("{{MODE_COOL}}", selected(holding[RegisterBank.MODE], 1))
                .replace("{{MODE_DRY}}", selected(holding[RegisterBank.MODE], 2))
                .replace("{{MODE_FAN}}", selected(holding[RegisterBank.MODE], 3))
                .replace("{{MODE_HEAT}}", selected(holding[RegisterBank.MODE], 4))
                .replace("{{FAN_VALUE}}", Integer.toString(holding[RegisterBank.FAN]))
                .replace("{{SETPOINT_VALUE}}", Integer.toString(holding[RegisterBank.SETPOINT_C]))
                .replace("{{TURBO_OFF}}", selected(holding[RegisterBank.TURBO], 0))
                .replace("{{TURBO_ON}}", selected(holding[RegisterBank.TURBO], 1))
                .replace("{{QUIET_OFF}}", selected(holding[RegisterBank.QUIET], 0))
                .replace("{{QUIET_ON}}", selected(holding[RegisterBank.QUIET], 1));
    }

    private static String selected(int actual, int candidate) {
        return actual == candidate ? " selected" : "";
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

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static final String HOME_TEMPLATE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Cool Raspberries</title>
              <style>
                body{margin:0;background:#eee;color:#222;font:14px Arial,Helvetica,sans-serif}
                #page{width:760px;max-width:94%;margin:20px auto}
                h1{margin:0 0 16px;padding:12px 15px;background:#24496b;color:#fff;font-size:24px}
                .panel{margin-bottom:16px;border:1px solid #999;background:#fff}
                .panel h2{margin:0;padding:8px 12px;border-bottom:1px solid #999;background:#ddd;font-size:18px}
                table{width:100%;border-collapse:collapse}
                th,td{padding:9px 12px;border-bottom:1px solid #ddd;text-align:left;vertical-align:top}
                tr:last-child th,tr:last-child td{border-bottom:0}
                .status-table th{width:28%;background:#f5f5f5}
                .status-value{font-weight:bold}
                .control-table th{background:#f5f5f5}
                .control-name{width:18%;font-weight:bold}
                .control-setting{width:20%}
                input,select,button{font:14px Arial,Helvetica,sans-serif;padding:5px;border:1px solid #777;background:#fff}
                input{width:70px}
                button{background:#24496b;color:#fff;font-weight:bold;cursor:pointer}
                .help{color:#555;line-height:1.35}
                .actions{margin:0;padding:12px;border-top:1px solid #999;background:#f5f5f5}
                .notice{margin:0;padding:10px 12px;color:#555}
                .offline{color:#b00020}.online{color:#087830}
                .refresh{margin:0;padding:10px 12px;border-top:1px solid #999;background:#f5f5f5}
                .message{padding:10px 12px;border:1px solid;margin:0 0 16px;font-weight:bold}
                .success{color:#087830;background:#edf8ef}.error{color:#b00020;background:#fff0f0}
                #result{margin-left:8px}
              </style>
            </head>
            <body>
              <div id="page">
                <h1>Cool Raspberries</h1>
                {{FLASH}}
                <div class="panel" id="status-panel">
                  <h2>Status</h2>
                  <table class="status-table">
                    <tr><th scope="row">Connection</th><td class="status-value {{CONNECTION_CLASS}}">{{CONNECTION}}</td></tr>
                    <tr><th scope="row">Return air</th><td class="status-value">{{TEMPERATURE}}</td></tr>
                    <tr><th scope="row">Power</th><td class="status-value">{{POWER_STATUS}}</td></tr>
                    <tr><th scope="row">Mode</th><td class="status-value">{{MODE_STATUS}}</td></tr>
                    <tr><th scope="row">Fan</th><td class="status-value">{{FAN_STATUS}}</td></tr>
                  </table>
                  <form method="get" action="/" class="refresh"><button type="submit">Refresh status</button></form>
                </div>
                <div class="panel" id="control-panel">
                  <h2>Controls</h2>
                  <form method="post" action="/control">
                    <table class="control-table">
                      <tr><th>Control</th><th>Setting</th><th>Description</th></tr>
                      <tr>
                        <td class="control-name"><label for="control-power">Power</label></td>
                        <td class="control-setting"><select id="control-power" name="power"><option value="0"{{POWER_OFF}}>Off</option><option value="1"{{POWER_ON}}>On</option></select></td>
                        <td class="help">Turns the indoor unit off or on.</td>
                      </tr>
                      <tr>
                        <td class="control-name"><label for="control-mode">Mode</label></td>
                        <td class="control-setting"><select id="control-mode" name="mode"><option value="0"{{MODE_AUTO}}>Auto</option><option value="1"{{MODE_COOL}}>Cool</option><option value="2"{{MODE_DRY}}>Dry</option><option value="3"{{MODE_FAN}}>Fan</option><option value="4"{{MODE_HEAT}}>Heat</option></select></td>
                        <td class="help">Selects automatic, cooling, drying, fan-only, or heating operation.</td>
                      </tr>
                      <tr>
                        <td class="control-name"><label for="control-fan">Fan</label></td>
                        <td class="control-setting"><input id="control-fan" name="fan" type="number" min="0" max="7" value="{{FAN_VALUE}}" required></td>
                        <td class="help">Model-specific fan code from 0 to 7; exact speeds still need hardware verification.</td>
                      </tr>
                      <tr>
                        <td class="control-name"><label for="control-setpoint">Setpoint °C</label></td>
                        <td class="control-setting"><input id="control-setpoint" name="setpoint" type="number" min="16" max="31" value="{{SETPOINT_VALUE}}" required></td>
                        <td class="help">Requested target temperature from 16 to 31 °C.</td>
                      </tr>
                      <tr>
                        <td class="control-name"><label for="control-turbo">Turbo</label></td>
                        <td class="control-setting"><select id="control-turbo" name="turbo"><option value="0"{{TURBO_OFF}}>Off</option><option value="1"{{TURBO_ON}}>On</option></select></td>
                        <td class="help">Requests maximum-output operation when supported by the selected mode.</td>
                      </tr>
                      <tr>
                        <td class="control-name"><label for="control-quiet">Quiet</label></td>
                        <td class="control-setting"><select id="control-quiet" name="quiet"><option value="0"{{QUIET_OFF}}>Off</option><option value="1"{{QUIET_ON}}>On</option></select></td>
                        <td class="help">Requests reduced-noise operation when supported by the selected mode.</td>
                      </tr>
                    </table>
                    <p class="notice">The air conditioner must send a valid state frame before controls are accepted.</p>
                    <p class="actions"><button type="submit">Apply controls</button></p>
                  </form>
                </div>
              </div>
            </body>
            </html>
            """;
}
