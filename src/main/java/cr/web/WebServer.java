package cr.web;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import cr.Config;
import cr.core.RegisterBank;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());
    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final String AUDIT_USER = "audit.user";
    private static final String AUDIT_ACTION = "audit.action";
    private static final String AUDIT_LOGGED = "audit.logged";
    private final Config config;
    private final Map<String, RegisterBank> registers;
    private final Javalin app;
    private final Template homeTemplate;

    public WebServer(Config config, Map<String, RegisterBank> registers) throws IOException {
        this.config = config;
        this.registers = Map.copyOf(registers);
        this.homeTemplate = loadTemplate("/cr/web/home.mustache");

        Config.Web web = config.web();
        KeyStore identity = identityStore(web);

        this.app = Javalin.create(javalin -> {
            javalin.http.maxRequestSize = 16_384L;
            javalin.concurrency.useVirtualThreads = true;
            javalin.jetty.addConnector((server, http) -> {
                SslContextFactory.Server tls = new SslContextFactory.Server();
                tls.setKeyStore(identity);
                tls.setKeyStorePassword("changeit");
                HttpConfiguration https = new HttpConfiguration(http);
                https.addCustomizer(new SecureRequestCustomizer());
                ServerConnector connector = new ServerConnector(server,
                        new SslConnectionFactory(tls, "http/1.1"),
                        new HttpConnectionFactory(https));
                connector.setHost(BIND_ADDRESS);
                connector.setPort(web.port());
                return connector;
            });
            javalin.routes.before(this::authenticate);
            javalin.routes.after(this::securityHeaders);
            javalin.routes.after(this::auditRequest);
            javalin.routes.get("/", this::home);
            javalin.routes.post("/control", this::formControl);
            javalin.routes.get("/health", this::health);
            javalin.routes.get("/health/{ac}", this::healthOne);
            javalin.routes.exception(IllegalArgumentException.class, (error, ctx) -> {
                auditAction(ctx, "request rejected: " + error.getMessage());
                ctx.status(400).contentType("text/plain; charset=utf-8").result(error.getMessage() + "\n");
            });
            javalin.routes.exception(Exception.class, (error, ctx) -> {
                if (error instanceof UnauthorizedResponse unauthorized) throw unauthorized;
                LOG.log(Level.WARNING, "web request failed", error);
                auditAction(ctx, "request failed with internal error");
                ctx.status(500).result("Internal error\n");
            });
        });
    }

    private static KeyStore identityStore(Config.Web web) throws IOException {
        try (InputStream certificates = java.nio.file.Files.newInputStream(web.certificatePath())) {
            Certificate[] chain = CertificateFactory.getInstance("X.509")
                    .generateCertificates(certificates).toArray(Certificate[]::new);
            String pem = java.nio.file.Files.readString(web.privateKeyPath(), StandardCharsets.US_ASCII);
            boolean encrypted = pem.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----");
            boolean rsaPkcs1 = pem.contains("-----BEGIN RSA PRIVATE KEY-----");
            String begin = encrypted ? "-----BEGIN ENCRYPTED PRIVATE KEY-----"
                    : rsaPkcs1 ? "-----BEGIN RSA PRIVATE KEY-----" : "-----BEGIN PRIVATE KEY-----";
            String end = encrypted ? "-----END ENCRYPTED PRIVATE KEY-----"
                    : rsaPkcs1 ? "-----END RSA PRIVATE KEY-----" : "-----END PRIVATE KEY-----";
            if (!pem.contains(begin)) throw new IOException("TLS private key must use PKCS#8 PEM format");
            byte[] encoded = Base64.getMimeDecoder().decode(pem.replace(begin, "").replace(end, ""));
            PKCS8EncodedKeySpec keySpec;
            if (encrypted) {
                EncryptedPrivateKeyInfo encryptedKey = new EncryptedPrivateKeyInfo(encoded);
                SecretKeyFactory keys = SecretKeyFactory.getInstance(encryptedKey.getAlgName());
                Cipher cipher = Cipher.getInstance(encryptedKey.getAlgName());
                cipher.init(Cipher.DECRYPT_MODE,
                        keys.generateSecret(new PBEKeySpec(web.privateKeyPassword().toCharArray())),
                        encryptedKey.getAlgParameters());
                keySpec = encryptedKey.getKeySpec(cipher);
            } else {
                keySpec = new PKCS8EncodedKeySpec(rsaPkcs1 ? wrapRsaPkcs1(encoded) : encoded);
            }
            PrivateKey key = null;
            for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
                try {
                    key = KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
                    break;
                } catch (java.security.GeneralSecurityException ignored) {
                    // Try the next common certificate-key algorithm.
                }
            }
            if (key == null) throw new IOException("Unsupported TLS private-key algorithm");
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", key, "changeit".toCharArray(), chain);
            return store;
        } catch (java.security.GeneralSecurityException error) {
            throw new IOException("Unable to load TLS identity", error);
        }
    }

    private static byte[] wrapRsaPkcs1(byte[] pkcs1) throws IOException {
        // PKCS#8 PrivateKeyInfo = SEQUENCE(version, rsaEncryption AlgorithmIdentifier,
        // OCTET STRING containing the PKCS#1 RSAPrivateKey).
        byte[] versionAndAlgorithm = {
                0x02, 0x01, 0x00,
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(versionAndAlgorithm);
        body.write(0x04);
        writeDerLength(body, pkcs1.length);
        body.write(pkcs1);
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        wrapped.write(0x30);
        writeDerLength(wrapped, body.size());
        body.writeTo(wrapped);
        return wrapped.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream output, int length) {
        if (length < 128) {
            output.write(length);
            return;
        }
        int bytes = 0;
        for (int value = length; value > 0; value >>>= 8) bytes++;
        output.write(0x80 | bytes);
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) output.write(length >>> shift);
    }

    public void start() {
        app.start();
        LOG.info(() -> "HTTPS server listening on https://" + BIND_ADDRESS + ":" + config.web().port());
    }

    private void authenticate(Context ctx) {
        String username = authenticatedUser(ctx.header("Authorization"));
        if (username != null) {
            ctx.attribute(AUDIT_USER, username);
            return;
        }
        ctx.attribute(AUDIT_LOGGED, true);
        ctx.header("WWW-Authenticate", "Basic realm=\"cool-raspberries\", charset=\"UTF-8\"");
        LOG.warning(() -> auditEntry("unauthenticated", ctx, 401, "authentication failed"));
        throw new UnauthorizedResponse();
    }

    private String authenticatedUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) return null;
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 1) return null;
            byte[] suppliedUser = decoded.substring(0, separator).getBytes(StandardCharsets.UTF_8);
            byte[] suppliedPassword = decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
            String matchedUser = null;
            for (Map.Entry<String, String> user : config.web().users().entrySet()) {
                boolean sameUser = MessageDigest.isEqual(
                        suppliedUser, user.getKey().getBytes(StandardCharsets.UTF_8));
                boolean samePassword = MessageDigest.isEqual(
                        suppliedPassword, user.getValue().getBytes(StandardCharsets.UTF_8));
                if (sameUser & samePassword) matchedUser = user.getKey();
            }
            return matchedUser;
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }
    }

    private void auditRequest(Context ctx) {
        if (Boolean.TRUE.equals(ctx.attribute(AUDIT_LOGGED))) return;
        String username = ctx.attribute(AUDIT_USER);
        String action = ctx.attribute(AUDIT_ACTION);
        LOG.info(() -> auditEntry(username == null ? "unknown" : username, ctx, ctx.statusCode(),
                action == null ? "request completed" : action));
        ctx.attribute(AUDIT_LOGGED, true);
    }

    private static String auditEntry(String username, Context ctx, int status, String action) {
        return "web-audit user=\"" + safe(username) + "\" client=\"" + safe(ctx.ip())
                + "\" method=" + ctx.method() + " path=\"" + safe(ctx.path())
                + "\" status=" + status + " action=\"" + safe(action) + "\"";
    }

    private static void auditAction(Context ctx, String action) {
        ctx.attribute(AUDIT_ACTION, action);
    }

    private static String safe(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private void securityHeaders(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("Strict-Transport-Security", "max-age=31536000");
        ctx.header("Content-Security-Policy",
                "default-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'; form-action 'self'");
    }

    private void home(Context ctx) {
        if (config.airConditioners().isEmpty()) {
            auditAction(ctx, "viewed gateway with no configured air conditioners");
            ctx.contentType("text/html; charset=utf-8").html(emptyHomeHtml());
            return;
        }
        Config.AirConditioner ac = selected(ctx.queryParam("ac"));
        auditAction(ctx, "viewed air conditioner id=" + ac.id() + " name=" + ac.name());
        boolean applied = "applied".equals(ctx.queryParam("result"));
        ctx.contentType("text/html; charset=utf-8")
                .html(homeHtml(ac, applied ? "Control request accepted." : "", false));
    }

    private void formControl(Context ctx) {
        String acId = required(ctx.formParam("ac"), "ac");
        Config.AirConditioner ac = selected(acId);
        RegisterBank bank = registers.get(ac.id());
        try {
            int[] values = {
                    integer(ctx.formParam("power"), "power"),
                    integer(ctx.formParam("temperature"), "temperature"),
                    integer(ctx.formParam("sleepTimer"), "sleep timer")
            };
            bank.writeHolding(0, values);
            auditAction(ctx, "updated air conditioner id=" + ac.id() + " name=" + ac.name()
                    + ": power=" + values[0]
                    + ", temperature=" + values[1] + "°C"
                    + ", sleepTimer=" + values[2] + " minutes");
            ctx.status(303).header("Location", "/?ac=" + ac.id() + "&result=applied");
        } catch (IllegalStateException notReady) {
            auditAction(ctx, "control update rejected for air conditioner id=" + ac.id()
                    + ": " + notReady.getMessage());
            ctx.status(409).contentType("text/html; charset=utf-8")
                    .html(homeHtml(ac, notReady.getMessage(), true));
        } catch (IllegalArgumentException error) {
            auditAction(ctx, "control update rejected for air conditioner id=" + ac.id()
                    + ": " + error.getMessage());
            ctx.status(400).contentType("text/html; charset=utf-8")
                    .html(homeHtml(ac, error.getMessage(), true));
        }
    }

    private void health(Context ctx) {
        boolean online = config.airConditioners().stream().allMatch(ac -> online(registers.get(ac.id())));
        auditAction(ctx, "checked aggregate health: online=" + online);
        ctx.status(online ? 200 : 503).contentType("text/plain; charset=utf-8")
                .result(online ? "ok\n" : "stale\n");
    }

    private void healthOne(Context ctx) {
        Config.AirConditioner ac = selected(ctx.pathParam("ac"));
        boolean online = online(registers.get(ac.id()));
        auditAction(ctx, "checked health for air conditioner id=" + ac.id() + " name=" + ac.name()
                + ": online=" + online);
        ctx.status(online ? 200 : 503).contentType("text/plain; charset=utf-8")
                .result(online ? "ok\n" : "stale\n");
    }

    private static boolean online(RegisterBank bank) {
        return bank.isOnline();
    }

    private Config.AirConditioner selected(String id) {
        if (id == null || id.isBlank()) return config.airConditioners().getFirst();
        return config.airConditioners().stream()
                .filter(ac -> ac.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown air conditioner: " + id));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static int integer(String value, String name) {
        return Integer.parseInt(required(value, name));
    }

    @Override
    public void close() {
        app.stop();
    }

    private String homeHtml(Config.AirConditioner ac, String message, boolean error) {
        RegisterBank bank = registers.get(ac.id());
        int[] input = bank.readInput(0, RegisterBank.REGISTER_COUNT);
        int[] holding = bank.readHolding(0, RegisterBank.REGISTER_COUNT);
        boolean online = bank.isOnline();
        String connection = online ? "AC online"
                : "AC status stale (" + bank.lastValidFrameAgeSeconds() + "s)";

        Map<String, Object> context = new HashMap<>();
        context.put("hasAirConditioners", true);
        context.put("airConditioners", airConditionerOptions(ac.id()));
        context.put("acId", ac.id());
        context.put("acName", ac.name());
        context.put("modbusUnitId", ac.modbusUnitId());
        context.put("hasMessage", !message.isBlank());
        context.put("message", message);
        context.put("messageClass", error ? "error" : "success");
        context.put("connectionClass", online ? "online" : "offline");
        context.put("connection", connection);
        context.put("temperature", String.format(Locale.ROOT, "%.1f °C",
                input[RegisterBank.STATUS_TEMPERATURE_TENTHS_C] / 10.0));
        context.put("powerStatus", input[RegisterBank.STATUS_POWER] == 1 ? "On" : "Off");
        context.put("sleepTimerStatus", input[RegisterBank.STATUS_SLEEP_TIMER_MINUTES]);
        context.put("powerOff", holding[RegisterBank.POWER] == 0);
        context.put("powerOn", holding[RegisterBank.POWER] == 1);
        context.put("temperatureValue", holding[RegisterBank.TEMPERATURE_C]);
        context.put("sleepTimerValue", holding[RegisterBank.SLEEP_TIMER_MINUTES]);
        return homeTemplate.execute(context);
    }

    private String emptyHomeHtml() {
        Map<String, Object> context = new HashMap<>();
        context.put("hasAirConditioners", false);
        return homeTemplate.execute(context);
    }

    private List<Map<String, Object>> airConditionerOptions(String selectedId) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Config.AirConditioner ac : config.airConditioners()) {
            options.add(Map.of(
                    "id", ac.id(),
                    "name", ac.name(),
                    "selected", ac.id().equals(selectedId)));
        }
        return options;
    }

    private static Template loadTemplate(String resource) throws IOException {
        try (InputStream stream = WebServer.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing web template: " + resource);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Mustache.compiler().escapeHTML(true).compile(source);
        }
    }
}
