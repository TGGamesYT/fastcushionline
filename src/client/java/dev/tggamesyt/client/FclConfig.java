package dev.tggamesyt.client;

import dev.tggamesyt.FastCushionLine;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistent, per-user settings for FastCushionLine. Values are toggled through
 * the {@code /fcl} command and saved to {@code config/fastcushionline.properties}.
 * Everything is client-side; nothing here is sent to the server.
 */
public final class FclConfig {

	/** Break the cushion you just left before/while hopping to the next one. */
	private boolean breakBehind = false;

	/**
	 * When a cushion line runs out, automatically place cushions from the
	 * hotbar to continue it (maintaining the line's heading), or to reach a
	 * {@code /fcl target}.
	 */
	private boolean autoPlace = false;

	private final Path file;

	public FclConfig() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		this.file = dir.resolve("fastcushionline.properties");
		load();
	}

	public boolean breakBehind() {
		return breakBehind;
	}

	public boolean autoPlace() {
		return autoPlace;
	}

	public void setBreakBehind(boolean value) {
		this.breakBehind = value;
		save();
	}

	public void setAutoPlace(boolean value) {
		this.autoPlace = value;
		save();
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			props.load(in);
			breakBehind = Boolean.parseBoolean(props.getProperty("breakBehind", "false"));
			autoPlace = Boolean.parseBoolean(props.getProperty("autoPlace", "false"));
		} catch (IOException e) {
			FastCushionLine.LOGGER.warn("Could not read FastCushionLine config", e);
		}
	}

	private void save() {
		Properties props = new Properties();
		props.setProperty("breakBehind", Boolean.toString(breakBehind));
		props.setProperty("autoPlace", Boolean.toString(autoPlace));
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "FastCushionLine settings");
			}
		} catch (IOException e) {
			FastCushionLine.LOGGER.warn("Could not write FastCushionLine config", e);
		}
	}
}
