# Development

## Requirements

- Git, and any JDK 17+ to run Gradle. Gradle downloads everything else on first use: Java 21 for compiling, and
  [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime) 21 for the dev client.
- No Minecraft account is needed. The dev client plays offline as the player `Dev`.

## Live dev client

| Command | What it does |
| --- | --- |
| `./gradlew runDevClient` | Launches this checkout's Minecraft straight into its test world with hot reload and the control API. If it's already running, shows its window and follows its log instead. |
| `./gradlew devReload` | Compiles, then pushes code, assets and data into this checkout's running dev client. |
| `./gradlew devReload -Pfull` | The same, but reloads all assets and data even if nothing changed. |
| `./gradlew devStart -PthreadId=<id>` | For agents: starts this checkout's dev client with its window hidden, in a `minecraft` terminal in that T3 Code thread, and waits until it has joined its world. |

In T3 Code, add these as project scripts (**Project settings â†’ Scripts**) so they're one click away. T3 stores
scripts in its own settings rather than reading them from the repo, so each developer adds them once. The scripts
themselves only call the Gradle tasks above, so their behaviour lives in the repo:

| Name | Command | Icon |
| --- | --- | --- |
| Launch Minecraft | `./gradlew runDevClient` | play |
| Reload Minecraft | `./gradlew devReload` | build |

Agents run `devReload` at the end of each turn (see [AGENTS.md](AGENTS.md)), so the **Reload Minecraft** button
is only a manual fallback. It replaces both F3+T and `/reload`.

The client runs inside a Gradle daemon, so `./gradlew --stop` closes every running dev client without saving.

### The main world and worktree copies

The main checkout's game uses the main checkout's `run/` folder, which git ignores. That's your main world: to build
in it, press **Launch Minecraft** in a thread that runs on the main checkout rather than in a worktree. To use a
different folder, pass `-PdevRunDir=<path>` or set `devRunDir` in `~/.gradle/gradle.properties`.

Each worktree runs its own game, in `run/worktrees/<worktree>`. The first time it launches, it copies the main
world, options and settings, so it starts from your current builds. After that its world is separate: changes in a
worktree's game stay in that worktree, and later changes to the main world don't reach it. To get a fresh copy,
delete `run/worktrees/<worktree>/saves` while that game is closed. Several worktrees' games can run at once.

When a worktree is removed, for example when T3 Code cleans up an archived thread, its game notices within a few
seconds and quits. A small helper process then deletes the game's folder and anything the removal left behind
because the game had it open (on Windows, `git worktree remove` stops partway in that case), and runs
`git worktree prune`. If the game wasn't running, any running dev client deletes the folder within a minute, and so
does the next launch or reload in any checkout.

On launch the client opens **Dev Testing World**. If it doesn't exist yet, the client creates it with these settings:

- Creative, Easy, cheats on
- Superflat `minecraft:bedrock,62*minecraft:dirt,minecraft:grass_block;minecraft:the_void`, with no structures
- `keepInventory` and `disableRaids` on. `mobGriefing`, `doDaylightCycle`, `doWeatherCycle`, and every rule in the
  *Spawning* and *Drops* categories off.
- On the first join, the time is set to 6000 (noon) and every advancement is granted, with no toasts or chat spam.

To start over, delete `saves/Dev Testing World` in the game's folder while the game is closed. Leaving the world returns to the title
screen as usual; the client only auto-joins once per launch.

The dev client also turns off *Pause on Lost Focus*, the accessibility onboarding screen and the movement tutorial,
so the game keeps running and stays controllable in the background.

### Extra mods from a Modrinth profile

The dev client loads this mod, Create, Ponder and Flywheel from Gradle. To add testing helpers such as Jade or EMI,
add them to the [Modrinth App](https://modrinth.com/app) profile **Create: Display Works Dev** (NeoForge 1.21.1,
stored in the `profiles/Create Display Works Dev` folder). Create one if it doesn't exist yet. Each **Launch Minecraft**
copies the profile's enabled mods into the game folder's `mods`. Mods you remove or disable in the profile are
removed from it on the next launch, and jars you put there by hand are left alone.

- Mods Gradle already loads are skipped, because a second copy stops the game with a duplicate-mod error. Modrinth
  usually adds Create as a dependency of Create addons, so addons must work with the Create version in
  [gradle.properties](gradle.properties).
- To use another profile, or any folder of mod jars, set `devModsProfile=<profile name or absolute path>` in
  `~/.gradle/gradle.properties` or pass `-PdevModsProfile=...`.
- Launching the profile itself from the Modrinth App doesn't include this mod. Use **Launch Minecraft** for that.

### Hidden games and switching in

Each checkout runs at most one game. An agent starts its worktree's game with `devStart`, which keeps the window
hidden: it doesn't appear, take focus or show in the taskbar, and it's muted and limited to 30 FPS. Rendering,
screenshots and the control API still work. The game runs in a terminal named `minecraft` in the agent's T3 Code
thread; press Ctrl+J in that thread to see its console, or close that terminal to stop the game.

