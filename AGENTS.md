# Agent instructions

The user tests changes in a live Minecraft dev client started with `./gradlew runDevClient` (the **Launch Minecraft**
script in T3 Code). [DEVELOPMENT.md](DEVELOPMENT.md) explains the setup.

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
| `DEV RELOAD: ERROR` | Explain what went wrong. If the game was launched from another worktree, say so: the user has to close it and press **Launch Minecraft** in this worktree to try these changes. |

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
which runs `./gradlew devReload`. Don't launch, close or restart the user's game unless they ask.

## Driving the game

While the dev client runs, you can control the `Dev` player through its localhost API on port 25595. Use it to
build test setups, verify behaviour and capture demos. First check that the `worktree` reported by `/status` is this
worktree. If it isn't, the game is running another worktree's code, so don't use it to verify your changes.
Endpoints are listed under "Agent control API" in DEVELOPMENT.md:

- `/command` runs commands as the player.
- `/use`, `/attack`, `/key`, `/type` and `/click` send input.
- `/status` and `/screenshot` let you check the result. Open the PNG it returns to look at it.
- For anything that moves or changes over time, such as animations, rotating parts, flip-dot transitions or media
  playback, capture a frame sequence with `/screenshot?frames=<n>&intervalMs=<ms>` and open the contact sheet it
  returns. One still image can't show motion. Choose the interval from the effect's speed, for example 50-100 ms
  for a 10 FPS animation, and use `timesMs` for the actual capture times.
- Delete screenshots and frame folders you no longer need from `run/screenshots/dev-tools/`.

Don't use computer use for this. Keep these rules in mind:

- The world is the user's persistent test world. Build fixtures away from existing builds, and only clear areas you
  created.
- Prefer verifying with `/status`, command output (`execute if block ...`, `data get ...`) and screenshots before you
  claim something works in-game.
- In PowerShell, call `curl.exe`, not `curl`.
