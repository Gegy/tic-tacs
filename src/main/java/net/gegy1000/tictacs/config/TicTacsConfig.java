package net.gegy1000.tictacs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.gegy1000.tictacs.TicTacs;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TicTacsConfig {
    public static final int VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static TicTacsConfig config;

    public int version = VERSION;

    @SerializedName("thread_count")
    public int threadCount = computeDefaultThreadCount();

    @SerializedName("max_view_distance")
    public int maxViewDistance = 32;

    @SerializedName("feature_generation_radius")
    public int featureGenerationRadius = 2;

    @SerializedName("debug")
    public Debug debug = new Debug();

    public static class Debug {
        @SerializedName("chunk_levels")
        public boolean chunkLevels;

        @SerializedName("chunk_map")
        public boolean chunkMap;
    }

    private static int computeDefaultThreadCount() {
        return MathHelper.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 2);
    }

    public boolean isSingleThreaded() {
        return this.threadCount == 1;
    }

    @NotNull
    public static TicTacsConfig get() {
        if (config == null) {
            try {
                config = loadConfig();
            } catch (IOException e) {
                TicTacs.LOGGER.warn("Failed to read config file", e);
                config = new TicTacsConfig();
            }
        }

        return config;
    }

    private static TicTacsConfig loadConfig() throws IOException {
        Path path = Paths.get("config", "tictacs.json");
        if (!Files.exists(path)) {
            return createConfig(path);
        }

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            TicTacsConfig config = parseConfig(reader);

            // version change: recreate the config
            if (config.version != VERSION) {
                return createConfig(path);
            }

            return config;
        }
    }

    private static TicTacsConfig createConfig(Path path) throws IOException {
        TicTacsConfig config = new TicTacsConfig();

        Files.createDirectories(path.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(GSON.toJson(config));
        }

        return config;
    }

    private static TicTacsConfig parseConfig(Reader reader) {
        return GSON.fromJson(reader, TicTacsConfig.class);
    }
}
