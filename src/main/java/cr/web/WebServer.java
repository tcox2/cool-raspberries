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
        server.createContext("/api/status", this::status);
        server.createContext("/api/control", this::control);
        server.createContext("/health", this::health);
    }

    public void start() {
        server.start();
        LOG.info(() -> "web server listening on http://" + BIND_ADDRESS + ":" + config.webPort());
    }

    private void home(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", HTML);
    }

    private void status(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        int[] input = registers.readInput(0, RegisterBank.REGISTER_COUNT);
        int[] holding = registers.readHolding(0, RegisterBank.REGISTER_COUNT);
        String json = """
                {"online":%s,"ageSeconds":%d,"returnAirTenthsC":%d,
                 "status":{"power":%d,"mode":%d,"fan":%d,"setpointC":%d,"flags":%d,
                 "sweepLR":%d,"sweepUD":%d,"timerMinutes":%d,"operatingHours":%d},
                 "control":{"power":%d,"mode":%d,"fan":%d,"setpointC":%d,"turbo":%d,
                 "quiet":%d,"sweepLR":%d,"sweepUD":%d,"display":%d,"ionizer":%d,
                 "auxHeater":%d,"sleep":%d,"energySaving":%d,"timerMinutes":%d},
                 "counters":{"validFramesLow":%d,"crcErrorsLow":%d}}
                """.formatted(
                input[RegisterBank.STATUS_AC_ONLINE] == 1,
                input[RegisterBank.STATUS_LAST_FRAME_AGE_SECONDS],
                input[RegisterBank.STATUS_RETURN_AIR_TENTHS_C],
                input[RegisterBank.STATUS_POWER], input[RegisterBank.STATUS_MODE],
                input[RegisterBank.STATUS_FAN], input[RegisterBank.STATUS_SETPOINT_C],
                input[RegisterBank.STATUS_FLAGS], input[RegisterBank.STATUS_SWEEP_LR],
                input[RegisterBank.STATUS_SWEEP_UD], input[RegisterBank.STATUS_TIMER_MINUTES],
                input[RegisterBank.STATUS_OPERATING_HOURS],
                holding[RegisterBank.POWER], holding[RegisterBank.MODE],
                holding[RegisterBank.FAN], holding[RegisterBank.SETPOINT_C],
                holding[RegisterBank.TURBO], holding[RegisterBank.QUIET],
                holding[RegisterBank.SWEEP_LR], holding[RegisterBank.SWEEP_UD],
                holding[RegisterBank.DISPLAY], holding[RegisterBank.IONIZER],
                holding[RegisterBank.AUX_HEATER], holding[RegisterBank.SLEEP],
                holding[RegisterBank.ENERGY_SAVING], holding[RegisterBank.TIMER_MINUTES],
                input[RegisterBank.STATUS_VALID_FRAMES_LOW], input[RegisterBank.STATUS_CRC_ERRORS_LOW]);
        noStore(exchange.getResponseHeaders());
        send(exchange, 200, "application/json; charset=utf-8", json);
    }

    private void control(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Cool-Raspberries"))) {
            send(exchange, 403, "text/plain; charset=utf-8", "Missing control request header\n");
            return;
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                send(exchange, 413, "text/plain; charset=utf-8", "Request too large\n");
                return;
            }
            Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
            int address = Integer.parseInt(required(form, "address"));
            int value = Integer.parseInt(required(form, "value"));
            registers.writeHolding(address, new int[]{value});
            send(exchange, 204, "text/plain; charset=utf-8", "");
        } catch (IllegalStateException notReady) {
            send(exchange, 409, "text/plain; charset=utf-8", notReady.getMessage() + "\n");
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "text/plain; charset=utf-8", error.getMessage() + "\n");
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "web control request failed", error);
            send(exchange, 500, "text/plain; charset=utf-8", "Internal error\n");
        }
    }

    private void health(HttpExchange exchange) throws IOException {
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

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed\n");
    }

    private static void noStore(Headers headers) {
        headers.set("Cache-Control", "no-store");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'");
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

    private static final String HTML = """
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
                #result{margin-left:8px}
              </style>
            </head>
            <body>
              <div id="page">
                <h1>Cool Raspberries</h1>
                <div class="panel" id="status-panel">
                  <h2>Status</h2>
                  <table class="status-table">
                    <tr><th scope="row">Connection</th><td id="connection" class="status-value">Loading…</td></tr>
                    <tr><th scope="row">Return air</th><td id="temp" class="status-value">—</td></tr>
                    <tr><th scope="row">Power</th><td id="powerState" class="status-value">—</td></tr>
                    <tr><th scope="row">Mode</th><td id="modeState" class="status-value">—</td></tr>
                    <tr><th scope="row">Fan</th><td id="fanState" class="status-value">—</td></tr>
                  </table>
                </div>
                <div class="panel" id="control-panel">
                  <h2>Controls</h2>
                  <table class="control-table">
                    <tr><th>Control</th><th>Setting</th><th>Description</th></tr>
                    <tr>
                      <td class="control-name"><label for="control-power">Power</label></td>
                      <td class="control-setting"><select id="control-power" data-register="0"><option value="0">Off</option><option value="1">On</option></select></td>
                      <td class="help">Turns the indoor unit off or on.</td>
                    </tr>
                    <tr>
                      <td class="control-name"><label for="control-mode">Mode</label></td>
                      <td class="control-setting"><select id="control-mode" data-register="1"><option value="0">Auto</option><option value="1">Cool</option><option value="2">Dry</option><option value="3">Fan</option><option value="4">Heat</option></select></td>
                      <td class="help">Selects automatic, cooling, drying, fan-only, or heating operation.</td>
                    </tr>
                    <tr>
                      <td class="control-name"><label for="control-fan">Fan</label></td>
                      <td class="control-setting"><input id="control-fan" data-register="2" type="number" min="0" max="7"></td>
                      <td class="help">Model-specific fan code from 0 to 7; exact speeds still need hardware verification.</td>
                    </tr>
                    <tr>
                      <td class="control-name"><label for="control-setpoint">Setpoint °C</label></td>
                      <td class="control-setting"><input id="control-setpoint" data-register="3" type="number" min="16" max="31"></td>
                      <td class="help">Requested target temperature from 16 to 31 °C.</td>
                    </tr>
                    <tr>
                      <td class="control-name"><label for="control-turbo">Turbo</label></td>
                      <td class="control-setting"><select id="control-turbo" data-register="4"><option value="0">Off</option><option value="1">On</option></select></td>
                      <td class="help">Requests maximum-output operation when supported by the selected mode.</td>
                    </tr>
                    <tr>
                      <td class="control-name"><label for="control-quiet">Quiet</label></td>
                      <td class="control-setting"><select id="control-quiet" data-register="5"><option value="0">Off</option><option value="1">On</option></select></td>
                      <td class="help">Requests reduced-noise operation when supported by the selected mode.</td>
                    </tr>
                  </table>
                  <p class="notice">Changes remain pending until applied. The air conditioner must send a valid state frame before controls are accepted.</p>
                  <p class="actions"><button id="apply">Apply changed controls</button><span id="result"></span></p>
                </div>
              </div>
              <script>
                const names=['Auto','Cool','Dry','Fan','Heat'];
                let initial={};
                async function refresh(){
                  try{
                    const r=await fetch('/api/status',{cache:'no-store'}),d=await r.json();
                    connection.textContent=d.online?'AC online':'AC status stale ('+d.ageSeconds+'s)';
                    connection.className='status-value '+(d.online?'online':'offline');
                    temp.textContent=(d.returnAirTenthsC/10).toFixed(1)+' °C';
                    powerState.textContent=d.status.power?'On':'Off';
                    modeState.textContent=names[d.status.mode]??d.status.mode;
                    fanState.textContent=d.status.fan;
                    const values=[d.control.power,d.control.mode,d.control.fan,d.control.setpointC,d.control.turbo,d.control.quiet];
                    document.querySelectorAll('[data-register]').forEach((e,i)=>{if(!(e.dataset.dirty)){e.value=values[i];initial[e.dataset.register]=String(values[i]);}});
                  }catch(e){connection.textContent='Gateway unavailable';connection.className='status-value offline';}
                }
                document.querySelectorAll('[data-register]').forEach(e=>e.addEventListener('change',()=>e.dataset.dirty='1'));
                apply.onclick=async()=>{
                  result.textContent='Applying…';
                  try{
                    for(const e of document.querySelectorAll('[data-register][data-dirty]')){
                      const body=new URLSearchParams({address:e.dataset.register,value:e.value});
                      const r=await fetch('/api/control',{method:'POST',headers:{'X-Cool-Raspberries':'1'},body});
                      if(!r.ok)throw new Error(await r.text());
                      delete e.dataset.dirty;
                    }
                    result.textContent='Applied';await refresh();
                  }catch(e){result.textContent='Error: '+e.message;}
                };
                refresh();setInterval(refresh,3000);
              </script>
            </body>
            </html>
            """;
}
