#!/usr/bin/env python3
"""Ensure the installable APK contains both HEOS and JNA for every supported ABI."""
import sys
import zipfile


def verify(path: str) -> None:
    with zipfile.ZipFile(path) as apk:
        for abi in ("arm64-v8a", "x86_64"):
            for library in ("libheos_core.so", "libjnidispatch.so"):
                name = f"lib/{abi}/{library}"
                try:
                    entry = apk.getinfo(name)
                except KeyError:
                    raise ValueError(f"Missing native library: {name}") from None
                if entry.file_size == 0:
                    raise ValueError(f"Empty native library: {name}")
                print(f"OK: {name} ({entry.file_size} bytes)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: verify-apk.py path/to/app-debug.apk")
    try:
        verify(sys.argv[1])
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
