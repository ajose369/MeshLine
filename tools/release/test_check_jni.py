"""Unit tests for check_jni.py.

Run:
  python -m unittest discover -s tools/release -p "test_*.py" -v
"""
from __future__ import annotations

import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import check_jni


class CheckJniTest(unittest.TestCase):
    def test_extract_bridge_methods(self):
        kt_path = check_jni.DEFAULT_KT_PATH
        self.assertTrue(kt_path.is_file(), f"Kotlin bridge file missing: {kt_path}")
        methods = check_jni.extract_bridge_methods(kt_path)

        # Expected core methods
        for expected in (
            "nativeInit",
            "nativeIsReady",
            "nativeNodeIdHex",
            "nativePublicKey",
            "nativeCreateSos",
            "nativeCreatePin",
            "nativeCreateChat",
            "nativeBeginHandshake",
            "nativeHasSession",
            "nativeProcessIncoming",
            "nativeActivePinsJson",
            "nativeUpdateBattery",
            "nativeSafetyNumber",
            "nativeSetPeerVerified",
            "nativeIsPeerVerified",
            "nativeCreateGroup",
            "nativeInviteToGroup",
            "nativeCreateGroupChat",
            "nativeRemoveFromGroup",
            "nativeRekeyGroup",
            "nativeLeaveGroup",
            "nativeGroupsJson",
            "nativeExportState",
            "nativeImportState",
            "nativeWipeSecureState",
        ):
            self.assertIn(expected, methods)

        self.assertEqual(len(methods), 25)

    def test_extract_rust_jni_methods(self):
        rust_path = check_jni.DEFAULT_RUST_PATH
        self.assertTrue(rust_path.is_file(), f"Rust lib.rs missing: {rust_path}")
        rust_methods = check_jni.extract_rust_jni_methods(rust_path)
        kt_methods = check_jni.extract_bridge_methods(check_jni.DEFAULT_KT_PATH)

        self.assertEqual(rust_methods, kt_methods)

    def test_verify_abi_symbols_match(self):
        expected = ["nativeInit", "nativeIsReady"]
        with tempfile.TemporaryDirectory() as tmpdir:
            fake_so = Path(tmpdir) / "libmeshline_ffi.so"
            fake_so.touch()

            with mock.patch("check_jni.extract_symbols_from_so", return_value=["nativeInit", "nativeIsReady"]):
                ok, msg = check_jni.verify_abi_symbols("arm64-v8a", fake_so, expected)
                self.assertTrue(ok)
                self.assertIn("OK [arm64-v8a]", msg)

    def test_verify_abi_symbols_mismatch(self):
        expected = ["nativeInit", "nativeIsReady", "nativeCreateSos"]
        with tempfile.TemporaryDirectory() as tmpdir:
            fake_so = Path(tmpdir) / "libmeshline_ffi.so"
            fake_so.touch()

            # Simulate native library missing nativeCreateSos and having extra nativeOldFoo
            with mock.patch("check_jni.extract_symbols_from_so", return_value=["nativeInit", "nativeIsReady", "nativeOldFoo"]):
                ok, msg = check_jni.verify_abi_symbols("arm64-v8a", fake_so, expected)
                self.assertFalse(ok)
                self.assertIn("FAILED [arm64-v8a]", msg)
                self.assertIn("Missing in .so (1)", msg)
                self.assertIn("- nativeCreateSos", msg)
                self.assertIn("Extra in .so (1)", msg)
                self.assertIn("+ nativeOldFoo", msg)

    def test_verify_abi_symbols_missing_file(self):
        missing_so = Path("/nonexistent/path/libmeshline_ffi.so")
        ok, msg = check_jni.verify_abi_symbols("x86_64", missing_so, ["nativeInit"])
        self.assertFalse(ok)
        self.assertIn("Missing native library for ABI 'x86_64'", msg)

    def test_elf_parser_invalid_data(self):
        with self.assertRaises(ValueError):
            check_jni.extract_symbols_from_elf_bytes(b"NOT_ELF")

    def test_live_built_libraries_if_present(self):
        """If libraries are already compiled, test all ABIs with pure Python ELF parser."""
        expected = check_jni.extract_bridge_methods(check_jni.DEFAULT_KT_PATH)
        debug_dir = check_jni.ROOT / "android/app/build/rustJniLibs/debug"
        if not debug_dir.is_dir():
            return  # Skip if not yet built

        for abi in check_jni.DEFAULT_ABIS:
            so_path = debug_dir / abi / "libmeshline_ffi.so"
            if not so_path.is_file():
                continue
            data = so_path.read_bytes()
            symbols = check_jni.extract_symbols_from_elf_bytes(data)
            self.assertEqual(symbols, expected, f"ABI {abi} symbols mismatch via ELF parser")


if __name__ == "__main__":
    unittest.main()
