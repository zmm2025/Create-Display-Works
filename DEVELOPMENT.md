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
| `./gradlew devStop` | Closes this checkout's dev client the way its close button does, saving the world, and its dedicated server. If the game doesn't close within 2 minutes, it ends the process without saving. |
| `./gradlew devRestart -PthreadId=<id>` | For agents: `devStop`, then `devStart`, for changes that need a restart or a different set of mods. |
| `./gradlew devServerStart -PthreadId=<id>` | Starts a test dedicated server for this checkout. See [A second player or a dedicated server](#a-second-player-or-a-dedicated-server). |
| `./gradlew devServerStop` | Closes that server, saving its world. |

In T3 Code, add these as project scripts (**Project settings → Scripts**) so they're one click away. T3 stores
scripts in its own settings rather than reading them from the repo, so each developer adds them once. The scripts
themselves only call the Gradle tasks above, so their behavior lives in the repo:

| Name | Command | Icon | Option |
| --- | --- | --- | --- |
| Launch Minecraft | `./gradlew runDevClient; if ($LASTEXITCODE -eq 0) { exit }` | play | |
| Reload Minecraft | `./gradlew devReload` | build | |
| Stop Minecraft | `./gradlew devStop pruneBranches --continue` | debug | **Run in the thread's worktree when the thread settles** |

T3 Code's terminals run PowerShell on Windows. On macOS and Linux, use `./gradlew runDevClient && exit` for
**Launch Minecraft**.

Agents run `devReload` at the end of each turn that changes `src/` (see [AGENTS.md](AGENTS.md)), so the
**Reload Minecraft** button is only a manual fallback. It replaces both F3+T and `/reload`.

### Closing games and their terminals

A terminal that's still open, even at an idle prompt, keeps T3 Code from cleaning up its worktree. So the game's
terminal closes itself once the game exits normally, whether you close the window, `devStop` closes it, or it quits
on its own. **Launch Minecraft** does this with the `exit` in its command, and `devStart` adds the same to the
command it types into the `minecraft` terminal. If the build fails or the game crashes, the terminal stays open so
you can read the error.

When a thread in its own worktree settles, manually or automatically, T3 Code runs **Stop Minecraft** there. That
closes the worktree's game, whether an agent started it or you launched it, and with it the game's terminal. T3 Code
skips settle scripts for threads on the main checkout, so this never closes your main world's game. T3 Code runs only
the first script marked to run on settle, so **Stop Minecraft** also runs `pruneBranches` (see
[Merged branches](#merged-branches)).

The client runs inside a Gradle daemon, so `./gradlew --stop` closes every running dev client without saving.

### Merged branches

GitHub deletes a PR's branch when it merges, and T3 Code removes a settled thread's worktree, but nothing deletes the
local branch. `./gradlew pruneBranches` does, and **Stop Minecraft** runs it whenever a thread settles. A thread's own
branch is still checked out at that moment, so it goes at the next settle.

It deletes a branch only when nothing on it can be lost: no worktree has it checked out, and its last commit is
already in `main` or is the head of a merged PR (found with the `gh` CLI, since squash merges leave the branch's
commits outside `main`). It also fast-forwards the local `main` when no checkout has it open. Everything else stays,
and the task lists merged branches that are still checked out. `-PdryRun` lists what it would delete without
deleting anything.

T3 Code only removes a worktree that's still on the branch it created, so agents never rename or switch branches (see
[AGENTS.md](AGENTS.md#branches)). A worktree with uncommitted changes or an open terminal stays too.

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
  `~/.gradle/gradle.properties` or pass `-PdevModsProfile=...`. `devStart` passes it on to the game it starts.
- Launching the profile itself from the Modrinth App doesn't include this mod. Use **Launch Minecraft** for that.

### Named sets of test mods

For compatibility tests, pass `-PdevModSet=<set>` to `devStart`, `devRestart`, `runDevClient` or `devServerStart`, in
place of the Modrinth profile. Several sets work, separated by commas: `-PdevModSet=interaction,claims`.

| Set | Mods |
| --- | --- |
| `rendering` | Sodium, Iris, Entity Culling |
| `claims` | Open Parties and Claims |
| `addons` | Create Crafts & Additions, Enchantment Industry, Connected, Big Cannons, New Age, Copycats+ |
| `ftb` | FTB Chunks, FTB Teams, FTB Library, Architectury |
| `interaction` | Carry On, WorldEdit, Jade, EMI |

Sets live in [gradle/dev-mod-sets.json](gradle/dev-mod-sets.json) as Modrinth project slugs, `slug@version` to pin
a version, or `{"maven": <repository>, "id": "<group:artifact:version>"}` for a mod that isn't on Modrinth. Modrinth's
required dependencies come along; list a Maven mod's yourself. The first use downloads the jars into
`~/.gradle/create-display-works/dev-mods`, which every worktree shares, so later uses need no network. Add
`-PdevModSetRefresh` to look for newer versions. A later launch without `-PdevModSet` goes back to the profile.

### Hidden games and switching in

Each checkout runs at most one game. An agent starts its worktree's game with `devStart`, which keeps the window
hidden: it doesn't appear, take focus or show in the taskbar, and it's limited to 30 FPS. It never opens your sound
device: its sound is mixed in memory on an OpenAL loopback device, so nothing it plays can reach your speakers. Rendering,
screenshots and the control API still work. The game runs in a terminal named `minecraft` in the agent's T3 Code
thread; press Ctrl+J in that thread to see its console. Close that terminal, or run `devStop`, to stop the game.

- **Launch Minecraft** in a checkout whose game is already running shows its window, moves its sound to your real device,
  puts its frame rate back to your settings, and follows its log instead of launching a second game. Stopping that console leaves the game
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

Its result line ends with `[window shown]` or `[window hidden: ...]`, so agents can tell a game you're looking at
from a hidden one they started. It also reports edits that need a restart and exits non-zero
(`DEV RELOAD: RESTART REQUIRED`) when it finds:

- changed static initializers
- new instance fields with a starting value (set where they're declared or in a constructor), in a class that has
  live objects. Swapping adds the field to existing objects without running the constructor, so for them it stays
  `null`, `0` or `false`, which can crash them every frame. The new class is still swapped in, so recreating the
  objects (re-placing the block, reopening the screen) also fixes them, but the restart notice stays until you
  restart. New fields without a starting value, or set to `null` or `0`, don't count. Neither do classes with no live
  objects. Fields only set in a helper method that a constructor calls aren't detected. To check for live objects,
  `devReload` counts them with the JVM's class histogram, which runs a full garbage collection and so can freeze the
  game briefly.
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
| `POST /window` | `?show=true` or `?show=false`; `?fps=<n>`, `unlimited` or `default`; `?width=&height=` | Shows the window and brings it to the front, or hides it. `fps` replaces a hidden game's 30 FPS cap until `default` undoes it. `width` and `height` resize the window, which changes what screenshots and screens lay out. |
| `POST /quit` | | Closes the game the way its close button does, saving the world. It answers before the game has exited; `devStop` waits for that. |
| `POST /command` | body: a command, with or without `/`, or a JSON array of steps | Runs the command as the player at permission level 4. Client commands, such as `flywheel backend`, run on the client. Returns the chat output and whether it succeeded. On a remote server, the command goes through chat with the player's own permissions, and failure means a red reply. An array runs its steps in order and returns `results`, one per step. A step is a command, `{"waitTicks": 20}`, `{"waitUntil": "<command>", "timeoutTicks": 200}`, which reruns the command each tick until it succeeds and stops the batch if it never does, or `{"call": "/key?key=w&ticks=20", "body": "..."}`, which runs any endpoint here and stops the batch if it fails. Consecutive commands run within one server tick. `tp` and `teleport` turn creative flight on first, so the player doesn't fall. |
| `POST /screenshot` | `?hud=false` hides the HUD; `?frames=<n>&intervalMs=<ms>` (up to 600 frames, default interval 100 ms; `0` takes consecutive frames) captures a sequence; `&video=mp4` or `&video=gif` also encodes it; `&audio=true` records the game's sound into an MP4; `&keep=<name>` keeps the result for chat | Saves PNGs under `screenshots/dev-tools/` in the game's folder and returns their paths. A sequence also returns `sheet`, one image with up to 36 evenly spread frames tiled left to right and top to bottom (`sheetFrames` lists which, when some are left out), `timesMs`, when each frame was taken, and `gameTimes`, the game time in ticks (with the partial tick) each frame drew. Frames are saved in the background, so a sequence doesn't slow the game down. `video` is made with ffmpeg and plays at the speed the frames were captured; an MP4 runs at 60 FPS and a GIF at 30 FPS, at most 960 pixels wide. With `hud=false`, an open chat is closed first; any other open screen still shows and is named in `screen`. `keep` copies the image, or the video and contact sheet, to the evidence folder, and returns `kept` and ready-to-paste `markdown`. See [Images in chat](#images-in-chat). |
| `POST /camera` | `?x=&y=&z=`; optional `&face=north` (default: the side the player is on), `&distance=3` or `&fit=<W>` / `&fit=<W>x<H>`, `&screenshot=true`, `&hud=false`, `&keep=<name>` | Flies the player to look straight at that point from `distance` blocks off the given face, or far enough back that `W` by `H` blocks fill the view. Block coordinates aim at the block's center; decimals aim at that exact point. Waits until the client has moved, and can take a screenshot in the same call. |
| `POST /look` | `?yaw=&pitch=`, `?dx=&dy=` (degrees from the current view), `?x=&y=&z=` or `?entity=<id>` | Turns the player without moving them, and returns the new crosshair target. |
| `GET /perf` | optional `?seconds=5` (0.5-60), `&namespace=<mod id>` (default: this mod), `&fps=<n>` or `&fps=unlimited` | Replaces a hidden game's frame rate cap for the measurement, if asked. Measures for that long and returns frame times (`avg`, `p50`, `p95`, `p99`, `max` in ms, and FPS), the CPU time each of the mod's block entity renderers takes per frame and how many it draws, server tick times over the last 100 ticks, and heap use. Renderer times cover building the geometry, not GPU time. |
| `GET /sounds` | optional `?sinceMs=3000&filter=<text in the sound id>` | Sounds holding a channel now (`playing`), with `heardGain`, the volume estimated to reach the player after the category slider and distance, and every sound started in the last `sinceMs` (`started`). Master volume is reported separately: `masterVolume` is the setting, `outputGain` what the sound engine plays at. `silentDevice` says the game's sound is going into memory. |
| `POST /audio` | `?seconds=5` (up to 300), or `?action=start` and later `?action=stop`; optional `&keep=<name>` | Records what the game plays to a WAV in `screenshots/dev-tools/`, and returns its `seconds`, `peakDb`, `rmsDb` and whether it's `silent`. `keep` saves an MP3 for chat. Only a hidden game can record, since only its sound stays in memory. |
| `GET /find` | `?block=<id or #tag>` or `?namespace=<mod id>`; optional `&radius=16` (up to 64), `&x=&y=&z=` (default: the player), `&limit=100` | Loaded blocks that match within a cube, nearest first, with their block states. |
| `GET /blockentity` | `?x=&y=&z=`; optional `&side=client\|server\|both`, `&fields=a,b.c`, `&all=true`, `&depth=2`, `&frames=<n>&intervalMs=<ms>` | The block entity's fields there, read by reflection on each side: this mod's own fields, or with `all=true` also those inherited from Create and vanilla (this mod's own objects are expanded `depth` levels). `fields` picks fields or dotted paths. With `frames`, the client side is sampled on that many rendered frames (up to 600; `intervalMs=0` takes every frame), each with its game time and partial tick, to follow animations. |
| `GET /entities` | optional `?radius=32&type=<text in the entity id>&limit=50` | Entities near the player, nearest first, with ids for `/look` and `/use`, what they ride, and a train's UUID for carriages. |
| `GET /screen` | | The open screen's title, its widgets with their text and whether they're active, and the items in its slots. Positions are the pixels `/click` takes, so finding a button takes no screenshot. |
| `POST /close` | | Closes the open screen, if any. Escape with no screen open would open the pause menu, which pauses the game. |
| `GET /chat` | optional `?lines=20` | Recent chat, and the action bar if it's showing, where Create and others report what a click did. |
| `GET` or `POST /options` | no parameters, or `?filter=<text>`: lists settings. `?graphicsMode=2&renderDistance=8`: sets them. `?restore=true`: puts them back. | Reads and changes the settings in `options.txt`, by the names that file uses, so this covers key bindings and sound levels too. The first change keeps a copy for `restore`. Returns each change's `before` and `after`. |
| `POST /use` | optional `?x=&y=&z=&face=` or `?entity=<id>`, `&mods=`, `&holding=<item id>` | Right-clicks the crosshair target, that block face or that entity, directly through the game mode. The player must be within reach. `holding` first waits until that item has reached the client's hand. For anything mods catch from real input, use `/click` instead. |
| `POST /attack` | the same | Left-clicks: breaks the block in creative mode, or attacks the entity. |
| `POST /key` | `?key=e` (GLFW names: `escape`, `enter`, `e`, `left.shift`, `f3`, ..., or an action such as `key.forward`, or `sneak`); optional `&action=down`, `&action=up` or `&ticks=<n>`; `&mods=` | Presses the key through the game's keyboard handler, like a real key press: in the open screen, where mods that hook screen keys, such as EMI's and JEI's recipe keys, see it too, or with no screen open as a game keybind or a key the game handles itself. By default it taps the key. `action=down` holds it until `action=up` or `/release`, and `ticks` holds it that long and returns after it's released. A held key also counts for code that reads the physical keyboard, such as `InputConstants.isKeyDown`, Ponder's hold key and Create's train controls. |
| `POST /type` | body: text | Types into the open screen. |
| `POST /click` | `?button=left`, `right`, `middle` or a number; `?x=&y=`, `?text=<widget label>` or `?slot=<slot index>`; `&action=`, `&ticks=`, `&mods=`, `&holding=` as above | Presses a mouse button the way a real click does, so input events reach mods. With a screen open it moves the cursor to that place first, and the cursor stays there, so later screenshots show that hover state. With no screen open it clicks in the world at the crosshair, and holding the left button mines. |
| `POST /mouse` | `?x=&y=`, `?text=` or `?slot=`; optional `&steps=<n>` | Moves the cursor in the open screen without clicking, to hover a widget for its tooltip. With a button down it drags, in `steps` even moves. Coordinates outside the window, such as `?x=-1&y=-1`, park it so nothing is hovered. |
| `POST /scroll` | `?delta=1`; optional `?x=&y=`, `?text=` or `?slot=`, `&mods=` | Scrolls the wheel over the open screen, or in the world, where it changes the hotbar slot unless a mod takes it. |
| `POST /release` | | Lets go of everything held. Leaving a world does the same. `/status` lists what's held under `held`, with the key mappings that are down. |
| `POST /drop` | body: file paths, one per line | Drops the files on the open screen, as dragging them onto the window does. Native file dialogs can't be driven, so test imports this way. |
| `POST /fixture` | `?name=board`, `contraption` or `train`; optional `&x=&y=&z=`, `&verbose=true` | Builds a working test setup. See [Fixtures](#fixtures). |
| `POST /player` | `?name=Alex`; optional `&op=true`, `&command=<command>`, `&x=&y=&z=&face=`, `&leave=true`; no parameters lists players | Adds a second player. See [A second player or a dedicated server](#a-second-player-or-a-dedicated-server). |
| `POST /save` | | Writes the world to disk now. `save-all` only exists on dedicated servers. |
| `POST /connect` | `?host=localhost:25700` or `?world=<name>` (default: the test world) | Leaves the current world and joins that server or world, returning once the player is in. |
| `POST /disconnect` | | Saves and leaves the world for the title screen. |
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

`mods` lists modifiers to hold around the press: `sneak` (the key bound to sneaking), `shortcut` (Cmd on macOS, Ctrl
elsewhere), `ctrl`, `shift`, `alt` and `super`. In a screen, `shift` is what shift-clicks want. In the world, sneaking
is `sneak`: a worktree's game copies the user's key bindings, and sneak may not be on shift. The modifier is held a
couple of ticks before the press, so the server knows about it first.

A whole interaction fits in one request, which costs an agent far less than one request per step:

```json
["give @s create:wrench",
 {"call": "/look?x=10&y=64&z=5"},
 {"waitTicks": 3},
 {"call": "/click?button=right&mods=sneak&holding=create:wrench"},
 {"waitTicks": 5},
 {"call": "/chat?lines=1"}]
```

### Fixtures

`POST /fixture?name=<name>` builds a working setup in a few seconds, each in its own spot far from the world's builds.
It returns the coordinates of its `parts`, the commands to `clear` it, and any `failed` steps. Recipes are batch steps
in [src/dev/resources/dev-fixtures](src/dev/resources/dev-fixtures), so they're also worked examples, and adding one
means adding a file and its name in `DevFixtures`.

| Name | What you get |
| --- | --- |
| `board` | A powered 4 by 2 flip-dot board facing north, playing scrolling stripes. |
| `contraption` | A 3 by 3 flip-dot board glued to a running mechanical bearing. |
| `train` | Stations Alpha and Beta 50 blocks apart, and an assembled train at Alpha, facing Beta, with a seat, controls and a Blaze Burner conductor. |

A train outlives `clear`: run `create train remove <uuid>` first, with the UUID from `/entities?type=carriage`.

### A second player or a dedicated server

`/player?name=Alex` adds a real second player to the integrated or dedicated server, with no client. It appears in the
world and the player list, and has no operator permissions unless you pass `op=true`. `command` runs a command as it
and `x`, `y`, `z`, `face` right-click a block with its main hand, so it can stand in for a stranger in a land claim:

```sh
# Dev claims the chunk; Alex can't use what's in it, but can outside it.
curl -s -X POST localhost:<port>/command --data "oclaims claim"
curl -s -X POST "localhost:<port>/player?name=Alex"
curl -s -X POST localhost:<port>/command --data "tp Alex 100 1 99"
curl -s -X POST "localhost:<port>/player?name=Alex&x=100&y=0&z=100&face=up"   # "use": "PASS" means nothing happened
curl -s -X POST "localhost:<port>/player?name=Alex&leave=true"
```

`./gradlew devServerStart -PthreadId=<id>` starts a dedicated server for this checkout, in a `minecraft-server` terminal,
with a superflat world in `build/server-run`, offline mode and the same mods as the dev client. Starting it accepts the
Minecraft EULA for that test server. Add `-PdevModSet=<set>` if the client uses a set, so both have the same mods. It
prints the address to join with `/connect`, and a console API, whose port is in `build/dev-server.json`:

| Endpoint | Effect |
| --- | --- |
| `GET /status` | The ports and the players online. |
| `POST /command` | Runs a command with full permissions, as the server console does. `op Dev` makes the client an operator. |
| `POST /player` | The same as the client's `/player`. |
| `POST /quit` | Saves and closes the server. `devServerStop` and `devStop` do this. |

The client joins as a non-operator, so `/command` shows what a normal player may do. `/camera` and `/fixture` need
single-player. The server quits after 30 idle minutes with nobody on it, or when its worktree is removed.

### Testing tips

- Check sounds with `/sounds` first. A sound that should be audible but is missing from `playing`, or has `heardGain`
  near 0, is the bug.
- To check the sound itself, wrap it in `/audio?action=start` and `action=stop` steps. Agents can't listen, so use the
  levels, or a spectrogram to see pitch and timing: `ffmpeg -i in.wav -lavfi showspectrumpic=s=800x300:scale=log:stop=8000 out.png`.
  Boards near the player keep rattling, so a "silent" baseline may not be silent.
- Check animation timing with `/blockentity?...&frames=<n>&intervalMs=0` instead of adding temporary logging. Removing
  logging that used a lambda can force a restart (see [What hot reload covers](#what-hot-reload-covers)).
- To see motion faster than a frame sequence can catch, slow the game down with `/command` `tick rate 5`, and set it
  back with `tick rate 20` when you're done.
- Use `/camera` to frame a block or a whole display (`fit=<W>x<H>`) instead of guessing teleport coordinates.
- Use `/perf` before and after a rendering change, with the same view and display size, to compare frame times.
- Use `/find` to locate a fixture's parts (motors, displays, gauges) instead of scanning coordinates one command at a
  time.
- Read `/screen`, `/chat` and `/entities` before reaching for a screenshot. A failed Create assembly only shows its
  reason on the screen, though, so look at one then.
- Teleport first, then build: `fill` and `setblock` fail in chunks that aren't loaded, and `/fixture` handles this. Use
  `{"waitUntil": "execute if loaded X Y Z"}`, and a newly loaded chunk may take a moment to start ticking.
- Change settings with `/options`, for example `?graphicsMode=2` for Fabulous, and `?restore=true` when you're done.
  Anything read only at startup, or a Flywheel or Create config file, needs `devRestart`. `flywheel backend <name>`
  works through `/command`, and persists, so set it back too.
- Create's kinetic blocks only reach a neighbor a moment after placing. A powered bearing assembles itself when
  rotation reaches it, so place the board and glue first and the motor last. A click on a running bearing stops it.
- Open Parties and Claims' commands are `/oclaims`, `/oparties` and `/opac`.
- With the `ftb` set, `ftbchunks claim` claims a chunk for the player, and `ftbchunks admin claim_as` for a team. After
  `ftbteams server create Name`, `/save`, then take the team's id from its file name in
  `saves/<world>/ftbteams/server/`: the team is `Name#<first 8 characters>`. `ftbteams force-add` didn't accept
  that form or the full id. A stranger's `/player` right-click answers `"use": "FAIL"` in a claim.
- Carry On needs empty hands and at most 2.5 blocks of distance. `mods=sneak` on `/click` picks up a block.
- To force an autosave-only path, such as saved-data cleanup, `tick sprint 6300` runs a save interval quickly.
- To check what the server does with media packets a modified client could send, type
  `/flipdot_packet control <pos> <action>` or `/flipdot_packet upload <pos> <columns> <rows>` in chat. These dev-only
  client commands send the packets without the media screen and log the server's replies with a `RAW_MEDIA` prefix in
  the game's `logs/latest.log`. The server only accepts them for a display whose screen it opened for that player.
  `/flipdot_packet connect <host:port>` leaves the world and joins a server, for testing as a non-op player on a
  `runServer` with `online-mode=false`.
- Create's kinetic blocks don't apply edits made with `data merge` to a running network. To change a creative motor's
  speed, replace it in one batch:
  `["setblock X Y Z air", "setblock X Y Z create:creative_motor[facing=F]{ScrollValue:256}"]`.

### Schematics

A Schematic and Quill works through real input, in one batch if you like. With the quill in hand, `/look` at each
corner and `/click?button=right`. The action bar reports each step in `/chat`. `/scroll?delta=3&mods=ctrl` resizes the
selection, and a third right-click opens the name prompt: click the box, `/type` a name, then `/click?text=Save`. A
corner on a block's top or bottom selects the block beyond it.

Upload at a Schematic Table: `/use` it, shift-click the empty schematic into it with `/click?slot=<n>&mods=shift`, and
click the upload button. With the schematic in hand, `/click?button=right` twice deploys it. In a Schematicannon's
screen, shift-click in the schematic and gunpowder and click the play button at about (360, 178). Keep a chest of
blocks next to the cannon. `/blockentity?x=&y=&z=&side=server&all=true&fields=state,statusMsg,blocksPlaced` follows it;
`statusMsg` `schematicExpired` means the selection was empty.

### Trains

`POST /fixture?name=train` does the first part of this. These steps all work through the API, and show what to change
when testing something the fixture doesn't cover.

1. **Track:** `fill` a straight line of `create:track[shape=xo]`, or `[shape=zo]` along Z, on solid ground.
2. **Station:** hold `create:track_station`, `/use` the track with `face=up` while facing the way the train will
   leave, then `/use` the ground beside it. `/use` the station to open its screen.
3. **Name it:** `/click?text=Track Station`, `/key?key=a&mods=shortcut`, `/type` the name, then `/close`.
4. **Assembly mode:** `/click?text=Create new Train`. `/blockentity` on the station shows `assemblyDirection`, the side
   the train is built on. That's the opposite of its forward direction.
5. **Bogey:** `/close`, then `/use` the track right next to the station marker, on the `assemblyDirection` side, holding
   `create:railway_casing`. The frontmost bogey has to be there. Bogeys at other track pieces add carriages or length.
6. **Body:** build on the bogey, and use `create glue <from> <to>` with a box that includes the bogey, or assembly
   fails with "No structure attached". Train Controls must face `assemblyDirection`. A Blaze Burner
   (`create:blaze_burner[blaze=smouldering]`) on the side the controls face is a conductor; a seat there only is if
   someone sits on it.
7. **Assemble:** `/use` the station and `/click?text=Assemble Train`. If it stays on the same screen, take a screenshot
   for the reason. `/entities?type=carriage` lists the carriage and its train's UUID.
8. **Drive:** right-click the seat and then the controls on the moving train: `/look` at each and `/click?button=right`,
   not `/use`, since contraptions catch real clicks. Then hold `/key?key=key.forward&action=down` and let go with
   `action=up`. The train accelerates like a player's would. A player who isn't on a seat stays behind and loses
   control at a distance. `/key?key=sneak&ticks=4` gets off the seat.
9. **Schedule:** drop a Schedule on the station with the train at it, and it runs if the train has a conductor:

   ```text
   summon item X Y+1 Z {Item:{id:"create:schedule",count:1,components:{"create:train_schedule":{Entries:[{Instruction:{Id:"create:destination",Data:{Text:"Beta"}},Conditions:[[{Id:"create:delay",Data:{Value:1,TimeUnit:1}}]]}],Cyclic:false}}}}
   ```

   Wait for it with `{"waitUntil": "execute if entity @e[type=create:carriage_contraption,x=X,y=Y,z=Z,dx=6,dy=4,dz=2]",
   "timeoutTicks": 600}`.
10. **Disassemble:** the train must have arrived by navigation; a manual stop leaves the station "Idle". `/use` the
    station and `/click?text=Disassemble Train`. The carriage goes back to blocks and the station returns to assembly
    mode. `create train remove <uuid>` deletes a train anywhere.

### Screenshots of hidden games

Screenshots capture the frames the game renders. That works while the window is hidden or in the background, but
Minecraft stops rendering while its window is minimized, so `/screenshot` times out then. Commands and input still
work. A worktree's game hides instead of minimizing for this reason.

## Images in chat

T3 Code stores an embedded image as its file path and reads the file each time it displays the message: once the file
is gone, the image's address answers 404 and no new one can be issued. So files shown in chat live in
`run/evidence/<worktree>/` in the main checkout (`run/evidence/main/` for its own game), which isn't deleted with the
worktree or its game folder. `keep=<name>` on `/screenshot`, `/camera` or `/audio` puts them there, and never replaces
an earlier file, since chat may already show it. Nothing cleans this folder up; delete from it by hand if you ever want
to, and those files stop displaying in their chats. `/status` reports the folder as `evidenceDirectory`. Git ignores
`run/`, so it stays out of every branch.

Scratch captures in a game's `screenshots/dev-tools/` go when that game next launches two days later, or with the
worktree.

### Recording a whole session

An empty file named `dev-record-session` in a game's folder records that game from then on, until the file is removed
or the game closes. Add it before the game starts to catch its launch; a running game notices within a few seconds. The
evidence folder gets a `session-<time>.mkv` at up to 15 FPS with real timing, its sound mix on a silent device as raw
48 kHz stereo 16-bit `.pcm`, and a `.log` of control API calls and commands, with their times and durations. Each game
launch makes a new set. Nothing an agent does changes while recording.

## PR evidence

`./gradlew prEvidence` uploads `build/pr-evidence/` as the branch's PR evidence: screenshots, recordings and other
files that a workflow embeds in the PR's Preview section. [CONTRIBUTING.md](CONTRIBUTING.md#pr-evidence) explains it.
The control API's `/screenshot` makes the images, GIFs and MP4s. A file `PREVIEW.md` names that's missing from
`build/pr-evidence/` is copied from this worktree's kept images, so the images shown in chat don't need capturing twice.

## Release testing with standard Java

JetBrains Runtime and every dev tool are limited to the `runDevClient` run and the `dev`/`devAgent` source sets. None
of them ship in the mod jar. `./gradlew build` still targets Java 21 and produces `build/libs/*.jar`, which runs on
the regular Minecraft Java runtime in any launcher, such as Modrinth, with your own account.

## Other runs

- `./gradlew runClient` / `runServer` are the plain NeoForge runs, using the checkout's own `run/` and `build/server-run/`.
- `./gradlew runClient -PgameplayCheck` and `runServer -PserverCheck` run the automated flip-dot checks in
  [src/dev](src/dev).
