#!/usr/bin/env python3
"""Verify JNI symbols in built libmeshline_ffi.so against MeshCoreBridge.kt declarations.

As documented in RELEASE.md §3:
A symbol mismatch between Kotlin external fun declarations and the native cdylib
fails only at runtime on a user device with UnsatisfiedLinkError. This tool verifies
that all shipped ABIs export every expected JNI symbol and no extra/missing ones exist.

Standard library only (Python 3.11+).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
from pathlib import Path

# Ensure UTF-8 output on Windows consoles
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_KT_PATH = ROOT / "android/app/src/main/java/org/meshline/app/bridge/MeshCoreBridge.kt"
DEFAULT_RUST_PATH = ROOT / "crates/meshline-ffi/src/lib.rs"
DEFAULT_ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]

JNI_SYMBOL_PREFIX_RE = re.compile(r"^Java_org_meshline.*?MeshCoreBridge_(\w+)$")


def find_llvm_nm(explicit_nm: str | None = None) -> Path | None:
    """Locate llvm-nm or nm binary if available on system or in Android NDK."""
    if explicit_nm:
        p = Path(explicit_nm)
        if p.is_file():
            return p

    # Check PATH first
    for candidate in ("llvm-nm", "llvm-nm.exe", "nm", "nm.exe"):
        which = shutil.which(candidate)
        if which:
            return Path(which)

    # Search in ANDROID_NDK_HOME, ANDROID_HOME, or ANDROID_SDK_ROOT
    env_dirs = [
        os.environ.get("ANDROID_NDK_HOME"),
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
    ]
    # Also check local.properties if present
    local_props = ROOT / "android" / "local.properties"
    if local_props.is_file():
        try:
            for line in local_props.read_text(encoding="utf-8").splitlines():
                if line.startswith("sdk.dir="):
                    sdk_dir = line.split("=", 1)[1].strip().replace("\\:", ":")
                    env_dirs.append(sdk_dir)
                elif line.startswith("ndk.dir="):
                    ndk_dir = line.split("=", 1)[1].strip().replace("\\:", ":")
                    env_dirs.append(ndk_dir)
        except OSError:
            pass

    for base in env_dirs:
        if not base:
            continue
        base_path = Path(base)
        if not base_path.is_dir():
            continue
        # If pointing directly to an NDK or to SDK containing ndk/
        search_roots = [base_path]
        ndk_subdir = base_path / "ndk"
        if ndk_subdir.is_dir():
            search_roots.extend([p for p in ndk_subdir.iterdir() if p.is_dir()])

        for root in search_roots:
            matches = list(root.glob("toolchains/llvm/prebuilt/*/bin/llvm-nm*"))
            if matches:
                executable = next((m for m in matches if m.is_file()), None)
                if executable:
                    return executable

    return None


def extract_symbols_with_nm(nm_path: Path, so_path: Path) -> list[str]:
    """Run `nm -D --defined-only` on the shared object and extract method names."""
    cmd = [str(nm_path), "-D", "--defined-only", str(so_path)]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    methods = []
    for line in result.stdout.splitlines():
        parts = line.strip().split()
        if not parts:
            continue
        # Symbol is usually the last token on the line
        sym = parts[-1]
        m = JNI_SYMBOL_PREFIX_RE.match(sym)
        if m:
            methods.append(m.group(1))
    return sorted(set(methods))


def extract_symbols_from_elf_bytes(data: bytes) -> list[str]:
    """Parse ELF dynsym/dynstr table directly in pure Python without external dependencies.
    
    Supports 32-bit and 64-bit ELF, both little and big endian.
    Only extracts defined symbols (st_shndx != 0 / SHN_UNDEF).
    """
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise ValueError("Not a valid ELF binary")

    ei_class = data[4]
    is_64 = ei_class == 2
    ei_data = data[5]
    endian = "<" if ei_data == 1 else ">"

    if is_64:
        if len(data) < 64:
            raise ValueError("ELF64 header truncated")
        e_shoff = struct.unpack_from(f"{endian}Q", data, 40)[0]
        e_shentsize = struct.unpack_from(f"{endian}H", data, 58)[0]
        e_shnum = struct.unpack_from(f"{endian}H", data, 60)[0]
    else:
        e_shoff = struct.unpack_from(f"{endian}I", data, 32)[0]
        e_shentsize = struct.unpack_from(f"{endian}H", data, 46)[0]
        e_shnum = struct.unpack_from(f"{endian}H", data, 48)[0]

    if e_shoff == 0 or e_shnum == 0 or e_shentsize == 0:
        return []

    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if off + e_shentsize > len(data):
            break
        if is_64:
            sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, sh_info, sh_addralign, sh_entsize = struct.unpack_from(
                f"{endian}IIQQQQIIQQ", data, off
            )
        else:
            sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, sh_info, sh_addralign, sh_entsize = struct.unpack_from(
                f"{endian}IIIIIIIIII", data, off
            )
        sections.append({
            "type": sh_type,
            "offset": sh_offset,
            "size": sh_size,
            "link": sh_link,
            "entsize": sh_entsize,
        })

    # SHT_DYNSYM = 11
    dynsym_sec = next((s for s in sections if s["type"] == 11), None)
    if not dynsym_sec:
        return []

    if dynsym_sec["link"] >= len(sections):
        return []

    strtab_sec = sections[dynsym_sec["link"]]
    strtab_start = strtab_sec["offset"]
    strtab_end = strtab_start + strtab_sec["size"]
    if strtab_end > len(data):
        return []
    strtab = data[strtab_start:strtab_end]

    sym_entsize = dynsym_sec["entsize"] or (24 if is_64 else 16)
    count = dynsym_sec["size"] // sym_entsize
    methods = []

    for i in range(count):
        sym_off = dynsym_sec["offset"] + i * sym_entsize
        if sym_off + sym_entsize > len(data):
            break

        if is_64:
            st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from(
                f"{endian}IBBHQQ", data, sym_off
            )
        else:
            st_name, st_value, st_size, st_info, st_other, st_shndx = struct.unpack_from(
                f"{endian}IIIBBH", data, sym_off
            )

        # Skip undefined symbols (SHN_UNDEF = 0)
        if st_shndx == 0:
            continue

        if st_name < len(strtab):
            null_idx = strtab.find(b"\0", st_name)
            if null_idx != -1:
                name_bytes = strtab[st_name:null_idx]
            else:
                name_bytes = strtab[st_name:]
            sym_name = name_bytes.decode("utf-8", errors="replace")
            m = JNI_SYMBOL_PREFIX_RE.match(sym_name)
            if m:
                methods.append(m.group(1))

    return sorted(set(methods))


def extract_symbols_from_so(so_path: Path, nm_path: Path | None = None) -> list[str]:
    """Extract JNI symbols using nm if available, falling back to pure Python ELF parser."""
    if nm_path and nm_path.is_file():
        try:
            return extract_symbols_with_nm(nm_path, so_path)
        except Exception:
            pass  # Fall back to ELF parser

    data = so_path.read_bytes()
    return extract_symbols_from_elf_bytes(data)


def extract_bridge_methods(kt_path: Path) -> list[str]:
    """Extract method names declared as `external fun <name>` in Kotlin."""
    content = kt_path.read_text(encoding="utf-8")
    methods = re.findall(r"external\s+fun\s+(\w+)", content)
    return sorted(set(methods))


def extract_rust_jni_methods(rust_path: Path) -> list[str]:
    """Extract method names exported as Java_org_meshline_*MeshCoreBridge_<name> in Rust."""
    content = rust_path.read_text(encoding="utf-8")
    methods = JNI_SYMBOL_PREFIX_RE.findall(content)
    # If findall with prefix regex:
    matches = re.findall(r"Java_org_meshline.*?MeshCoreBridge_(\w+)", content)
    return sorted(set(matches))


def verify_abi_symbols(
    abi: str,
    so_path: Path,
    expected_methods: list[str],
    nm_path: Path | None = None,
) -> tuple[bool, str]:
    """Verify that a specific .so matches the expected Kotlin bridge methods."""
    if not so_path.is_file():
        return False, f"Missing native library for ABI '{abi}': {so_path}"

    actual_methods = extract_symbols_from_so(so_path, nm_path)
    expected_set = set(expected_methods)
    actual_set = set(actual_methods)

    missing = sorted(expected_set - actual_set)
    extra = sorted(actual_set - expected_set)

    if not missing and not extra:
        return True, f"OK [{abi}]: {len(actual_methods)} symbols verified in {so_path.name}"

    lines = [f"FAILED [{abi}]: JNI symbol mismatch in {so_path}"]
    if missing:
        lines.append(f"  Missing in .so ({len(missing)}):")
        for m in missing:
            lines.append(f"    - {m}")
    if extra:
        lines.append(f"  Extra in .so ({len(extra)}):")
        for m in extra:
            lines.append(f"    + {m}")
    return False, "\n".join(lines)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify JNI symbols in built libmeshline_ffi.so against MeshCoreBridge.kt"
    )
    parser.add_argument(
        "--so",
        type=Path,
        help="Path to an individual libmeshline_ffi.so file to verify",
    )
    parser.add_argument(
        "--variant",
        choices=["debug", "release"],
        default=None,
        help="Build variant to check (default: check debug or release directory)",
    )
    parser.add_argument(
        "--jni-libs-dir",
        type=Path,
        default=None,
        help="Explicit directory containing ABI folders (e.g. android/app/build/rustJniLibs/debug)",
    )
    parser.add_argument(
        "--kt",
        type=Path,
        default=DEFAULT_KT_PATH,
        help=f"Path to MeshCoreBridge.kt (default: {DEFAULT_KT_PATH.relative_to(ROOT)})",
    )
    parser.add_argument(
        "--rust",
        type=Path,
        default=DEFAULT_RUST_PATH,
        help="Path to crates/meshline-ffi/src/lib.rs (to cross-check Rust exports)",
    )
    parser.add_argument(
        "--nm",
        type=str,
        default=None,
        help="Explicit path to llvm-nm or nm binary",
    )
    parser.add_argument(
        "--abis",
        default=",".join(DEFAULT_ABIS),
        help=f"Comma-separated list of ABIs to require (default: {','.join(DEFAULT_ABIS)})",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if not args.kt.is_file():
        print(f"Error: Kotlin bridge file not found: {args.kt}", file=sys.stderr)
        return 1

    expected_methods = extract_bridge_methods(args.kt)
    if not expected_methods:
        print(f"Error: No external fun declarations found in {args.kt}", file=sys.stderr)
        return 1

    print(f"Found {len(expected_methods)} external method(s) in {args.kt.name}")

    if args.rust and args.rust.is_file():
        rust_methods = extract_rust_jni_methods(args.rust)
        rust_missing = set(expected_methods) - set(rust_methods)
        rust_extra = set(rust_methods) - set(expected_methods)
        if rust_missing or rust_extra:
            print("Warning: Rust source declarations and Kotlin bridge declarations differ:", file=sys.stderr)
            if rust_missing:
                print(f"  Missing in Rust: {sorted(rust_missing)}", file=sys.stderr)
            if rust_extra:
                print(f"  Extra in Rust: {sorted(rust_extra)}", file=sys.stderr)
        else:
            print(f"Rust source exports match Kotlin bridge declarations ({len(rust_methods)} methods)")

    nm_path = find_llvm_nm(args.nm)
    if nm_path:
        print(f"Using symbol extractor: {nm_path}")
    else:
        print("Using built-in pure Python ELF dynsym parser")

    # If single .so specified
    if args.so:
        ok, msg = verify_abi_symbols("custom", args.so, expected_methods, nm_path)
        print(msg)
        return 0 if ok else 1

    abis = [a.strip() for a in args.abis.split(",") if a.strip()]

    # Find the rustJniLibs directory
    jni_dir = args.jni_libs_dir
    if not jni_dir:
        base_build = ROOT / "android/app/build/rustJniLibs"
        if args.variant:
            jni_dir = base_build / args.variant
        else:
            # Check debug, then release
            if (base_build / "debug").is_dir():
                jni_dir = base_build / "debug"
            elif (base_build / "release").is_dir():
                jni_dir = base_build / "release"
            else:
                jni_dir = base_build / "debug"

    print(f"Checking native libraries in: {jni_dir}")
    if not jni_dir.is_dir():
        print(
            f"Error: Native library directory not found: {jni_dir}\n"
            f"Run `./gradlew cargoBuildDebug` or `./gradlew assembleDebug` first.",
            file=sys.stderr,
        )
        return 1

    all_ok = True
    for abi in abis:
        so_path = jni_dir / abi / "libmeshline_ffi.so"
        ok, msg = verify_abi_symbols(abi, so_path, expected_methods, nm_path)
        print(msg)
        if not ok:
            all_ok = False

    if all_ok:
        print(f"\nSUCCESS: All {len(abis)} ABIs match MeshCoreBridge ({len(expected_methods)} symbols each).")
        return 0
    else:
        print(f"\nFAILURE: JNI symbol verification failed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
