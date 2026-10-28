"""Tests for the two-agent coordination tools: check_owners.py and board.py.

Run: python -m unittest discover -s tools -p "test_*.py" -v
These run in CI (policy job). The ownership cases pin the MeshLine lane split in OWNERS.toml;
the board cases cover the "--next must not jump milestones" rule.
"""
from __future__ import annotations

import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import board
import check_owners


class OwnerOfTest(unittest.TestCase):
    """The real OWNERS.toml, including most-specific-wins overrides."""

    @classmethod
    def setUpClass(cls):
        cls.rules = check_owners.load_rules()

    def assertOwner(self, path, expected):
        self.assertEqual(check_owners.owner_of(path, self.rules), expected, path)

    def test_lane_paths(self):
        self.assertOwner("crates/meshline-core/src/crypto/session.rs", "A")
        self.assertOwner("crates/meshline-ffi/src/lib.rs", "A")
        self.assertOwner("crates/meshline-sim/src/main.rs", "A")
        self.assertOwner("android/app/src/main/java/org/meshline/app/ui/sos/SosScreen.kt", "B")
        self.assertOwner("android/app/src/main/java/org/meshline/app/transport/BleTransportManager.kt", "B")
        self.assertOwner("android/app/src/main/AndroidManifest.xml", "B")
        self.assertOwner("android/app/src/test/java/org/meshline/app/db/EntityTest.kt", "B")
        self.assertOwner("site/index.html", "B")
        self.assertOwner("RELEASE.md", "B")

    def test_more_specific_rule_wins(self):
        # Lane A's Kotlin islands inside Lane B's android/ tree
        self.assertOwner("android/app/src/main/java/org/meshline/app/bridge/MeshCoreBridge.kt", "A")
        self.assertOwner("android/app/src/main/java/org/meshline/app/security/KeystoreVault.kt", "A")
        self.assertOwner("android/app/src/main/java/org/meshline/app/db/EncryptedMessageStore.kt", "A")
        self.assertOwner("android/app/src/main/java/org/meshline/app/db/StoreAndForwardManager.kt", "B")
        # the wire format is a contract inside Lane A's crate
        self.assertOwner("crates/meshline-core/src/packet/schema.rs", "contract")
        self.assertOwner("crates/meshline-core/src/packet/mod.rs", "A")
        # Gradle build files are contracts inside Lane B's tree
        self.assertOwner("android/app/build.gradle.kts", "contract")
        self.assertOwner("android/gradle/wrapper/gradle-wrapper.properties", "contract")

    def test_contract_files(self):
        for path in ("Cargo.toml", "AGENTS.md", "OWNERS.toml", "README.md", "PRIVACY.md", ".github/workflows/ci.yml",
                     "tools/board.py", "tools/check_owners.py", "tools/agents/autopilot.toml",
                     "tools/agents/guard.py", "android/app/proguard-rules.pro", "docs/PLAN.md"):
            self.assertOwner(path, "contract")

    def test_shared_and_user_and_default(self):
        self.assertOwner("Cargo.lock", "any")
        self.assertOwner("docs/decisions/2026-10-01-x.md", "any")
        self.assertOwner("tasks/A-101.md", "A")
        self.assertOwner("tasks/B-RFC-001.md", "B")
        self.assertOwner("tasks/X-001.md", "X")
        self.assertOwner("REVIEW.md", "X")
        self.assertOwner("CLAUDE.md", "A")
        self.assertOwner("GEMINI.md", "B")
        self.assertOwner("somethingnew.txt", "contract")  # unmatched paths need a contract PR

    def test_windows_separators(self):
        self.assertOwner("android\\app\\src\\main\\java\\org\\meshline\\app\\ui\\sos\\SosScreen.kt", "B")
        self.assertOwner("crates\\meshline-core\\src\\lib.rs", "A")


