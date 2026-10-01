#!/usr/bin/env python3
"""原生库 16KB 页对齐检查（重构方案 R-O①，随 release 前置固化）

背景：Google Play 对 targetSdk 35+ 强制要求原生库支持 16KB 内存页设备。
sherpa-onnx static-link AAR 当前已实测合规（arm64-v8a/x86_64 PT_LOAD align=16384），
本脚本把该检查固化为发布门禁，防止未来 AAR 升级引入未对齐库的回归。

用法：python3 scripts/check_16kb_alignment.py <apk_or_dir> [<apk_or_dir> ...]
对每个 APK 内的全部 .so 条目解析 ELF 程序头，任一 PT_LOAD 段对齐 < 16KB 即非零退出。

仅依赖标准库（zipfile/struct），可在 CI 与本地直接运行。
"""
import struct
import sys
import zipfile
from pathlib import Path

PT_LOAD = 1
REQUIRED_ALIGN = 16384


def max_pt_load_align(elf: bytes) -> int:
    if len(elf) < 0x40 or elf[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = elf[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", elf, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", elf, 0x36)[0]
        e_phnum = struct.unpack_from("<H", elf, 0x38)[0]
        fmt, size = "<IIQQQQQQ", 56
    else:
        e_phoff = struct.unpack_from("<I", elf, 0x1C)[0]
        e_phentsize = struct.unpack_from("<H", elf, 0x2A)[0]
        e_phnum = struct.unpack_from("<H", elf, 0x2C)[0]
        fmt, size = "<IIIIIIII", 32
    assert struct.calcsize(fmt) == size
    best = 0
    for i in range(e_phnum):
        fields = struct.unpack_from(fmt, elf, e_phoff + i * e_phentsize)
        if fields[0] == PT_LOAD:
            best = max(best, fields[-1])
    return best


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    apks = []
    for arg in argv[1:]:
        path = Path(arg)
        if path.is_dir():
            apks.extend(sorted(path.glob("*.apk")))
        else:
            apks.append(path)
    if not apks:
        print("no APK found to check")
        return 2

    failures = []
    for apk in apks:
        with zipfile.ZipFile(apk) as zf:
            for info in zf.infolist():
                if not info.filename.endswith(".so"):
                    continue
                data = zf.read(info)
                try:
                    align = max_pt_load_align(data)
                except ValueError as e:
                    failures.append(f"{apk.name}:{info.filename}: {e}")
                    continue
                status = "OK" if align >= REQUIRED_ALIGN else "TOO SMALL"
                print(f"{apk.name}  {info.filename}  PT_LOAD align={align}  {status}")
                if align < REQUIRED_ALIGN:
                    failures.append(
                        f"{apk.name}:{info.filename}: PT_LOAD align={align} < {REQUIRED_ALIGN}"
                    )
    if failures:
        print("\nFAILED — 16KB page alignment violations:")
        for f in failures:
            print(f"  {f}")
        return 1
    print("\nAll native libraries satisfy 16KB page alignment.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
