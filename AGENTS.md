# Agent instructions

The user tests changes in a live Minecraft dev client started with `./gradlew runDevClient` (the **Launch Minecraft**
script in T3 Code). Each worktree runs its own game, with its own copy of the test world, so this worktree's game is
yours to use. [DEVELOPMENT.md](DEVELOPMENT.md) explains the setup.

## No release yet

The mod hasn't been released, so no player has a world with its blocks in it. Until the first release, don't write code
that upgrades older saves or keeps old data formats, block states, registry names or network payloads working. Change
them freely, even if the user's test worlds lose data. [PUBLISHING.md](PUBLISHING.md) says to remove this section
before the first release.

## When you finish a turn

If the turn changed anything under `src/`, run `./gradlew devReload` from this worktree once, as the last step
before your final reply. Don't run it after intermediate edits: if you're still going to change code, or the build
or tests fail and you're fixing them, wait. Skip it when nothing under `src/` changed.

Before that, make sure the checks CI runs pass on this machine, so problems show up before a PR does:

- `./gradlew build`: formatting (`devReload` and `./gradlew spotlessApply` fix it), unit tests and the generated
  resources check.
- `./gradlew runGameTestServer`, when the turn changed gameplay or server-side code.

If the turn changed anything data generation reads, run `./gradlew runData` and keep what it changes in
`src/generated/resources` as part of the change. That covers registrations (`Mod*` classes such as `ModBlocks`, and
the blockstate generators they use), `src/main/lang/en_us.json`, Ponder scenes and hand-made models a generated file
points to. Never edit files under `src/generated` by hand; `runData` replaces the whole folder. `build` and `devReload`
fail on a lang string that `runData` hasn't picked up yet, but can't see other stale data. CI runs `runData` and fails
if anything under `src/generated` changes.

### The closing line

A closing line tells the user what they have to do to see this turn's change in their own game. Write it from what
actually happened, not from `devReload`'s status alone, and leave it out when it wouldn't help:

- **Nothing to try in-game:** answers, research, plans, docs, README, CI or Gradle tasks that don't change the game,
  and dev tooling the user doesn't play with. End the reply normally. Don't add a reload line, and don't explain
  that you skipped `devReload`.
- **Whose game it is:** `devReload` ends with `[window shown]` or `[window hidden: ...]`. A shown window is a game the
  user is looking at. A hidden one is the game you started with `devStart`, or one the user minimized. Either way the
  user isn't running it, so never call it "your Minecraft client", and never ask the user to reload or restart it.
- **Fresh games:** if a game started after your edits, `devReload` prints `NO CHANGES` because the game already loaded
  them. That doesn't mean there's nothing new to see.

Pick the line that fits, then adjust the wording to the change:

| Situation | Closing line |
| --- | --- |
| Window shown, change reloaded | ✅ **Your Minecraft client has been reloaded.** |
| Window shown, game needs a restart | ⚠️ **Restart Minecraft to try these changes.** followed by the reason in plain words, not in bold. |
| Window shown, game started after your edits | Usually none; the user's game already shows the change. |
| Window hidden, or no game running | 🚀 **Launch Minecraft to try these changes.** It shows a hidden game, or starts one. |
| `DEV RELOAD: ERROR` | Explain what went wrong. |

If a hidden game needs a restart, restart it yourself before you reply (run `./gradlew devStop`, then `devStart`
again), so **Launch Minecraft** doesn't show the user old code. If you can't, say so in plain words
instead of asking the user to restart a game they never opened.

When the user's game and your test game differ, say where each stands, for example that you tested it in this
worktree's hidden game but the main checkout's game needs a restart once it's merged. Don't let the closing line
contradict the rest of the reply.

### Restart rules

`devReload` prints `DEV RELOAD: RESTART REQUIRED` and exits non-zero when it finds:

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

`devReload` can't see every restart-only change. Treat the game as needing a restart, even if it printed `RELOADED`,
when you:

- register anything new: blocks, items, block entities, menus, entities, sounds, data components, creative tabs or
  network payloads
- change code that only runs during startup, setup or registration events, such as renderer, model, key mapping,
  config spec or Ponder registration
