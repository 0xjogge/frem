import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import {
  generateMarkDown,
  getGitDiff,
  loadChangelogConfig,
  parseCommits,
} from "changelogen";

const [tag, ref = tag, previous = ""] = process.argv.slice(2);
const tagPattern = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-release$/;
if (!tagPattern.test(tag || "") || (previous && !tagPattern.test(previous))) {
  throw new Error(
    "Usage: release-notes.mjs <feature>.<patch>-release [HEAD|tag] [previous-tag]",
  );
}
if (ref !== "HEAD" && ref !== tag) {
  throw new Error("The release reference must be HEAD or the release tag");
}
execFileSync("git", ["rev-parse", "--verify", `${ref}^{commit}`]);

const config = await loadChangelogConfig(process.cwd(), {
  repo: "0xjogge/frem",
  from: previous || ref,
  to: ref,
  newVersion: tag,
  templates: { tagBody: "{{newVersion}}" },
  noAuthors: true,
  hideAuthorEmail: true,
});
// Explicit ranges also handle the first release and Frem's two-part version scheme.
config.from = previous;
const commits = parseCommits(
  await getGitDiff(previous || undefined, ref),
  config,
);
// Contributor information comes from Git, without external email-lookup services.
let markdown = await generateMarkDown(
  commits.map((commit) => ({ ...commit, author: undefined })),
  config,
);
const contributors = new Map();
for (const commit of commits) {
  for (const author of commit.authors) {
    if (!author.name || author.name.includes("[bot]")) continue;
    const username = author.email?.match(
      /^(?:\d+\+)?([^@]+)@users\.noreply\.github\.com$/,
    )?.[1];
    const name = author.name.replace(/[\[\]<>\r\n]/g, "");
    contributors.set(
      author.email || name,
      username
        ? `- ${name} ([@${username}](https://github.com/${username}))`
        : `- ${name}`,
    );
  }
}
if (contributors.size) {
  markdown += `\n\n### ❤️ Contributors\n\n${[...contributors.values()].join("\n")}`;
}
await mkdir("build", { recursive: true });
await writeFile("build/release-notes.md", `${markdown}\n`);
console.log(markdown);
