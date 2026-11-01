"""Tests for the autopilot decision engine, guard, supervisor and setup (run in CI's Python tool tests)."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import guard  # noqa: E402
import next_action as na  # noqa: E402
import setup_clone  # noqa: E402
import supervisor  # noqa: E402
from common import CHECKS, CLONES, ENV, INTEGRATION, PROJECT, SLUG, STATE, marker  # noqa: E402

LINE_SEP = chr(10)
T0, T1, T2 = "2026-09-21T10:00:00Z", "2026-09-21T11:00:00Z", "2026-09-21T12:00:00Z"
APP = "android/app/src/main/java/org/meshline/app"


def pr(number, head, last=T1, ci="success", mergeable="MERGEABLE", verdicts=None, draft=False):
    lane, contract = na.pr_lane(head)
    return na.PR(number, f"PR {number}", head, lane, contract, draft, mergeable, ci, last, verdicts or {})


def task(tid, milestone="M0", status="todo", needs=(), **extra):
    return {"id": tid, "title": f"Task {tid}", "lane": tid[0], "milestone": milestone,
            "status": status, "needs": list(needs), **extra}


def state(prs=(), tasks=(), branches=()):
    return {"prs": list(prs), "tasks": {t["id"]: t for t in tasks}, "branches": list(branches)}


class ConfigTest(unittest.TestCase):
    """Everything project-specific comes from autopilot.toml."""

    def test_meshline_values(self):
        self.assertEqual((PROJECT, SLUG, ENV), ("MeshLine", "meshline", "MESHLINE"))
        self.assertEqual(CLONES["A"].name, "MeshLine-claude")
        self.assertEqual(CLONES["B"].name, "MeshLine-gemini")
        self.assertEqual(STATE.name, "MeshLine-agents")
        self.assertEqual(marker("claude", "lgtm"), "<!-- meshline:agent=claude verdict=lgtm -->")
        self.assertTrue(any("meshline-sim" in c for c in CHECKS))
        self.assertTrue(any("gradlew" in c for c in CHECKS))

    def test_no_other_project_leaks_into_the_autopilot(self):
        here = Path(__file__).resolve().parent
        for path in here.rglob("*"):
            if path.is_file() and path.suffix in (".py", ".json", ".md", ".toml", ".template", "") \
                    and path.name != Path(__file__).name and "__pycache__" not in path.parts:
                text = path.read_text(encoding="utf-8", errors="replace")
                # Mentions of the reference project in prose are fine; its paths, markers, git key,
                # env vars or repo would mean a value escaped autopilot.toml.
                self.assertNotRegex(text, r"(?i)CDR-(?:claude|gemini|agents)|rekha:agent|rekha\.lane|REKHA_"
                                          r"|ajose369/rekha|rekha-autopilot", str(path))


class DecideTest(unittest.TestCase):
    def test_priority_ci_failure_beats_everything(self):
        s = state([pr(1, "claude/A-1-x", ci="failure"), pr(2, "gemini/B-1-y")])
        self.assertEqual(na.decide(s, "A")["kind"], "FIX_CI")

    def test_conflict_means_rebase(self):
        self.assertEqual(na.decide(state([pr(1, "claude/A-1-x", mergeable="CONFLICTING")]), "A")["kind"], "REBASE")

    def test_changes_requested_after_last_commit(self):
        v = {"gemini": {"verdict": "changes", "at": T2}}
        self.assertEqual(na.decide(state([pr(1, "claude/A-1-x", verdicts=v)]), "A")["kind"], "ADDRESS_REVIEW")

    def test_changes_before_last_commit_waits_for_rereview(self):
        v = {"gemini": {"verdict": "changes", "at": T0}}
        self.assertNotEqual(na.decide(state([pr(1, "claude/A-1-x", verdicts=v)]), "A")["kind"], "ADDRESS_REVIEW")

    def test_merge_needs_fresh_lgtm_and_green_ci(self):
        fresh = {"gemini": {"verdict": "lgtm", "at": T2}}
        stale = {"gemini": {"verdict": "lgtm", "at": T0}}
        self.assertEqual(na.decide(state([pr(1, "contract/claude-x", verdicts=fresh)]), "A")["kind"], "MERGE")
        self.assertNotEqual(na.decide(state([pr(1, "claude/A-1-x", verdicts=stale)]), "A")["kind"], "MERGE")
        self.assertNotEqual(na.decide(state([pr(1, "claude/A-1-x", verdicts=fresh, ci="pending")]), "A")["kind"], "MERGE")

    def test_review_other_lanes_pr_once_per_commit(self):
        self.assertEqual(na.decide(state([pr(7, "gemini/B-5-x")]), "A")["kind"], "REVIEW")
        reviewed = {"claude": {"verdict": "lgtm", "at": T2}}
        self.assertNotEqual(na.decide(state([pr(7, "gemini/B-5-x", verdicts=reviewed)]), "A")["kind"], "REVIEW")
        self.assertNotEqual(na.decide(state([pr(7, "gemini/B-5-x", draft=True)]), "A")["kind"], "REVIEW")

    def test_user_branches_are_nobodys_lane(self):
        # e.g. the setup PR or a hotfix the user opens: neither agent reviews or merges it
        s = state([pr(1, "user/autopilot-setup"), pr(2, "fix/sos-location-must-be-optional")])
        self.assertEqual(na.decide(s, "A")["kind"], "WAIT")
        self.assertEqual(na.decide(s, "B")["kind"], "WAIT")

    def test_start_task_prefers_current_milestone_then_works_ahead(self):
        tasks = [task("A-004", needs=["X-001"]), task("X-001", status="todo"),
                 task("A-003", status="done"), task("A-101", "M1", needs=["A-003"])]
        d = na.decide(state(tasks=tasks), "A")
        self.assertEqual((d["kind"], d["task"], d["ahead"]), ("START_TASK", "A-101", True))
        self.assertEqual(na.decide(state(tasks=tasks), "A", work_ahead=False)["kind"], "WAIT")

    def test_waits_on_the_user_and_says_why(self):
        tasks = [task("X-001"), task("A-001", needs=["X-001"]), task("A-101", "M1", needs=["A-001"])]
        d = na.decide(state(tasks=tasks), "A")
        self.assertEqual(d["kind"], "WAIT")
        self.assertIn("A-001 needs X-001", d["waiting"])
        self.assertEqual(na.user_queue(state(tasks=tasks)), ["X-001: Task X-001"])

    def test_in_flight_branch_is_continued_not_restarted(self):
        tasks = [task("A-101", "M1")]
        d = na.decide(state(tasks=tasks, branches=["origin/claude/A-101-nonce"]), "A")
        self.assertEqual((d["kind"], d["task"]), ("CONTINUE_BRANCH", "A-101"))

    def test_rfc_addressed_to_lane(self):
        d = na.decide(state(tasks=[task("B-RFC-001", to="A")]), "A")
        self.assertEqual((d["kind"], d["task"]), ("RFC", "B-RFC-001"))

    def test_too_many_open_prs_waits(self):
        prs = [pr(n, f"claude/A-10{n}-x") for n in range(1, 4)]
        self.assertEqual(na.decide(state(prs, [task("A-104", "M1")]), "A")["kind"], "WAIT")

    def test_classify_comments(self):
        self.assertEqual(na.classify_comment(marker("gemini", "changes") + "\n1. x"), ("gemini", "changes"))
        self.assertEqual(na.classify_comment("  <!--meshline:agent=claude  verdict=LGTM-->\nok"), ("claude", "lgtm"))
        self.assertEqual(na.classify_comment(marker("claude", "comment")), ("claude", "comment"))
        # unsigned comments and other projects' markers never count as a verdict
        self.assertEqual(na.classify_comment("LGTM. Verified in MeshLine-gemini"), (None, None))
        self.assertEqual(na.classify_comment("<!-- rekha:agent=gemini verdict=lgtm -->"), (None, None))

    def test_ci_state(self):
        ok = {"status": "COMPLETED", "conclusion": "SUCCESS"}
        self.assertEqual(na.ci_state([ok, ok]), "success")
        self.assertEqual(na.ci_state([ok, {"status": "IN_PROGRESS", "conclusion": ""}]), "pending")
        self.assertEqual(na.ci_state([ok, {"status": "COMPLETED", "conclusion": "FAILURE"}]), "failure")
        self.assertEqual(na.ci_state([]), "none")

    def test_every_action_has_a_prompt(self):
        for kind in ("FIX_CI", "REBASE", "ADDRESS_REVIEW", "MERGE", "REVIEW", "RFC", "CONTINUE_BRANCH",
                     "CONTINUE_TASK", "START_TASK"):
            text = na.prompt_for({"kind": kind, "lane": "B", "summary": "s", "pr": 3, "branch": "gemini/B-3", "task": "B-003"})
            self.assertIn("MESHLINE AUTOPILOT", text)
            self.assertIn(str(CLONES["B"]), text)
            self.assertIn("cargo run -q -p meshline-sim", text)
        review = na.prompt_for({"kind": "REVIEW", "lane": "A", "summary": "s", "pr": 3, "branch": "gemini/B-3"})
        self.assertIn(marker("claude", "lgtm"), review)
        self.assertIn(marker("claude", "changes"), review)


class GuardTest(unittest.TestCase):
    def setUp(self):
        gemini = CLONES["B"].name
        patcher = mock.patch.object(guard, "lane_of", lambda p: "B" if p and gemini in str(p) else None)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.cwd = str(CLONES["B"])

    def cmd(self, c, cwd=None, lane="B"):
        return guard.check_command(c, cwd or self.cwd, lane)[0]

    def test_routine_work_is_allowed(self):
        for c in ("cargo test --workspace", "git push -u origin gemini/B-101-ble-retry", "gh pr view 3 --comments",
                  "git push --force-with-lease", "python tools/board.py --next B", "git log --format=%H -3",
                  "cd android && ./gradlew testDebugUnitTest lintDebug -Pmeshline.skipRustBuild=true",
                  "cargo run -q -p meshline-sim", "python -m pip install pyyaml", "adb devices"):
            self.assertEqual(self.cmd(c), "allow", c)

    def test_dangerous_things_are_denied(self):
        for c in ("git push origin main", "git push origin HEAD:main", "git push --force origin gemini/B-1",
                  "git push origin claude/A-101-nonce", "git commit --no-verify -m x", "rustup default stable-msvc",
                  "winget install Kitware.CMake", "ssh ubuntu@144.24.141.188", "git config --global user.name x",
                  "git config meshline.lane A", "gh repo delete ajose369/MeshLine", "npm install -g something",
                  "sdkmanager \"platforms;android-36\"", "cargo install cargo-ndk",
                  "adb install app/build/outputs/apk/debug/app-debug.apk", "adb shell pm clear org.meshline.app"):
            self.assertEqual(self.cmd(c), "deny", c)

    def test_other_clones_are_off_limits(self):
        other = str(CLONES["A"])
        self.assertEqual(self.cmd(f'python x.py "{other}\\README.md"'), "deny")
        self.assertEqual(self.cmd(f'type "{INTEGRATION}\\README.md"'), "deny")
        self.assertEqual(self.cmd("dir", cwd=other), "deny")
        self.assertEqual(self.cmd(f'type "{CLONES["B"]}\\README.md"'), "allow")  # MeshLine-gemini is not "MeshLine"
        self.assertEqual(self.cmd(f'type "{STATE}\\status.md"'), "allow")         # nor is MeshLine-agents

    def test_recursive_delete_only_inside_own_clone(self):
        self.assertEqual(self.cmd(f'Remove-Item -Recurse -Force "{CLONES["B"]}\\target"'), "allow")
        self.assertEqual(self.cmd('Remove-Item -Recurse -Force "C:\\Users\\aldri\\Documents"'), "deny")

    def test_not_a_lane_clone_gets_no_decision(self):
        with mock.patch.dict("os.environ", {}, clear=False):
            os.environ.pop(f"{ENV}_LANE", None)
            self.assertIsNone(guard.check_command("git push origin main", r"C:\somewhere\else")[0])

    def test_merge_requires_fresh_lgtm_and_green_ci(self):
        fresh = {"claude": {"verdict": "lgtm", "at": T2}}
        with mock.patch("next_action.collect", return_value=state([pr(3, "gemini/B-101-x", verdicts=fresh),
                                                                     pr(6, "contract/claude-x", verdicts={}),
                                                                     pr(9, "gemini/B-9-x")])):
            self.assertEqual(self.cmd("gh pr merge 3 --squash --delete-branch"), "allow")
            self.assertEqual(self.cmd("gh pr merge 6 --squash"), "deny")   # other lane's PR
            self.assertEqual(self.cmd("gh pr merge 9 --squash"), "deny")   # no review yet

    def test_writes_respect_ownership(self):
        own = CLONES["B"]
        w = lambda p: guard.check_write(str(p), str(own), "B")[0]
        self.assertEqual(w(own / APP / "ui/sos/SosScreen.kt"), "allow")
        self.assertEqual(w(own / APP / "db/StoreAndForwardManager.kt"), "allow")
        self.assertEqual(w(own / APP / "bridge/MeshCoreBridge.kt"), "deny")
        self.assertEqual(w(own / APP / "db/EncryptedMessageStore.kt"), "deny")
        self.assertEqual(w(own / "crates/meshline-core/src/lib.rs"), "deny")
        self.assertEqual(w(own / "android/app/build.gradle.kts"), "allow")  # contract: allowed, CI checks the branch
        self.assertEqual(w(own / "tasks/X-001.md"), "deny")
        self.assertEqual(w(own / "REVIEW.md"), "deny")
        self.assertEqual(w(CLONES["A"] / "README.md"), "deny")
        a = lambda p: guard.check_write(str(p), str(CLONES["A"]), "A")[0]
        self.assertEqual(a(CLONES["A"] / APP / "security/KeystoreVault.kt"), "allow")
        self.assertEqual(a(CLONES["A"] / APP / "ui/sos/SosScreen.kt"), "deny")

    def test_lane_comes_from_the_agent_not_the_folder(self):
        # Gemini (lane B) running a command whose cwd is Claude's clone is still judged as lane B.
        self.assertEqual(self.cmd("git status", cwd=str(CLONES["A"]), lane="B"), "deny")


class RateLimitTest(unittest.TestCase):
    """A usage-limited run must pause the lane until its window resets, not count as a failure."""

    def write_log(self, *events: dict) -> Path:
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d, True)
        f = d / "run.jsonl"
        f.write_text(LINE_SEP.join(json.dumps(e) for e in events) + LINE_SEP, encoding="utf-8")
        return f

    def test_uses_the_rejected_limits_own_reset_not_the_latest_window(self):
        # The real event carries several windows; only the rejected one unblocks the lane.
        soon = int(time.time()) + 3600
        log = self.write_log(
            {"type": "system", "subtype": "init"},
            {"type": "rate_limit_event",
             "rate_limit_info": {"status": "rejected", "resetsAt": soon,
                                 "rateLimitType": "five_hour",
                                 "unifiedWindows": {"weekly": {"resetsAt": soon + 86400}}}},
            {"type": "result", "is_error": True, "result": "You have hit your session limit"})
        self.assertEqual(supervisor.rate_limit_reset(log), soon)

    def test_limit_without_a_reset_time_falls_back_to_a_short_wait(self):
        log = self.write_log({"type": "result", "is_error": True, "result": "usage limit reached"})
        reset = supervisor.rate_limit_reset(log)
        self.assertIsNotNone(reset)
        self.assertLess(abs(reset - (time.time() + 1800)), 60)

    def test_ordinary_failure_is_not_a_rate_limit(self):
        log = self.write_log({"type": "result", "is_error": True, "result": "tests failed"})
        self.assertIsNone(supervisor.rate_limit_reset(log))
        self.assertIsNone(supervisor.rate_limit_reset(None))

    def test_reset_is_clamped_to_six_hours(self):
        far = int(time.time()) + 50 * 3600
        log = self.write_log({"type": "rate_limit_event",
                              "rate_limit_info": {"status": "rejected", "resetsAt": far}})
        self.assertLessEqual(supervisor.rate_limit_reset(log), time.time() + 6 * 3600 + 1)

    def test_daily_cap_ignores_rate_limited_runs(self):
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d, True)
        runs = d / "runs.jsonl"
        today = datetime.now().strftime("%Y-%m-%d")
        rows = [{"lane": "A", "start": today + " 10:00:00", "rate_limited": False},
                {"lane": "A", "start": today + " 10:05:00", "rate_limited": True},
                {"lane": "A", "start": today + " 10:06:00", "rate_limited": True}]
        runs.write_text(LINE_SEP.join(json.dumps(r) for r in rows) + LINE_SEP, encoding="utf-8")
        with mock.patch.object(supervisor, "RUNS", runs):
            self.assertEqual(supervisor.LaneA(None).runs_today(), 1)


class RepeatPauseTest(unittest.TestCase):
    """The no-progress pause lasts an hour; it must not silently become permanent."""

    def test_same_action_is_dispatched_again_after_the_pause(self):
        lane = supervisor.LaneA("claude.exe")
        action = {"kind": "FIX_CI", "pr": 14}
        lane.recent = ["FIX_CI:14"] * 3
        with mock.patch.object(lane, "runs_today", return_value=0), mock.patch.object(lane, "start") as start:
            lane.tick(action)
            start.assert_not_called()
            self.assertGreater(lane.paused_until, time.time())
            lane.paused_until = time.time() - 1
            lane.tick(action)
            start.assert_called_once_with(action, "FIX_CI:14")


class LaneBMonitorTest(unittest.TestCase):
    """Antigravity never calls the hooks, so Gemini's pushes must count as activity."""

    def test_open_pr_commit_counts_as_activity(self):
        recent = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        s = state([pr(4, "gemini/B-101-x", last=recent)])
        with mock.patch.object(supervisor, "GEMINI_HOOK_LOG", Path(tempfile.gettempdir()) / "no-such-hook.log"), \
                mock.patch.object(supervisor, "git", side_effect=RuntimeError("no clone")):
            self.assertLess(time.time() - supervisor.gemini_last_activity(s), 120)
            self.assertEqual(supervisor.gemini_last_activity(state()), 0.0)

    def test_only_gemini_branches_count(self):
        refs = "origin/contract/claude-fmt 1792000000\norigin/gemini/B-101-x 1791000000\n"
        with mock.patch.object(supervisor, "GEMINI_HOOK_LOG", Path(tempfile.gettempdir()) / "no-such-hook.log"), \
                mock.patch.object(supervisor, "git", return_value=refs):
            self.assertEqual(supervisor.gemini_last_activity(state()), 1791000000.0)

    def test_status_warns_when_gemini_has_work_but_is_idle(self):
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d, True)
        actions = {"A": {"kind": "WAIT", "summary": "nothing", "waiting": ["A-001 needs X-001"]},
                   "B": {"kind": "START_TASK", "summary": "Start B-101"}}
        with mock.patch.object(supervisor, "STATUS", d / "status.md"), \
                mock.patch.object(supervisor, "RUNS", d / "runs.jsonl"), \
                mock.patch.object(supervisor, "gemini_last_activity", return_value=0.0):
            supervisor.write_status(supervisor.LaneA(None), actions, ["X-001: merge"])
        text = (d / "status.md").read_text(encoding="utf-8")
        self.assertIn("# MeshLine autopilot status", text)
        self.assertIn("start autopilot", text)
        self.assertIn("A-001 needs X-001", text)
        self.assertIn("X-001: merge", text)


