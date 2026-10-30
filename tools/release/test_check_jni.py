"""Unit tests for check_jni.py.

Run:
  python -m unittest discover -s tools/release -p "test_*.py" -v
"""
from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import check_jni


def make_synthetic_elf64(symbols: list[str]) -> bytes:
    """Create a minimal valid ELF64 shared object bytes containing .dynsym and .dynstr."""
    endian = "<"
    dynstr = bytearray(b"\x00")
    sym_offsets = []
    for s in symbols:
        sym_offsets.append(len(dynstr))
        dynstr.extend(s.encode("utf-8") + b"\x00")

    # Entry 0 in .dynsym is the NULL symbol (24 bytes)
    dynsym = bytearray(b"\x00" * 24)
    for off in sym_offsets:
        # st_name(I=off), st_info(B=0x12), st_other(B=0), st_shndx(H=1), st_value(Q=0x1000), st_size(Q=16)
        dynsym.extend(struct.pack(f"{endian}IBBHQQ", off, 0x12, 0, 1, 0x1000, 16))

    dynstr_offset = 64
    dynstr_size = len(dynstr)

    dynsym_offset = (dynstr_offset + dynstr_size + 7) & ~7
    dynsym_size = len(dynsym)

    sh_offset = (dynsym_offset + dynsym_size + 7) & ~7
    sh_entsize = 64
    sh_num = 3

    e_ident = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 8
    e_type = 3  # ET_DYN
    e_machine = 0x3E  # EM_X86_64
    e_version = 1
    e_entry = 0
    e_phoff = 0
    e_shoff = sh_offset
    e_flags = 0
    e_ehsize = 64
    e_phentsize = 0
    e_phnum = 0
    e_shentsize = sh_entsize
    e_shnum = sh_num
    e_shstrndx = 0

    header = struct.pack(
        f"{endian}16sHHIQQQIHHHHHH",
        e_ident,
        e_type,
        e_machine,
        e_version,
        e_entry,
        e_phoff,
        e_shoff,
        e_flags,
        e_ehsize,
        e_phentsize,
        e_phnum,
        e_shentsize,
        e_shnum,
        e_shstrndx,
    )

    sec0 = b"\x00" * 64
    # Section 1: .dynsym (SHT_DYNSYM=11, link=2 to .dynstr, entsize=24)
    sec1 = struct.pack(f"{endian}IIQQQQIIQQ", 0, 11, 2, 0, dynsym_offset, dynsym_size, 2, 1, 8, 24)
    # Section 2: .dynstr (SHT_STRTAB=3)
    sec2 = struct.pack(f"{endian}IIQQQQIIQQ", 0, 3, 2, 0, dynstr_offset, dynstr_size, 0, 0, 1, 0)

    data = bytearray(header)
    data.extend(dynstr)
    data.extend(b"\x00" * (dynsym_offset - len(data)))
    data.extend(dynsym)
    data.extend(b"\x00" * (sh_offset - len(data)))
    data.extend(sec0)
    data.extend(sec1)
    data.extend(sec2)
    return bytes(data)


