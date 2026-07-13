package dev.tggamesyt.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Pure geometry / world-query helpers shared by the travel manager. Nothing
 * here mutates game state; these methods only read the world and do math so the
 * manager can decide where to hop or place a cushion.
 *
 * <p>Cushions are {@code BlockAttachedEntity}s that sit centred on a block
 * column ({@code x+0.5}, {@code z+0.5}) resting on the surface below them, so a
 * cushion's position always looks like {@code (bx+0.5, surfaceY, bz+0.5)}. A
 * "line" is a run of cushions separated by a constant 3D displacement, which is
 * what makes any-direction (diagonal, sloped, staircase) travel possible.</p>
 */
public final class CushionNav {

	/** Positions are grid-snapped, so exact matches only need a small epsilon. */
	public static final double POS_EPS = 0.2;

	private CushionNav() {
	}

	/** A concrete placement the manager can execute: where the cushion lands and which block supports it. */
	public record PlacePlan(Vec3 cushionPos, BlockPos supportPos, Vec3 hitLocation) {
	}

	/** Squared horizontal (x/z) distance between two points. */
	public static double horizDistSqr(Vec3 a, double x, double z) {
		double dx = a.x - x;
		double dz = a.z - z;
		return dx * dx + dz * dz;
	}

	/** Returns the closest cushion within {@code eps} of {@code target}, or null. */
	public static Cushion cushionAt(Level level, Vec3 target, double eps) {
		AABB box = new AABB(target.x - eps, target.y - eps, target.z - eps,
				target.x + eps, target.y + eps, target.z + eps);
		Cushion best = null;
		double bestSqr = eps * eps;
		for (Cushion c : level.getEntitiesOfClass(Cushion.class, box)) {
			double d = c.position().distanceToSqr(target);
			if (d <= bestSqr) {
				bestSqr = d;
				best = c;
			}
		}
		return best;
	}

	/** All cushions whose centre is within {@code radius} of {@code center}. */
	public static List<Cushion> cushionsWithin(Level level, Vec3 center, double radius) {
		AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
				center.x + radius, center.y + radius, center.z + radius);
		return level.getEntitiesOfClass(Cushion.class, box,
				c -> c.position().distanceToSqr(center) <= radius * radius);
	}

	/**
	 * Whether a cushion could legally be placed so that it rests at
	 * {@code cushionPos}. Mirrors the vanilla {@code CushionItem}/{@code Cushion}
	 * checks: there must be a supporting surface just below, the cell itself must
	 * be replaceable (air/grass/…), and no other cushion may already occupy it.
	 */
	public static boolean isPlaceable(Level level, Vec3 cushionPos) {
		AABB spawnBox = EntityTypes.CUSHION.getSpawnAABB(cushionPos);
		if (!Cushion.wouldSuriveAt(level, spawnBox)) {
			return false; // nothing solid underneath to support it
		}
		if (!level.getEntitiesOfClass(Cushion.class, spawnBox).isEmpty()) {
			return false; // already a cushion here
		}
		BlockState cell = level.getBlockState(BlockPos.containing(cushionPos));
		return cell.isAir() || cell.canBeReplaced();
	}

	/** The block that supports a cushion resting at {@code cushionPos}. */
	public static BlockPos supportBlock(Vec3 cushionPos) {
		return BlockPos.containing(cushionPos).below();
	}

	/**
	 * Finds a placeable cushion cell in the block column ({@code bx},{@code bz})
	 * whose height is as close as possible to {@code preferredY}, searching
	 * {@code window} blocks up and down. This is what lets auto-placement follow
	 * terrain (steps, slopes) while keeping the horizontal heading fixed.
	 *
	 * @return the cushion position, or null if the column has no usable surface.
	 */
	public static Vec3 findSurfaceInColumn(Level level, int bx, int bz, double preferredY, int window) {
		int base = Mth.floor(preferredY + 0.5);
		for (int radius = 0; radius <= window; radius++) {
			// Prefer the height closest to preferredY; on ties try the lower one
			// first so the path hugs the ground instead of climbing.
			for (int sign : (radius == 0 ? new int[] {0} : new int[] {-1, 1})) {
				int cellY = base + sign * radius;
				Vec3 pos = new Vec3(bx + 0.5, cellY, bz + 0.5);
				if (isPlaceable(level, pos)) {
					return pos;
				}
			}
		}
		return null;
	}

	/**
	 * Approximate eye position of a player sitting on a cushion at
	 * {@code cushionPos}, reusing the offset the player currently has from their
	 * own cushion. Reach for mounting/placing is measured from the eye.
	 */
	public static Vec3 seatedEye(Player player, Vec3 currentCushionPos, Vec3 cushionPos) {
		Vec3 offset = player.getEyePosition().subtract(currentCushionPos);
		return cushionPos.add(offset);
	}

	/**
	 * Whether a cushion at {@code cushionPos} could be reached (to mount it) from
	 * the given eye position, honouring the player's entity-interaction-range
	 * attribute. A small slack accounts for the cushion's own size.
	 */
	public static boolean mountReachable(Vec3 eye, Vec3 cushionPos, double entityRange) {
		return eye.distanceTo(cushionPos) <= entityRange + 0.5;
	}

	/** Direction of the UP face, used when simulating cushion placement clicks. */
	public static Direction placeFace() {
		return Direction.UP;
	}
}
