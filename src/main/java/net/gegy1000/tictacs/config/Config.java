package net.gegy1000.tictacs.config;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.gegy1000.tictacs.TicTacs;

public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static void read() {
		ConfigData data = null;

		// Attempt to read config
		try {
			Path configDir = Paths.get("", "config", "tictacs.json");
			if (Files.exists(configDir)) {
				// Config exists, read and store
				data = GSON.fromJson(new FileReader(configDir.toFile()), ConfigData.class);
				// Save new values if out of date
				if (!data.version.equals(TicTacs.VERSION)) {
					data.version = TicTacs.VERSION;
					BufferedWriter writer = new BufferedWriter(new FileWriter(configDir.toFile()));
					writer.write(GSON.toJson(data));
					writer.close();
				}
			} else {
				// Config doesn't exist, write new file
				data = new ConfigData();
				Paths.get("", "config").toFile().mkdirs();
				BufferedWriter writer = new BufferedWriter(new FileWriter(configDir.toFile()));
				writer.write(GSON.toJson(data));

				writer.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Store config data
		TicTacs.CONFIG = data;
	}
}
