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

import static android.net.wifi.SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD;
import static android.net.wifi.SoftApConfiguration.BAND_2GHZ;
import static android.net.wifi.SoftApConfiguration.BAND_5GHZ;
import static android.net.wifi.SoftApConfiguration.BAND_6GHZ;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiScanner;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.modules.utils.build.SdkLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SoftAP channel fallback from wireless-regdb when vendor HAL SAP list is empty.
 *
 * Does not override or spoof {@code WifiCountryCode}; uses the real ISO code only.
 */
public final class SoftApRegdbFallback {
    private static final String TAG = "SoftApRegdbFallback";

    private SoftApRegdbFallback() {}

    /**
     * @param countryCode ISO 3166-1 alpha-2 (may be null)
     * @param band SoftApConfiguration.BAND_5GHZ or BAND_6GHZ
     * @return channel numbers, or null if no regdb entry
     */
    @Nullable
    public static int[] channelsFor(@Nullable String countryCode, int band) {
        if (countryCode == null || countryCode.length() != 2) {
            return null;
        }
        return SoftApRegdbChannels.get(countryCode.toUpperCase(Locale.US), band);
    }

    /**
     * Prefer HAL/wificond list; only if empty, use regdb for SoftAP 5/6 GHz.
     *
     * @param regulatoryList channel numbers or frequencies from HAL/wificond (may be null/empty)
     * @param countryCode real WifiCountryCode ISO
     * @param softApBand SoftApConfiguration.BAND_*
     * @param inFrequencyMHz true to return MHz frequencies instead of channel numbers
     */
    @NonNull
    public static List<Integer> resolve(
            @Nullable List<Integer> regulatoryList,
            @Nullable String countryCode,
            int softApBand,
            boolean inFrequencyMHz) {
        if (regulatoryList != null && !regulatoryList.isEmpty()) {
            return regulatoryList;
        }
        if (softApBand != SoftApConfiguration.BAND_5GHZ
                && softApBand != SoftApConfiguration.BAND_6GHZ) {
            return regulatoryList != null ? regulatoryList : Collections.emptyList();
        }
        int[] chans = channelsFor(countryCode, softApBand);
        if (chans == null || chans.length == 0) {
            return regulatoryList != null ? regulatoryList : Collections.emptyList();
        }
        if (softApBand == SoftApConfiguration.BAND_5GHZ) {
            chans = filterSoftApSafe5g(chans);
        }
        Log.i(TAG, "Empty HAL SoftAP list for " + countryCode + " band=" + softApBand
                + "; using wireless-regdb (" + chans.length + " channels)");
        List<Integer> out = new ArrayList<>(chans.length);
        for (int ch : chans) {
            out.add(inFrequencyMHz
                    ? ApConfigUtil.convertChannelToFrequency(ch, softApBand)
                    : ch);
        }
        return out;
    }

    /**
     * Augment a SAP {@code getUsableChannels} result when 5/6 GHz portions
     * requested by {@code scannerBand} are empty.
     *
     * Non-empty HAL results for a band are left unchanged. 2.4/60 GHz are never
     * filled from regdb.
     */
    @Nullable
    public static List<WifiAvailableChannel> augmentUsableChannels(
            @Nullable List<WifiAvailableChannel> halChannels,
            @Nullable String countryCode,
            @WifiScanner.WifiBand int scannerBand) {
        if (halChannels == null) {
            return null;
        }
        final boolean need5 = (scannerBand & (WifiScanner.WIFI_BAND_5_GHZ
                | WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY
                | WifiScanner.WIFI_BAND_5_GHZ_LOW
                | WifiScanner.WIFI_BAND_5_GHZ_HIGH)) != 0;
        final boolean need6 = (scannerBand & WifiScanner.WIFI_BAND_6_GHZ) != 0;

        boolean has5 = false;
        boolean has6 = false;
        for (WifiAvailableChannel ch : halChannels) {
            int freq = ch.getFrequencyMhz();
            if (ScanResult.is5GHz(freq)) {
                has5 = true;
            } else if (ScanResult.is6GHz(freq)) {
                has6 = true;
            }
        }

        List<WifiAvailableChannel> out = null;
        if (need5 && !has5) {
            out = appendRegdbBand(halChannels, out, countryCode,
                    SoftApConfiguration.BAND_5GHZ);
        }
        if (need6 && !has6) {
            out = appendRegdbBand(halChannels, out, countryCode,
                    SoftApConfiguration.BAND_6GHZ);
        }
        return out != null ? out : halChannels;
    }

