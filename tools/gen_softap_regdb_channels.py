#!/usr/bin/env python3
# Copyright (C) 2026 cmets-os
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Generate SoftApRegdbChannels.java from Linux wireless-regdb db.txt.

Pinned input: tools/wireless-regdb/db.txt (see PINNED_SHA in that directory).

Algorithm (per country block):
  1. Parse frequency rules (lo - hi @ bw), (power), FLAGS…
  2. Skip disabled / zero-power / NO-IR rules (SoftAP must initiate radiation).
  3. For bands overlapping 5150–5895 → 5 GHz SoftAP candidates; 5925–7125 → 6 GHz.
  4. Emit every standard 20 MHz channel whose center is fully inside [lo, hi].
  5. DFS / NO-OUTDOOR are kept as comment metadata only (not filtered).

Usage:
  python3 tools/gen_softap_regdb_channels.py
  python3 tools/gen_softap_regdb_channels.py --db tools/wireless-regdb/db.txt \\
      --out service/java/com/android/server/wifi/util/SoftApRegdbChannels.java
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

# Android ScanResult 5/6 GHz channelization
BAND_5_FIRST_CH = 32
BAND_5_START_MHZ = 5160
BAND_6_START_MHZ = 5955
BAND_6_CH2_MHZ = 5935

# SoftAP-relevant frequency windows (MHz)
WINDOW_5_LO, WINDOW_5_HI = 5150, 5895
WINDOW_6_LO, WINDOW_6_HI = 5925, 7125

COUNTRY_RE = re.compile(r"^country\s+([0-9A-Z]{2})\b")
# (lo - hi @ bw), (power...), FLAGS
RULE_RE = re.compile(
    r"^\s*\("
    r"(?P<lo>[0-9]+(?:\.[0-9]+)?)\s*-\s*(?P<hi>[0-9]+(?:\.[0-9]+)?)"
    r"\s*@\s*(?P<bw>[0-9]+(?:\.[0-9]+)?)"
    r"\)\s*,\s*\((?P<power>[^)]*)\)"
    r"(?P<flags>.*)$"
)


def freq5(ch: int) -> int:
    return (ch - BAND_5_FIRST_CH) * 5 + BAND_5_START_MHZ


def freq6(ch: int) -> int:
    if ch == 2:
        return BAND_6_CH2_MHZ
    return (ch - 1) * 5 + BAND_6_START_MHZ


def iter_5ghz_20mhz_channels() -> list[int]:
    """Standard 20 MHz SoftAP channel numbers in 5 GHz."""
    chans: list[int] = []
    # UNII-1 / UNII-2A / mid / UNII-2C style (36..144 step 4)
    for ch in range(36, 145, 4):
        chans.append(ch)
    # UNII-3 / UNII-4 (149..177 step 4)
    for ch in range(149, 178, 4):
        chans.append(ch)
    return chans


def iter_6ghz_20mhz_channels() -> list[int]:
    """Standard 20 MHz SoftAP channel numbers in 6 GHz (incl. ch 2)."""
    chans = [1, 2]
    for ch in range(5, 234, 4):
        chans.append(ch)
    return chans


def power_is_disabled(power: str) -> bool:
    p = power.strip().lower()
    if p == "0" or p.startswith("0 "):
        return True
    # bare zero milliwatt / dBm
    if re.match(r"^0(\.0+)?(\s*(mw|dbm))?$", p):
        return True
    return False


def channel_fully_inside(center: int, lo: float, hi: float, bw_mhz: int = 20) -> bool:
    half = bw_mhz / 2.0
    return center - half >= lo and center + half <= hi


def parse_db(db_text: str) -> dict[str, dict[str, set[int]]]:
    """Return {ISO: {"5": set(ch), "6": set(ch)}}."""
    result: dict[str, dict[str, set[int]]] = defaultdict(
        lambda: {"5": set(), "6": set()}
    )
    current: str | None = None
    chans5 = iter_5ghz_20mhz_channels()
    chans6 = iter_6ghz_20mhz_channels()

    for line in db_text.splitlines():
        m_country = COUNTRY_RE.match(line)
        if m_country:
            current = m_country.group(1)
            continue
        if current is None:
            continue
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = RULE_RE.match(line)
        if not m:
            continue
        lo = float(m.group("lo"))
        hi = float(m.group("hi"))
        bw = float(m.group("bw"))
        power = m.group("power")
        flags = m.group("flags").upper()
        if bw <= 0 or hi <= lo:
            continue
        if power_is_disabled(power):
            continue
        # SoftAP must TX; NO-IR means no initiating radiation.
        if re.search(r"\bNO-IR\b", flags):
            continue

        # 5 GHz overlap
        if hi > WINDOW_5_LO and lo < WINDOW_5_HI:
            for ch in chans5:
                f = freq5(ch)
                if channel_fully_inside(f, lo, hi):
                    result[current]["5"].add(ch)
        # 6 GHz overlap
        if hi > WINDOW_6_LO and lo < WINDOW_6_HI:
            for ch in chans6:
                f = freq6(ch)
                if channel_fully_inside(f, lo, hi):
                    result[current]["6"].add(ch)

    return result