class BranchRulesTest(unittest.TestCase):
    def test_lane_branches(self):
        self.assertEqual(check_owners.allowed_owners("claude/A-101-nonce"), {"A", "any"})
        self.assertEqual(check_owners.allowed_owners("gemini/B-101-ble-retry"), {"B", "any"})

    def test_contract_branches_keep_their_lane(self):
        self.assertEqual(check_owners.allowed_owners("contract/claude-v1"), {"contract", "A", "any"})
        self.assertEqual(check_owners.allowed_owners("contract/gemini-x"), {"contract", "B", "any"})

    def test_unrestricted_and_unknown(self):
        self.assertIsNone(check_owners.allowed_owners("main"))
        self.assertIsNone(check_owners.allowed_owners("user/hotfix"))
        self.assertEqual(check_owners.allowed_owners("feature/whatever"), set())

    def test_cross_lane_edit_is_a_violation(self):
        rules = check_owners.load_rules()
        allowed = check_owners.allowed_owners("gemini/B-101-ble-retry")
        self.assertNotIn(check_owners.owner_of("crates/meshline-core/src/lib.rs", rules), allowed)
        self.assertNotIn(check_owners.owner_of("android/app/src/main/java/org/meshline/app/bridge/MeshCoreBridge.kt", rules), allowed)
        self.assertIn(check_owners.owner_of("android/app/src/main/java/org/meshline/app/transport/BleTransportManager.kt", rules), allowed)


def _task(tid, milestone, status="todo", needs=(), extra=""):
    lane = tid[0]
    owner = {"A": "claude", "B": "gemini", "X": "user"}[lane]
    return (f"---\nid: {tid}\ntitle: \"Task {tid}: with a colon\"\nlane: {lane}\nowner: {owner}\n"
            f"milestone: {milestone}\nstatus: {status}\nneeds: [{', '.join(needs)}]\n{extra}---\n\n# {tid}\n")


class BoardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tasks = Path(self.tmp.name)
        patches = [mock.patch.object(board, "TASKS", self.tasks),
                   mock.patch.object(board, "remote_in_flight", lambda: {})]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        self.addCleanup(self.tmp.cleanup)

    def write(self, tid, *args, **kwargs):
        (self.tasks / f"{tid}.md").write_text(_task(tid, *args, **kwargs), encoding="utf-8")

    def run_board(self, *argv):
        out = io.StringIO()
        with mock.patch.object(sys, "argv", ["board.py", *argv]), redirect_stdout(out):
            code = board.main()
        return code, out.getvalue()

    def test_real_task_files_are_valid(self):
        mock.patch.stopall()  # use the repository's real tasks/ directory
        tasks, errors = board.load_tasks()
        self.assertEqual(errors, [])
        self.assertGreaterEqual(len(tasks), 30)

    def test_quoted_title_with_colon_parses(self):
        self.write("A-001", "M0")
        tasks, errors = board.load_tasks()
        self.assertEqual(errors, [])
        self.assertEqual(tasks["A-001"]["title"], "Task A-001: with a colon")

    def test_validate_catches_unknown_dependency_and_bad_status(self):
        self.write("A-001", "M0", status="finished", needs=["A-999"])
        code, out = self.run_board("--validate")
        self.assertEqual(code, 1)
        self.assertIn("needs unknown task A-999", out)
        self.assertIn("bad status", out)

    def test_ready_requires_all_needs_done(self):
        self.write("A-001", "M0", status="done")
        self.write("A-002", "M0", needs=["A-001"])
        self.write("A-003", "M0", needs=["A-002"])
        tasks, _ = board.load_tasks()
        self.assertTrue(board.is_ready(tasks["A-002"], tasks))
        self.assertFalse(board.is_ready(tasks["A-003"], tasks))

    def test_next_picks_lowest_ready_id_in_current_milestone(self):
        self.write("A-001", "M0", status="done")
        self.write("A-002", "M0", needs=["A-001"])
        self.write("A-005", "M0")
        _, out = self.run_board("--next", "A")
        self.assertIn("NEXT for Lane A: A-002", out)

    def test_next_does_not_jump_milestones(self):
        # A-002 (M0) is blocked; A-602 (M6) has no needs. --next must not suggest A-602 as NEXT.
        self.write("B-001", "M0")
        self.write("A-002", "M0", needs=["B-001"])
        self.write("A-602", "M6")
        _, out = self.run_board("--next", "A")
        self.assertNotIn("NEXT for Lane A", out)
        self.assertIn("nothing ready in current milestone M0", out)
        self.assertIn("A-002", out)
        self.assertIn("Ahead-of-schedule option (only if the user agrees): A-602", out)

    def test_rfcs_addressed_to_lane_come_first(self):
        self.write("A-001", "M0")
        self.write("B-RFC-001", "M0", extra="to: A\n")
        _, out = self.run_board("--next", "A")
        self.assertLess(out.index("B-RFC-001"), out.index("NEXT for Lane A: A-001"))


if __name__ == "__main__":
    unittest.main()