    /**
     * When ACS offload is used and AllowedAcsChannels are unset, copy SoftApCapability channel
     * lists (which may already include wireless-regdb fallback) into the SoftAP config so hostapd
     * ACS sees the same channels as Settings.
     */
    public static void applyCapabilityChannelsToAllowedAcs(
            @NonNull SoftApConfiguration.Builder builder,
            @NonNull SoftApConfiguration config,
            @NonNull SoftApCapability capability) {
        if (!SdkLevel.isAtLeastT()) {
            return;
        }
        if (!capability.areFeaturesSupported(SOFTAP_FEATURE_ACS_OFFLOAD)) {
            return;
        }
        for (int band : new int[] {BAND_2GHZ, BAND_5GHZ, BAND_6GHZ}) {
            if (!configurationIncludesBand(config, band)) {
                continue;
            }
            if (config.getAllowedAcsChannels(band).length > 0) {
                continue;
            }
            int[] channels = capability.getSupportedChannelList(band);
            if (channels == null || channels.length == 0) {
                continue;
            }
            if (band == BAND_5GHZ) {
                channels = filterSoftApSafe5g(channels);
            }
            builder.setAllowedAcsChannels(band, channels);
            Log.i(TAG, "Set AllowedAcsChannels band=" + band + " count=" + channels.length
                    + " from SoftApCapability"
                    + (band == BAND_5GHZ ? " (SoftAP-safe filter)" : ""));
        }
    }

