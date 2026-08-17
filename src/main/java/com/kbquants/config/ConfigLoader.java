package com.kbquants.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kbquants.domain.Milestone;
import com.kbquants.domain.MilestoneLadder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads the optional {@code config.yml}. Its own existence is opt-in: no
 * file at the resolved path means "nothing configured here," not an error
 * -- see story #22's "no behavior change unless opted in" acceptance
 * criterion.
 */
public final class ConfigLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {
    }

    /**
     * {@code CONFIG_FILE} overrides; otherwise {@code config.yml} in the
     * working directory, matching how {@code accounts.properties} is
     * located.
     */
    public static Path defaultPath() {
        String override = System.getenv("CONFIG_FILE");
        return (override == null || override.isBlank()) ? Paths.get("config.yml") : Paths.get(override);
    }

    /**
     * Empty when nothing exists at {@code path}. Malformed YAML propagates
     * as-is from Jackson -- its messages already name the offending line.
     */
    public static Optional<AppConfig> load(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(YAML_MAPPER.readValue(path.toFile(), AppConfig.class));
    }

    /**
     * Converts the {@code milestoneLadders} section into ready-to-use
     * {@link MilestoneLadder}s, keyed by name uppercased (matching
     * {@link com.kbquants.domain.MilestoneSets#byName} lookups). Empty map
     * when the section is absent -- callers treat that as "keep the
     * hardcoded defaults," not "no ladders at all."
     * <p>
     * An invalid ladder (empty rungs, an out-of-range hard stop) fails
     * fast via {@link MilestoneLadder}'s own constructor validation --
     * this method adds no validation of its own on top of that.
     */
    public static Map<String, MilestoneLadder> toMilestoneLadders(AppConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        Map<String, MilestoneLadderConfig> configured = config.getMilestoneLadders();
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }

        Map<String, MilestoneLadder> ladders = new LinkedHashMap<>();
        for (Map.Entry<String, MilestoneLadderConfig> entry : configured.entrySet()) {
            String name = entry.getKey().trim().toUpperCase();
            MilestoneLadderConfig cfg = entry.getValue();
            List<Milestone> milestones = cfg.getRungs().stream()
                    .map(r -> new Milestone(r.getPercent(), r.getPhaseTransition(), r.getOwnershipLockPercent()))
                    .toList();
            ladders.put(name, new MilestoneLadder(name, milestones, cfg.getHardStopPercent()));
        }
        return ladders;
    }
}
