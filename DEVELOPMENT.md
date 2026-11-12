# Development

How to build the mod, test it in the dev client, and add new content. Coding agents also follow
[AGENTS.md](AGENTS.md), which holds the full control API reference and testing recipes.

## Requirements

- Git, and any JDK 17+ to run Gradle. Gradle downloads everything else on first use: Java 21 for compiling, and
  [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime) 21 for the dev client.
- No Minecraft account is needed. The dev client plays offline as the player `Dev`.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew build` | Compiles, runs the unit tests, checks formatting and generated resources, and builds `build/libs/*.jar`. |
| `./gradlew spotlessApply` | Formats the Java code. `build` fails on unformatted code. |
| `./gradlew runData` | Regenerates `src/generated/resources` (see [Generated resources](#generated-resources)). |
| `./gradlew runGameTestServer` | Runs the GameTests on a headless server (see [GameTests](#gametests)). |
| `./gradlew runDevClient` | Launches this checkout's game into its test world, with hot reload and the control API. If it's already running, shows its window and follows its log instead. |
| `./gradlew devReload` | Formats, compiles, then pushes code, assets and data into the running game. `-Pfull` reloads all assets and data. |
| `./gradlew devStop` | Closes this checkout's game, saving the world, and its dedicated server if one runs. |
| `./gradlew devStart -PthreadId=<id>` | Starts this checkout's game with its window hidden, in a T3 Code thread's terminal. Agents use this. `devRestart` stops it first. |
| `./gradlew devServerStart -PthreadId=<id>` | Starts a test dedicated server for this checkout. `devServerStop` closes it. |
| `./gradlew prEvidence` | Uploads `build/pr-evidence/` as the branch's PR evidence (see [CONTRIBUTING.md](CONTRIBUTING.md#pr-evidence)). |
| `./gradlew pruneBranches` | Deletes local branches already merged into `main` (see [Merged branches](#merged-branches)). `-PdryRun` only lists them. |

Before opening a PR, `./gradlew build` and `./gradlew runGameTestServer` should pass, and `./gradlew runData`
should leave `src/generated` unchanged. CI checks all three.

### T3 Code scripts

[t3.json](t3.json) declares the project's T3 Code scripts. T3 Code doesn't add them on its own: on each machine,
import them once from the scripts menu's **From t3.json** group, or **Settings → Projects → Actions → Import
scripts**. They're then saved in that machine's T3 settings, so a later change to `t3.json` means editing or
re-importing them there. They only call the Gradle tasks above.

| Name | Command | Icon | Option |
| --- | --- | --- | --- |
| Launch Minecraft | `./gradlew runDevClient && exit` | play | |
| Reload Minecraft | `./gradlew devReload` | build | |
| Stop & Clean Up | `./gradlew devStop pruneBranches --continue` | debug | **Run in the thread's worktree when the thread settles** |

The commands work in the shells T3 Code's terminals use: bash or zsh on macOS and Linux, and PowerShell 7 on
Windows. Install PowerShell 7 on Windows (`winget install Microsoft.PowerShell`); without it, T3 Code falls back to
Windows PowerShell 5.1, which can't run `&&`. The `exit` closes the game's terminal when the game exits normally,
because an open terminal keeps T3 Code from cleaning up a worktree. If the build fails or the game crashes, the
terminal stays open so you can read the error. When a worktree's thread settles, **Stop & Clean Up**
closes that worktree's game; T3 Code skips settle scripts on the main checkout. T3 Code runs only the first script
marked to run on settle, so **Stop & Clean Up** also runs `pruneBranches`.

`t3.json` also makes new threads start in a worktree, since each worktree gets its own game and test world. A
project or machine setting in T3 Code overrides it.

Agents run `devReload` at the end of each turn that changes `src/`, so **Reload Minecraft** is a manual fallback. It
replaces both F3+T and `/reload`.

### Merged branches

GitHub deletes a PR's branch when it merges, and T3 Code removes a settled thread's worktree, but nothing deletes the
local branch. `./gradlew pruneBranches` does, and **Stop & Clean Up** runs it whenever a thread settles. A thread's own
branch is still checked out at that moment, so it goes at the next settle.

It deletes a branch only when nothing on it can be lost: no worktree has it checked out, and its last commit is
already in `main` or is the head of a merged PR (found with the `gh` CLI, since squash merges leave the branch's
commits outside `main`). It also fast-forwards the local `main` when no checkout has it open. Everything else stays,
and the task lists merged branches that are still checked out. `-PdryRun` lists what it would delete without
deleting anything.

T3 Code only removes a worktree that's still on the branch it created, so agents never rename or switch branches (see
[AGENTS.md](AGENTS.md#branches)). A worktree with uncommitted changes or an open terminal stays too.

## Project layout

| Folder | What's in it |
| --- | --- |
| `src/main/java` | The mod. One package per feature, such as `flipdot` and `media`, plus the `Mod*` registration classes. |
| `src/main/resources` | Hand-made assets: models with real geometry, textures, sounds, Ponder structures. |
| `src/main/lang/en_us.json` | Player-facing English strings that aren't names. Not shipped as-is; `runData` merges it. |
| `src/generated/resources` | Output of `runData`. Committed, never edited by hand. |
| `src/test` | Unit tests (`./gradlew test`). |
| `src/dev` | Dev-only code that never ships: the control API, fixtures, GameTests and dev commands. |
| `src/devAgent` | The Java agent that hot-swaps classes. |
| `gradle/` | The dev client, PR evidence and branch cleanup tasks, and the named sets of test mods. |

## Adding a block

1. **Block:** write the block class (and its block entity, renderer or visual if it has them) in a package for the
   feature, like `flipdot`.
2. **Register:** add it to `ModBlocks` with its properties, `.transform(pickaxeOnly())` or other tags,
   `.blockstate(...)`, `.recipe(...)`, `.lang("English Name")` and `.item(...)`. Add its block entity to
   `ModBlockEntities` with `.visual(...)` and `.renderer(...)`, a display target to `ModDisplayTargets` with
   `.transform(displayTarget(...))` on the block, and sounds to `ModSounds`. New registrations need a game restart.
3. **Assets:** add hand-made models and textures under `src/main/resources/assets/create_display_works`, and
   player-facing strings that aren't names to `src/main/lang/en_us.json`.
4. **Generate:** run `./gradlew runData`, check what changed in `src/generated/resources`, and commit it.
5. **GameTests:** add a class like
   [FlipDotTests](src/dev/java/com/zmm2025/createdisplayworks/dev/flipdot/FlipDotTests.java), built with
   [GameTestKit](src/dev/java/com/zmm2025/createdisplayworks/dev/gametest/GameTestKit.java), and register it in
   [DevGameTests](src/dev/java/com/zmm2025/createdisplayworks/dev/gametest/DevGameTests.java). Cover what the server
   must know from the generated data too: the recipe loads, it drops itself, Display Links find it.
6. **Fixture:** add a JSON file to [src/dev/resources/dev-fixtures](src/dev/resources/dev-fixtures) that builds it
   working, and dev commands under `/displayworks_dev <block>` in their own class (see
   [DevCommands](src/dev/java/com/zmm2025/createdisplayworks/dev/DevCommands.java)) if it needs them.
7. **Ponder:** add scenes to
   [DisplayWorksPonderPlugin](src/main/java/com/zmm2025/createdisplayworks/ponder/DisplayWorksPonderPlugin.java) (or a
   plugin class per feature once it grows), with structures in `assets/create_display_works/ponder`, and add the block
   to the fitting Create Ponder tags. Run `runData` again for the scene text.

A display that shows imported media implements
[MediaDisplay](src/main/java/com/zmm2025/createdisplayworks/media/MediaDisplay.java) and owns a
[MediaPlayback](src/main/java/com/zmm2025/createdisplayworks/media/MediaPlayback.java); the media screen, network and
storage then work for it unchanged.

## Generated resources

Content is registered with Create's Registrate (`CreateDisplayWorks.REGISTRATE`), one class per kind of thing:
[ModBlocks](src/main/java/com/zmm2025/createdisplayworks/ModBlocks.java) (blocks and their items),
[ModBlockEntities](src/main/java/com/zmm2025/createdisplayworks/ModBlockEntities.java) (with their renderers and
Flywheel visuals), [ModDisplayTargets](src/main/java/com/zmm2025/createdisplayworks/ModDisplayTargets.java),
[ModSounds](src/main/java/com/zmm2025/createdisplayworks/ModSounds.java) and
[ModCreativeTabs](src/main/java/com/zmm2025/createdisplayworks/ModCreativeTabs.java). Standalone items would go in a
`ModItems` class.

`./gradlew runData` writes `src/generated/resources` from them in about 15 seconds. It empties the folder first, so
files nothing generates any more disappear.

| Generated | From |
| --- | --- |
| Blockstates | `.blockstate(...)`, such as [FlipDotBlockStateGen](src/main/java/com/zmm2025/createdisplayworks/flipdot/FlipDotBlockStateGen.java) |
| Item models | `.item()...model(...)`. `customItemModel()` makes one that points to hand-made geometry in `models/block/<name>/item.json`. |
| Loot tables, recipes and their unlock advancements, tags | `.loot(...)` (the default drops the block), `.recipe(...)`, `.tag(...)` |
| `sounds.json` | `ModSounds` |
| `lang/en_us.json`, `lang/en_ud.json` | `.lang(...)` and `addRawLang` in registrations, [src/main/lang/en_us.json](src/main/lang/en_us.json), and the Ponder scenes. See "Language" in [AGENTS.md](AGENTS.md#language). |

A file must not be both hand-made and generated. `build` and `devReload` run `checkGeneratedResources`, which fails
on that, and on a string in `src/main/lang/en_us.json` that hasn't been generated yet. It can't see other stale output,
so CI runs `runData` and fails if anything under `src/generated` changes.

## The dev client

### Your main world and worktree copies

The main checkout's game uses the main checkout's `run/` folder, which git ignores. That's your main world. To use a
different folder, pass `-PdevRunDir=<path>` or set `devRunDir` in `~/.gradle/gradle.properties`.

Each worktree runs its own game, in `run/worktrees/<worktree>`. The first time it launches, it copies the main world,
options and settings, so it starts from your current builds. After that its world is separate. To get a fresh copy,
delete `run/worktrees/<worktree>/saves` while that game is closed. Several worktrees' games can run at once.

When a worktree is removed, its game quits within a few seconds. The next launch or reload in any checkout deletes
the game's folder and anything the removal left behind, and runs `git worktree prune`.

The game opens **Dev Testing World**, creating it the first time: creative, Easy, cheats on, a superflat world with no
structures, no mob spawning, drops, mob griefing, raids, daylight or weather cycles, `keepInventory` on, noon, and
every advancement granted. To start over, delete `saves/Dev Testing World` while the game is closed. The client only
auto-joins once per launch, and it turns off *Pause on Lost Focus* and the tutorials so it stays controllable in the
background.

The window title starts with the branch and worktree, for example
`feat/split-flap-display (t3-dab23ea6) | Minecraft 1.21.1 - Singleplayer`.

### Hidden games

An agent starts its worktree's game with `devStart`, which keeps the window hidden. It doesn't appear, take focus or
show in the taskbar, runs at 30 FPS, and mixes its sound in memory, so nothing reaches your speakers. Its console is
in a `minecraft` terminal in the agent's thread (Ctrl+J). It quits after 30 minutes without control API requests.

**Launch Minecraft** in a checkout whose game is already running brings that window to the front, moves its sound
to your speakers and restores its frame rate, instead of launching a second game. Minimizing a worktree's game hides
it again, so the agent can keep using it.

### Extra mods

The dev client loads this mod, Create, Ponder and Flywheel from Gradle. There are two ways to add more:

- **Your Modrinth profile:** add mods to the [Modrinth App](https://modrinth.com/app) profile
  **Create: Display Works Dev** (NeoForge 1.21.1). Each **Launch Minecraft** copies its enabled mods into the game's
  `mods` folder, and removes ones you've removed or disabled. Set `devModsProfile=<name or folder>` in
  `~/.gradle/gradle.properties` to use another profile or a folder of jars.
- **Named mod sets:** pass `-PdevModSet=<set>` (several with commas) to `runDevClient`, `devStart`, `devRestart` or
  `devServerStart` to load a set from [gradle/dev-mod-sets.json](gradle/dev-mod-sets.json) instead of the profile.
  Sets are reproducible, so agents use them for compatibility tests:

  | Set | Mods |
  | --- | --- |
  | `rendering` | Sodium, Iris, Entity Culling |
  | `claims` | Open Parties and Claims |
  | `addons` | Create Crafts & Additions, Enchantment Industry, Connected, Big Cannons, New Age, Copycats+ |
  | `ftb` | FTB Chunks, FTB Teams, FTB Library, Architectury |
  | `interaction` | Carry On, WorldEdit, Jade, EMI |

  Entries are Modrinth slugs, `slug@version`, or `{"maven": <repository>, "id": "<group:artifact:version>"}`.
  Modrinth's required dependencies come along. Jars are cached in `~/.gradle/create-display-works/dev-mods` for every
  worktree; `-PdevModSetRefresh` looks for newer versions.

Either way, mods Gradle already loads are skipped, so addons must work with the Create version in
[gradle.properties](gradle.properties).

### Hot reload

`devReload` compares compiled output with what the game last loaded, and reloads:

- **Code:** changed classes are redefined in place. JetBrains Runtime lets this include new methods, fields and
  classes, not just method bodies.
- **Assets:** textures, models, sounds and lang files, as F3+T does.
- **Data:** recipes, tags, loot tables and advancements, as `/reload` does.

It prints `DEV RELOAD: RESTART REQUIRED` for changes it can't apply safely, such as static initializers, new event
handlers, mixins or lambdas renumbered by an edit. New registrations, startup-only code and build changes also need a
restart, though it can't detect those. Code that already ran keeps its results: re-place a block or reopen a screen
to see a change to how it's set up. [AGENTS.md](AGENTS.md#when-you-finish-a-turn) lists the rules in full.

### The control API

Each dev client serves a localhost-only HTTP API that drives the `Dev` player: commands, real keyboard and mouse
input, screenshots and video, sound checks, block entity inspection, frame timing and ready-made test setups. Agents
use it instead of computer use. The main checkout's game listens on port `25595` (`-PdevControlPort=<port>` changes
it); a worktree's game picks a free port and writes it to `build/dev-client.json`.

```sh
curl -s localhost:25595/status
curl -s -X POST localhost:25595/command --data "setblock ~2 ~ ~ create_display_works:flip_dot_display"
curl -s -X POST "localhost:25595/screenshot?hud=false"
curl -s -X POST "localhost:25595/fixture?name=board"
```

In Windows PowerShell, use `curl.exe`. The API refuses requests from web pages. Every endpoint is described in
[AGENTS.md](AGENTS.md#control-api-reference).

## GameTests

`./gradlew runGameTestServer` runs the mod's GameTests on a headless dedicated server in `build/gametest-run/`, and
fails if any test fails. Because it's a dedicated server, it also proves the mod loads without client classes. The
tests live in `src/dev`, one class per block, such as
[FlipDotTests](src/dev/java/com/zmm2025/createdisplayworks/dev/flipdot/FlipDotTests.java), and build their setups with
the helpers in [GameTestKit](src/dev/java/com/zmm2025/createdisplayworks/dev/gametest/GameTestKit.java).
[DevGameTests](src/dev/java/com/zmm2025/createdisplayworks/dev/gametest/DevGameTests.java) registers them.

## Release testing with standard Java

JetBrains Runtime and every dev tool are limited to `runDevClient` and the `dev`/`devAgent` source sets, and none of
them ship in the jar. `./gradlew build` targets Java 21 and produces `build/libs/*.jar`, which runs on the regular
Minecraft runtime in any launcher, with your own account.

## Other runs

- `./gradlew runClient` and `runServer` are the plain NeoForge runs, using the checkout's own `run/` and
  `build/server-run/`.
- `./gradlew test -PmediaChecks` also imports sample files of every media format from `build/media-decoder-check/`,
  and `-PyoutubeCheck` imports a public YouTube video.

## Notes for planned features

### Render distance

The goal is for display blocks from Create and Minecraft, such as display boards, nixie tubes and signs, to stay
visible out to the player's render distance, as flip-dot displays already do.

- Measure first: use `/perf` on a wall of display boards, near and far, then decide whether this needs a client setting.
- Registering a renderer subclass that overrides `getViewDistance` looks possible without a mixin, but this is
  unconfirmed.
- The real limit becomes the client's render distance, because block entities further away aren't loaded. Distant
  Horizons won't show them.
- Create's flap animation runs in the client block entity tick, not while drawing, so off-screen boards don't fall
  behind.
