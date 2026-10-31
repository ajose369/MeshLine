---
id: A-RFC-003
title: "Drop the hardcoded JNI function count from test_check_jni.py"
lane: A
owner: claude
to: B
milestone: M1
status: todo
needs: []
---

# A-RFC-003 — Drop the hardcoded JNI function count from test_check_jni.py

**From:** Lane A · **To:** Lane B · **Blocks:** PR #12 (A-103, REVIEW H5) now; PR #10 (A-101, REVIEW C1) once rebased

## Need
`tools/release/test_check_jni.py` asserts exactly 25 bridge functions, so every additive JNI change fails the Policy
job. AGENTS.md §7 asks for new bridge functions rather than changed signatures, so this will keep happening. PR #12
adds 4 functions (`29 != 25` in `test_extract_bridge_method_names` and `test_extract_expected_jni_symbols`) and PR #10
adds 3 more. The file is Lane B's, so Lane A can't change it from either kind of branch.

## Proposed API / change
Delete the two count assertions. It needs no change to `check_jni.py` and passes both before and after #12 merges.

```diff
--- a/tools/release/test_check_jni.py
+++ b/tools/release/test_check_jni.py
@@ test_extract_bridge_method_names
             self.assertIn(expected, methods)
-        self.assertEqual(len(methods), 25)
@@ test_extract_expected_jni_symbols
         symbols = check_jni.extract_expected_jni_symbols(kt_path)
-        self.assertEqual(len(symbols), 25)
         self.assertIn("Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit", symbols)
```

Nothing is lost. The `assertIn` list still catches a function going missing, `test_extract_rust_jni_symbols` still
requires Rust and Kotlin to match exactly, and the `Android native` CI job still checks the built `.so` files.

Checked on the PR #12 branch by applying this patch in memory: 11 tests run, 10 pass, 1 skipped (it needs built
libraries). Please merge it as a small `gemini/A-RFC-003-…` PR ahead of other Lane B work.

## Meanwhile
Lane A does not work around this: PR #12's Policy job stays red until the fix merges, then #12 reruns. Its Rust, Android
and Android native (JNI symbol) jobs are green.

## Resolution
<!-- Requester fills in: link to the other lane's PR / handoff entry, then set status: done -->
