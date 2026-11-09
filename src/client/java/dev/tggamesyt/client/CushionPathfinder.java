package dev.tggamesyt.client;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

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

	private static final int MAX_EXPANSIONS = 6000;
	private static final int VERT_WINDOW = 5;
	private static final double ARRIVE_DIST = 1.0;
	// Cost is essentially "number of hops", so the fewest-hop route wins — which
	// is the direct one (over a hill, onto a bridge, or a short self-built bridge)
	// rather than a long detour. Bridging costs a little extra so a real surface
	// of similar length is preferred, but not enough to favour a big detour.
	private static final double BRIDGE_PENALTY = 0.5;
	// Hopping onto a cushion that already exists is nearly free, so an existing
	// line is strongly preferred over placing a fresh parallel one beside it.
	private static final double EXISTING_COST = 0.15;
	private static final double EXISTING_RADIUS = 48.0;
	private static final int MAX_REL = 256;           // search radius from the start (blocks)
	// Per-hop surcharge for every block a placed spot sits *below* the start
	// height. Dropping into a valley and climbing back out therefore costs more
	// than staying level, so a flat route — an existing bridge, or a self-built
	// one — is preferred over descending into a dip. It is asymmetric: rising is
	// NOT penalised, so going up and over a hill or mountain is free and preferred
	// to a long way around; only a genuinely lower target makes it descend.
	private static final double DESCENT_PENALTY = 0.4;

	private CushionPathfinder() {
	}

	/**
	 * @return the sequence of cushion positions from {@code start} (index 0)
	 *         toward ({@code targetX},{@code targetZ}); the closest-reachable
	 *         approximation if the goal can't be reached within budget, or null
	 *         if not a single neighbouring spot is reachable.
	 */
	public static List<Vec3> plan(Level level, Vec3 start, Vec3 eyeOffset, double targetX, double targetZ,
			double entityRange, double blockRange, boolean allowBridge, boolean placementAllowed,
			Set<Long> deadEnds) {
		// Mounting an existing cushion only needs entity-interaction range; placing
		// a new one also needs to reach the support block, so it is limited by
		// whichever of the two ranges is smaller.
		double placeRange = Math.min(entityRange, blockRange);
		int reach = Math.max(1, (int) Math.ceil(entityRange));
		int startBx = Mth.floor(start.x);
		int startBy = Mth.floor(start.y);
		int startBz = Mth.floor(start.z);

		// Snapshot existing cushions by column at their ACTUAL height. They're
		// seeded as nodes directly (below), so a line is routable and reusable no
		// matter what it rests on — including cushions on slabs/paths/farmland that
		// sit at a fractional height the integer surface scan would never land on
		// (the old cause of it laying a fresh line beside one it already placed).
		Map<Long, List<Double>> existingCol = new HashMap<>();
		for (Cushion c : CushionNav.cushionsWithin(level, start, EXISTING_RADIUS)) {
			int cx = Mth.floor(c.position().x);
			int cz = Mth.floor(c.position().z);
			existingCol.computeIfAbsent(col(cx, cz), k -> new ArrayList<>()).add(c.position().y);
		}

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

					// Prefer hopping onto an existing cushion in this column (at its
					// real height, whatever it rests on); only consider placing a new
					// one when placement is allowed (auto-place on and cushions to hand).
					Vec3 q;
					boolean nodeExisting;
					boolean bridged = false;
					Vec3 existNode = nearestExistingInColumn(existingCol, nbx, nbz, cur.pos.y);
					if (existNode != null && CushionNav.mountReachable(eye, existNode, entityRange)) {
						q = existNode;
						nodeExisting = true;
					} else if (placementAllowed) {
						int curY = Mth.floor(cur.pos.y);
						long memoKey = key(nbx, curY, nbz, startBx, startBy, startBz);
						Cell cell = columnMemo.get(memoKey);
						if (cell == null) {
							cell = computeCell(level, nbx, nbz, cur.pos.y, allowBridge);
							columnMemo.put(memoKey, cell);
						}
						if (cell.pos == null) {
							continue;
						}
						q = cell.pos;
						nodeExisting = false;
						bridged = cell.bridged;
						if (!CushionNav.mountReachable(eye, q, placeRange)) {
							continue;
						}
					} else {
						continue; // can't place here and no existing cushion to ride
					}

					int qy = Mth.floor(q.y);
					if (Math.abs(qy - startBy) > MAX_REL) {
						continue;
					}
					// Skip spots we've learned are dead-ends this trip, so we route
					// around them instead of climbing back into the one we just fled.
					if (deadEnds != null && deadEnds.contains(BlockPos.asLong(nbx, qy, nbz))) {
						continue;
					}
					double stepCost;
					if (nodeExisting) {
						// Riding an existing cushion is far cheaper than placing one
						// (no place-then-mount), so it sticks to a line as long as the
						// line keeps getting it closer, and only leaves when continuing
						// on it would cost more than a direct placed route.
						stepCost = EXISTING_COST;
					} else {
						stepCost = 1.0 + (bridged ? BRIDGE_PENALTY : 0.0)
								+ DESCENT_PENALTY * Math.max(0, startBy - qy);
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
			// We couldn't get any closer from here. If we can place, treat this as a
			// dead-end to escape: step to the most promising reachable spot (nearest
			// the target, ties toward cheaper) even though it's farther, so travel
			// goes down/around and re-plans. If we can't place (ride-existing-only),
			// there's nothing to detour to — stop at the closest cushion instead of
			// backtracking pointlessly along the line.
			if (!placementAllowed) {
				return null;
			}
			Node escape = null;
			double escapeScore = Double.MAX_VALUE;
			for (Node n : nodes.values()) {
				if (n == startNode || n.parent == null) {
					continue;
				}
				double score = horiz(n.pos, targetX, targetZ) + 0.5 * n.g;
				if (score < escapeScore) {
					escapeScore = score;
					escape = n;
				}
			}
			if (escape == null) {
				return null; // genuinely could not take a single step
			}
			// We're on a dead-end (couldn't get any closer from here). Remember it so
			// the next plan — made once we've stepped off — won't route straight back
			// onto it, which is what caused the back-and-forth stall.
			if (deadEnds != null) {
				deadEnds.add(BlockPos.asLong(startBx, startBy, startBz));
			}
			best = escape;
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

	/** Packs a block column (x,z) into a single long for the existing-cushion map. */
	private static long col(int bx, int bz) {
		return (((long) bx & 0xFFFFFFFFL) << 32) | ((long) bz & 0xFFFFFFFFL);
	}

	/**
	 * The existing cushion in column ({@code bx},{@code bz}) whose height is nearest
	 * {@code preferredY} and within the vertical window, as a hop-to position, or
	 * null if none. Lets A* ride a line at its true height whatever it rests on.
	 */
	private static Vec3 nearestExistingInColumn(Map<Long, List<Double>> cols, int bx, int bz, double preferredY) {
		List<Double> ys = cols.get(col(bx, bz));
		if (ys == null) {
			return null;
		}
		double bestDist = VERT_WINDOW + 0.5;
		Double bestY = null;
		for (double y : ys) {
			double d = Math.abs(y - preferredY);
			if (d < bestDist) {
				bestDist = d;
				bestY = y;
			}
		}
		return bestY == null ? null : new Vec3(bx + 0.5, bestY, bz + 0.5);
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
