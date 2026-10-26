#!/usr/bin/env python3
"""Open the pull request that merges main back into dev after a release.

A release leaves main with commits dev lacks: the F-Droid metadata pin the
release workflow pushes, and any fix that went to main on its own. Left there,
the next release pull request conflicts on the metadata file. So after a
release this opens one pull request from main into dev, unless dev already has
everything main has or such a pull request is already open, and main comes
back to dev through the same checks as any other change.

Calls the gh CLI, which reads GH_TOKEN. Use a token whose pull requests start
workflows, such as the Releaser App's: one opened with GITHUB_TOKEN starts no
CI run. The release is done by the time this runs, so a failure here is a
warning, never a failed job.
"""

import argparse
import json
import subprocess
import sys

BASE = "dev"
HEAD = "main"


def gh(run, *args):
    """Runs `gh args...`; the completed process."""
    return run(["gh", *args], capture_output=True, text=True)


def warn(message: str) -> int:
    print(f"::warning::{message}")
    return 0


def main(argv=None, run=subprocess.run) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--tag", required=True, help="the release just published, e.g. v2.4.0")
    args = parser.parse_args(argv)

    compare = gh(run, "api", f"repos/{args.repo}/compare/{BASE}...{HEAD}")
    if compare.returncode != 0:
        return warn(f"Could not compare {BASE} with {HEAD}: {compare.stderr.strip()}. "
                    f"Merge {HEAD} back into {BASE} by hand.")
    if json.loads(compare.stdout)["ahead_by"] == 0:
        print(f"{BASE} already has everything on {HEAD}; nothing to merge back.")
        return 0

    # --head matches the branch name alone, so a fork's own main lists too.
    listed = gh(run, "pr", "list", "--repo", args.repo, "--base", BASE, "--head", HEAD,
                "--state", "open", "--json", "number,isCrossRepository")
    if listed.returncode == 0:
        already = [pr for pr in json.loads(listed.stdout) if not pr["isCrossRepository"]]
        if already:
            print(f"#{already[0]['number']} already merges {HEAD} back into {BASE}; leaving it as it is.")
            return 0

    body = (
        f"{args.tag} is released. This brings what the release left on `{HEAD}` back to "
        f"`{BASE}`: the F-Droid metadata pin, and any fix that went to `{HEAD}` on its own. "
        f"Without it the next release pull request conflicts on the metadata file.\n\n"
        f"Merge it with a merge commit, not a squash: `{HEAD}`'s commits must stay the same "
        f"commits on `{BASE}`."
    )
    created = gh(run, "pr", "create", "--repo", args.repo, "--base", BASE, "--head", HEAD,
                 "--title", f"chore(release): merge {HEAD} back into {BASE} after {args.tag}",
                 "--body", body)
    if created.returncode != 0:
        return warn(f"Could not open the pull request merging {HEAD} back into {BASE}: "
                    f"{created.stderr.strip()}. Open it by hand.")
    print(f"Opened {created.stdout.strip()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
