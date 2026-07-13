package dev.tggamesyt;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common initializer. FastCushionLine is a client-side utility mod, so all of
 * the real logic lives in {@link dev.tggamesyt.client.FastCushionLineClient}
 * and the classes in the {@code dev.tggamesyt.client} package.
 */
public class FastCushionLine implements ModInitializer {
	public static final String MOD_ID = "fastcushionline";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("FastCushionLine loaded (client-side cushion fast-travel).");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
