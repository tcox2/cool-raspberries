package cr;

import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class Logging {
    private Logging() {}

    static void configure(Config config) throws IOException {
        if (config.logPath().getParent() != null) Files.createDirectories(config.logPath().getParent());
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) root.removeHandler(handler);
        root.setLevel(Level.INFO);

        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                String thrown = record.getThrown() == null ? "" : System.lineSeparator() + stackTrace(record.getThrown());
                return "%1$tFT%1$tT.%1$tLZ %2$-7s [%3$s] %4$s%5$s%n".formatted(
                        record.getMillis(), record.getLevel(), record.getLoggerName(),
                        formatMessage(record), thrown);
            }
        };
        FileHandler file = new FileHandler(config.logPath().toString(), config.logLimitBytes(), config.logFiles(), true);
        file.setFormatter(formatter);
        root.addHandler(file);
    }

    private static String stackTrace(Throwable error) {
        java.io.StringWriter text = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }
}
