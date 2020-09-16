package net.gegy1000.tictacs;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.gegy1000.tictacs.config.TicTacsConfig;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class TicTacs implements ModInitializer {
    public static final String ID = "tic_tacs";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    public static final Identifier DEBUG_CHUNK_TICKETS = new Identifier(ID, "debug_chunk_tickets");

    public static final boolean DEBUG = FabricLoader.getInstance().isDevelopmentEnvironment();

    @Override
    public void onInitialize() {
        TicTacsConfig.get();
    }
}
