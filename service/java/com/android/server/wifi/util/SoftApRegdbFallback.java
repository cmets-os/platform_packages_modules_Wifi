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

import com.android.modules.utils.build.SdkLevel;

import java.util.ArrayList;
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
            builder.setAllowedAcsChannels(band, channels);
            Log.i(TAG, "Set AllowedAcsChannels band=" + band + " count=" + channels.length
                    + " from SoftApCapability");
        }
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
