/*
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiScanner;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiBaseTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link SoftApRegdbFallback}.
 */
@SmallTest
public class SoftApRegdbFallbackTest extends WifiBaseTest {

    @Test
    public void channelsFor_nullOrInvalidCountry_returnsNull() {
        assertNull(SoftApRegdbFallback.channelsFor(null, SoftApConfiguration.BAND_5GHZ));
        assertNull(SoftApRegdbFallback.channelsFor("", SoftApConfiguration.BAND_5GHZ));
        assertNull(SoftApRegdbFallback.channelsFor("R", SoftApConfiguration.BAND_5GHZ));
        assertNull(SoftApRegdbFallback.channelsFor("RUS", SoftApConfiguration.BAND_5GHZ));
    }

    @Test
    public void channelsFor_ru_has5And6Ghz() {
        int[] ch5 = SoftApRegdbFallback.channelsFor("RU", SoftApConfiguration.BAND_5GHZ);
        int[] ch6 = SoftApRegdbFallback.channelsFor("ru", SoftApConfiguration.BAND_6GHZ);
        assertNotNull(ch5);
        assertNotNull(ch6);
        assertTrue(ch5.length > 0);
        assertTrue(ch6.length > 0);
        assertTrue(Arrays.stream(ch5).anyMatch(c -> c == 36));
        assertTrue(Arrays.stream(ch5).anyMatch(c -> c == 149));
        assertTrue(Arrays.stream(ch6).anyMatch(c -> c == 1));
    }

    @Test
    public void resolve_nonEmptyHal_unchanged() {
        List<Integer> hal = Arrays.asList(149, 153);
        List<Integer> out = SoftApRegdbFallback.resolve(
                hal, "RU", SoftApConfiguration.BAND_5GHZ, false);
        assertSame(hal, out);
        assertEquals(Arrays.asList(149, 153), out);
    }

    @Test
    public void resolve_emptyHal_ru_fills5GhzChannels() {
        List<Integer> out = SoftApRegdbFallback.resolve(
                Collections.emptyList(), "RU", SoftApConfiguration.BAND_5GHZ, false);
        assertFalse(out.isEmpty());
        assertTrue(out.contains(36));
        assertTrue(out.contains(149));
    }

    @Test
    public void resolve_nullHal_ru_fillsFrequencies() {
        List<Integer> out = SoftApRegdbFallback.resolve(
                null, "RU", SoftApConfiguration.BAND_5GHZ, true);
        assertFalse(out.isEmpty());
        assertTrue(out.contains(5180)); // ch 36
        assertTrue(out.contains(5745)); // ch 149
    }

