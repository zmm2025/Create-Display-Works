# Contributing

[DEVELOPMENT.md](DEVELOPMENT.md) explains how to build the mod and test it in the dev client. Coding agents should
also follow [AGENTS.md](AGENTS.md).

## Pull requests

- Keep each PR to one change, and describe it with the [PR template](.github/pull_request_template.md): a summary,
  a preview and how you tested it.
- Never commit screenshots, recordings or other PR-only files to your branch. Upload them as PR evidence instead.

## PR evidence

A change you can see or hear in game needs evidence, so reviewers, and anyone looking back at the PR later, can see
what it does without building it. Show it however works best: before and after screenshots, a GIF of an animation, a
video, a sound, a log. Any file type works.

Evidence is stored in git outside every branch, at `refs/pr-evidence/branches/<your branch>` in the repo you push
to. It never appears in a branch, a release or anyone's clone. When your PR opens, or gets new commits, the
[PR evidence workflow](.github/workflows/pr-evidence.yml) copies your evidence into this repo, so it stays available
even if your branch or fork is deleted, and puts it in the PR's **Preview** section.

### Uploading it

1. Put the files in `build/pr-evidence/` with a `PREVIEW.md` that shows them, using paths relative to that folder:

   ```markdown
   | Before | After |
   | --- | --- |
   | ![Before](before.png) | ![After](after.png) |

   ![The display flipping to the new text](flip.gif)

   <!-- drop: flip-with-sound.mp4 -->

   Frame times: [perf.json](perf.json)
   ```

2. Run `./gradlew prEvidence`. It checks that every file `PREVIEW.md` names is in the folder, then uploads the folder
   as your branch's evidence. It needs `git`, and pushes to the remote your branch pushes to.
3. To change the evidence, edit the folder and run it again. Each upload replaces the previous one. If the folder was
   cleaned, `./gradlew prEvidence -Pfetch` downloads the current evidence into it first.

Upload under the branch's final name: after renaming a branch, run `prEvidence` again.

If the PR is already open, `prEvidence` also asks the workflow to refresh the Preview, which needs the
[GitHub CLI](https://cli.github.com) and write access to this repo. Otherwise the Preview updates on your next push.

### How each file type shows up

GitHub decides how files display in a PR:

- **Images and GIFs** show inline. Prefer a GIF for short motion.
- **Videos** only play inline if they're dragged into the PR in the browser. Mark each one with a
  `<!-- drop: <file> -->` line in `PREVIEW.md`, and the Preview gets a placeholder with a download link in its place.
  Once the PR is open, edit its description and drag the video in to replace the placeholder line. Your upload stays
  through later refreshes until that video file changes. GitHub's drag-and-drop limit is 10 MB on free plans.
- **Everything else**, such as sounds, logs and data, appears as a link. The Preview also links to a page listing
  every uploaded file.

Files can be up to 100 MB.

### Without the Gradle task

You can always drag screenshots and recordings into the PR's Preview section yourself instead. The workflow only
changes the Preview when the branch has uploaded evidence.
