package dev.tggamesyt.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Registers the client-only {@code /fcl} command:
 * <pre>
 *   /fcl target &lt;x&gt; &lt;z&gt;      pathfind toward these coordinates
 *   /fcl target clear         clear the target and stop
 *   /fcl breakbehind &lt;bool&gt;   break cushions left behind while travelling
 *   /fcl autoplace &lt;bool&gt;     auto-place cushions from the hotbar to extend a line
 *   /fcl stop                 stop travelling
 *   /fcl status               show current state and settings
 * </pre>
 */
public final class FclCommand {

	private FclCommand() {
	}

	public static void register(CushionTravelManager manager) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(literal("fcl")
						.then(literal("target")
								.then(literal("clear").executes(ctx -> {
									manager.cancel();
									feedback(ctx.getSource(), "Target cleared.");
									return 1;
								}))
								.then(argument("x", IntegerArgumentType.integer())
										.then(argument("z", IntegerArgumentType.integer())
												.executes(ctx -> {
													int x = IntegerArgumentType.getInteger(ctx, "x");
													int z = IntegerArgumentType.getInteger(ctx, "z");
													manager.setTarget(x, z);
													feedback(ctx.getSource(), "Target set to " + x + ", " + z + ".");
													return 1;
												}))))
						.then(literal("breakbehind")
								.then(argument("value", BoolArgumentType.bool())
										.executes(ctx -> {
											boolean v = BoolArgumentType.getBool(ctx, "value");
											manager.config().setBreakBehind(v);
											feedback(ctx.getSource(), "breakBehind = " + v);
											return 1;
										})))
						.then(literal("autoplace")
								.then(argument("value", BoolArgumentType.bool())
										.executes(ctx -> {
											boolean v = BoolArgumentType.getBool(ctx, "value");
											manager.config().setAutoPlace(v);
											feedback(ctx.getSource(), "autoPlace = " + v);
											return 1;
										})))
						.then(literal("stop").executes(ctx -> {
							manager.cancel();
							feedback(ctx.getSource(), "Stopped.");
							return 1;
						}))
						.then(literal("status").executes(ctx -> {
							feedback(ctx.getSource(), manager.status());
							return 1;
						}))));
	}

	private static void feedback(FabricClientCommandSource source, String text) {
		source.sendFeedback(Component.literal("[FCL] " + text));
	}
}
