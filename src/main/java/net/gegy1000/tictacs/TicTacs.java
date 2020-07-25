package net.gegy1000.tictacs;

import net.gegy1000.tictacs.config.Config;
import net.gegy1000.tictacs.config.ConfigData;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;

public final class TicTacs implements ModInitializer {
    public static final String VERSION = "1.0.0";
    public static final String ID = "tic_tacs";
    public static ConfigData CONFIG;

    public static final Identifier DEBUG_CHUNK_TICKETS = new Identifier(ID, "debug_chunk_tickets");

    @Override
    public void onInitialize() {
        Config.read();
    }
}
