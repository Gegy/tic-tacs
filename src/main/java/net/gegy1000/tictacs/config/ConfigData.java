package net.gegy1000.tictacs.config;

import net.gegy1000.tictacs.TicTacs;

public class ConfigData {
	public String version = TicTacs.VERSION;

	public int threadCount = Runtime.getRuntime().availableProcessors();
	public int featureGenerationRadius = 1;

	public boolean debugChunkTickets = false;
}