- change mixins, mod metadata, dependencies, or Gradle settings the game launches with

If hot-swapped code only affects new objects or newly opened screens, tell the user to re-place the block or reopen
the screen.

Never tell the user to press F3+T or run `/reload`. Point them to the **Reload Minecraft** script button instead,
which runs `./gradlew devReload`. In the main checkout, don't launch, close or restart the game unless the user asks,
because it holds their main world. In a worktree, you may start its game with `devStart` (below) and leave it
running; don't close or restart it while the user has its window shown.

## Driving the game

You control the `Dev` player through the game's localhost API. Use it to build test setups, verify behavior and
capture demos. Every endpoint is listed in [Control API reference](#control-api-reference).

- To start this worktree's game, run `./gradlew devStart -PthreadId=<your T3 Code thread id>` (T3's thread tools give
  the current thread's id). It opens a `minecraft` terminal in your thread, starts the game with its window hidden so
  it doesn't disturb the user, and returns once the game is in its world, printing the control API's port. If the
  game is already running, it just prints the port. Don't start it with `runDevClient`, which shows the window, or
  in a background shell command, which the user can't see.
- The port is also in `build/dev-client.json`. Check that `/status` reports this worktree before trusting a result.
- The window stays hidden unless the user shows it. Don't call `/window?show=true` yourself.
- `./gradlew devRestart -PthreadId=<id>` restarts it. Add `-PdevModSet=rendering` (or `claims`, `ftb`, `addons`,
  `interaction`, or several with commas) to test with other mods from a cache all worktrees share.

Anything a player can do, you can do. Keep tests cheap: batch steps, read text instead of screenshots, and use
fixtures instead of building by hand. [Control API reference](#control-api-reference) and [Testing tips](#testing-tips) have the details.

- `/command` runs commands as the player, including client commands such as `flywheel backend`.
- `/key`, `/click`, `/scroll`, `/mouse`, `/look` and `/type` send real input, in the world as well as in screens.
  Add `action=down`, `action=up` or `ticks=<n>` to hold a key or button, and `mods=` to hold modifiers. Held input
  also satisfies code that reads the physical keyboard.
- Key bindings come from the user's settings, where sneak isn't shift. Use `mods=sneak` and `key=key.forward`.
- `/use` and `/attack` act on a block or entity directly. Pass `holding=<item>` after changing the held item by
  command, since that takes some ticks to reach the client. `/close` closes a screen; Escape with none open pauses.
- `/screen`, `/chat` and `/entities` read what's on screen as text, so most checks need no screenshot.
- `/fixture?name=board`, `contraption` or `train` builds a working setup in seconds and returns its coordinates.
- `./gradlew runGameTestServer` runs the GameTests headless, with no game window. Prefer a GameTest for server-side
  behavior you want checked on every build; "GameTests" and "Adding a block" in DEVELOPMENT.md say how to add them.
- `/options` changes game settings and puts them back. `/player` adds a second player, and `devServerStart` a
  dedicated server, for permission tests.
- `/status` and `/screenshot` let you check the result. Open the PNG it returns to look at it.
- For anything that moves or changes over time, such as animations, rotating parts, flip-dot transitions or media
  playback, capture a frame sequence with `/screenshot?frames=<n>&intervalMs=<ms>` and open the contact sheet it
  returns. One still image can't show motion. Choose the interval from the effect's speed, for example 50-100 ms
  for a 10 FPS animation, and use `timesMs` for the actual capture times.
- Delete screenshots and frame folders you no longer need from the `screenshots/dev-tools/` folder next to the PNGs.
  Never delete kept images (see [Images in chat](#images-in-chat)).
- `/sounds` shows what the game plays and how loud it reaches the player. Use it for most sound checks. When how a
  sound actually sounds matters, `/audio` records it, and `audio=true` adds it to an MP4. A hidden game's sound goes
  into memory and can't reach the user's speakers.
- `/blockentity` reads a block entity's fields on the client and server, and can sample them every frame. Use it
  instead of adding temporary debug logging.
- Send several steps as one JSON array to `/command`, with `waitTicks` or `waitUntil` steps instead of `sleep`, and
  `{"call": "/click?button=right"}` steps for any other endpoint. Use `/find` to locate blocks instead of scanning
  coordinates one command at a time.
- `/camera` flies the player to face a block or fit a whole display in view, and can screenshot in the same call. Use it
  instead of guessing teleport coordinates.
- `/perf` measures frame times and the mod's render cost. Use it to check that large or many displays stay fast, with
  `fps=unlimited` to lift the hidden game's 30 FPS cap.
- Add `video=mp4` or `video=gif` to a frame sequence when the user wants a demo. [Testing tips](#testing-tips) has more shortcuts.

Don't use computer use for this. Keep these rules in mind:

- A worktree's world starts as a copy of the user's main world. Build fixtures away from existing builds, and only
  clear areas you created; the user may open this world to look at your work.
- Prefer verifying with `/status`, command output (`execute if block ...`, `data get ...`) and screenshots before you
  claim something works in-game.
- In PowerShell, call `curl.exe`, not `curl`.

## Images in chat

When a turn changes or reports on anything a player can see, the reply embeds before and after images, without being
asked. Do this for each change, not one summary image. A text description doesn't replace an image.

- Capture the before while the game still runs the old code, before your first edit. If a before image truly can't be
  captured, say so.
- Add `keep=<name>` to `/screenshot` or `/camera` only for images you'll embed; everything else stays scratch. The
  response's `markdown` is ready to paste into your reply.
- For motion, add `frames=<n>&intervalMs=<ms>&video=gif` to `/screenshot`. `keep` saves the GIF and its contact sheet.
- Chat reads an image from disk every time it's shown, so a deleted file shows up broken. Kept files live in the
  main checkout's `run/evidence/<worktree>/`, which survives the worktree and is never cleaned up. Never delete them,
  and embed nothing from `screenshots/dev-tools/`, a scratch folder or `build/`. Copy an image from anywhere else
  into the `evidenceDirectory` that `/status` reports first.
- Open each image before you embed it. A wrong camera angle proves nothing.
- Subagents capture the evidence and return the absolute image paths. The parent thread embeds them.

T3 Code reads an embedded image from its file path every time it shows the message, and a deleted file can never be
shown again. That's why kept files go to `run/evidence/<worktree>/` in the main checkout (`run/evidence/main/` for its
own game), which outlives the worktree, is ignored by git, and is never cleaned up. `keep` never replaces an earlier
file, since chat may already show it. Scratch captures in a game's `screenshots/dev-tools/` are deleted when that game
launches two or more days later, or with the worktree.

## Language

- Write chat replies, code, comments, docs, commit messages and PR text in American English (color, behavior,
  center, gray). Names that come from Minecraft, NeoForge or Create keep their own spelling, such as
  `BlockBehaviour` or `setColour`.
- English (`en_us`) is the mod's source language. Write every player-facing string in American English, even when
  you match Create's wording, since Create's own lang file mixes spellings. Other locale files, such as `en_gb.json`,
  follow their own locale's spelling and conventions.
- Datagen writes the English lang file, `src/generated/resources/assets/create_display_works/lang/en_us.json`, and its
  upside-down `en_ud.json`. Never edit them. Each string has exactly one source, and `runData` fails if two sources
  give the same key:
  - Names of registered things live in their registration: `.lang("...")` on a block or other builder in the `Mod*`
    classes, a subtitle in `ModSounds`, and `REGISTRATE.addRawLang(key, text)` for other keys that belong to a
    registration, such as the creative tab title.
  - Ponder text lives in the scene code in `DisplayWorksPonderPlugin` (`scene.title(...)`, `.text(...)`). Ponder
    makes the keys.
  - Every other string goes in [src/main/lang/en_us.json](src/main/lang/en_us.json): screen text, chat and
    action-bar messages, tooltips, config option names and the like.

  Then run `./gradlew runData` (see [When you finish a turn](#when-you-finish-a-turn)).
- Never hard-code text a player can see or hear. Put it in one of those sources and use `Component.translatable` with
  arguments for numbers and names, not `Component.literal` or string concatenation. This covers screen labels,
  buttons, tooltips, hints, status and error text, chat and action-bar messages, subtitles, config option names,
  and file dialog titles (pass them through `.getString()`). When the server sends text for the client to show, send
  a lang key, not an English sentence, so each player sees their own language. Proper names, file format names and
  player-entered text can stay literal. Log, exception and dev-tool text that players never see can stay in code.

## Branch names

T3 Code names each thread's branch from the user's first message, before any code exists, so the name can stop
matching the work as the scope grows. Before a branch's first push, or before opening a PR, compare the branch name
with the full diff against `main`. If the name no longer describes the change, rename the branch locally with
`git branch -m <type>/<short-description>` before pushing. Use the `feat/`, `fix/`, `docs/` or `chore/` prefix that
fits the change. If the branch is already on GitHub, ask before renaming it there.

## PR evidence

The user opens PRs with T3 Code's **Commit, push & PR** button, at any point and without asking you first. The button
writes the PR text but can't capture or upload anything. So whenever a branch changes something a player can see or
hear, keep its evidence uploaded and current, as [CONTRIBUTING.md](CONTRIBUTING.md#pr-evidence) describes. A
workflow then puts it in the PR's Preview section.

- Reuse the images you kept for chat (see [Images in chat](#images-in-chat)) instead of capturing them again.
- Pick whatever shows the change best, in any format:
  - before and after screenshots for how something looks
  - a GIF (`/screenshot?...&video=gif`) for short motion, since it plays inline
  - an MP4 (`&video=mp4`) for longer or smoother motion, marked `<!-- drop: <file> -->`, since GitHub only plays
    videos the user drags in
  - links for anything else, such as changed sound files, `/sounds` or `/perf` output, or logs
  - nothing, when there's nothing to see
- If hearing a sound matters, add an MP4 with `audio=true`, or link an `/audio` recording. You can't listen to it, so
  check its levels, and tell the user when a sound needs their ears.
- Write `build/pr-evidence/PREVIEW.md` and run `./gradlew prEvidence` before your final reply. A file it names that
  isn't in that folder is copied from this worktree's kept images, so use their file names. Rerun it whenever later
  work changes what the evidence shows, and remove files that no longer apply.
- If the evidence has drop files, add a short note before your closing line that lists them, linked to their files in
  `build/pr-evidence/`, so the user can drag them into the PR once it's open.
- Settle the branch name (see [Branch names](#branch-names)) before the first upload. After renaming the branch, run
  `prEvidence` again.

## Control API reference

The main checkout's game listens on port `25595`; a worktree's game picks a free port, writes it to
`build/dev-client.json` and `devStart` prints it. Every endpoint returns JSON with an `ok` field. Requests run one at a
time, and requests from web pages (with an `Origin` header or a non-local `Host`) are refused.

| Endpoint | Body or query | Effect |
| --- | --- | --- |
| `GET /status` | | Worktree, world, open screen, whether the window is hidden, window size and GUI scale, player position and rotation, held item and crosshair target. |
| `POST /window` | `?show=true` or `?show=false`; `?fps=<n>`, `unlimited` or `default`; `?width=&height=` | Shows the window and brings it to the front, or hides it. `fps` replaces a hidden game's 30 FPS cap until `default` undoes it. `width` and `height` resize the window, which changes what screenshots and screens lay out. |
| `POST /quit` | | Closes the game the way its close button does, saving the world. It answers before the game has exited; `devStop` waits for that. |
| `POST /command` | body: a command, with or without `/`, or a JSON array of steps | Runs the command as the player at permission level 4. Client commands, such as `flywheel backend`, run on the client. Returns the chat output and whether it succeeded. On a remote server, the command goes through chat with the player's own permissions, and failure means a red reply. An array runs its steps in order and returns `results`, one per step. A step is a command, `{"waitTicks": 20}`, `{"waitUntil": "<command>", "timeoutTicks": 200}`, which reruns the command each tick until it succeeds and stops the batch if it never does, or `{"call": "/key?key=w&ticks=20", "body": "..."}`, which runs any endpoint here and stops the batch if it fails. Consecutive commands run within one server tick. `tp` and `teleport` turn creative flight on first, so the player doesn't fall. |
| `POST /screenshot` | `?hud=false` hides the HUD; `?frames=<n>&intervalMs=<ms>` (up to 600 frames, default interval 100 ms; `0` takes consecutive frames) captures a sequence; `&video=mp4` or `&video=gif` also encodes it; `&audio=true` records the game's sound into an MP4; `&keep=<name>` keeps the result for chat | Saves PNGs under `screenshots/dev-tools/` in the game's folder and returns their paths. A sequence also returns `sheet`, one image with up to 36 evenly spread frames tiled left to right and top to bottom (`sheetFrames` lists which, when some are left out), `timesMs`, when each frame was taken, and `gameTimes`, the game time in ticks (with the partial tick) each frame drew. Frames are saved in the background, so a sequence doesn't slow the game down. `video` is made with ffmpeg and plays at the speed the frames were captured; an MP4 runs at 60 FPS and a GIF at 30 FPS, at most 960 pixels wide. With `hud=false`, an open chat is closed first; any other open screen still shows and is named in `screen`. `keep` copies the image, or the video and contact sheet, to the evidence folder, and returns `kept` and ready-to-paste `markdown`. See [Images in chat](#images-in-chat). |
| `POST /camera` | `?x=&y=&z=`; optional `&face=north` (default: the side the player is on), `&distance=3` or `&fit=<W>` / `&fit=<W>x<H>`, `&screenshot=true`, `&hud=false`, `&keep=<name>` | Flies the player to look straight at that point from `distance` blocks off the given face, or far enough back that `W` by `H` blocks fill the view. Block coordinates aim at the block's center; decimals aim at that exact point. Waits until the client has moved, and can take a screenshot in the same call. |
| `POST /look` | `?yaw=&pitch=`, `?dx=&dy=` (degrees from the current view), `?x=&y=&z=` or `?entity=<id>` | Turns the player without moving them, and returns the new crosshair target. Block coordinates aim at the block's center; decimals aim at that exact point. |
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
| `POST /fixture` | `?name=board`, `contraption`, `train` or any other fixture; optional `&x=&y=&z=`, `&verbose=true` | Builds a working test setup. See [Fixtures](#fixtures). |
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
in [src/dev/resources/dev-fixtures](src/dev/resources/dev-fixtures), so they're also worked examples. Each JSON file
there is a fixture named after the file, so adding one means adding a file; `devReload` makes it available.

| Name | What you get |
| --- | --- |
| `board` | A powered 4 by 2 flip-dot board facing north, playing scrolling stripes. |
| `contraption` | A 3 by 3 flip-dot board glued to a running mechanical bearing. |
| `train` | Stations Alpha and Beta 50 blocks apart, and an assembled train at Alpha, facing Beta, with a seat, controls and a Blaze Burner conductor. |

A train outlives `clear`: run `create train remove <uuid>` first, with the UUID from `/entities?type=carriage`.

For a new block, add a fixture that builds it working, a class of GameTests next to
[FlipDotTests](src/dev/java/com/zmm2025/createdisplayworks/dev/flipdot/FlipDotTests.java) (see
[GameTests](DEVELOPMENT.md#gametests)), and any dev commands under `/displayworks_dev <block>` in its own class (see
[DevCommands](src/dev/java/com/zmm2025/createdisplayworks/dev/DevCommands.java)).

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
  logging that used a lambda can force a restart (see [Restart rules](#restart-rules)).
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
- Dev-only commands live under `/displayworks_dev`. `/displayworks_dev flipdot animate <pos> stripes|invert|checker`
  plays a generated clip on a board, for render and performance tests.
- To check what the server does with media packets a modified client could send, type
  `/displayworks_dev media packet control <pos> <action>` or
  `/displayworks_dev media packet upload <pos> <columns> <rows>` in chat. These client commands send the packets
  without the media screen and log the server's replies with a `RAW_MEDIA` prefix in the game's `logs/latest.log`.
  The server only accepts them for a display whose screen it opened for that player.
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
