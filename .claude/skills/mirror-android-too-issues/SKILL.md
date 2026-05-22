---
name: mirror-android-too-issues
description: Use when the user asks to mirror, sync, copy, or check the upstream iOS repo for issues labelled "Android too" and add them to the Android repo (e.g. "scan the Apple repo for Android too issues", "mirror the Android too tab", "sync upstream issues to the Android side"). Skips issues already tracked here, including semantic duplicates worded differently. Each mirrored issue gets a Claude analysis comment.
---

# Mirror "Android too" Issues

The upstream iOS repo **`tigerduck-app/tigerduck-app`** tags issues that
also apply to Android with the label **`Android too`**. This skill copies
those into **`tigerduck-app-android`** (this repo), one local issue per
upstream issue, each carrying a Claude codebase-analysis comment.

**Never mirror an issue that is already tracked here** — including issues
whose wording differs but which describe the same bug/feature.

## Required workflow (do every step in order)

### 1. Confirm scope

Upstream `Android too` issues come in open and closed states. Closed ones
are usually already resolved on iOS. Ask the user whether to mirror **open
only** (the usual choice) or **open + closed**, unless they already said.

### 2. Pull both issue lists

```bash
# Upstream candidates
gh issue list --repo tigerduck-app/tigerduck-app \
  --label "Android too" --state all --limit 100 \
  --json number,title,body,labels,state,url

# Everything already in this repo (open AND closed — closed counts as a dup)
gh issue list --repo tigerduck-app/tigerduck-app-android \
  --state all --limit 200 --json number,title,body,labels,state
```

Filter the upstream list to the state(s) the user picked in step 1.

### 3. Deduplicate — this is the judgment step

For each upstream candidate, scan **every** existing Android issue (open
and closed) and skip the candidate if any of these is true:

- An Android issue body already says `Mirrored from upstream …#<that number>`.
- An Android issue describes the **same underlying bug/feature**, even with
  different wording or language. Compare *meaning*, not string match —
  e.g. upstream "新增衝堂課程時需另外處理" vs a local "conflict course add
  bug" are the same thing.

When in doubt whether two issues are the same, list the pair for the user
and ask rather than creating a duplicate.

### 4. Investigate the Android codebase

Before writing anything, grep this repo for the feature each surviving
candidate touches — find the real files, current state, and whether
Android already implements it (Android is sometimes ahead of iOS).

zsh gotcha: quote glob args — `grep -rln -i 'foo' --include='*.kt' .`
(unquoted `--include=*.kt` makes zsh error "no matches found").

### 5. Create the mirrored issue

```bash
gh issue create --repo tigerduck-app/tigerduck-app-android \
  --title '<upstream title>' --label '<mapped labels>' \
  --body-file /tmp/issue-body.md
```

- **Title**: keep the upstream title. If it is phrased from the iOS point
  of view (e.g. "與 Android 統一…"), reword the direction for the Android
  side ("與 iOS 統一…") and explain in the body.
- **Body** (`--body-file`, not inline — avoids shell-escaping pain):
  ```
  Mirrored from upstream **tigerduck-app/tigerduck-app#<N>** (labelled `Android too`).

  > <upstream body, quoted; keep images/attachment URLs intact>
  ```
- **Labels**: map upstream → this repo. This repo has `bug`, `enhancement`,
  `security` (run `gh label list` to confirm). Drop labels that don't
  exist here (e.g. upstream `language`); don't invent new ones.

### 6. Add the analysis comment

Post one comment per mirrored issue (`gh issue comment <N> --body-file …`).
Reference style: see issue #66's comment in this repo. Structure:

```
**By Claude — executed by <git user>.**

## Applies to Android?
Yes / Partially / Mostly no — and WHY. Call out if Android is already
ahead of the upstream screenshot.

## Files
- `path/to/File.kt:line` — what it does, why it's relevant.
  (Real paths from step 4, not guesses.)

## Suggested approach
Concrete options, ordered by effort. Respect repo invariants from
CLAUDE.md — especially the play/fdroid flavor split for anything
GMS/Firebase-related, and the upgrade-safe-persistence rule for data
models.

## Side effects
Edge cases, things that break, cross-cutting concerns (e.g. widget
bitmaps must stay in sync with in-app cells).
```

`<git user>` = `git config user.name`.

### 7. Report

Print a table: new Android issue # → upstream # → topic, plus anything
skipped as a duplicate and why.

## Quick reference

| Thing | Value |
|---|---|
| Upstream repo | `tigerduck-app/tigerduck-app` |
| This repo | `tigerduck-app/tigerduck-app-android` |
| Upstream label | `Android too` |
| Body marker (for dedup) | `Mirrored from upstream …#<N>` |
| Comment prefix | `**By Claude — executed by <git user>.**` |

## Common mistakes

- **String-matching titles for dedup.** Same feature, different wording =
  still a duplicate. Compare meaning.
- **Mirroring closed-upstream issues without asking.** Confirm scope first.
- **Inline `--body` with CJK/HTML.** Use `--body-file`.
- **Guessing file paths in the comment.** Grep the repo (step 4) first.
- **Applying a label that doesn't exist here.** Check `gh label list`.
- **Ignoring that Android may already implement it.** Say so in the comment
  instead of proposing net-new work.
