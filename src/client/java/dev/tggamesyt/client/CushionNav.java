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
import net.minecraft.world.phys.BlockHitResult;
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

	/** Whether there is a surface just below {@code cushionPos} to hold a cushion up. */
	public static boolean hasSupport(Level level, Vec3 cushionPos) {
		return Cushion.wouldSuriveAt(level, EntityTypes.CUSHION.getSpawnAABB(cushionPos));
	}

	/** Whether the cushion's own cell is free (replaceable and, when checked, not already a cushion). */
	public static boolean cellClear(Level level, Vec3 cushionPos, boolean checkEntities) {
		if (checkEntities) {
			AABB spawnBox = EntityTypes.CUSHION.getSpawnAABB(cushionPos);
			if (!level.getEntitiesOfClass(Cushion.class, spawnBox).isEmpty()) {
				return false; // already a cushion here
			}
		}
		return replaceable(level, BlockPos.containing(cushionPos));
	}

	/**
	 * Whether a cushion could legally be placed so that it rests at
	 * {@code cushionPos}. Mirrors the vanilla {@code CushionItem}/{@code Cushion}
	 * checks: there must be a supporting surface just below, the cell itself must
	 * be replaceable (air/grass/…), and (when {@code checkEntities}) no other
	 * cushion may already occupy it. The pathfinder passes {@code false} to skip
	 * the per-cell entity query for speed; real placement always checks.
	 */
	public static boolean isPlaceable(Level level, Vec3 cushionPos, boolean checkEntities) {
		return hasSupport(level, cushionPos) && cellClear(level, cushionPos, checkEntities);
	}

	public static boolean isPlaceable(Level level, Vec3 cushionPos) {
		return isPlaceable(level, cushionPos, true);
	}

	private static boolean replaceable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.isAir() || state.canBeReplaced();
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
		return findSurfaceInColumn(level, bx, bz, preferredY, window, true);
	}

	public static Vec3 findSurfaceInColumn(Level level, int bx, int bz, double preferredY, int window,
			boolean checkEntities) {
		int base = Mth.floor(preferredY + 0.5);
		for (int radius = 0; radius <= window; radius++) {
			// Prefer the height closest to preferredY; on ties try the lower one
			// first so the path hugs the ground instead of climbing.
			for (int sign : (radius == 0 ? new int[] {0} : new int[] {-1, 1})) {
				int cellY = base + sign * radius;
				Vec3 pos = new Vec3(bx + 0.5, cellY, bz + 0.5);
				if (isPlaceable(level, pos, checkEntities)) {
					return pos;
				}
			}
		}
		return null;
	}

	/**
	 * Finds a cell in column ({@code bx},{@code bz}) near {@code preferredY} that
	 * is <em>clear but unsupported</em> — i.e. a gap we could bridge by first
	 * placing a support block beneath it. Only returns a cell whose support
	 * position is empty and has a solid neighbour to build against.
	 *
	 * @return the cushion position for a bridgeable cell, or null.
	 */
	public static Vec3 findBridgeCellInColumn(Level level, int bx, int bz, double preferredY, int window) {
		return findBridgeCellInColumn(level, bx, bz, preferredY, window, true);
	}

	public static Vec3 findBridgeCellInColumn(Level level, int bx, int bz, double preferredY, int window,
			boolean checkEntities) {
		int base = Mth.floor(preferredY + 0.5);
		for (int radius = 0; radius <= window; radius++) {
			for (int sign : (radius == 0 ? new int[] {0} : new int[] {-1, 1})) {
				int cellY = base + sign * radius;
				Vec3 q = new Vec3(bx + 0.5, cellY, bz + 0.5);
				if (!cellClear(level, q, checkEntities) || hasSupport(level, q)) {
					continue; // occupied, or already has natural support (not a bridge case)
				}
				BlockPos support = supportBlock(q);
				if (replaceable(level, support) && blockPlaceAnchor(level, support) != null) {
					return q;
				}
			}
		}
		return null;
	}

	/**
	 * Builds the click needed to place a support block at {@code target} by
	 * clicking a solid neighbour's face. Prefers building off the block below,
	 * then sideways. Returns null if {@code target} isn't empty or has no solid
	 * neighbour to place against.
	 */
	public static BlockHitResult blockPlaceAnchor(Level level, BlockPos target) {
		if (!replaceable(level, target)) {
			return null;
		}
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
				Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction d : order) {
			BlockPos neighbour = target.relative(d);
			BlockState ns = level.getBlockState(neighbour);
			if (ns.isAir() || ns.canBeReplaced() || !ns.isCollisionShapeFullBlock(level, neighbour)) {
				continue;
			}
			Direction face = d.getOpposite(); // face of the neighbour pointing at target
			Vec3 loc = Vec3.atCenterOf(neighbour)
					.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			return new BlockHitResult(loc, face, neighbour, false);
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
	 * Whether a cushion at {@code cushionPos} could be reached from the given eye
	 * position. Mirrors vanilla's real reach test, which measures the eye to the
	 * nearest point of the cushion's <em>hitbox</em> (not its centre) against the
	 * interaction-range attribute. Measuring to the hitbox edge is why a 3×1 hop
	 * (centre distance √10 ≈ 3.16, over the 3.0 range) is still reachable — its
	 * near edge sits inside the range. A tiny tolerance is added so boundary hops
	 * like that aren't lost (the server is far more lenient still).
	 */
	public static boolean mountReachable(Vec3 eye, Vec3 cushionPos, double range) {
		AABB box = EntityTypes.CUSHION.getSpawnAABB(cushionPos);
		double max = range + 0.1;
		return box.distanceToSqr(eye) <= max * max;
	}

	/** Direction of the UP face, used when simulating cushion placement clicks. */
	public static Direction placeFace() {
		return Direction.UP;
	}
}
