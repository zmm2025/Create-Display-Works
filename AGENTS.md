# Agent instructions

The user tests changes in a live Minecraft dev client started with `./gradlew runDevClient` (the **Launch Minecraft**
script in T3 Code). Each worktree runs its own game, with its own copy of the test world, so this worktree's game is
yours to use. [DEVELOPMENT.md](DEVELOPMENT.md) explains the setup.

## When you finish a turn

Once all of a turn's changes under `src/` are complete, run `./gradlew devReload` from this worktree as the last
step before your final reply, even if you think nothing loaded at runtime changed. Run it once, not after
intermediate edits: if you're still going to change code, or the build or tests fail and you're fixing them, wait.
Then end your reply with one short line that matches the result:

| `devReload` output | Closing line |
| --- | --- |
| `DEV RELOAD: RELOADED` | ✅ **Your Minecraft client has been reloaded.** |
| `DEV RELOAD: NO CHANGES` | ✅ **No reload or restart needed.** |
| `DEV RELOAD: NO SESSION` | 🚀 **Launch Minecraft to try these changes.** |
| `DEV RELOAD: RESTART REQUIRED` | ⚠️ **Restart Minecraft to try these changes.** followed by the reasons it printed, in plain words and not in bold. |
| `DEV RELOAD: ERROR` | Explain what went wrong. |

`devReload` can't see every restart-only change. Use the restart line, with the reason in plain words, even if it
printed `RELOADED`, when you:

- register anything new: blocks, items, block entities, menus, entities, sounds, data components, creative tabs or
  network payloads
- change code that only runs during startup, setup or registration events, such as renderer, model, key mapping,
  config spec or Ponder registration
- change mixins, mod metadata, Gradle files or dependencies

If hot-swapped code only affects new objects or newly opened screens, tell the user to re-place the block or reopen
the screen.

Never tell the user to press F3+T or run `/reload`. Point them to the **Reload Minecraft** script button instead,
which runs `./gradlew devReload`. In the main checkout, don't launch, close or restart the game unless the user asks,
because it holds their main world. In a worktree, you may start its game with `devStart` (below) and leave it
running; don't close or restart it while the user has its window shown.

## Driving the game

You control the `Dev` player through the game's localhost API. Use it to build test setups, verify behaviour and
capture demos. Endpoints are listed under "Agent control API" in DEVELOPMENT.md.

- To start this worktree's game, run `./gradlew devStart -PthreadId=<your T3 Code thread id>` (T3's thread tools give
  the current thread's id). It opens a `minecraft` terminal in your thread, starts the game with its window hidden so
  it doesn't disturb the user, and returns once the game is in its world, printing the control API's port. If the
  game is already running, it just prints the port. Don't start it with `runDevClient`, which shows the window, or
  in a background shell command, which the user can't see.
- The port is also in `build/dev-client.json`. Check that `/status` reports this worktree before trusting a result.
- The window stays hidden unless the user shows it. Don't call `/window?show=true` yourself.


- `/command` runs commands as the player.
- `/use`, `/attack`, `/key`, `/type` and `/click` send input.
- `/status` and `/screenshot` let you check the result. Open the PNG it returns to look at it.
- For anything that moves or changes over time, such as animations, rotating parts, flip-dot transitions or media
  playback, capture a frame sequence with `/screenshot?frames=<n>&intervalMs=<ms>` and open the contact sheet it
  returns. One still image can't show motion. Choose the interval from the effect's speed, for example 50-100 ms
  for a 10 FPS animation, and use `timesMs` for the actual capture times.
- Delete screenshots and frame folders you no longer need from the `screenshots/dev-tools/` folder next to the PNGs.
- `/sounds` shows what the game plays and how loud it reaches the player. Use it instead of recording audio.
- `/blockentity` reads a block entity's fields on the client and server, and can sample them every frame. Use it
  instead of adding temporary debug logging.
- Send several commands as one JSON array to `/command`, with `waitTicks` or `waitUntil` steps instead of `sleep`.
  Use `/find` to locate blocks instead of scanning coordinates one command at a time.
- `/camera` flies the player to face a block or fit a whole display in view, and can screenshot in the same call. Use it
  instead of guessing teleport coordinates.
- `/perf` measures frame times and the mod's render cost. Use it to check that large or many displays stay fast.
- Add `video=mp4` or `video=gif` to a frame sequence when the user wants a demo. "Testing tips" in DEVELOPMENT.md
  has more shortcuts.

Don't use computer use for this. Keep these rules in mind:

- A worktree's world starts as a copy of the user's main world. Build fixtures away from existing builds, and only
  clear areas you created; the user may open this world to look at your work.
- Prefer verifying with `/status`, command output (`execute if block ...`, `data get ...`) and screenshots before you
  claim something works in-game.
- In PowerShell, call `curl.exe`, not `curl`.

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

- Before your first edit, capture the before state, while the game still runs the old code.
- Pick whatever shows the change best, in any format:
  - before and after screenshots for how something looks
  - a GIF (`/screenshot?...&video=gif`) for short motion, since it plays inline
  - an MP4 (`&video=mp4`) for longer or smoother motion, marked `<!-- drop: <file> -->`, since GitHub only plays
    videos the user drags in
  - links for anything else, such as changed sound files, `/sounds` or `/perf` output, or logs
  - nothing, when there's nothing to see
- The control API records no audio. If hearing a sound matters, say so in your reply, so the user can record it.
- Copy the files you choose from the game's `screenshots/dev-tools/` into `build/pr-evidence/`, write its
  `PREVIEW.md`, and run `./gradlew prEvidence` before your final reply. Rerun it whenever later work changes what the
  evidence shows, and remove files that no longer apply.
- If the evidence has drop files, add a short note before your closing line that lists them, linked to their files in
  `build/pr-evidence/`, so the user can drag them into the PR once it's open.
- Settle the branch name (see [Branch names](#branch-names)) before the first upload. After renaming the branch, run
  `prEvidence` again.