- **Launch Minecraft** in a checkout whose game is already running shows its window, with its sound and frame rate back
  to your settings, and follows its log instead of launching a second game. Stopping that console leaves the game
  running.
- Minimizing a worktree's game hides it again, so the agent can keep using it. A normal minimize would stop it
  drawing frames.
- Closing the window saves and quits as usual.
- A hidden worktree game quits after 30 minutes without control API requests.

The window title starts with the branch and worktree the client runs, for example
`t3/minecraft-live-reload-testing (t3-dab23ea6) | Minecraft 1.21.1 - Singleplayer`. The same label appears in chat
when you join.

### What hot reload covers

`devReload` compares compiled output against what the game last loaded:

- **Code:** changed classes are redefined in place through a `-javaagent` ([src/devAgent](src/devAgent)).
  JetBrains Runtime's enhanced class redefinition lets this include new methods, fields and classes, not just
  method-body edits.
- **Assets** (`assets/`): textures, models, sounds and lang files reload client-side (F3+T).
- **Data** (`data/`): recipes, tags, loot tables and advancements reload server-side (`/reload`).

It also reports edits that need a restart and exits non-zero (`DEV RELOAD: RESTART REQUIRED`) when it finds:

- changed static initializers
- added or removed `@SubscribeEvent` handlers
- changed `@Mod`, `@EventBusSubscriber` or mixin classes
- changed `META-INF` files
- lambdas removed from a class the game has already loaded, inserted before its existing lambdas, or added alongside
  edits to its existing lambdas. The compiler numbers lambdas in source order, so these renumber them, and swapping
  would leave running code calling the wrong or a missing lambda. In this case it swaps nothing. Editing code inside
  existing lambdas, or adding lambdas after all of them, still hot-swaps.

Some restarts it can't detect, because the code still swaps but never runs again:

- registering new blocks, items, block entities, menus, entities, sounds, data components or network payloads
- changes to setup or registration event handlers
- config specs
- build or dependency changes

Hot-swapped code applies to new calls. Objects that already exist, such as placed block entities and open screens,
keep any state they computed earlier, so you may need to re-place the block or reopen the screen.

## Agent control API

The dev client serves a localhost-only HTTP API so agents can drive the `Dev` player without computer use. The main
checkout's game listens on port `25595` (change it with `-PdevControlPort=<port>`); a worktree's game picks a free
port. Each game writes its port to `build/dev-client.json` in its checkout, and `devStart` prints it. Every endpoint
returns JSON with an `ok` field. Requests run one at a time.

