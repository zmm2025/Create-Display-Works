# Publishing checklist

Things to do when the mod first goes on a listing site, and to recheck for each release.

## Disclose the media tools

Media import downloads and runs other programs, so every listing has to say so.

- **Modrinth:** turn on the **External System Interactions** disclosure in the project's settings
  ([Content Disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures)).
- **CurseForge:** there's no disclosure setting, so put the same text in the description. The YouTube import may get a
  manual review, because [YouTube's terms](https://www.youtube.com/static?template=terms) restrict downloading.

Use this text in both descriptions, and keep its versions and sizes up to date:

> The first time you import media, this mod downloads [ffmpeg](https://ffmpeg.org) (about 30 MB, from Maven Central) to
> convert it. The first YouTube link also downloads [yt-dlp](https://github.com/yt-dlp/yt-dlp) and
> [Deno](https://deno.com) (about 60 MB, from their GitHub releases). Each download is checked against a pinned SHA-256
> hash and saved in `create_display_works/media-cache` in your game folder. Nothing is downloaded until you import
> something, dedicated servers never download them, and you can turn them off with the **Media Tools** setting.

## Each release

- Check that the pinned yt-dlp still works with YouTube. It's pinned in `YouTubeImport.java`, and YouTube changes often.
- Update the Create version range in `neoforge.mods.toml` if a new Create release needs it.
