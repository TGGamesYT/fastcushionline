# FastCushionLine

A **client-side** Fabric mod for Minecraft **26.3** cushions. It turns a row of
cushions into a fast-travel rail: sit down and you are automatically hopped from
cushion to cushion until the line ends or you get up.

Everything is done client-side by sending the exact same interact / attack /
use-item packets a player could send by hand, so it works on normal
vanilla-compatible servers without a server-side mod.

## How cushions work (and why this is possible)

In 26.3 a cushion is a `BlockAttachedEntity` you **mount** by right-clicking it,
resting on top of a supporting block (it needs a solid surface directly beneath
it). A cushion's position is always the centre of a block column at the support
surface: `(x+0.5, surfaceY, z+0.5)`. A "cushion line" is therefore any run of
cushions separated by a **constant 3D displacement** — which is what lets travel
work in any direction: straight, diagonal, up/down stairs or slopes.

Mounting the next cushion automatically dismounts the current one (vanilla
`startRiding` behaviour), and **sneak** is the vanilla "get up" gesture, so:

* **Sneak (Shift)** always ends travel — it is how you leave a cushion.
* **Jump (Space)** does nothing while sitting on a cushion in vanilla, so the
  mod reuses it as the travel **toggle**.

## Triggering travel

* **Rapid sit** — sit on three evenly spaced, collinear cushions in quick
  succession. The mod reads that as "I want to travel this line" and engages
  automatically, then keeps hopping to the next cushion the instant the server
  confirms each seat.
* **Space** — while sitting on a cushion, tap the jump key to toggle travel on
  or off. When toggling on, the direction is taken from your recent sits, or
  from the nearest cushion you are looking toward.
* **`/fcl target <x> <z>`** — pathfind toward coordinates (see below).

Travel ends when you sneak, when the line runs out (and auto-place can't
continue it), when a cushion is missing/unreachable, or when you reach the
target.

## Interaction range is respected

Reaching the next cushion uses your **entity interaction range** attribute
(measured to the cushion's hitbox, exactly like vanilla — so the longer diagonal
hops such as 2×2 and 3×1 are used) and placing cushions uses your **block
interaction range** attribute. Anything that modifies those (e.g. attribute
modifiers) automatically changes how far apart cushions may be while still
chaining. The pathfinder takes the **fewest-hop** route, so each hop covers as
much ground as your reach allows, and it strongly **prefers reusing an existing
cushion line** over placing a parallel one beside it.

## The `/fcl` command

| Command | Description |
| --- | --- |
| `/fcl target <x> <z>` | Pathfind toward these X/Z coordinates. If you have `autoplace` on and cushions in your hotbar it will place a line for you; if a nearby cushion line already gets you closer, it uses that instead. |
| `/fcl target clear` | Clear the target and stop. |
| `/fcl breakbehind <true\|false>` | Break each cushion as you leave it (reclaims the item and prevents re-use of the line behind you). |
| `/fcl autoplace <true\|false>` | When a line would end, auto-place cushions to continue it or to reach a target, keeping the line's overall heading. Pulls cushions from your inventory into the hotbar automatically if the hotbar has none. |
| `/fcl showpath <true\|false>` | Show the planned route as a coloured line rendered in the world (on by default). Cyan = the planned route, green = the immediate next hop. |
| `/fcl smoothcamera <true\|false>` | Slide the camera smoothly from cushion to cushion while travelling instead of snapping (on by default). |
| `/fcl stop` | Stop travelling and clear any target. |
| `/fcl status` | Show current state and settings. |

Settings (`breakbehind`, `autoplace`, `showpath`, `smoothcamera`) persist in
`config/fastcushionline.properties`.

### Pathfinding & auto-placement details

* **Toward a target**, routing is a bounded **A\* search** over reachable cushion
  spots, costed by **time**: mounting a cushion that's already there costs half
  as much as placing one and then mounting it. So it rides an existing line
  whenever that's genuinely faster, and places a direct route when a line would
  only wind around.
* **Trees are never used.** Cushions are never placed on — or ridden across —
  leaves or logs: a canopy is a dead-end you can't step back down from within
  cushion reach. A column whose top is a tree keeps looking for the ground
  under/beside it, so routes go **around or under** trees. Because this is decided
  by block type rather than height, **climbing hills and mountains is free** and
  it goes over high ground instead of around it.
* **Dropping below your starting height** carries a surcharge, so it bridges
  across a dip (or rides a bridge already there) rather than diving into a valley
  and climbing back out.
* **No suffocation:** a spot is only used if the seated player's head would be
  clear of suffocating blocks.
* **Reusing lines:** each existing cushion is a route node at its real height
  (slabs, paths and farmland included). The route is re-planned before every
  placement, so a line that comes into view and would be faster is picked up
  immediately instead of building beside it. When it **can't place** (auto-place
  off, or out of cushions) it rides existing lines as far as they lead — even a
  line that first bends away from the target, as long as it ends up closer — and
  only stops when they genuinely run out.
* The route is followed node-by-node; while riding a line it hops as far as reach
  allows. If it hits a dead-end (e.g. a ledge with nowhere to continue) it steps
  back and around, **remembers that spot for the rest of the trip** so it won't
  bounce straight back into it, and bounds how far it will detour. A progress
  watchdog stops it if it genuinely stalls. The route is drawn in the world as a
  line (toggle with `/fcl showpath`).
* **Drowning safeguard:** travelling in survival, if the route leaves you seated
  underwater with your air almost gone and no quick way out, it drops you off the
  cushion so you can surface, and stops — it won't let you drown.
* With **break-behind** on, cushions the mod placed are also cleaned up when they
  fall behind you or are left over from an abandoned path attempt.
* **Continuing an existing line** (auto-place with no target), it reuses the
  line's exact horizontal spacing and heading and only adapts the height to the
  terrain, so the line keeps the same overall angle.
* Every candidate placement is validated exactly like the vanilla cushion item:
  there must be a supporting surface below, the cell must be air/replaceable, and
  no other cushion may already occupy it.
* Cushions are placed and mounted **one at a time** — it commits to sitting on
  the cushion it just placed before planning the next, so it never scatters a
  pile of unused cushions. When you set a target while standing, it mounts a
  reachable cushion first and only lays a starter cushion if none is in reach.
* **Gap bridging:** if there is no supported spot ahead at all (e.g. a gap
  between two cliffs), and you have ordinary blocks in your hotbar, it will
  place a support block and then a cushion on top to cross. This is only a
  fallback — surface-supported spots are always preferred because they are
  faster.

## Building

Standard Fabric Loom project. Requires JDK 25 (matching the Minecraft version).

```
./gradlew build
```

The built jar is a client-side mod and depends on the Fabric API.

## License

Available under the CC0 license.