| Endpoint | Body or query | Effect |
| --- | --- | --- |
| `GET /status` | | Worktree, world, open screen, whether the window is hidden, window size and GUI scale, player position and rotation, held item and crosshair target. |
| `POST /window` | `?show=true` or `?show=false` | Shows the window and brings it to the front, or hides it. |
| `POST /command` | body: a command, with or without `/`, or a JSON array of steps | Runs the command as the player at permission level 4. Returns the chat output and whether it succeeded. An array runs its steps in order and returns `results`, one per step. A step is a command, `{"waitTicks": 20}`, or `{"waitUntil": "<command>", "timeoutTicks": 200}`, which reruns the command each tick until it succeeds and stops the batch if it never does. Consecutive commands run within one server tick. `tp` and `teleport` turn creative flight on first, so the player doesn't fall. |
| `POST /screenshot` | `?hud=false` hides the HUD; `?frames=<n>&intervalMs=<ms>` (up to 600 frames, default interval 100 ms; `0` takes consecutive frames) captures a sequence; `&video=mp4` or `&video=gif` also encodes it | Saves PNGs under `screenshots/dev-tools/` in the game's folder and returns their paths. A sequence also returns `sheet`, one image with up to 36 evenly spread frames tiled left to right and top to bottom (`sheetFrames` lists which, when some are left out), `timesMs`, when each frame was taken, and `gameTimes`, the game time in ticks (with the partial tick) each frame drew. Frames are saved in the background, so a sequence doesn't slow the game down. `video` is made with ffmpeg and plays at the speed the frames were captured; an MP4 runs at 60 FPS and a GIF at 30 FPS, at most 960 pixels wide. With `hud=false`, an open chat is closed first; any other open screen still shows and is named in `screen`. |
| `POST /camera` | `?x=&y=&z=`; optional `&face=north` (default: the side the player is on), `&distance=3` or `&fit=<W>` / `&fit=<W>x<H>`, `&screenshot=true`, `&hud=false` | Flies the player to look straight at that point from `distance` blocks off the given face, or far enough back that `W` by `H` blocks fill the view. Block coordinates aim at the block's centre; decimals aim at that exact point. Waits until the client has moved, and can take a screenshot in the same call. |
| `GET /perf` | optional `?seconds=5` (0.5-60), `&namespace=<mod id>` (default: this mod) | Measures for that long and returns frame times (`avg`, `p50`, `p95`, `p99`, `max` in ms, and FPS), the CPU time each of the mod's block entity renderers takes per frame and how many it draws, server tick times over the last 100 ticks, and heap use. Renderer times cover building the geometry, not GPU time. |
| `GET /sounds` | optional `?sinceMs=3000&filter=<text in the sound id>` | Sounds holding a channel now (`playing`), with `heardGain`, the volume estimated to reach the player after the category slider and distance, and every sound started in the last `sinceMs` (`started`). Master volume is reported separately: `masterVolume` is the setting, `outputGain` what the sound engine plays at (0 while the window is hidden). |
| `GET /find` | `?block=<id or #tag>` or `?namespace=<mod id>`; optional `&radius=16` (up to 64), `&x=&y=&z=` (default: the player), `&limit=100` | Loaded blocks that match within a cube, nearest first, with their block states. |
| `GET /blockentity` | `?x=&y=&z=`; optional `&side=client\|server\|both`, `&fields=a,b.c`, `&all=true`, `&depth=2`, `&frames=<n>&intervalMs=<ms>` | The block entity's fields there, read by reflection on each side: this mod's own fields, or with `all=true` also those inherited from Create and vanilla (this mod's own objects are expanded `depth` levels). `fields` picks fields or dotted paths. With `frames`, the client side is sampled on that many rendered frames (up to 600; `intervalMs=0` takes every frame), each with its game time and partial tick, to follow animations. |
| `POST /use` | optional `?x=&y=&z=&face=` | Right-clicks the crosshair target, or that block face. The player must be within reach. |
| `POST /attack` | optional `?x=&y=&z=&face=` | Left-clicks: breaks the block in creative mode, or attacks the entity. |
| `POST /key` | `?key=e` (GLFW names: `escape`, `enter`, `e`, `left.shift`, `f3`, ...) | Presses the key in the open screen, or as a game keybind when no screen is open. |
| `POST /type` | body: text | Types into the open screen. |
| `POST /click` | `?x=&y=&button=0` | Clicks the open screen at screenshot pixel coordinates. |
| `POST /reload` | `?worktree=&full=` | What `devReload` calls after compiling. |

Use `/command` to move and aim the player, for example
`tp @s 10 0 5 90 30` (x y z yaw pitch) or `tp @s ~ ~ ~ facing 0 1 0`. Then check the result with `/status` or
`/screenshot`.

These examples use the main checkout's port; use your game's port from `build/dev-client.json`.

```sh
curl -s localhost:25595/status
curl -s -X POST localhost:25595/command --data "setblock ~2 ~ ~ create_display_works:flip_dot_display"
curl -s -X POST "localhost:25595/screenshot?hud=false"
```

In Windows PowerShell, use `curl.exe`, because plain `curl` is an alias for `Invoke-WebRequest`.

### Testing tips

- Check sounds with `/sounds` instead of recording audio. A sound that should be audible but is missing from
  `playing`, or has `heardGain` near 0, is the bug.
- Check animation timing with `/blockentity?...&frames=<n>&intervalMs=0` instead of adding temporary logging. Removing
  logging that used a lambda can force a restart (see [What hot reload covers](#what-hot-reload-covers)).
- To see motion faster than a frame sequence can catch, slow the game down with `/command` `tick rate 5`, and set it
  back with `tick rate 20` when you're done.
- Use `/camera` to frame a block or a whole display (`fit=<W>x<H>`) instead of guessing teleport coordinates.
- Use `/perf` before and after a rendering change, with the same view and display size, to compare frame times.
- Use `/find` to locate a fixture's parts (motors, displays, gauges) instead of scanning coordinates one command at a
  time.
- Create's kinetic blocks don't apply edits made with `data merge` to a running network. To change a creative motor's
  speed, replace it in one batch:
  `["setblock X Y Z air", "setblock X Y Z create:creative_motor[facing=F]{ScrollValue:256}"]`.

Screenshots capture the frames the game renders. That works while the window is hidden or in the background, but
Minecraft stops rendering while its window is minimized, so `/screenshot` times out then. Commands and input still
work. A worktree's game hides instead of minimizing for this reason.

## Release testing with standard Java

JetBrains Runtime and every dev tool are limited to the `runDevClient` run and the `dev`/`devAgent` source sets. None
of them ship in the mod jar. `./gradlew build` still targets Java 21 and produces `build/libs/*.jar`, which runs on
the regular Minecraft Java runtime in any launcher, such as Modrinth, with your own account.

## Other runs

- `./gradlew runClient` / `runServer` are the plain NeoForge runs, using the checkout's own `run/` and `build/server-run/`.
- `./gradlew runClient -PgameplayCheck` and `runServer -PserverCheck` run the automated flip-dot checks in
  [src/dev](src/dev).
