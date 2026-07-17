package dev.tggamesyt.client;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded A* search over cushion positions, used to route toward a
 * {@code /fcl target} when the cheap forward-only planner is blocked. It treats
 * every legal cushion spot as a node and every "hop within reach" as an edge, so
 * it can detour sideways, backtrack or go up/down to get around an obstacle
 * instead of giving up.
 *
 * <p>To stay cheap it uses light placeability checks (no per-cell entity query)
 * and caps how many nodes it expands; the manager re-validates and only ever
 * places one cushion at a time. If it cannot reach the goal within budget it
 * still returns the first step toward the closest spot it found, so travel keeps
 * making progress rather than stopping dead.</p>
 */
public final class CushionPathfinder {

	private static final int MAX_EXPANSIONS = 2500;
	private static final int VERT_WINDOW = 5;
	private static final double REACH_MARGIN = 0.6;
	private static final double ARRIVE_DIST = 1.0;
	// Cost is essentially "number of hops", so the fewest-hop route wins — which
	// is the direct one (over a hill, onto a bridge, or a short self-built bridge)
	// rather than a long detour. Bridging costs a little extra so a real surface
	// of similar length is preferred, but not enough to favour a big detour.
	private static final double BRIDGE_PENALTY = 0.5;
	private static final int MAX_REL = 220;           // search radius from the start (blocks)

	private CushionPathfinder() {
	}

	/**
	 * @return the sequence of cushion positions from {@code start} (index 0)
	 *         toward ({@code targetX},{@code targetZ}); the closest-reachable
	 *         approximation if the goal can't be reached within budget, or null
	 *         if not a single neighbouring spot is reachable.
	 */
	public static List<Vec3> plan(Level level, Vec3 start, Vec3 eyeOffset, double targetX, double targetZ,
			double entityRange, boolean allowBridge) {
		int reach = Math.max(1, (int) Math.ceil(entityRange));
		double maxHop = entityRange - REACH_MARGIN;
		int startBx = Mth.floor(start.x);
		int startBy = Mth.floor(start.y);
		int startBz = Mth.floor(start.z);

		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));

		Node startNode = new Node(start);
		startNode.g = 0;
		startNode.f = heuristic(start, targetX, targetZ, entityRange);
		nodes.put(key(startBx, startBy, startBz, startBx, startBy, startBz), startNode);
		open.add(startNode);

		Node best = startNode;
		double bestH = horiz(start, targetX, targetZ);
		int expansions = 0;

		// Memoise column lookups within this run: the same (column, height band)
		// is reached from many nodes, and each scan touches several blocks.
		Map<Long, Cell> columnMemo = new HashMap<>();

		while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
			Node cur = open.poll();
			if (cur.closed) {
				continue;
			}
			cur.closed = true;
			expansions++;

			double h = horiz(cur.pos, targetX, targetZ);
			if (h < bestH) {
				bestH = h;
				best = cur;
			}
			if (h <= ARRIVE_DIST) {
				best = cur;
				break;
			}

			Vec3 eye = cur.pos.add(eyeOffset);
			int cbx = Mth.floor(cur.pos.x);
			int cbz = Mth.floor(cur.pos.z);
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					int nbx = cbx + dx;
					int nbz = cbz + dz;
					if (Math.abs(nbx - startBx) > MAX_REL || Math.abs(nbz - startBz) > MAX_REL) {
						continue;
					}
					int curY = Mth.floor(cur.pos.y);
					long memoKey = key(nbx, curY, nbz, startBx, startBy, startBz);
					Cell cell = columnMemo.get(memoKey);
					if (cell == null) {
						cell = computeCell(level, nbx, nbz, cur.pos.y, allowBridge);
						columnMemo.put(memoKey, cell);
					}
					Vec3 q = cell.pos;
					if (q == null || eye.distanceTo(q) > maxHop) {
						continue;
					}
					double stepCost = 1.0 + (cell.bridged ? BRIDGE_PENALTY : 0.0);
					int qy = Mth.floor(q.y);
					if (Math.abs(qy - startBy) > MAX_REL) {
						continue;
					}
					long k = key(nbx, qy, nbz, startBx, startBy, startBz);
					double ng = cur.g + stepCost;
					Node nb = nodes.get(k);
					if (nb == null) {
						nb = new Node(q);
						nodes.put(k, nb);
					} else if (ng >= nb.g) {
						continue;
					}
					nb.g = ng;
					nb.parent = cur;
					nb.f = ng + heuristic(q, targetX, targetZ, entityRange);
					if (!nb.closed) {
						open.add(nb);
					}
				}
			}
		}

		if (best == startNode || best.parent == null) {
			return null; // could not take a single step
		}
		List<Vec3> path = new ArrayList<>();
		for (Node n = best; n != null; n = n.parent) {
			path.add(n.pos);
		}
		Collections.reverse(path); // start .. best
		return path;
	}

	/** The best cushion spot in a column at a given height band, and whether it needs a bridge block. */
	private record Cell(Vec3 pos, boolean bridged) {
	}

	private static Cell computeCell(Level level, int bx, int bz, double preferredY, boolean allowBridge) {
		Vec3 q = CushionNav.findSurfaceInColumn(level, bx, bz, preferredY, VERT_WINDOW, false);
		if (q != null) {
			return new Cell(q, false);
		}
		if (allowBridge) {
			Vec3 bridge = CushionNav.findBridgeCellInColumn(level, bx, bz, preferredY, VERT_WINDOW, false);
			if (bridge != null) {
				return new Cell(bridge, true);
			}
		}
		return new Cell(null, false);
	}

	private static double heuristic(Vec3 p, double tx, double tz, double range) {
		return horiz(p, tx, tz) / Math.max(1.0, range);
	}

	private static double horiz(Vec3 p, double x, double z) {
		double dx = p.x - x;
		double dz = p.z - z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Packs block coords relative to the start into a single long (search is bounded to MAX_REL). */
	private static long key(int bx, int by, int bz, int sbx, int sby, int sbz) {
		long rx = bx - sbx + MAX_REL;
		long ry = by - sby + MAX_REL;
		long rz = bz - sbz + MAX_REL;
		return rx | (ry << 20) | (rz << 40);
	}

	private static final class Node {
		final Vec3 pos;
		double g = Double.MAX_VALUE;
		double f;
		Node parent;
		boolean closed;

		Node(Vec3 pos) {
			this.pos = pos;
		}
	}
}