class CheckJniTest(unittest.TestCase):
    def test_derive_jni_prefix(self):
        kt_path = check_jni.DEFAULT_KT_PATH
        self.assertTrue(kt_path.is_file(), f"Kotlin bridge file missing: {kt_path}")
        prefix = check_jni.derive_jni_prefix(kt_path)
        self.assertEqual(prefix, "Java_org_meshline_app_bridge_MeshCoreBridge_")

    def test_extract_bridge_method_names(self):
        kt_path = check_jni.DEFAULT_KT_PATH
        methods = check_jni.extract_bridge_method_names(kt_path)
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

    def test_extract_expected_jni_symbols(self):
        kt_path = check_jni.DEFAULT_KT_PATH
        symbols = check_jni.extract_expected_jni_symbols(kt_path)
        self.assertEqual(len(symbols), 25)
        self.assertIn("Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit", symbols)
        self.assertIn("Java_org_meshline_app_bridge_MeshCoreBridge_nativeWipeSecureState", symbols)

    def test_extract_rust_jni_symbols(self):
        rust_path = check_jni.DEFAULT_RUST_PATH
        self.assertTrue(rust_path.is_file(), f"Rust lib.rs missing: {rust_path}")
        rust_symbols = check_jni.extract_rust_jni_symbols(rust_path)
        kt_symbols = check_jni.extract_expected_jni_symbols(check_jni.DEFAULT_KT_PATH)
        self.assertEqual(rust_symbols, kt_symbols)

    def test_synthetic_elf64_parser(self):
        raw_symbols = [
            "Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit",
            "Java_org_meshline_app_bridge_MeshCoreBridge_nativeIsReady",
            "non_jni_symbol_foo",
        ]
        elf_bytes = make_synthetic_elf64(raw_symbols)
        extracted = check_jni.extract_symbols_from_elf_bytes(elf_bytes)
        self.assertEqual(
            extracted,
            [
                "Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit",
                "Java_org_meshline_app_bridge_MeshCoreBridge_nativeIsReady",
            ],
        )

    def test_package_mismatch_case_a_wrong_package_in_so(self):
        """Case A: .so symbols patched to wrong package; verification must fail."""
        wrong_symbols = [
            "Java_org_meshline_app_WRONGG_MeshCoreBridge_nativeInit",
            "Java_org_meshline_app_WRONGG_MeshCoreBridge_nativeIsReady",
        ]
        expected_symbols = [
            "Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit",
            "Java_org_meshline_app_bridge_MeshCoreBridge_nativeIsReady",
        ]
        elf_bytes = make_synthetic_elf64(wrong_symbols)
        with tempfile.TemporaryDirectory() as tmpdir:
            so_path = Path(tmpdir) / "libmeshline_ffi.so"
            so_path.write_bytes(elf_bytes)

            ok, msg = check_jni.verify_abi_symbols("arm64-v8a", so_path, expected_symbols)
            self.assertFalse(ok)
            self.assertIn("FAILED [arm64-v8a]", msg)
            self.assertIn("Missing in .so (2)", msg)
            self.assertIn("- Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit", msg)
            self.assertIn("Extra in .so (2)", msg)
            self.assertIn("+ Java_org_meshline_app_WRONGG_MeshCoreBridge_nativeInit", msg)

    def test_package_mismatch_case_b_moved_kotlin_package(self):
        """Case B: Kotlin moved to another package, .so has old symbols; verification must fail."""
        real_so_symbols = [
            "Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit",
        ]
        with tempfile.TemporaryDirectory() as tmpdir:
            moved_kt = Path(tmpdir) / "MeshCoreBridge.kt"
            moved_kt.write_text(
                "package org.meshline.app.moved\n\nobject MeshCoreBridge {\n  external fun nativeInit(): Boolean\n}\n",
                encoding="utf-8",
            )
            expected_symbols = check_jni.extract_expected_jni_symbols(moved_kt)
            self.assertEqual(expected_symbols, ["Java_org_meshline_app_moved_MeshCoreBridge_nativeInit"])

            elf_bytes = make_synthetic_elf64(real_so_symbols)
            so_path = Path(tmpdir) / "libmeshline_ffi.so"
            so_path.write_bytes(elf_bytes)

            ok, msg = check_jni.verify_abi_symbols("arm64-v8a", so_path, expected_symbols)
            self.assertFalse(ok)
            self.assertIn("Missing in .so (1)", msg)
            self.assertIn("- Java_org_meshline_app_moved_MeshCoreBridge_nativeInit", msg)
            self.assertIn("Extra in .so (1)", msg)
            self.assertIn("+ Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit", msg)

    def test_verify_abi_symbols_missing_file(self):
        missing_so = Path("/nonexistent/path/libmeshline_ffi.so")
        ok, msg = check_jni.verify_abi_symbols("x86_64", missing_so, ["Java_org_meshline_foo"])
        self.assertFalse(ok)
        self.assertIn("Missing native library for ABI 'x86_64'", msg)

    def test_find_llvm_nm_explicit_not_found_raises(self):
        with self.assertRaises(FileNotFoundError):
            check_jni.find_llvm_nm("/path/does/not/exist/llvm-nm")

    def test_elf_parser_invalid_data(self):
        with self.assertRaises(ValueError):
            check_jni.extract_symbols_from_elf_bytes(b"NOT_ELF")

    def test_live_built_libraries_if_present(self):
        """If libraries are already compiled, test all ABIs with pure Python ELF parser."""
        expected = check_jni.extract_expected_jni_symbols(check_jni.DEFAULT_KT_PATH)
        debug_dir = check_jni.ROOT / "android/app/build/rustJniLibs/debug"
        if not debug_dir.is_dir():
            self.skipTest(f"No built native libraries found at {debug_dir}")

        tested_abis = 0
        for abi in check_jni.DEFAULT_ABIS:
            so_path = debug_dir / abi / "libmeshline_ffi.so"
            if not so_path.is_file():
                continue
            tested_abis += 1
            data = so_path.read_bytes()
            symbols = check_jni.extract_symbols_from_elf_bytes(data)
            self.assertEqual(symbols, expected, f"ABI {abi} symbols mismatch via ELF parser")

        if tested_abis == 0:
            self.skipTest("No compiled .so files found in ABI subdirectories")


if __name__ == "__main__":
    unittest.main()
