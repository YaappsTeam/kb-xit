package com.kbquants.config;

import com.kbquants.domain.MilestoneLadder;
import com.kbquants.domain.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void loadReturnsEmptyWhenTheFileDoesNotExist(@TempDir Path dir) throws IOException {

        Optional<AppConfig> config = ConfigLoader.load(dir.resolve("does-not-exist.yml"));

        assertTrue(config.isEmpty());
    }

    @Test
    void loadParsesMilestoneLaddersIntoTheSameShapeTheHardcodedSetsUse(@TempDir Path dir) throws IOException {

        String yaml = """
                milestoneLadders:
                  EQUITY:
                    hardStopPercent: 0.20
                    rungs:
                      - percent: 1.0
                      - percent: 2.0
                        phaseTransition: PHASE_2
                      - percent: 5.0
                        phaseTransition: PHASE_3
                        ownershipLockPercent: 0.30
                """;
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);

        AppConfig config = ConfigLoader.load(file).orElseThrow();
        Map<String, MilestoneLadder> ladders = ConfigLoader.toMilestoneLadders(config);

        assertEquals(1, ladders.size());
        MilestoneLadder equity = ladders.get("EQUITY");
        assertEquals("EQUITY", equity.getName());
        assertEquals(0.20, equity.getHardStopPercent());
        assertEquals(3, equity.getMilestones().size());
        assertEquals(Phase.PHASE_2, equity.getMilestones().get(1).getPhaseTransition());
        assertEquals(0.30, equity.getMilestones().get(2).getOwnershipLockPercent());
    }

    @Test
    void toMilestoneLaddersReturnsEmptyMapWhenTheSectionIsAbsent() {

        AppConfig config = new AppConfig();

        assertTrue(ConfigLoader.toMilestoneLadders(config).isEmpty());
    }

    @Test
    void ladderNamesAreUppercasedForLookupConsistency(@TempDir Path dir) throws IOException {

        String yaml = """
                milestoneLadders:
                  custom:
                    hardStopPercent: 0.15
                    rungs:
                      - percent: 2.0
                """;
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);

        AppConfig config = ConfigLoader.load(file).orElseThrow();
        Map<String, MilestoneLadder> ladders = ConfigLoader.toMilestoneLadders(config);

        assertTrue(ladders.containsKey("CUSTOM"));
    }

    @Test
    void anInvalidLadderFailsFastViaMilestoneLaddersOwnValidation(@TempDir Path dir) throws IOException {

        String yaml = """
                milestoneLadders:
                  BROKEN:
                    hardStopPercent: 1.5
                    rungs:
                      - percent: 2.0
                """;
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);

        AppConfig config = ConfigLoader.load(file).orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.toMilestoneLadders(config));
    }

    @Test
    void malformedYamlFailsFastWithAJacksonMessage(@TempDir Path dir) throws IOException {

        Path file = dir.resolve("config.yml");
        Files.writeString(file, "milestoneLadders: [this, is, not, a, map}");

        assertThrows(Exception.class, () -> ConfigLoader.load(file));
    }

    @Test
    void defaultPathIsConfigYmlInTheWorkingDirectoryWhenConfigFileIsUnset() {
        assertEquals(Path.of("config.yml"), ConfigLoader.defaultPath());
    }
}
