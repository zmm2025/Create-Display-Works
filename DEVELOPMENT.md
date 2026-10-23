# Development

## Requirements

- Git, and any JDK 17+ to run Gradle. Gradle downloads everything else on first use: Java 21 for compiling, and
  [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime) 21 for the dev client.
- No Minecraft account is needed. The dev client plays offline as the player `Dev`.

## Live dev client

| Command | What it does |
| --- | --- |
| `./gradlew runDevClient` | Launches Minecraft straight into the shared test world with hot reload and the control API. |
| `./gradlew devReload` | Compiles, then pushes code, assets and data into the running dev client. |
| `./gradlew devReload -Pfull` | The same, but reloads all assets and data even if nothing changed. |

In T3 Code, add these as project scripts (**Project settings → Scripts**) so they're one click away. T3 stores
scripts in its own settings rather than reading them from the repo, so each developer adds them once. The scripts
themselves only call the Gradle tasks above, so their behaviour lives in the repo:

| Name | Command | Icon |
| --- | --- | --- |
| Run Minecraft | `./gradlew runDevClient` | play |
| Reload Minecraft | `./gradlew devReload` | build |

Agents run `devReload` at the end of each turn (see [AGENTS.md](AGENTS.md)), so the **Reload Minecraft** button
is only a manual fallback. It replaces both F3+T and `/reload`.

The client runs inside a Gradle daemon, so `./gradlew --stop` closes every running dev client without saving.

### The shared test world

Every worktree of the repository shares one game directory: the main checkout's `run/` folder, which git ignores.
Worlds, options and screenshots therefore survive across worktrees and branches. To use a different folder, pass
`-PdevRunDir=<path>` or set `devRunDir` in `~/.gradle/gradle.properties`.

On launch the client opens **Dev Testing World**. If it doesn't exist yet, the client creates it with these settings:

- Creative, Easy, cheats on
- Superflat `minecraft:bedrock,62*minecraft:dirt,minecraft:grass_block;minecraft:the_void`, with no structures
- `keepInventory` and `disableRaids` on. `mobGriefing`, `doDaylightCycle`, `doWeatherCycle`, and every rule in the
  *Spawning* and *Drops* categories off.
- On the first join, the time is set to 6000 (noon) and every advancement is granted, with no toasts or chat spam.

To start over, delete `run/saves/Dev Testing World` while the game is closed. Leaving the world returns to the title
screen as usual; the client only auto-joins once per launch.

The dev client also turns off *Pause on Lost Focus*, the accessibility onboarding screen and the movement tutorial,
so the game keeps running and stays controllable in the background.

### Extra mods from a Modrinth profile

The dev client loads this mod, Create, Ponder and Flywheel from Gradle. To add testing helpers such as Jade or EMI,
add them to the [Modrinth App](https://modrinth.com/app) profile **Create: Display Works Dev** (NeoForge 1.21.1,
stored in the `profiles/Create Display Works Dev` folder). Create one if it doesn't exist yet. Each **Run Minecraft**
copies the profile's enabled mods into `run/mods`. Mods you remove or disable in the profile are removed from
`run/mods` on the next launch, and jars you put in `run/mods` by hand are left alone.

- Mods Gradle already loads are skipped, because a second copy stops the game with a duplicate-mod error. Modrinth
  usually adds Create as a dependency of Create addons, so addons must work with the Create version in
  [gradle.properties](gradle.properties).
- To use another profile, or any folder of mod jars, set `devModsProfile=<profile name or absolute path>` in
  `~/.gradle/gradle.properties` or pass `-PdevModsProfile=...`.
- Launching the profile itself from the Modrinth App doesn't include this mod. Use **Run Minecraft** for that.

### One client at a time

Only one dev client runs at a time, because every worktree shares the same game directory and test world. Pressing
**Run Minecraft** while a client is already running fails without launching, and names the worktree that client
runs. To test another worktree, close the running client first, then press **Run Minecraft** in that worktree.

The window title starts with the branch and worktree the client runs, for example
`t3/minecraft-live-reload-testing (t3-dab23ea6) | Minecraft 1.21.1 - Singleplayer`. The same label appears in chat
when you join. `devReload` only accepts code from that worktree; from any other worktree it fails with an error
instead of reloading.

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

The dev client serves a localhost-only HTTP API so agents can drive the `Dev` player without computer use. It
listens on port `25595` (change it with `-PdevControlPort=<port>`). Every endpoint returns JSON with an `ok` field.
Requests run one at a time.

| Endpoint | Body or query | Effect |
| --- | --- | --- |
| `GET /status` | | Worktree, world, open screen, window size and GUI scale, player position and rotation, held item and crosshair target. |
| `POST /command` | body: a command, with or without `/` | Runs the command as the player at permission level 4. Returns the chat output and whether it succeeded. |
| `POST /screenshot` | `?hud=false` hides the HUD; `?frames=<n>&intervalMs=<ms>` (up to 120 frames, default interval 100 ms) captures a sequence | Saves PNGs under `run/screenshots/dev-tools/` and returns their paths. A sequence also returns `sheet`, one image with every frame tiled left to right and top to bottom, and `timesMs`, when each frame was taken. |
| `POST /use` | optional `?x=&y=&z=&face=` | Right-clicks the crosshair target, or that block face. The player must be within reach. |
| `POST /attack` | optional `?x=&y=&z=&face=` | Left-clicks: breaks the block in creative mode, or attacks the entity. |
| `POST /key` | `?key=e` (GLFW names: `escape`, `enter`, `e`, `left.shift`, `f3`, ...) | Presses the key in the open screen, or as a game keybind when no screen is open. |
| `POST /type` | body: text | Types into the open screen. |
| `POST /click` | `?x=&y=&button=0` | Clicks the open screen at screenshot pixel coordinates. |
| `POST /reload` | `?worktree=&full=` | What `devReload` calls after compiling. |

Use `/command` to move and aim the player, for example
`tp @s 10 0 5 90 30` (x y z yaw pitch) or `tp @s ~ ~ ~ facing 0 1 0`. Then check the result with `/status` or
`/screenshot`.

```sh
curl -s localhost:25595/status
curl -s -X POST localhost:25595/command --data "setblock ~2 ~ ~ create_display_works:flip_dot_display"
curl -s -X POST "localhost:25595/screenshot?hud=false"
```

In Windows PowerShell, use `curl.exe`, because plain `curl` is an alias for `Invoke-WebRequest`.

Screenshots capture the frames the game renders. That works while the window is in the background, but Windows
stops Minecraft rendering while its window is minimized, so `/screenshot` times out then. Commands and input still
work.

## Release testing with standard Java

JetBrains Runtime and every dev tool are limited to the `runDevClient` run and the `dev`/`devAgent` source sets. None
of them ship in the mod jar. `./gradlew build` still targets Java 21 and produces `build/libs/*.jar`, which runs on
the regular Minecraft Java runtime in any launcher, such as Modrinth, with your own account.

## Other runs

- `./gradlew runClient` / `runServer` are the plain NeoForge runs, using the checkout's own `run/` and `build/server-run/`.
- `./gradlew runClient -PgameplayCheck` and `runServer -PserverCheck` run the automated flip-dot checks in
  [src/dev](src/dev).
