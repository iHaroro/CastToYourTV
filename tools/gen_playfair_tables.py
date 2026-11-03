# -*- coding: utf-8 -*-
"""从 UxPlay 的 omg_hax.h 提取 PlayFair 解密所需的查找表，打包为 Android assets 二进制 + Java 加载类。"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "analysis/uxplay/lib/playfair/omg_hax.h"
OUT_BIN = ROOT / "app/src/main/assets/playfair_tables.bin"
OUT_JAVA = ROOT / "app/src/main/java/com/casttoyourtv/receiver/airplay/PlayfairTables.java"

# 顺序即二进制布局；char=1字节，long/uint32=4字节小端
TABLES = [
    ("message_key", "char"),
    ("message_iv", "char"),
    ("z_key", "char"),
    ("x_key", "char"),
    ("t_key", "char"),
    ("table_s5", "long"),
    ("table_s6", "long"),
    ("table_s7", "long"),
    ("table_s8", "long"),
    ("table_s1", "char"),
    ("table_s3", "char"),
    ("table_s4", "char"),
    ("table_s2", "char"),
    ("table_s9", "long"),
    ("table_s10", "char"),
]

src = SRC.read_text(encoding="utf-8")


def find_array(name):
    m = re.search(r"\b" + name + r"\s*(\[[^\]]*\])+\s*=\s*\{", src)
    if not m:
        raise SystemExit(f"array {name} not found")
    start = src.index("{", m.end() - 1)
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    raise SystemExit(f"array {name} not terminated")


def parse_values(block):
    vals = []
    for m in re.finditer(r"(?:0x[0-9a-fA-F]+|\d+)", block):
        s = m.group()
        vals.append(int(s, 16) if s.startswith("0x") else int(s))
    return vals


blob = bytearray()
offsets = {}
lengths = {}
for name, typ in TABLES:
    vals = parse_values(find_array(name))
    offsets[name] = len(blob)
    lengths[name] = len(vals)
    if typ == "char":
        for v in vals:
            blob.append(v & 0xFF)
    else:
        for v in vals:
            blob += (v & 0xFFFFFFFF).to_bytes(4, "little")

OUT_BIN.parent.mkdir(parents=True, exist_ok=True)
OUT_BIN.write_bytes(blob)

const_lines = "\n".join(
    f"    public static final int {name.upper()}_OFF = {offsets[name]}; // {lengths[name]} entries"
    for name, _ in TABLES)
field_lines = "\n".join(
    f"    public static final {'byte' if typ == 'char' else 'int'}[] {name} = new {'byte' if typ == 'char' else 'int'}[{lengths[name]}];"
    for name, typ in TABLES)
load_lines = "\n".join(
    f"        System.arraycopy(blob, {name.upper()}_OFF, {name}, 0, {lengths[name]});"
    if typ == "char" else
    f"        for (int i = 0; i < {lengths[name]}; i++) {name}[i] = readWord(blob, {name.upper()}_OFF + i * 4);"
    for name, typ in TABLES)

java = f"""package com.casttoyourtv.receiver.airplay;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * PlayFair 解密查找表（由 tools/gen_playfair_tables.py 从 UxPlay omg_hax.h 自动生成）。
 */
public final class PlayfairTables {{
    public static final String ASSET = "playfair_tables.bin";

{const_lines}

{field_lines}

    private static boolean inited;

    public static synchronized void init(Context ctx) {{
        if (inited) return;
        try (InputStream in = ctx.getAssets().open(ASSET)) {{
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] blob = out.toByteArray();
{load_lines}
        }} catch (Exception e) {{
            throw new IllegalStateException("load playfair_tables failed", e);
        }}
        inited = true;
    }}

    private static int readWord(byte[] b, int off) {{
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }}

    private PlayfairTables() {{
    }}
}}
"""

OUT_JAVA.parent.mkdir(parents=True, exist_ok=True)
OUT_JAVA.write_text(java, encoding="utf-8", newline="\n")

print(f"binary: {OUT_BIN} ({len(blob)} bytes)")
for name, _ in TABLES:
    print(f"  {name:12s} off={offsets[name]:6d} len={lengths[name]}")
