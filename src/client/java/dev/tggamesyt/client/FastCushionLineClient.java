package dev.tggamesyt.client;

import dev.tggamesyt.FastCushionLine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entrypoint. Wires up the per-tick travel manager and the {@code /fcl}
 * command. All behaviour is client-side: it only sends the same interact,
 * attack and use-item packets a player could send by hand, so it works on any
 * vanilla-compatible server.
 */
public class FastCushionLineClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		FclConfig config = new FclConfig();
		CushionTravelManager manager = new CushionTravelManager(config);

		ClientTickEvents.END_CLIENT_TICK.register(client -> manager.tick());
		FclCommand.register(manager);

		FastCushionLine.LOGGER.info("FastCushionLine client ready (/fcl for options).");
	}
}