    /**
     * When vendor HAL SoftAP 5 GHz was empty (capability filled from wireless-regdb), pin an
     * explicit non-DFS 5 GHz channel on ACS (channel 0) band entries that include 5 GHz.
     * Pure 2.4 GHz entries are left on ACS.
     */
    public static void maybePinHighBandChannelWhenHalSapEmpty(
            @NonNull SoftApConfiguration.Builder builder,
            @Nullable String countryCode,
            @NonNull SoftApCapability capability) {
        if (!SdkLevel.isAtLeastS()) {
            return;
        }
        if (!shouldPinHighBandWhenHalSapEmpty(countryCode, capability)) {
            return;
        }
        SoftApConfiguration staged = builder.build();
        SparseIntArray channels = staged.getChannels();
        int pin = 0;
        boolean needPin = false;
        for (int i = 0; i < channels.size(); i++) {
            int bandKey = channels.keyAt(i);
            if (channels.valueAt(i) == 0 && (bandKey & BAND_5GHZ) != 0
                    && (bandKey & BAND_6GHZ) == 0) {
                needPin = true;
                break;
            }
        }
        if (!needPin) {
            return;
        }
        pin = pickSoftApSafe5gChannel(staged.getAllowedAcsChannels(BAND_5GHZ));
        if (pin <= 0) {
            return;
        }
        SparseIntArray pinned = new SparseIntArray();
        boolean placed5 = false;
        for (int i = 0; i < channels.size(); i++) {
            int bandKey = channels.keyAt(i);
            int ch = channels.valueAt(i);
            if (ch == 0 && (bandKey & BAND_5GHZ) != 0 && (bandKey & BAND_6GHZ) == 0) {
                if (!placed5) {
                    pinned.put(BAND_5GHZ, pin);
                    placed5 = true;
                }
            } else {
                pinned.put(bandKey, ch);
            }
        }
        if (pinned.size() == 0 || pinned.size() > 2) {
            Log.e(TAG, "skip SoftAP 5 GHz pin: unsupported channel map size=" + pinned.size());
            return;
        }
        try {
            builder.setChannels(pinned);
            Log.i(TAG, "pin high-band SoftAP channel=" + pin
                    + " on BAND_5GHZ (HAL SAP empty)");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "skip invalid SoftAP 5 GHz pin channel=" + pin, e);
        }
    }

    /**
     * True when SoftAP 5 GHz capability matches the SoftAP-safe wireless-regdb list for
     * {@code countryCode}, which indicates HAL SAP list was empty and {@link #resolve} filled
     * from regdb (already filtered to UNII-1/UNII-3).
     */
    public static boolean shouldPinHighBandWhenHalSapEmpty(
            @Nullable String countryCode, @NonNull SoftApCapability capability) {
        int[] supported = capability.getSupportedChannelList(BAND_5GHZ);
        if (supported == null || supported.length == 0) {
            return false;
        }
        int[] regdb = channelsFor(countryCode, BAND_5GHZ);
        if (regdb == null || regdb.length == 0) {
            return false;
        }
        int[] safe = filterSoftApSafe5g(regdb);
        return Arrays.equals(supported, safe);
    }

    /** UNII-1 + UNII-3 style non-DFS SoftAP-safe 5 GHz channels. */
    static boolean isSoftApSafe5gChannel(int ch) {
        return (ch >= 36 && ch <= 48) || (ch >= 149 && ch <= 165);
    }

    /**
     * Prefer SoftAP-safe 5 GHz channels. If none are present, keep the original list
     * (better than empty).
     */
    @NonNull
    static int[] filterSoftApSafe5g(@NonNull int[] channels) {
        int n = 0;
        for (int ch : channels) {
            if (isSoftApSafe5gChannel(ch)) {
                n++;
            }
        }
        if (n == 0 || n == channels.length) {
            return channels;
        }
        int[] out = new int[n];
        int i = 0;
        for (int ch : channels) {
            if (isSoftApSafe5gChannel(ch)) {
                out[i++] = ch;
            }
        }
        return out;
    }

    /** Preferred SoftAP pin order: UNII-1 then UNII-3 (36 before 149). */
    private static final int[] SOFTAP_SAFE_5G_PIN_ORDER = {
            36, 40, 44, 48, 149, 153, 157, 161, 165
    };

    /**
     * First SoftAP-safe channel present in AllowedAcs: 36..48 then 149..165
     * (prefer 36, else 149).
     *
     * @return channel number, or 0 if none
     */
    static int pickSoftApSafe5gChannel(@Nullable int[] allowedAcs) {
        if (allowedAcs == null || allowedAcs.length == 0) {
            return 0;
        }
        for (int preferred : SOFTAP_SAFE_5G_PIN_ORDER) {
            for (int ch : allowedAcs) {
                if (ch == preferred) {
                    return preferred;
                }
            }
        }
        return 0;
    }

    private static boolean configurationIncludesBand(
            @NonNull SoftApConfiguration config, int band) {
        if (SdkLevel.isAtLeastS()) {
            for (int configured : config.getBands()) {
                if ((configured & band) != 0) {
                    return true;
                }
            }
            return false;
        }
        return (config.getBand() & band) != 0;
    }

    @Nullable
    private static List<WifiAvailableChannel> appendRegdbBand(
            @NonNull List<WifiAvailableChannel> halChannels,
            @Nullable List<WifiAvailableChannel> building,
            @Nullable String countryCode,
            int softApBand) {
        int[] chans = channelsFor(countryCode, softApBand);
        if (chans == null || chans.length == 0) {
            return building;
        }
        if (softApBand == SoftApConfiguration.BAND_5GHZ) {
            chans = filterSoftApSafe5g(chans);
        }
        List<WifiAvailableChannel> out = building;
        if (out == null) {
            out = new ArrayList<>(halChannels);
        }
        Log.i(TAG, "Empty HAL SoftAP usable channels for " + countryCode
                + " band=" + softApBand + "; using wireless-regdb ("
                + chans.length + " channels)");
        for (int ch : chans) {
            int freq = ApConfigUtil.convertChannelToFrequency(ch, softApBand);
            if (freq <= 0) {
                continue;
            }
            out.add(new WifiAvailableChannel(freq, WifiAvailableChannel.OP_MODE_SAP,
                    ScanResult.CHANNEL_WIDTH_20MHZ));
        }
        return out;
    }
}
