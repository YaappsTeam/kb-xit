package com.kbquants.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of story #22's "no behavior change unless opted in"
 * acceptance criterion: with no config.yml anywhere the process would
 * actually look (the real working directory this test runs from, not a
 * @TempDir fixture), loading resolves to nothing -- exactly what {@code
 * Main} relies on to fall through to every existing env-var/hardcoded
 * default unchanged.
 */
class NoConfigFileBehaviorTest {

    @Test
    void noConfigFileExistsAtTheDefaultPathInThisRepo() {
        assertTrue(Files.notExists(ConfigLoader.defaultPath()),
                "config.yml must stay gitignored/untracked -- if this fails, something committed a real config.yml");
    }

    @Test
    void loadingTheDefaultPathResolvesToEmpty() throws IOException {

        Optional<AppConfig> config = ConfigLoader.load(ConfigLoader.defaultPath());

        assertTrue(config.isEmpty());
    }

    @Test
    void loadingAnyNonexistentPathNeverThrows(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {

        Optional<AppConfig> config = ConfigLoader.load(dir.resolve("nope.yml"));

        assertTrue(config.isEmpty());
    }
}
