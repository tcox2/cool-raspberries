package cr;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppInfo {
    private static final Path DEFAULT_VERSION_FILE = Path.of("/opt/cool-raspberries/version");

    private AppInfo() {}

    public static String version() {
        String configuredPath = System.getProperty("cool.raspberries.version.file");
        Path path = configuredPath == null ? DEFAULT_VERSION_FILE : Path.of(configuredPath);
        try {
            String version = Files.readString(path).trim();
            return version.isEmpty() ? "development" : version;
        } catch (IOException error) {
            return "development";
        }
    }

    public static String uptime() {
        long totalSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1_000;
        long days = totalSeconds / 86_400;
        long hours = totalSeconds % 86_400 / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long seconds = totalSeconds % 60;
        return (days == 0 ? "" : days + "d ")
                + (days == 0 && hours == 0 ? "" : hours + "h ")
                + (days == 0 && hours == 0 && minutes == 0 ? "" : minutes + "m ")
                + seconds + "s";
    }
}
