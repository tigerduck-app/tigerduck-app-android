"""Tests for back_merge.py, against a fake gh that answers from a script.

    python3 -m unittest discover -s tools/github
"""

import io
import json
import subprocess
import unittest
from contextlib import redirect_stdout

import back_merge

REPO = "owner/app"


class FakeGh:
    """Stands in for subprocess.run: answers gh calls from [answers], records each call."""

    def __init__(self, ahead_by=2, open_prs=(), fork_prs=(), create_fails=False, compare_fails=False):
        self.ahead_by = ahead_by
        self.open_prs = list(open_prs)
        # Pull requests into dev from a fork's own branch named main.
        self.fork_prs = list(fork_prs)
        self.create_fails = create_fails
        self.compare_fails = compare_fails
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append(args)
        if args[:2] == ["gh", "api"]:
            if self.compare_fails:
                return subprocess.CompletedProcess(args, 1, "", "HTTP 404")
            return subprocess.CompletedProcess(args, 0, json.dumps({"ahead_by": self.ahead_by}), "")
        if args[:3] == ["gh", "pr", "list"]:
            listed = [{"number": n, "isCrossRepository": False} for n in self.open_prs]
            listed += [{"number": n, "isCrossRepository": True} for n in self.fork_prs]
            return subprocess.CompletedProcess(args, 0, json.dumps(listed), "")
        if args[:3] == ["gh", "pr", "create"]:
            if self.create_fails:
                return subprocess.CompletedProcess(args, 1, "", "Resource not accessible by integration")
            return subprocess.CompletedProcess(args, 0, "https://github.com/owner/app/pull/9\n", "")
        raise AssertionError(f"unexpected call {args}")

    def created(self):
        return [c for c in self.calls if c[:3] == ["gh", "pr", "create"]]


def run(fake, tag="v2.4.0"):
    out = io.StringIO()
    with redirect_stdout(out):
        code = back_merge.main(["--repo", REPO, "--tag", tag], run=fake)
    return code, out.getvalue()


class BackMergeTest(unittest.TestCase):

    def test_opens_a_pull_request_from_main_into_dev(self):
        fake = FakeGh(ahead_by=2)
        code, out = run(fake)

        self.assertEqual(0, code)
        [create] = fake.created()
        self.assertEqual("main", create[create.index("--head") + 1])
        self.assertEqual("dev", create[create.index("--base") + 1])
        self.assertIn("v2.4.0", create[create.index("--title") + 1])
        self.assertIn("https://github.com/owner/app/pull/9", out)

    def test_compares_dev_with_main_in_that_order(self):
        fake = FakeGh()
        run(fake)

        [compare] = [c for c in fake.calls if c[:2] == ["gh", "api"]]
        self.assertIn(f"repos/{REPO}/compare/dev...main", compare)

    def test_nothing_to_open_when_dev_has_everything_main_has(self):
        fake = FakeGh(ahead_by=0)
        code, out = run(fake)

        self.assertEqual(0, code)
        self.assertEqual([], fake.created())
        self.assertIn("nothing to merge back", out)

    def test_an_open_one_is_left_as_it_is(self):
        fake = FakeGh(ahead_by=3, open_prs=[7])
        code, out = run(fake)

        self.assertEqual(0, code)
        self.assertEqual([], fake.created())
        self.assertIn("#7", out)

    def test_a_forks_main_is_not_taken_for_the_repos_own(self):
        # gh's --head filters by branch name alone, so a contributor's main lists too.
        fake = FakeGh(ahead_by=3, fork_prs=[12])
        code, out = run(fake)

        self.assertEqual(0, code)
        self.assertEqual(1, len(fake.created()))
        self.assertNotIn("#12", out)

    def test_a_refused_pull_request_warns_without_failing_the_release(self):
        fake = FakeGh(create_fails=True)
        code, out = run(fake)

        # The release is done by then: the job must not turn red over this.
        self.assertEqual(0, code)
        self.assertIn("::warning::", out)
        self.assertIn("Resource not accessible by integration", out)

    def test_a_failed_comparison_warns_and_opens_nothing(self):
        fake = FakeGh(compare_fails=True)
        code, out = run(fake)

        self.assertEqual(0, code)
        self.assertEqual([], fake.created())
        self.assertIn("::warning::", out)


if __name__ == "__main__":
    unittest.main()
