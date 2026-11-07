# Publishing checklist

Things to do when the mod first goes on a listing site, and to recheck for each release.

## Disclose the media tools

Media import downloads and runs other programs, and YouTube imports also check a version list on GitHub, so every
listing has to say so.

- **Modrinth:** turn on the **External System Interactions** disclosure in the project's settings
  ([Content Disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures)).
- **CurseForge:** there's no disclosure setting, so put the same text in the description. The YouTube import may get a
  manual review, because [YouTube's terms](https://www.youtube.com/static?template=terms) restrict downloading.

Use this text in both descriptions, and keep its versions and sizes up to date:

> The first time you import media, this mod downloads [ffmpeg](https://ffmpeg.org) (about 30 MB, from Maven Central) to
> convert it. The first YouTube link also downloads [yt-dlp](https://github.com/yt-dlp/yt-dlp) and
> [Deno](https://deno.com) (60 to 80 MB, from their GitHub releases). So that YouTube keeps working between mod
> releases, YouTube imports check a short version list in
> [this mod's GitHub repository](https://github.com/zmm2025/Create-Display-Works/blob/main/media-tools.json) at most
> every few hours, and offer to update yt-dlp when a newer one is listed. That list can only point at the official
> yt-dlp and Deno releases. Each download is checked against its SHA-256 hash and saved in
> `create_display_works/media-cache` in your game folder. The mod asks before every download, dedicated servers never
> download them, and you can turn all of this off with the **Media Tools** setting.

## Each release

- Merge any open **Update yt-dlp** PR first, so the release's built-in list is current. The jar bundles
  `media-tools.json` as the fallback for players who can't reach GitHub.
- Update the Create version range in `neoforge.mods.toml` if a new Create release needs it.

## The media tool list

[media-tools.json](media-tools.json) names the yt-dlp and Deno release YouTube imports use on each platform, with each
download's URL, SHA-256 and size. Released mods fetch it from `main` (`MediaToolList.SOURCE`), so a merged change
reaches players without a mod release. They ignore a list they can't fetch or that fails validation, and keep the
release they have installed, or else use the list built into their jar.

- The [Media tools workflow](.github/workflows/media-tools.yml) checks for a new yt-dlp every day. It takes each hash
  from the release's `SHA2-256SUMS`, downloads every file to check it, runs the Linux build, and opens or updates a
  PR from `chore/media-tools-update`. Before merging, try a YouTube import with that branch's list if you can.
- Deno only changes when you run the workflow by hand with a Deno version (or `latest`), because yt-dlp rarely needs
  a newer one and each update is a 40 MB download for players.
- The workflow needs **Allow GitHub Actions to create and approve pull requests** turned on in **Settings > Actions >
  General**. Without it, the run fails with a link to open the PR yourself.
- PRs opened by the workflow don't start the Build workflow, so the update run tests the list itself.
- Released mods only accept `"schema": 1` at this path. A change they can't read needs a new file, with this one kept
  up to date until those versions are gone. Downloads must stay on `github.com/yt-dlp/yt-dlp/releases/download/` and
  `github.com/denoland/deno/releases/download/`.
