// Fills a PR's Preview section from the evidence its branch uploaded with `./gradlew prEvidence` (see CONTRIBUTING.md).
//
// The evidence is a commit at refs/pr-evidence/branches/<branch> in the repo the PR comes from, holding PREVIEW.md and
// the files it embeds. This pins that commit in this repo as refs/pr-evidence/pulls/<number>, so the files outlive the
// branch and any fork, then puts PREVIEW.md in the PR body with its links pointing at the pinned files.
//
// Runs in .github/workflows/pr-evidence.yml. It never runs the PR's code. To run it by hand:
//   GITHUB_TOKEN=$(gh auth token) GITHUB_REPOSITORY=owner/repo PR_NUMBER=12 node .github/scripts/pr-evidence.mjs

import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const token = process.env.GITHUB_TOKEN;
const base = process.env.GITHUB_REPOSITORY;
const number = Number(process.env.PR_NUMBER);
if (!token || !base || !number) throw new Error("GITHUB_TOKEN, GITHUB_REPOSITORY and PR_NUMBER are required.");

const PREVIEW = "PREVIEW.md";
const START = /<!-- pr-evidence:start sha=([0-9a-f]{40}) -->/;
const BLOCK = /<!-- pr-evidence:start sha=[0-9a-f]{40} -->[\s\S]*?<!-- pr-evidence:end -->/;
const END = "<!-- pr-evidence:end -->";
const DROP_MARKER = /^[ \t]*<!--\s*drop:\s*(.+?)\s*-->[ \t]*$/gm;
const DROP_BLOCK = /<!-- drop (\S+) blob=([0-9a-f]{40}) -->\n([\s\S]*?)\n<!-- \/drop -->/g;
// Text only the placeholder contains, so a drop block without it holds the author's upload.
const DROP_PROMPT = "Replace this line with";
const MAX_BODY = 65536;