class SetupTest(unittest.TestCase):
    """Templates render with no placeholders left, and configure() marks the clone."""

    def test_templates_render_completely(self):
        here = Path(setup_clone.__file__).resolve().parent
        for tpl in [here / "Autopilot.cmd.template", here / "antigravity" / "hooks.template.json",
                    *(here / "antigravity" / "skills" / "autopilot").iterdir()]:
            text = setup_clone.render(tpl.read_text(encoding="utf-8"), json_escape=tpl.suffix == ".json")
            self.assertNotRegex(text, r"\{[A-Z_]+\}", str(tpl))
        hooks = json.loads(setup_clone.render(
            (here / "antigravity" / "hooks.template.json").read_text(encoding="utf-8"), json_escape=True))
        self.assertIn("meshline-guard", hooks)
        self.assertIn(str(STATE), hooks["meshline-guard"]["PreToolUse"][0]["hooks"][0]["command"])

    def test_configure_lane_b_clone(self):
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d, True)
        subprocess.run(["git", "init", "-q", str(d)], check=True)
        setup_clone.configure("B", d, runtime=Path(r"C:\rt"), identity=True)
        get = lambda k: subprocess.run(["git", "-C", str(d), "config", "--get", k],
                                       capture_output=True, text=True).stdout.strip()
        self.assertEqual(get("meshline.lane"), "B")
        self.assertEqual(get("core.hooksPath"), "C:/rt/tools/agents/githooks")
        self.assertEqual(get("user.name"), "Gemini (MeshLine Lane B)")
        self.assertTrue((d / ".agents" / "skills" / "meshline-autopilot" / "SKILL.md").exists())
        self.assertIn(".agents/", (d / ".git" / "info" / "exclude").read_text(encoding="utf-8"))
        self.assertTrue(re.search(r"name: meshline-autopilot",
                                  (d / ".agents" / "skills" / "meshline-autopilot" / "SKILL.md").read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
