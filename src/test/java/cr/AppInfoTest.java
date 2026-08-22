package cr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppInfoTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsPackagedVersionAndFormatsUptime() throws Exception {
        Path versionFile = temporaryDirectory.resolve("version");
        Files.writeString(versionFile, "1.2.3-test\n");
        String previous = System.getProperty("cool.raspberries.version.file");
        try {
            System.setProperty("cool.raspberries.version.file", versionFile.toString());
            assertEquals("1.2.3-test", AppInfo.version());
            assertTrue(AppInfo.uptime().matches("(?:\\d+d )?(?:\\d+h )?(?:\\d+m )?\\d+s"));
        } finally {
            if (previous == null) System.clearProperty("cool.raspberries.version.file");
            else System.setProperty("cool.raspberries.version.file", previous);
        }
    }
}
