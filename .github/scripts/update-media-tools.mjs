// Updates media-tools.json, the list of yt-dlp and Deno releases that YouTube imports download (see PUBLISHING.md).
//
// The mod fetches that file from main, so a new yt-dlp reaches players once the update PR is merged. For each download
// this takes the hash from the release's own checksum file, downloads the file, and checks that it matches. On Linux
// it also runs the yt-dlp it downloaded, to make sure it reports the version the list names.
//
// Runs in .github/workflows/media-tools.yml. To run it by hand:
//   node .github/scripts/update-media-tools.mjs                   newest yt-dlp, Deno unchanged
//   YT_DLP=2026.10.26 DENO=latest node .github/scripts/update-media-tools.mjs
// Set GITHUB_TOKEN to avoid GitHub's API rate limit for anonymous requests.

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { appendFileSync, chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const LIST = "media-tools.json";
// The format the mod understands. A change older mods can't read needs a new file, not a new schema in this one.
const SCHEMA = 1;
// Keys use the mod's platform names (MediaDecoder.platform()); only platforms that have an ffmpeg build are listed.
const YT_DLP_ASSETS = {
  "windows-x86_64": "yt-dlp.exe",
  "linux-x86_64": "yt-dlp_linux",
  "linux-arm64": "yt-dlp_linux_aarch64",
  "macosx-x86_64": "yt-dlp_macos",
  "macosx-arm64": "yt-dlp_macos",
};
const DENO_TARGETS = {
  "windows-x86_64": "x86_64-pc-windows-msvc",
  "linux-x86_64": "x86_64-unknown-linux-gnu",
  "linux-arm64": "aarch64-unknown-linux-gnu",
  "macosx-x86_64": "x86_64-apple-darwin",
  "macosx-arm64": "aarch64-apple-darwin",
};
const HASH = /\b[0-9a-f]{64}\b/i;

const token = process.env.GITHUB_TOKEN;
const work = mkdtempSync(join(tmpdir(), "media-tools-"));

async function get(url, accept) {
  const headers = { "User-Agent": "Create-Display-Works media-tools update" };
  if (accept) headers.Accept = accept;
  if (token && url.startsWith("https://api.github.com/")) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(url, { headers, redirect: "follow" });
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response;
}
async function release(repo, tag) {
  const path = tag === "latest" ? "releases/latest" : `releases/tags/${tag}`;
  const json = await (await get(`https://api.github.com/repos/${repo}/${path}`, "application/vnd.github+json")).json();
  if (json.draft || json.prerelease) throw new Error(`${repo} ${json.tag_name} is not a full release.`);
  return json;
}
/** Downloads a release file and returns its size and SHA-256, after checking the hash against the release's own. */
const downloaded = new Map();
async function verify(url, expected) {
  if (!downloaded.has(url)) {
    const bytes = Buffer.from(await (await get(url)).arrayBuffer());
    const file = join(work, url.split("/").pop());
    writeFileSync(file, bytes);
    downloaded.set(url, { file, size: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") });
  }
  const result = downloaded.get(url);
  if (result.sha256 !== expected) throw new Error(`${url} has SHA-256 ${result.sha256}, but its release says ${expected}.`);
  return result;
}
/** GitHub records its own digest for newer release assets. When it does, it must agree with the checksum file. */
function checkDigest(json, name, sha256) {
  const digest = json.assets.find((asset) => asset.name === name)?.digest;
  if (digest && digest !== `sha256:${sha256}`) throw new Error(`${name}: GitHub reports ${digest}, the checksum file ${sha256}.`);
}

async function ytDlp(tag) {
  const json = await release("yt-dlp/yt-dlp", tag);
  const version = json.tag_name;
  if (!/^\d{4}\.\d{2}\.\d{2}(\.\d+)?$/.test(version)) throw new Error(`Unexpected yt-dlp tag ${version}.`);
  const base = `https://github.com/yt-dlp/yt-dlp/releases/download/${version}/`;
  const sums = new Map();
  for (const line of (await (await get(base + "SHA2-256SUMS")).text()).split("\n")) {
    const [hash, name] = line.trim().split(/\s+/);
    if (hash && name && HASH.test(hash)) sums.set(name, hash.toLowerCase());
  }
  const downloads = {};
  for (const [platform, asset] of Object.entries(YT_DLP_ASSETS)) {
    const sha256 = sums.get(asset);
    if (!sha256) throw new Error(`yt-dlp ${version}'s SHA2-256SUMS has no ${asset}.`);
    checkDigest(json, asset, sha256);
    const { size } = await verify(base + asset, sha256);
    downloads[platform] = { url: base + asset, sha256, size };
  }
  if (process.platform === "linux" && process.arch === "x64") {
    const { file } = downloaded.get(base + "yt-dlp_linux");
    chmodSync(file, 0o755);
    const reported = execFileSync(file, ["--ignore-config", "--version"], { encoding: "utf8" }).trim();
    if (reported !== version) throw new Error(`The downloaded yt-dlp reports ${reported}, not ${version}.`);
  }
  return { version, downloads };
}

async function deno(tag) {
  const json = await release("denoland/deno", tag === "latest" ? "latest" : `v${tag}`);
  const version = json.tag_name.replace(/^v/, "");
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error(`Unexpected Deno tag ${json.tag_name}.`);
  const base = `https://github.com/denoland/deno/releases/download/v${version}/`;
  const downloads = {};
  for (const [platform, target] of Object.entries(DENO_TARGETS)) {
    const asset = `deno-${target}.zip`;
    // Deno publishes one checksum file per download, in sha256sum or PowerShell Get-FileHash format.
    const sha256 = (await (await get(`${base}${asset}.sha256sum`)).text()).match(HASH)?.[0].toLowerCase();
    if (!sha256) throw new Error(`Deno ${version} has no readable checksum for ${asset}.`);
    checkDigest(json, asset, sha256);
    const { size } = await verify(base + asset, sha256);
    downloads[platform] = { url: base + asset, sha256, size };
  }
  return { version, downloads };
}

try {
  let current = null;
  try {
    current = JSON.parse(readFileSync(LIST, "utf8"));
  } catch {}
  const wantYtDlp = process.env.YT_DLP || "latest";
  // Deno only changes on request: yt-dlp rarely needs a newer one, and each update is a 40 MB download for players.
  const wantDeno = process.env.DENO || current?.tools?.deno?.version;
  if (!wantDeno) throw new Error("No Deno version in media-tools.json; set DENO.");

  const tools = {
    "yt-dlp": current?.tools?.["yt-dlp"]?.version === wantYtDlp ? current.tools["yt-dlp"] : await ytDlp(wantYtDlp),
    deno: current?.tools?.deno?.version === wantDeno ? current.tools.deno : await deno(wantDeno),
  };
  const next = JSON.stringify({ schema: SCHEMA, tools }, null, 2) + "\n";
  const changed = next !== (current ? JSON.stringify(current, null, 2) + "\n" : "");
  if (changed) writeFileSync(LIST, next);

  const names = [];
  for (const [tool, label] of [["yt-dlp", "yt-dlp"], ["deno", "Deno"]]) {
    if (tools[tool].version !== current?.tools?.[tool]?.version) names.push(`${label} ${tools[tool].version}`);
  }
  const title = names.length ? `Update ${names.join(" and ")}` : "Update media tool list";
  console.log(changed ? `${title}: wrote ${LIST}.` : `${LIST} is up to date (yt-dlp ${tools["yt-dlp"].version}, Deno ${tools.deno.version}).`);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `changed=${changed}\ntitle=${title}\nyt_dlp=${tools["yt-dlp"].version}\n`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
