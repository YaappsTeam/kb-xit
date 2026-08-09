package com.kbquants.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Stores open trades as a JSON file, rewritten whenever something material
 * changes.
 * <p>
 * Written to a temporary file and moved into place, so a crash midway
 * through cannot leave a half-written file that fails to parse on the next
 * start -- which would lose exactly the state this exists to keep.
 * <p>
 * A read failure returns nothing rather than throwing. Starting with no
 * memory of previous trades is bad; refusing to start at all is worse,
 * because then nothing is watching the positions either.
 */
@Slf4j
public final class JsonTradeStore implements TradeStore {

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public JsonTradeStore() {
        this(defaultPath());
    }

    public JsonTradeStore(Path file) {
        this.file = file;
    }

    private static Path defaultPath() {
        String override = System.getenv("TRADE_STATE_FILE");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".xit-mc", "open-trades.json");
    }

    @Override
    public void save(List<TradeSnapshot> trades) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "open-trades", ".tmp");
            Files.writeString(temp, gson.toJson(trades), StandardCharsets.UTF_8);
            moveIntoPlace(temp);
            log.debug("Persisted {} open trade(s) to {}", trades.size(), file);
        } catch (Exception e) {
            // Never propagate: a trade must not fail to open because the
            // disk is full or the path is unwritable.
            log.error("Could not persist open trades to {}: {}", file, e.getMessage());
        }
    }

    private void moveIntoPlace(Path temp) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public List<TradeSnapshot> load() {

        if (!Files.exists(file)) {
            return List.of();
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<TradeSnapshot> trades = gson.fromJson(json, new TypeToken<List<TradeSnapshot>>() {}.getType());
            return trades == null ? List.of() : trades;
        } catch (Exception e) {
            log.error("Could not read open trades from {} -- starting with none: {}", file, e.getMessage());
            return List.of();
        }
    }
}