def generate_java(countries: dict[str, dict[str, set[int]]], pinned_sha: str) -> str:
    isos = sorted(countries.keys())
    # Only emit countries that have at least one 5 or 6 GHz SoftAP channel.
    isos = [iso for iso in isos if countries[iso]["5"] or countries[iso]["6"]]

    parts: list[str] = []
    parts.append(
        """/*
 * Copyright (C) 2026 cmets-os
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.util;

import android.annotation.Nullable;
import android.net.wifi.SoftApConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SoftAP 5/6 GHz channel tables derived from Linux wireless-regdb.
 *
 * Generated by tools/gen_softap_regdb_channels.py — do not hand-edit.
 * wireless-regdb db.txt @ """
        + pinned_sha
        + """
 *
 * DFS / NO-OUTDOOR flags from regdb are not filtered here; SoftAP capability
 * lists use channel numbers only. NO-IR / zero-power rules are omitted.
 */
public final class SoftApRegdbChannels {
    private SoftApRegdbChannels() {}

    private static final Map<String, int[]> CHANNELS_5GHZ;
    private static final Map<String, int[]> CHANNELS_6GHZ;

    static {
        Map<String, int[]> map5 = new HashMap<>();
        Map<String, int[]> map6 = new HashMap<>();
"""
    )

    for iso in isos:
        ch5 = sorted(countries[iso]["5"])
        ch6 = sorted(countries[iso]["6"])
        arr5 = "new int[] {" + ", ".join(str(c) for c in ch5) + "}" if ch5 else "null"
        arr6 = "new int[] {" + ", ".join(str(c) for c in ch6) + "}" if ch6 else "null"
        if ch5:
            parts.append(f'        map5.put("{iso}", {arr5});\n')
        if ch6:
            parts.append(f'        map6.put("{iso}", {arr6});\n')

    parts.append(
        """
        CHANNELS_5GHZ = Collections.unmodifiableMap(map5);
        CHANNELS_6GHZ = Collections.unmodifiableMap(map6);
    }

    /**
     * @param iso ISO 3166-1 alpha-2 country code (case-insensitive)
     * @param softApBand {@link SoftApConfiguration#BAND_5GHZ} or
     *                   {@link SoftApConfiguration#BAND_6GHZ}
     * @return channel numbers, or null if no regdb SoftAP entry for that band
     */
    @Nullable
    public static int[] get(@Nullable String iso, int softApBand) {
        if (iso == null || iso.length() != 2) {
            return null;
        }
        String key = iso.toUpperCase(Locale.US);
        if (softApBand == SoftApConfiguration.BAND_5GHZ) {
            return CHANNELS_5GHZ.get(key);
        }
        if (softApBand == SoftApConfiguration.BAND_6GHZ) {
            return CHANNELS_6GHZ.get(key);
        }
        return null;
    }
}
"""
    )
    return "".join(parts)


def main(argv: list[str]) -> int:
    repo_tools = Path(__file__).resolve().parent
    default_db = repo_tools / "wireless-regdb" / "db.txt"
    default_pin = repo_tools / "wireless-regdb" / "PINNED_SHA"
    default_out = (
        repo_tools.parent
        / "service"
        / "java"
        / "com"
        / "android"
        / "server"
        / "wifi"
        / "util"
        / "SoftApRegdbChannels.java"
    )

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=default_db)
    parser.add_argument("--out", type=Path, default=default_out)
    parser.add_argument("--pinned-sha", default=None)
    args = parser.parse_args(argv)

    if not args.db.is_file():
        print(f"error: db.txt not found: {args.db}", file=sys.stderr)
        return 1

    pinned = args.pinned_sha
    if not pinned and default_pin.is_file():
        pinned = default_pin.read_text().strip()
    if not pinned:
        pinned = "unknown"

    countries = parse_db(args.db.read_text(encoding="utf-8", errors="replace"))
    java = generate_java(countries, pinned)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(java, encoding="utf-8")

    n5 = sum(1 for c in countries.values() if c["5"])
    n6 = sum(1 for c in countries.values() if c["6"])
    ru5 = sorted(countries.get("RU", {}).get("5", []))
    ru6 = sorted(countries.get("RU", {}).get("6", []))
    print(f"Wrote {args.out}")
    print(f"Pinned SHA: {pinned}")
    print(f"Countries with 5 GHz SoftAP: {n5}, with 6 GHz: {n6}")
    print(f"RU 5 GHz ({len(ru5)}): {ru5}")
    print(f"RU 6 GHz ({len(ru6)}): {ru6}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
