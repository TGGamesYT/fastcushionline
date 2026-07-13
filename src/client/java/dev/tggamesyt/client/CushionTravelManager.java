package dev.tggamesyt.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CushionItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Drives the whole "sit-hop" fast-travel behaviour. Runs once per client tick.
 *
 * <p>How travel is triggered:</p>
 * <ul>
 *   <li><b>Rapid sit</b> — sitting on three evenly spaced, collinear cushions in
 *       quick succession is read as intent to travel and engages automatically.</li>
 *   <li><b>Space</b> — while sitting on a cushion, the jump key toggles travel
 *       on/off (jump does nothing on a cushion in vanilla, so it is free to
 *       reuse).</li>
 *   <li><b>{@code /fcl target x z}</b> — pathfinds toward coordinates, using
 *       nearby cushions and (optionally) auto-placed ones.</li>
 * </ul>
 *
 * <p>Once engaged it repeatedly mounts the next cushion the moment the server
 * confirms the current seat, honouring the player's entity-/block-interaction
 * range attributes for both reaching and placing. Sneaking (the vanilla "get
 * up" gesture) or a missing cushion ends travel.</p>
 */
public final class CushionTravelManager {

	// Timing / geometry tuning (all client-side, in ticks unless noted).
	private static final int RAPID_WINDOW = 40;   // max gap between the 3 quick sits
	private static final int OFF_SEAT_GRACE = 40; // tolerate ejection while waiting to re-mount
	private static final int PLACE_COOLDOWN = 3;  // wait after placing (entity must spawn)
	private static final int STUCK_LIMIT = 200;   // give up if no hop happens for this long
	private static final double REACH_MARGIN = 0.5;
	private static final int VERT_WINDOW = 4;     // how far up/down to look for a surface
	private static final double ARRIVE_DIST = 2.0;

	private final FclConfig config;

	private boolean traveling = false;
	private Vec3 hopDelta = null;        // per-hop displacement of the active line (null in pure target mode)
	private Cushion lastCushion = null;  // cushion we are (or were last) sitting on
	private Cushion hopSource = null;    // cushion we are leaving during an in-progress hop (for break-behind)
	private int placeCooldown = 0;       // brief wait after placing, for the entity to spawn
	private int noSeatTicks = 0;
	private int sinceProgress = 0;       // ticks since the last successful hop (stuck watchdog)
	private int ticks = 0;
	private boolean jumpWasDown = false;
	private int savedSlot = -1;          // hotbar slot to restore after auto-placing

	private boolean hasTarget = false;
	private double targetX, targetZ;

	private final Deque<Sit> sitHistory = new ArrayDeque<>();

	private record Sit(Vec3 pos, int tick) {
	}

	public CushionTravelManager(FclConfig config) {
		this.config = config;
	}

	public FclConfig config() {
		return config;
	}

	// ------------------------------------------------------------------ tick

	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null) {
			hardReset();
			return;
		}
		ticks++;

		Entity vehicle = player.getVehicle();
		Cushion current = vehicle instanceof Cushion c ? c : null;

		handleToggleKey(mc, player, current);

		boolean boardedNewCushion = current != lastCushion && current != null;
		if (current != lastCushion) {
			onSitChanged(current);
			if (current != null) {
				sinceProgress = 0; // boarding a cushion counts as progress
			}
			lastCushion = current;
		}

		if (traveling && ++sinceProgress > STUCK_LIMIT) {
			stop("stuck");
			return;
		}

		if (current == null) {
			handleOffSeat(player, level);
			return;
		}
		noSeatTicks = 0;

		// A hop completes when we board a cushion different from the one we left.
		// Break the departed cushion here (robust to the brief ejection mid-hop).
		if (traveling && hopSource != null && current != hopSource) {
			if (config.breakBehind() && hopSource.isAlive()
					&& player.isWithinEntityInteractionRange(hopSource, 1.0)) {
				breakCushion(player, hopSource);
			}
			hopSource = null;
		}

		if (!traveling) {
			// Only consider the rapid-sit gesture on the tick we actually board a
			// new cushion — otherwise stale sit history would re-trigger every
			// tick, flickering between engage and stop at the end of a line.
			if (boardedNewCushion) {
				tryRapidSitEngage(player, current);
			}
			return;
		}

		// --- travelling, seated ---
		if (player.isShiftKeyDown()) {
			stop("you got up");
			return;
		}
		if (placeCooldown > 0) {
			placeCooldown--;
			return;
		}
		advance(player, level, current);
	}

	/** Not on a cushion: either recover by mounting a reachable one, or give up. */
	private void handleOffSeat(LocalPlayer player, Level level) {
		if (!traveling) {
			return;
		}
		if (player.isShiftKeyDown()) {
			stop("you got up");
			return;
		}
		noSeatTicks++;
		if (placeCooldown > 0) {
			placeCooldown--;
			return;
		}
		// Retry mounting every tick (like a max-rate autoclicker) so we re-seat
		// the instant the server's sitting rules allow it.
		Cushion c = pickReachableCushion(player, level);
		if (c != null) {
			mountCushion(player, c);
			noSeatTicks = 0; // still actively trying; the stuck watchdog is the real backstop
			return;
		}
		if (config.autoPlace()) {
			CushionNav.PlacePlan starter = starterPlacement(player, level);
			if (starter != null && placeCushion(player, starter)) {
				placeCooldown = PLACE_COOLDOWN;
				noSeatTicks = 0;
				return;
			}
		}
		if (noSeatTicks > OFF_SEAT_GRACE) {
			stop(hasTarget ? "no reachable cushion toward target" : "no reachable cushion");
		}
	}

	// ----------------------------------------------------------- sit tracking

	private void onSitChanged(Cushion current) {
		if (current != null) {
			sitHistory.addLast(new Sit(current.position(), ticks));
			while (sitHistory.size() > 4) {
				sitHistory.removeFirst();
			}
		}
	}

	// --------------------------------------------------------- engage / toggle

	private void handleToggleKey(Minecraft mc, LocalPlayer player, Cushion current) {
		// While any screen (menu, chat, inventory) is open the vanilla input
		// system releases every key mapping, so isDown() is already false there
		// and space stays free for jumping in the world otherwise.
		boolean down = mc.options.keyJump.isDown();
		boolean pressed = down && !jumpWasDown;
		jumpWasDown = down;
		if (!pressed || current == null) {
			return;
		}
		if (traveling) {
			stop("toggled off");
		} else {
			Vec3 dir = deriveDirection(player, current);
			if (dir == null && !hasTarget) {
				actionBar(player, "FastCushionLine: no line direction — sit along a line or set /fcl target");
				return;
			}
			hopDelta = dir;
			startTravel(player, current, "toggled on");
		}
	}

	/** Detects the "sit on three quickly, evenly spaced" gesture. */
	private void tryRapidSitEngage(LocalPlayer player, Cushion current) {
		if (sitHistory.size() < 3) {
			return;
		}
		Sit[] s = sitHistory.toArray(new Sit[0]);
		Sit s0 = s[s.length - 3];
		Sit s1 = s[s.length - 2];
		Sit s2 = s[s.length - 1];
		if (s1.tick - s0.tick > RAPID_WINDOW || s2.tick - s1.tick > RAPID_WINDOW) {
			return;
		}
		Vec3 d1 = s1.pos.subtract(s0.pos);
		Vec3 d2 = s2.pos.subtract(s1.pos);
		if (d2.length() < 0.5 || !vecApproxEquals(d1, d2)) {
			return; // not an equal-spaced collinear run
		}
		hopDelta = d2;
		startTravel(player, current, "line detected");
	}

	/**
	 * Best-effort travel direction when engaging manually: from the last two
	 * sits if they line up, otherwise from the nearest cushion the player is
	 * looking toward.
	 */
	private Vec3 deriveDirection(LocalPlayer player, Cushion current) {
		if (sitHistory.size() >= 2) {
			Sit[] s = sitHistory.toArray(new Sit[0]);
			Vec3 d = s[s.length - 1].pos.subtract(s[s.length - 2].pos);
			if (d.length() > 0.5) {
				return d;
			}
		}
		Level level = current.level();
		Vec3 cur = current.position();
		Vec3 look = player.getLookAngle();
		Vec3 lookFlat = new Vec3(look.x, 0, look.z);
		if (lookFlat.length() < 1.0e-4) {
			return null;
		}
		lookFlat = lookFlat.normalize();
		double range = player.entityInteractionRange();
		Vec3 best = null;
		double bestScore = -1;
		for (Cushion c : CushionNav.cushionsWithin(level, cur, range * 1.7)) {
			if (c == current) {
				continue;
			}
			Vec3 delta = c.position().subtract(cur);
			if (delta.length() < 0.5) {
				continue;
			}
			Vec3 flat = new Vec3(delta.x, 0, delta.z);
			if (flat.length() < 1.0e-4) {
				continue;
			}
			double align = flat.normalize().dot(lookFlat);
			if (align <= 0.3) {
				continue; // must be roughly in front of the player
			}
			double score = align - 0.05 * delta.length();
			if (score > bestScore) {
				bestScore = score;
				best = delta;
			}
		}
		return best;
	}

	// ------------------------------------------------------------- advancing

	private void advance(LocalPlayer player, Level level, Cushion current) {
		Vec3 cur = current.position();
		double range = player.entityInteractionRange();

		Cushion next = pickNextCushion(player, level, current, cur, range);
		if (next != null) {
			hopSource = current; // remember what to break behind once we board `next`
			// Send the mount every tick (no artificial throttle) so we hop as
			// fast as the server's sitting rules permit — as quick as, or quicker
			// than, a manual autoclicker.
			mountCushion(player, next);
			return;
		}
		if (config.autoPlace()) {
			CushionNav.PlacePlan plan = planPlacement(player, level, cur, range);
			if (plan != null && placeCushion(player, plan)) {
				placeCooldown = PLACE_COOLDOWN;
				return;
			}
		}
		if (hasTarget && horizDist(cur, targetX, targetZ) <= ARRIVE_DIST) {
			stop("arrived at target");
		} else {
			stop("cushion line ended");
		}
	}

	/** Existing cushion to hop to next, or null. */
	private Cushion pickNextCushion(LocalPlayer player, Level level, Cushion current, Vec3 cur, double range) {
		if (hasTarget) {
			double bestRemain = horizDist(cur, targetX, targetZ) - 0.5; // require real progress
			Cushion best = null;
			for (Cushion c : CushionNav.cushionsWithin(level, cur, range + 0.5)) {
				if (c == current || !c.isAlive() || !player.isWithinEntityInteractionRange(c, 1.0)) {
					continue;
				}
				double remain = horizDist(c.position(), targetX, targetZ);
				if (remain < bestRemain) {
					bestRemain = remain;
					best = c;
				}
			}
			return best;
		}
		if (hopDelta == null) {
			return null;
		}
		// Line-follow: prefer the cushion nearest the extrapolated next point
		// (cur + hopDelta), but tolerate small spacing/alignment error so a
		// hand-placed, not-perfectly-even line still chains reliably.
		double spacing = hopDelta.length();
		if (spacing < 1.0e-4) {
			return null;
		}
		Vec3 dir = hopDelta.scale(1.0 / spacing);
		Vec3 expected = cur.add(hopDelta);
		double maxOffset = Math.max(0.75, spacing * 0.5);
		Cushion best = null;
		double bestOffset = Double.MAX_VALUE;
		for (Cushion c : CushionNav.cushionsWithin(level, cur, spacing + range + 1.5)) {
			if (c == current || !c.isAlive() || !player.isWithinEntityInteractionRange(c, 1.0)) {
				continue;
			}
			Vec3 pos = c.position();
			Vec3 delta = pos.subtract(cur);
			double len = delta.length();
			if (len < 0.4) {
				continue;
			}
			// Must lie forward along the line, not off to the side or behind.
			if (delta.scale(1.0 / len).dot(dir) < 0.9) {
				continue;
			}
			double offset = pos.distanceTo(expected);
			if (offset <= maxOffset && offset < bestOffset) {
				bestOffset = offset;
				best = c;
			}
		}
		return best;
	}

	// ------------------------------------------------------------- placement

	/**
	 * Chooses where to place the next cushion. In target mode it steps toward
	 * the goal using the reach distance as the ideal spacing (fewer cushions,
	 * more ground per hop); in line mode it reuses the line's spacing and only
	 * adapts height to the terrain, preserving the overall heading. Obstructed
	 * columns are routed around with short/lateral candidates.
	 */
	private CushionNav.PlacePlan planPlacement(LocalPlayer player, Level level, Vec3 cur, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 horizDir;
		double stepLen;
		double preferredY;

		if (hasTarget) {
			double dx = targetX - cur.x;
			double dz = targetZ - cur.z;
			double horiz = Math.sqrt(dx * dx + dz * dz);
			if (horiz <= ARRIVE_DIST) {
				return null;
			}
			horizDir = new Vec3(dx / horiz, 0, dz / horiz);
			stepLen = Math.min(range - REACH_MARGIN, horiz);
			stepLen = Math.max(stepLen, 1.0);
			preferredY = cur.y;
		} else {
			if (hopDelta == null) {
				return null;
			}
			double horiz = Math.sqrt(hopDelta.x * hopDelta.x + hopDelta.z * hopDelta.z);
			preferredY = cur.y + hopDelta.y;
			if (horiz < 0.5) {
				// (near-)vertical line: keep using the raw delta.
				Vec3 q = new Vec3(Mth.floor(cur.x) + 0.5, Math.round(cur.y + hopDelta.y),
						Mth.floor(cur.z) + 0.5);
				return validatedPlacement(player, eye, level, q, range);
			}
			horizDir = new Vec3(hopDelta.x / horiz, 0, hopDelta.z / horiz);
			stepLen = horiz;
		}

		Vec3 perp = new Vec3(-horizDir.z, 0, horizDir.x);
		int curBx = Mth.floor(cur.x);
		int curBz = Mth.floor(cur.z);

		CushionNav.PlacePlan best = null;
		double bestScore = Double.MAX_VALUE;
		double[] stepFactors = {1.0, 0.85, 0.7, 0.55, 0.4};
		int[] laterals = {0, 1, -1, 2, -2};

		for (double f : stepFactors) {
			for (int lat : laterals) {
				double sx = cur.x + horizDir.x * stepLen * f + perp.x * lat;
				double sz = cur.z + horizDir.z * stepLen * f + perp.z * lat;
				int bx = Mth.floor(sx);
				int bz = Mth.floor(sz);
				if (bx == curBx && bz == curBz) {
					continue;
				}
				Vec3 q = CushionNav.findSurfaceInColumn(level, bx, bz, preferredY, VERT_WINDOW);
				if (q == null) {
					continue;
				}
				// must make forward progress along the heading
				Vec3 step = q.subtract(cur);
				if (step.x * horizDir.x + step.z * horizDir.z <= 0.3) {
					continue;
				}
				if (!CushionNav.mountReachable(eye, q, range)) {
					continue;
				}
				BlockPos support = CushionNav.supportBlock(q);
				if (!player.isWithinBlockInteractionRange(support, 1.0)) {
					continue;
				}
				double score;
				if (hasTarget) {
					score = horizDist(q, targetX, targetZ); // closest to goal wins
				} else {
					Vec3 ideal = cur.add(hopDelta);
					score = q.distanceToSqr(ideal); // closest to the extrapolated line point
				}
				if (score < bestScore) {
					bestScore = score;
					best = new CushionNav.PlacePlan(q, support, q);
				}
			}
		}
		return best;
	}

	/** A place-and-mount starter cushion at the player's feet (for /fcl target from the ground). */
	private CushionNav.PlacePlan starterPlacement(LocalPlayer player, Level level) {
		Vec3 p = player.position();
		int feetY = Mth.floor(p.y);
		Vec3 q = new Vec3(Mth.floor(p.x) + 0.5, feetY, Mth.floor(p.z) + 0.5);
		return validatedPlacement(player, player.getEyePosition(), level, q, player.entityInteractionRange());
	}

	private CushionNav.PlacePlan validatedPlacement(LocalPlayer player, Vec3 eye, Level level, Vec3 q, double range) {
		if (!CushionNav.isPlaceable(level, q)) {
			return null;
		}
		BlockPos support = CushionNav.supportBlock(q);
		if (!player.isWithinBlockInteractionRange(support, 1.0)) {
			return null;
		}
		if (!CushionNav.mountReachable(eye, q, range)) {
			return null;
		}
		return new CushionNav.PlacePlan(q, support, q);
	}

	// ----------------------------------------------------------- game actions

	private void mountCushion(LocalPlayer player, Cushion c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode == null) {
			return;
		}
		EntityHitResult hit = new EntityHitResult(c, c.position());
		mc.gameMode.interact(player, c, hit, InteractionHand.MAIN_HAND);
		player.swing(InteractionHand.MAIN_HAND);
	}

	private void breakCushion(LocalPlayer player, Cushion c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode == null) {
			return;
		}
		mc.gameMode.attack(player, c);
		player.swing(InteractionHand.MAIN_HAND);
	}

	private boolean placeCushion(LocalPlayer player, CushionNav.PlacePlan plan) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode == null) {
			return false;
		}
		int slot = findHotbarCushionSlot(player);
		if (slot < 0) {
			return false; // no cushions available in the hotbar
		}
		Inventory inv = player.getInventory();
		if (savedSlot < 0) {
			savedSlot = inv.getSelectedSlot();
		}
		if (inv.getSelectedSlot() != slot) {
			inv.setSelectedSlot(slot);
		}
		BlockHitResult hit = new BlockHitResult(plan.hitLocation(), Direction.UP, plan.supportPos(), false);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	private int findHotbarCushionSlot(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int hotbar = Inventory.getSelectionSize();
		for (int i = 0; i < hotbar; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.getItem() instanceof CushionItem) {
				return i;
			}
		}
		return -1;
	}

	private Cushion pickReachableCushion(LocalPlayer player, Level level) {
		Vec3 pos = player.position();
		double range = player.entityInteractionRange();
		List<Cushion> near = CushionNav.cushionsWithin(level, player.getEyePosition(), range + 1.0);
		Cushion best = null;
		double bestScore = Double.MAX_VALUE;
		for (Cushion c : near) {
			if (!c.isAlive() || !player.isWithinEntityInteractionRange(c, 1.0)) {
				continue;
			}
			double score = hasTarget
					? horizDist(c.position(), targetX, targetZ)
					: c.position().distanceToSqr(pos);
			if (score < bestScore) {
				bestScore = score;
				best = c;
			}
		}
		return best;
	}

	// ------------------------------------------------------------- lifecycle

	private void startTravel(LocalPlayer player, Cushion current, String reason) {
		traveling = true;
		lastCushion = current;
		hopSource = null;
		noSeatTicks = 0;
		placeCooldown = 0;
		sinceProgress = 0;
		savedSlot = -1;
		actionBar(player, "FastCushionLine: travelling (" + reason + ")");
	}

	private void stop(String reason) {
		boolean was = traveling;
		traveling = false;
		hopDelta = null;
		hasTarget = false;
		hopSource = null;
		sinceProgress = 0;
		restoreSlot();
		if (was) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				actionBar(player, "FastCushionLine: stopped (" + reason + ")");
			}
		}
	}

	private void restoreSlot() {
		if (savedSlot >= 0) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.getInventory().setSelectedSlot(savedSlot);
			}
			savedSlot = -1;
		}
	}

	private void hardReset() {
		traveling = false;
		hopDelta = null;
		hasTarget = false;
		lastCushion = null;
		hopSource = null;
		savedSlot = -1;
		placeCooldown = 0;
		noSeatTicks = 0;
		sinceProgress = 0;
		sitHistory.clear();
	}

	// ------------------------------------------------------- command-facing API

	/** Handles {@code /fcl target x z}: sets the goal and starts moving toward it. */
	public void setTarget(int x, int z) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null) {
			return;
		}
		targetX = x + 0.5;
		targetZ = z + 0.5;
		hasTarget = true;
		hopDelta = null;

		Entity vehicle = player.getVehicle();
		Cushion current = vehicle instanceof Cushion c ? c : null;
		if (current != null) {
			startTravel(player, current, "target " + x + ", " + z);
			return;
		}
		if (config.autoPlace() && findHotbarCushionSlot(player) >= 0) {
			traveling = true;
			lastCushion = null;
			hopSource = null;
			noSeatTicks = 0;
			placeCooldown = 0;
			sinceProgress = 0;
			savedSlot = -1;
			CushionNav.PlacePlan starter = starterPlacement(player, level);
			if (starter != null) {
				placeCushion(player, starter);
				placeCooldown = PLACE_COOLDOWN;
			}
			actionBar(player, "FastCushionLine: heading to " + x + ", " + z);
		} else {
			actionBar(player, "Target " + x + ", " + z
					+ " set. Sit on a cushion, or enable /fcl autoplace with cushions in your hotbar.");
		}
	}

	public void cancel() {
		stop("cancelled");
	}

	public boolean isTraveling() {
		return traveling;
	}

	public boolean hasTarget() {
		return hasTarget;
	}

	public String status() {
		StringBuilder sb = new StringBuilder();
		sb.append("travelling=").append(traveling);
		if (hopDelta != null) {
			sb.append(String.format(", step=(%.1f, %.1f, %.1f)", hopDelta.x, hopDelta.y, hopDelta.z));
		}
		if (hasTarget) {
			sb.append(String.format(", target=(%.0f, %.0f)", targetX, targetZ));
		}
		sb.append(", breakBehind=").append(config.breakBehind());
		sb.append(", autoPlace=").append(config.autoPlace());
		return sb.toString();
	}

	// ------------------------------------------------------------------ utils

	private static boolean vecApproxEquals(Vec3 a, Vec3 b) {
		return Math.abs(a.x - b.x) <= CushionNav.POS_EPS
				&& Math.abs(a.y - b.y) <= CushionNav.POS_EPS
				&& Math.abs(a.z - b.z) <= CushionNav.POS_EPS;
	}

	private static double horizDist(Vec3 from, double x, double z) {
		return Math.sqrt(CushionNav.horizDistSqr(from, x, z));
	}

	private static void actionBar(LocalPlayer player, String text) {
		player.sendOverlayMessage(Component.literal(text));
	}
}