async function api(method, path, body) {
  const response = await fetch(`https://api.github.com${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`${method} ${path} failed: ${response.status} ${await response.text()}`);
  return response.status === 204 ? {} : response.json();
}

const encodePath = (path) => path.split("/").map(encodeURIComponent).join("/");

const pr = await api("GET", `/repos/${base}/pulls/${number}`);
if (!pr) throw new Error(`PR #${number} not found in ${base}.`);
const head = pr.head.repo?.full_name;
if (!head) {
  console.log("The PR's source repo was deleted, so there's no evidence to read.");
  process.exit(0);
}
const branchRef = `pr-evidence/branches/${pr.head.ref}`;
const found = await api("GET", `/repos/${head}/git/ref/${encodePath(branchRef)}`);
if (!found) {
  console.log(`${head} has no refs/${branchRef}, so there's nothing to add.`);
  process.exit(0);
}
const sha = found.object.sha;
const body = (pr.body ?? "").replace(/\r\n/g, "\n");
if (body.match(START)?.[1] === sha) {
  console.log(`The Preview already shows ${sha.slice(0, 10)}.`);
  process.exit(0);
}

// Pin the evidence here. A fork's commit has to be fetched and pushed, since this repo can't point a ref at it yet.
const pinRef = `pr-evidence/pulls/${number}`;
if (head.toLowerCase() === base.toLowerCase()) {
  const pinned = await api("GET", `/repos/${base}/git/ref/${pinRef}`);
  if (!pinned) await api("POST", `/repos/${base}/git/refs`, { ref: `refs/${pinRef}`, sha });
  else if (pinned.object.sha !== sha) await api("PATCH", `/repos/${base}/git/refs/${pinRef}`, { sha, force: true });
} else {
  const dir = mkdtempSync(join(tmpdir(), "pr-evidence-"));
  const git = (...args) => execFileSync("git", args, { cwd: dir, stdio: ["ignore", "inherit", "inherit"] });
  try {
    git("init", "--quiet", "--bare");
    git("fetch", "--quiet", "--no-tags", `https://github.com/${head}.git`, `refs/${branchRef}`);
    git("push", "--quiet", "--force", `https://x-access-token:${token}@github.com/${base}.git`, `${sha}:refs/${pinRef}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const tree = await api("GET", `/repos/${base}/git/trees/${sha}?recursive=1`);
const files = new Map(tree.tree.filter((entry) => entry.type === "blob").map((entry) => [entry.path, entry.sha]));
if (!files.has(PREVIEW)) {
  console.log(`The evidence at ${sha.slice(0, 10)} has no ${PREVIEW}, so there's nothing to add.`);
  process.exit(0);
}
const previewBlob = await api("GET", `/repos/${base}/git/blobs/${files.get(PREVIEW)}`);
const preview = Buffer.from(previewBlob.content, "base64").toString("utf8").replace(/\r\n/g, "\n").trim();

const rawUrl = (path) => `https://raw.githubusercontent.com/${base}/${sha}/${encodePath(path)}`;
const evidencePath = (target) => {
  let path = target.replace(/^<|>$/g, "").replace(/^\.\//, "");
  try {
    path = decodeURIComponent(path);
  } catch {}
  return files.has(path) && path !== PREVIEW ? path : null;
};

// Uploads the author already dragged into a drop block stay, unless the file they replace has changed since.
const kept = new Map();
for (const [, name, blob, content] of body.match(BLOCK)?.[0].matchAll(DROP_BLOCK) ?? []) {
  if (!content.includes(DROP_PROMPT)) kept.set(`${decodeURIComponent(name)} ${blob}`, content);
}

const rendered = preview
  .replace(DROP_MARKER, (marker, target) => {
    const path = evidencePath(target);
    if (!path) return marker;
    const blob = files.get(path);
    const name = path.split("/").pop();
    const content =
      kept.get(`${path} ${blob}`) ??
      `▶️ **${DROP_PROMPT} \`${name}\`** by dragging the file into the editor, so it plays here. [Download it](${rawUrl(path)})`;
    return `<!-- drop ${encodeURIComponent(path)} blob=${blob} -->\n${content}\n<!-- /drop -->`;
  })
  .replace(/(\]\(\s*)(<[^>]+>|[^)\s<]+)/g, (link, open, target) => {
    const path = evidencePath(target);
    return path ? `${open}${rawUrl(path)}` : link;
  })
  .replace(/\b(src|href)(\s*=\s*)(["'])([^"']+)\3/g, (attribute, name, equals, quote, target) => {
    const path = evidencePath(target);
    return path ? `${name}${equals}${quote}${rawUrl(path)}${quote}` : attribute;
  });

const block = [
  `<!-- pr-evidence:start sha=${sha} -->`,
  rendered,
  "",
  `<sub>[All evidence files](https://github.com/${base}/tree/${sha}) · uploaded with \`./gradlew prEvidence\`</sub>`,
  END,
].join("\n");

let updated;
if (BLOCK.test(body)) {
  updated = body.replace(BLOCK, () => block);
} else if (/^## Preview[ \t]*$/m.test(body)) {
  // Replace whatever the PR's author or generator wrote under the heading, up to the next heading of the same level.
  updated = body.replace(/^(## Preview[ \t]*\n)[\s\S]*?(?=^## |(?![\s\S]))/m, (_, heading) => `${heading}\n${block}\n\n`);
} else if (/^## Testing[ \t]*$/m.test(body)) {
  updated = body.replace(/^## Testing[ \t]*$/m, (heading) => `## Preview\n\n${block}\n\n${heading}`);
} else {
  updated = `${body.trimEnd()}\n\n## Preview\n\n${block}\n`;
}
if (updated.length > MAX_BODY) {
  throw new Error(`The PR body would be ${updated.length} characters, over GitHub's ${MAX_BODY}. Shorten ${PREVIEW}.`);
}
await api("PATCH", `/repos/${base}/pulls/${number}`, { body: updated });
console.log(`Filled PR #${number}'s Preview from ${head} ${sha.slice(0, 10)}.`);
