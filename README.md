# FastCushionLine

A **client-side** Fabric mod for Minecraft **26.3-snapshot-3** (the snapshot that
added cushions). It turns a row of cushions into a fast-travel rail: sit down and
you are automatically hopped from cushion to cushion until the line ends or you
get up.

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

Reaching the next cushion uses your **entity interaction range** attribute and
placing cushions uses your **block interaction range** attribute, so anything
that modifies those (e.g. attribute modifiers) automatically changes how far
apart cushions may be while still chaining. When auto-placing toward a target
the mod spaces cushions near the edge of your reach, so each hop covers as much
ground as possible with the fewest cushions.

## The `/fcl` command

| Command | Description |
| --- | --- |
| `/fcl target <x> <z>` | Pathfind toward these X/Z coordinates. If you have `autoplace` on and cushions in your hotbar it will place a line for you; if a nearby cushion line already gets you closer, it uses that instead. |
| `/fcl target clear` | Clear the target and stop. |
| `/fcl breakbehind <true\|false>` | Break each cushion as you leave it (reclaims the item and prevents re-use of the line behind you). |
| `/fcl autoplace <true\|false>` | When a line would end, auto-place cushions from your hotbar to continue it, keeping the line's overall heading. |
| `/fcl showpath <true\|false>` | Show the planned route as a coloured line rendered in the world (on by default). Cyan = the planned route, green = the immediate next hop. |
| `/fcl stop` | Stop travelling and clear any target. |
| `/fcl status` | Show current state and settings. |

Settings (`breakbehind`, `autoplace`, `showpath`) persist in
`config/fastcushionline.properties`.

### Pathfinding & auto-placement details

* **Toward a target**, routing is done by a bounded **A\* search** over reachable
  cushion spots (cached and reused between hops, so it stays off the hot path).
  It routes *around* obstacles — sideways, backtracking, up or down — instead of
  giving up, and vertical movement is penalised so it prefers a **level route
  (e.g. an existing bridge) over walking down a cliff into a dead end**. It only
  stops when there is genuinely no reachable spot at all; otherwise it heads for
  the closest spot it can find. The planned route is drawn in the world as a
  line (toggle with `/fcl showpath`).
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
  pile of unused cushions.
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