    @Test
    public void resolve_nullCountry_noCrashEmpty() {
        List<Integer> out = SoftApRegdbFallback.resolve(
                Collections.emptyList(), null, SoftApConfiguration.BAND_5GHZ, false);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    public void resolve_2ghz_notFilledFromRegdb() {
        List<Integer> out = SoftApRegdbFallback.resolve(
                Collections.emptyList(), "RU", SoftApConfiguration.BAND_2GHZ, false);
        assertTrue(out.isEmpty());
    }

    @Test
    public void augmentUsableChannels_empty5GhzSap_ru_fills() {
        List<WifiAvailableChannel> hal = new ArrayList<>();
        List<WifiAvailableChannel> out = SoftApRegdbFallback.augmentUsableChannels(
                hal, "RU", WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS);
        assertNotNull(out);
        assertFalse(out.isEmpty());
        boolean saw5 = false;
        for (WifiAvailableChannel ch : out) {
            if (ScanResult.is5GHz(ch.getFrequencyMhz())) {
                saw5 = true;
                assertEquals(WifiAvailableChannel.OP_MODE_SAP, ch.getOperationalModes());
            }
        }
        assertTrue(saw5);
    }

    @Test
    public void augmentUsableChannels_nonEmpty5Ghz_unchanged() {
        List<WifiAvailableChannel> hal = Arrays.asList(
                new WifiAvailableChannel(5745, WifiAvailableChannel.OP_MODE_SAP,
                        ScanResult.CHANNEL_WIDTH_20MHZ));
        List<WifiAvailableChannel> out = SoftApRegdbFallback.augmentUsableChannels(
                hal, "RU", WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS);
        assertSame(hal, out);
        assertEquals(1, out.size());
    }

    @Test
    public void augmentUsableChannels_nullHal_returnsNull() {
        assertNull(SoftApRegdbFallback.augmentUsableChannels(
                null, "RU", WifiScanner.WIFI_BAND_5_GHZ));
    }

    @Test
    public void softApRegdbChannels_get_matchesChannelsFor() {
        int[] a = SoftApRegdbChannels.get("RU", SoftApConfiguration.BAND_5GHZ);
        int[] b = SoftApRegdbFallback.channelsFor("RU", SoftApConfiguration.BAND_5GHZ);
        assertArrayEquals(a, b);
    }

    @Test
    public void applyCapabilityChannelsToAllowedAcs_fillsEmpty5gFromCapability() {
        SoftApCapability capability = new SoftApCapability(
                SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD);
        capability.setSupportedChannelList(SoftApConfiguration.BAND_5GHZ, new int[] {36, 40});
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setBand(SoftApConfiguration.BAND_5GHZ)
                .build();
        SoftApConfiguration.Builder builder = new SoftApConfiguration.Builder(config);

        SoftApRegdbFallback.applyCapabilityChannelsToAllowedAcs(builder, config, capability);

        SoftApConfiguration out = builder.build();
        assertArrayEquals(new int[] {36, 40},
                out.getAllowedAcsChannels(SoftApConfiguration.BAND_5GHZ));
    }

    @Test
    public void applyCapabilityChannelsToAllowedAcs_ruLike5g_stripsDfsFromAllowedAcs() {
        // RU-like capability spanning UNII-1 through UNII-3 including DFS 52–144.
        int[] ruLike = new int[] {
                36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128,
                132, 136, 140, 144, 149, 153, 157, 161, 165
        };
        SoftApCapability capability = new SoftApCapability(
                SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD);
        capability.setSupportedChannelList(SoftApConfiguration.BAND_5GHZ, ruLike);
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setBand(SoftApConfiguration.BAND_5GHZ)
                .build();
        SoftApConfiguration.Builder builder = new SoftApConfiguration.Builder(config);

        SoftApRegdbFallback.applyCapabilityChannelsToAllowedAcs(builder, config, capability);

        SoftApConfiguration out = builder.build();
        int[] allowed = out.getAllowedAcsChannels(SoftApConfiguration.BAND_5GHZ);
        assertArrayEquals(new int[] {36, 40, 44, 48, 149, 153, 157, 161, 165}, allowed);
        for (int ch : allowed) {
            assertFalse("DFS channel " + ch + " must not be in AllowedAcs",
                    ch >= 52 && ch <= 144);
        }
    }

    @Test
    public void resolve_emptyHal_ru_excludesDfsChannels() {
        List<Integer> out = SoftApRegdbFallback.resolve(
                Collections.emptyList(), "RU", SoftApConfiguration.BAND_5GHZ, false);
        assertFalse(out.isEmpty());
        assertTrue(out.contains(36));
        assertTrue(out.contains(149));
        for (int ch : out) {
            assertFalse("DFS channel " + ch + " must not come from SoftAP regdb resolve",
                    ch >= 52 && ch <= 144);
        }
    }

    @Test
    public void shouldPinHighBandWhenHalSapEmpty_matchesRegdbSafeList() {
        SoftApCapability capability = new SoftApCapability(0L);
        List<Integer> fromRegdb = SoftApRegdbFallback.resolve(
                Collections.emptyList(), "RU", SoftApConfiguration.BAND_5GHZ, false);
        capability.setSupportedChannelList(SoftApConfiguration.BAND_5GHZ,
                fromRegdb.stream().mapToInt(Integer::intValue).toArray());
        assertTrue(SoftApRegdbFallback.shouldPinHighBandWhenHalSapEmpty("RU", capability));

        SoftApCapability halNonEmpty = new SoftApCapability(0L);
        halNonEmpty.setSupportedChannelList(SoftApConfiguration.BAND_5GHZ,
                new int[] {149, 153});
        assertFalse(SoftApRegdbFallback.shouldPinHighBandWhenHalSapEmpty("RU", halNonEmpty));

        // Full unfiltered regdb (includes DFS) must not match SoftAP-safe pin condition.
        SoftApCapability fullRegdb = new SoftApCapability(0L);
        fullRegdb.setSupportedChannelList(SoftApConfiguration.BAND_5GHZ,
                SoftApRegdbFallback.channelsFor("RU", SoftApConfiguration.BAND_5GHZ));
        assertFalse(SoftApRegdbFallback.shouldPinHighBandWhenHalSapEmpty("RU", fullRegdb));
    }

    @Test
    public void maybePinHighBandChannelWhenHalSapEmpty_pins5gEntryLeaves2gAcs() {
        SoftApCapability capability = new SoftApCapability(
                SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD);
        List<Integer> fromRegdb = SoftApRegdbFallback.resolve(
                Collections.emptyList(), "RU", SoftApConfiguration.BAND_5GHZ, false);
        capability.setSupportedChannelList(SoftApConfiguration.BAND_5GHZ,
                fromRegdb.stream().mapToInt(Integer::intValue).toArray());
        capability.setSupportedChannelList(SoftApConfiguration.BAND_2GHZ,
                new int[] {1, 6, 11});

        android.util.SparseIntArray channels = new android.util.SparseIntArray();
        channels.put(SoftApConfiguration.BAND_2GHZ, 0);
        channels.put(SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ, 0);
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setChannels(channels)
                .build();
        SoftApConfiguration.Builder builder = new SoftApConfiguration.Builder(config);
        SoftApRegdbFallback.applyCapabilityChannelsToAllowedAcs(builder, config, capability);
        SoftApRegdbFallback.maybePinHighBandChannelWhenHalSapEmpty(builder, "RU", capability);

        SoftApConfiguration out = builder.build();
        android.util.SparseIntArray outCh = out.getChannels();
        assertEquals(0, outCh.get(SoftApConfiguration.BAND_2GHZ));
        assertEquals(36, outCh.get(SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ));
    }
}
