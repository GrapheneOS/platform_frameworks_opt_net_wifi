/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wifi.coex;

import static android.net.wifi.ScanResult.UNSPECIFIED;
import static android.net.wifi.WifiScanner.WIFI_BAND_24_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_5_GHZ;

import android.net.wifi.CoexUnsafeChannel;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations;
import android.telephony.PhysicalChannelConfig;
import android.telephony.TelephonyManager;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Class containing the unsafe channel algorithms and other utility methods for Wi-Fi/Cellular coex.
 */
public class CoexUtils {
    private static final int INVALID_CHANNEL = -1;
    @VisibleForTesting
    /* package */ static final int INVALID_BAND = -1;
    @VisibleForTesting
    /* package */ static final int INVALID_FREQ = -1;

    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_20_MHZ = create5g20MhzChannels();
    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_40_MHZ = create5g40MhzChannels();
    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_80_MHZ = create5g80MhzChannels();
    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_160_MHZ = create5g160MhzChannels();
    private static final SparseIntArray DEPENDENT_MAP_5_GHZ = create5gDependentChannelMap();
    private static final Set<LteBandInfo> LTE_BAND_INFO_SET = createLteBandInfoSet();
    private static final NavigableMap<Integer, LteBandInfo> EARFCN_OFFSET_TO_LTE_BAND_INFO =
            createEarfcnOffsetToLteBandInfo();

    private static NavigableSet<Integer> create5g20MhzChannels() {
        NavigableSet<Integer> set = new TreeSet<>();
        for (int chan = 32; chan <= 68; chan += 4) {
            set.add(chan);
        }
        for (int chan = 96; chan <= 144; chan += 4) {
            set.add(chan);
        }
        for (int chan = 149; chan <= 173; chan += 4) {
            set.add(chan);
        }
        return set;
    }

    private static NavigableSet<Integer> create5g40MhzChannels() {
        NavigableSet<Integer> set = new TreeSet<>();
        set.add(34);
        for (int chan = 38; chan <= 62; chan += 8) {
            set.add(chan);
        }
        for (int chan = 102; chan <= 142; chan += 8) {
            set.add(chan);
        }
        for (int chan = 151; chan <= 159; chan += 8) {
            set.add(chan);
        }
        return set;
    }

    private static NavigableSet<Integer> create5g80MhzChannels() {
        NavigableSet<Integer> set = new TreeSet<>();
        set.add(42);
        set.add(58);
        set.add(106);
        set.add(122);
        set.add(138);
        set.add(155);
        return set;
    }

    private static NavigableSet<Integer> create5g160MhzChannels() {
        NavigableSet<Integer> set = new TreeSet<>();
        set.add(50);
        set.add(114);
        return set;
    }

    /**
     * Creates a SparseIntArray map of 5GHz channel to the dependent channel that contains it,
     * if it exists.
     */
    private static SparseIntArray create5gDependentChannelMap() {
        SparseIntArray map = new SparseIntArray();
        // Map 160Mhz channels with their dependency 80Mhz channels.
        for (int chan : CHANNEL_SET_5_GHZ_160_MHZ) {
            map.put(chan - 8, chan);
            map.put(chan + 8, chan);
        }
        // Map 80Mhz channels with their dependency 40Mhz channels.
        for (int chan : CHANNEL_SET_5_GHZ_80_MHZ) {
            map.put(chan - 4, chan);
            map.put(chan + 4, chan);
        }
        // Map 40Mhz channels with their dependency 20Mhz channels.
        // Note channel 36 maps to both 34 and 38, but will only map to 38 in the dependent map.
        for (int chan : CHANNEL_SET_5_GHZ_40_MHZ) {
            map.put(chan - 2, chan);
            map.put(chan + 2, chan);
        }
        return map;
    }

    // Channels to frequencies

    /** Gets the upper or lower edge of a given channel */
    private static int getChannelEdgeKhz(int channel, @WifiAnnotations.WifiBandBasic int band,
            boolean lowerEdge) {
        int centerFreqMhz = ScanResult.convertChannelToFrequencyMhz(channel, band);
        if (centerFreqMhz == UNSPECIFIED) {
            return INVALID_FREQ;
        }

        int bandwidthOffsetMhz = 0;
        if (band == WIFI_BAND_24_GHZ) {
            bandwidthOffsetMhz = 11;
        } else if (band == WIFI_BAND_5_GHZ) {
            if (CHANNEL_SET_5_GHZ_20_MHZ.contains(channel)) {
                bandwidthOffsetMhz = 10;
            } else if (CHANNEL_SET_5_GHZ_40_MHZ.contains(channel)) {
                bandwidthOffsetMhz = 20;
            } else if (CHANNEL_SET_5_GHZ_80_MHZ.contains(channel)) {
                bandwidthOffsetMhz = 40;
            } else {
                bandwidthOffsetMhz = 80;
            }
        }

        if (lowerEdge) {
            bandwidthOffsetMhz = -bandwidthOffsetMhz;
        }
        return (centerFreqMhz + bandwidthOffsetMhz) * 1_000;
    }

    /** Gets the lower frequency of a given channel */
    @VisibleForTesting
    /* package */ static int getLowerFreqKhz(int channel, @WifiAnnotations.WifiBandBasic int band) {
        return getChannelEdgeKhz(channel, band, true);
    }

    /** Gets the upper frequency of a given channel */
    @VisibleForTesting
    /* package */ static int getUpperFreqKhz(int channel, @WifiAnnotations.WifiBandBasic int band) {
        return getChannelEdgeKhz(channel, band, false);
    }

    // Frequencies to channels

    /**
     * Gets the highest 2.4GHz Wi-Fi channel overlapping the upper edge of an interference
     * frequency approaching from below the band.
     *
     * Returns INVALID_CHANNEL if there is no overlap.
     */
    private static int get2gHighestOverlapChannel(int upperEdgeKhz) {
        final int band = WIFI_BAND_24_GHZ;
        if (upperEdgeKhz > getLowerFreqKhz(14, band)) {
            return 14;
        }
        if (upperEdgeKhz > getLowerFreqKhz(13, band)) {
            return 13;
        }
        final int chan1LowerFreqKhz = getLowerFreqKhz(1, band);
        if (upperEdgeKhz > chan1LowerFreqKhz) {
            return getOffsetChannel(1, upperEdgeKhz - chan1LowerFreqKhz, 1);
        }
        // Edge does not overlap the band.
        return INVALID_CHANNEL;
    }

    /**
     * Gets the lowest 2.4GHz Wi-Fi channel overlapping the lower edge of an interference
     * frequency approaching from above the band.
     *
     * Returns INVALID_CHANNEL if there is no overlap.
     */
    private static int get2gLowestOverlapChannel(int lowerEdgeKhz) {
        final int band = WIFI_BAND_24_GHZ;
        if (lowerEdgeKhz < getUpperFreqKhz(1, band)) {
            return 1;
        }
        final int chan13UpperFreqKhz = getUpperFreqKhz(13, band);
        if (lowerEdgeKhz < chan13UpperFreqKhz) {
            return getOffsetChannel(13, lowerEdgeKhz - chan13UpperFreqKhz, 1);
        }
        if (lowerEdgeKhz < getUpperFreqKhz(14, band)) {
            return 14;
        }
        // Edge does not overlap the band.
        return INVALID_CHANNEL;
    }

    /**
     * Gets the highest 5GHz Wi-Fi channel overlapping the upper edge of an interference
     * frequency approaching from below the band.
     *
     * Returns INVALID_CHANNEL if there is no overlap.
     */
    private static int get5gHighestOverlap20MhzChannel(int upperEdgeKhz) {
        final int band = WIFI_BAND_5_GHZ;
        // 149 to 173
        if (upperEdgeKhz > getLowerFreqKhz(173, band)) {
            return 173;
        }
        final int chan149LowerFreqKhz = getLowerFreqKhz(149, band);
        if (upperEdgeKhz > chan149LowerFreqKhz) {
            return getOffsetChannel(149, upperEdgeKhz - chan149LowerFreqKhz, 4);
        }
        // 96 to 144
        if (upperEdgeKhz > getLowerFreqKhz(144, band)) {
            return 144;
        }
        final int chan96LowerFreqKhz = getLowerFreqKhz(96, band);
        if (upperEdgeKhz > chan96LowerFreqKhz) {
            return getOffsetChannel(96, upperEdgeKhz - chan96LowerFreqKhz, 4);
        }
        // 32 to 68
        if (upperEdgeKhz > getLowerFreqKhz(68, band)) {
            return 68;
        }
        final int chan32LowerFreqKhz = getLowerFreqKhz(32, band);
        if (upperEdgeKhz > chan32LowerFreqKhz) {
            return getOffsetChannel(32, upperEdgeKhz - chan32LowerFreqKhz, 4);
        }
        // Edge does not overlap the band.
        return INVALID_CHANNEL;
    }

    /**
     * Gets the lowest 5GHz Wi-Fi channel overlapping the lower edge of an interference
     * frequency approaching from above the band.
     *
     * Returns INVALID_CHANNEL if there is no overlap.
     */
    private static int get5gLowestOverlap20MhzChannel(int lowerEdgeKhz) {
        final int band = WIFI_BAND_5_GHZ;
        // 32 to 68
        if (lowerEdgeKhz < getUpperFreqKhz(32, band)) {
            return 32;
        }
        final int chan68UpperFreqKhz = getUpperFreqKhz(68, band);
        if (lowerEdgeKhz < chan68UpperFreqKhz) {
            return getOffsetChannel(68, lowerEdgeKhz - chan68UpperFreqKhz, 4);
        }
        // 96 to 144
        if (lowerEdgeKhz < getUpperFreqKhz(96, band)) {
            return 96;
        }
        final int chan144UpperFreqKhz = getUpperFreqKhz(144, band);
        if (lowerEdgeKhz < chan144UpperFreqKhz) {
            return getOffsetChannel(144, lowerEdgeKhz - chan144UpperFreqKhz, 4);
        }
        // 149 to 173
        if (lowerEdgeKhz < getUpperFreqKhz(149, band)) {
            return 149;
        }
        final int chan173UpperFreqKhz = getUpperFreqKhz(173, band);
        if (lowerEdgeKhz < chan173UpperFreqKhz) {
            return getOffsetChannel(173, lowerEdgeKhz - chan173UpperFreqKhz, 4);
        }
        // Edge does not overlap the band.
        return INVALID_CHANNEL;
    }

    /**
     * Returns the furthest channel located a given frequency offset away from a start channel
     * counting by a given channel step size. A positive frequency offset will give a channel
     * above the start, and a negative frequency offset will give a channel below the start.
     *
     * @param startChannel Channel to start from
     * @param offsetKhz Offset distance in Khz
     * @param channelStep Step size to count channels by
     * @return
     */
    private static int getOffsetChannel(int startChannel, int offsetKhz, int channelStep) {
        // Each channel number always counts 5Mhz.
        int channelSpacingKhz = 5_000;
        int offsetChannel = startChannel + offsetKhz / channelSpacingKhz;
        // Offset lands directly channel edge; use previous channel based on offset direction.
        if (offsetKhz % (channelSpacingKhz * channelStep) == 0) {
            if (offsetKhz > 0) {
                offsetChannel -= channelStep;
            } else {
                offsetChannel += channelStep;
            }
        }
        return offsetChannel;
    }

    /**
     * Returns the percent overlap (0 to 100) of an aggressor frequency range over a victim
     * frequency range,
     */
    private static int getOverlapPercent(int aggressorLowerKhz, int aggressorUpperKhz,
            int victimLowerKhz, int victimUpperKhz) {
        final int victimBandwidthKhz = victimUpperKhz - victimLowerKhz;
        int overlapWidthKhz = Math.min(aggressorUpperKhz, victimUpperKhz)
                - Math.max(aggressorLowerKhz, victimLowerKhz);
        if (overlapWidthKhz < 0) {
            overlapWidthKhz = 0;
        }
        return overlapWidthKhz * 100 / victimBandwidthKhz;
    }

    /**
     * Returns the CoexUnsafeChannels for the given cell channel and threshold.
     */
    public static Set<CoexUnsafeChannel> getNeighboringCoexUnsafeChannels(
            int cellFreqKhz, int cellBandwidthKhz, int thresholdKhz) {
        Set<CoexUnsafeChannel> coexUnsafeChannels = new HashSet<>();
        final int unsafeLowerKhz = cellFreqKhz - (cellBandwidthKhz / 2) - thresholdKhz;
        final int unsafeUpperKhz = cellFreqKhz + (cellBandwidthKhz / 2) + thresholdKhz;

        // 2.4Ghz
        final int lowest2gChannel = get2gLowestOverlapChannel(unsafeLowerKhz);
        final int highest2gChannel = get2gHighestOverlapChannel(unsafeUpperKhz);
        // If the interference has a valid overlap over the 2.4GHz band, mark every channel
        // in the inclusive range of the lowest and highest overlapped channels.
        if (lowest2gChannel != INVALID_CHANNEL && highest2gChannel != INVALID_CHANNEL) {
            for (int channel = lowest2gChannel; channel <= highest2gChannel; channel++) {
                coexUnsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, channel));
            }
        }

        // 5Ghz
        final int highest5gChannel = get5gHighestOverlap20MhzChannel(unsafeUpperKhz);
        final int lowest5gChannel = get5gLowestOverlap20MhzChannel(unsafeLowerKhz);
        // If the interference has a valid overlap over the 5GHz band, mark every channel
        // in the inclusive range of the lowest and highest overlapped channels.
        if (lowest5gChannel != INVALID_CHANNEL && highest5gChannel != INVALID_CHANNEL) {
            final Set<Integer> overlapped5g20MhzChannels = CHANNEL_SET_5_GHZ_20_MHZ.subSet(
                    lowest5gChannel, true,
                    highest5gChannel, true);
            final Set<Integer> seen = new HashSet<>();
            // Mark overlapped 20Mhz channels and their dependents as unsafe
            for (int channel : overlapped5g20MhzChannels) {
                while (channel != 0) {
                    if (!seen.add(channel)) {
                        // Dependent channel was already marked unsafe by another dependency channel
                        break;
                    }
                    coexUnsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, channel));
                    // Go to each dependent 40, 80, 160Mhz channel and mark them as unsafe.
                    // If a dependent doesn't exist, channel will be set to 0 and the loop ends.
                    channel = DEPENDENT_MAP_5_GHZ.get(channel, 0);
                }
            }
            // 36 should also map to 34, but only maps to 38 in the dependent channel map.
            if (overlapped5g20MhzChannels.contains(36)) {
                coexUnsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, 34));
            }
        }

        return coexUnsafeChannels;
    }

    /**
     * Returns the 2.4GHz UnsafeChannels affected by the harmonic interference from a given uplink
     * cell channel.
     */
    public static Set<CoexUnsafeChannel> get2gHarmonicCoexUnsafeChannels(
            int ulFreqKhz, int ulBandwidthKhz, int harmonicDegree, int overlapPercentThreshold) {
        Set<CoexUnsafeChannel> coexUnsafeChannels = new HashSet<>();
        final int unsafeLowerKhz = (ulFreqKhz - (ulBandwidthKhz / 2)) * harmonicDegree;
        final int unsafeUpperKhz = (ulFreqKhz + (ulBandwidthKhz / 2)) * harmonicDegree;

        int lowest2gChannel = get2gLowestOverlapChannel(unsafeLowerKhz);
        int highest2gChannel = get2gHighestOverlapChannel(unsafeUpperKhz);
        if (lowest2gChannel != INVALID_CHANNEL && highest2gChannel != INVALID_CHANNEL) {
            // Find lowest channel at max overlap
            while (getOverlapPercent(unsafeLowerKhz, unsafeUpperKhz,
                    getLowerFreqKhz(lowest2gChannel, WIFI_BAND_24_GHZ),
                    getUpperFreqKhz(lowest2gChannel, WIFI_BAND_24_GHZ)) < overlapPercentThreshold
                    && lowest2gChannel <= highest2gChannel) {
                lowest2gChannel++;
            }
            // Find highest channel at max overlap
            while (getOverlapPercent(unsafeLowerKhz, unsafeUpperKhz,
                    getLowerFreqKhz(highest2gChannel, WIFI_BAND_24_GHZ),
                    getUpperFreqKhz(highest2gChannel, WIFI_BAND_24_GHZ)) < overlapPercentThreshold
                    && highest2gChannel >= lowest2gChannel) {
                highest2gChannel--;
            }
            // Mark every channel in between as unsafe
            for (int channel = lowest2gChannel; channel <= highest2gChannel; channel++) {
                coexUnsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_24_GHZ, channel));
            }
        }
        return coexUnsafeChannels;
    }


    /**
     * Returns the 5GHz CoexUnsafeChannels affected by the harmonic interference from a given uplink
     * cell channel.
     */
    public static Set<CoexUnsafeChannel> get5gHarmonicCoexUnsafeChannels(
            int ulFreqKhz, int ulBandwidthKhz, int harmonicDegree, int overlapPercentThreshold) {
        Set<CoexUnsafeChannel> coexUnsafeChannels = new HashSet<>();
        final int unsafeLowerKhz = (ulFreqKhz - (ulBandwidthKhz / 2)) * harmonicDegree;
        final int unsafeUpperKhz = (ulFreqKhz + (ulBandwidthKhz / 2)) * harmonicDegree;

        final int lowest5gChannel = get5gLowestOverlap20MhzChannel(unsafeLowerKhz);
        final int highest5gChannel = get5gHighestOverlap20MhzChannel(unsafeUpperKhz);
        if (lowest5gChannel != INVALID_CHANNEL && highest5gChannel != INVALID_CHANNEL) {
            Map<Integer, Integer> overlapPercents = new HashMap<>();
            // Find lowest 20MHz overlap channel
            overlapPercents.put(lowest5gChannel, getOverlapPercent(unsafeLowerKhz, unsafeUpperKhz,
                    getLowerFreqKhz(lowest5gChannel, WIFI_BAND_5_GHZ),
                    getUpperFreqKhz(lowest5gChannel, WIFI_BAND_5_GHZ)));
            // Find highest 2MHz overlap channel
            overlapPercents.put(highest5gChannel, getOverlapPercent(unsafeLowerKhz, unsafeUpperKhz,
                    getLowerFreqKhz(highest5gChannel, WIFI_BAND_5_GHZ),
                    getUpperFreqKhz(highest5gChannel, WIFI_BAND_5_GHZ)));
            // Every channel in between should be at 100 percent overlap
            for (int channel : CHANNEL_SET_5_GHZ_20_MHZ.subSet(
                    lowest5gChannel, false, highest5gChannel, false)) {
                overlapPercents.put(channel, 100);
            }
            // Iterate through each group of 20Mhz, 40Mhz, 80Mhz, 160Mhz channels, and add to
            // unsafe channel set if the pre-calculated overlap percent meets the threshold.
            while (!overlapPercents.isEmpty()) {
                Map<Integer, Integer> dependentOverlaps = new HashMap<>();
                for (int channel : overlapPercents.keySet()) {
                    int overlapPercent = overlapPercents.get(channel);
                    // Add channel to unsafe channel set if overlap percent meets threshold.
                    if (overlapPercent >= overlapPercentThreshold) {
                        coexUnsafeChannels.add(new CoexUnsafeChannel(WIFI_BAND_5_GHZ, channel));
                    }
                    // Pre-calculate the dependent channel overlap for the next iteration by adding
                    // half of each dependency channel percent.
                    final int dependentChannel =  DEPENDENT_MAP_5_GHZ.get(channel, 0);
                    if (dependentChannel != 0) {
                        dependentOverlaps.put(dependentChannel, overlapPercent / 2
                                + dependentOverlaps.getOrDefault(dependentChannel, 0));
                    }
                    // 36 should also map to 34, but only maps to 38 in the dependent map.
                    if (channel == 36) {
                        dependentOverlaps.put(34, overlapPercent / 2
                                + dependentOverlaps.getOrDefault(34, 0));
                    }
                }
                // Set the next dependent group to iterate over until there are no more dependents.
                overlapPercents = dependentOverlaps;
            }
        }
        return coexUnsafeChannels;
    }

    /**
     * Returns CoexUnsafeChannels of a given band affected by the intermod interference from a given
     * uplink and downlink cell channel.
     */
    public static Set<CoexUnsafeChannel> getIntermodCoexUnsafeChannels(
            int ulFreqKhz, int ulBandwidthKhz, int dlFreqKhz, int dlBandwidthKhz,
            int n, int m, int overlapPercentThreshold, @WifiAnnotations.WifiBandBasic int band) {
        Set<CoexUnsafeChannel> coexUnsafeChannels = new HashSet<>();
        final int ulLowerKhz = (ulFreqKhz - (ulBandwidthKhz / 2));
        final int ulUpperKhz = (ulFreqKhz + (ulBandwidthKhz / 2));
        final int dlLowerKhz = (dlFreqKhz - (dlBandwidthKhz / 2));
        final int dlUpperKhz = (dlFreqKhz + (dlBandwidthKhz / 2));

        Set<Integer> channelSet = new HashSet<>();
        if (band == WIFI_BAND_24_GHZ) {
            for (int channel = 1; channel <= 14; channel++) {
                channelSet.add(channel);
            }
        } else if (band == WIFI_BAND_5_GHZ) {
            channelSet.addAll(CHANNEL_SET_5_GHZ_20_MHZ);
            channelSet.addAll(CHANNEL_SET_5_GHZ_40_MHZ);
            channelSet.addAll(CHANNEL_SET_5_GHZ_80_MHZ);
            channelSet.addAll(CHANNEL_SET_5_GHZ_160_MHZ);
        }

        for (int channel : channelSet) {
            int intermodLowerKhz =
                    Math.abs(n * ulLowerKhz + m * getUpperFreqKhz(channel, band));
            int intermodUpperKhz =
                    Math.abs(n * ulUpperKhz + m * getLowerFreqKhz(channel, band));
            // Swap the bounds if lower becomes upper from taking the absolute value
            if (intermodLowerKhz > intermodUpperKhz) {
                int temp = intermodLowerKhz;
                intermodLowerKhz = intermodUpperKhz;
                intermodUpperKhz = temp;
            }
            if (getOverlapPercent(intermodLowerKhz, intermodUpperKhz, dlLowerKhz, dlUpperKhz)
                    >= overlapPercentThreshold) {
                coexUnsafeChannels.add(new CoexUnsafeChannel(band, channel));
            }
        }

        return coexUnsafeChannels;
    }

    // Cellular Utilities

    /**
     * Data structure containing the EARFCN and carrier frequency ranges for an LTE band.
     */
    private static class LteBandInfo {
        private final int mBand;
        private final int mDlLowKhz;
        private final int mDlChannelStart;
        private final int mDlChannelEnd;
        private final int mUlLowKhz;
        private final int mUlChannelStart;
        private final int mUlChannelEnd;

        /** Constructor for FDD bands */
        private LteBandInfo(int band, int dlLowKhz, int dlChannelStart, int dlChannelEnd,
                int ulLowKhz, int ulChannelStart, int ulChannelEnd) {
            mBand = band;
            mDlLowKhz = dlLowKhz;
            mDlChannelStart = dlChannelStart;
            mDlChannelEnd = dlChannelEnd;
            mUlLowKhz = ulLowKhz;
            mUlChannelStart = ulChannelStart;
            mUlChannelEnd = ulChannelEnd;
        }

        /** Constructor for TDD bands */
        private LteBandInfo(int band, int lowKhz, int channelStart, int channelEnd) {
            this(band,
                    lowKhz, channelStart, channelEnd,
                    lowKhz, channelStart, channelEnd);
        }

        public int getBand() {
            return mBand;
        }

        public int getDlLowKhz() {
            return mDlLowKhz;
        }

        public int getDlChannelStart() {
            return mDlChannelStart;
        }

        public int getDlChannelEnd() {
            return mDlChannelEnd;
        }

        public int getUlLowKhz() {
            return mUlLowKhz;
        }

        public int getUlChannelStart() {
            return mUlChannelStart;
        }

        public int getUlChannelEnd() {
            return mUlChannelEnd;
        }
    }

    private static Set<LteBandInfo> createLteBandInfoSet() {
        Set<LteBandInfo> set = new HashSet<>();
        set.add(new LteBandInfo(1,
                2110_000, 0, 599,
                1920_000, 18000, 18599));
        set.add(new LteBandInfo(2,
                1930_000, 600, 1199,
                1850_000, 18600, 19199));
        set.add(new LteBandInfo(3,
                1805_000, 1200, 1949,
                1710_000, 19200, 19949));
        set.add(new LteBandInfo(4,
                2110_000, 1950, 2399,
                1710_000, 19950, 20399));
        set.add(new LteBandInfo(5,
                869_000, 2400, 2649,
                824_000, 20400, 20649));
        set.add(new LteBandInfo(6,
                875_000, 2650, 2749,
                830_000, 20650, 20749));
        set.add(new LteBandInfo(7,
                2620_000, 2750, 3449,
                2500_000, 20750, 21449));
        set.add(new LteBandInfo(8,
                925_000, 3450, 3799,
                880_000, 21450, 21799));
        set.add(new LteBandInfo(9,
                1844_900, 3800, 4149,
                1749_900, 21800, 22149));
        set.add(new LteBandInfo(10,
                2110_000, 4150, 4749,
                1710_000, 22150, 22749));
        set.add(new LteBandInfo(11,
                1475_900, 4750, 4949,
                1427_900, 22750, 22949));
        set.add(new LteBandInfo(12,
                729_000, 5010, 5179,
                699_000, 23010, 23179));
        set.add(new LteBandInfo(13,
                746_000, 5180, 5279,
                777_000, 23180, 23279));
        set.add(new LteBandInfo(14,
                758_000, 5280, 5379,
                788_000, 23280, 23379));
        set.add(new LteBandInfo(17,
                734_000, 5730, 5849,
                704_000, 23730, 23849));
        set.add(new LteBandInfo(18,
                860_000, 5850, 5999,
                815_000, 23850, 23999));
        set.add(new LteBandInfo(19,
                875_000, 6000, 6149,
                830_000, 24000, 24149));
        set.add(new LteBandInfo(20,
                791_000, 6150, 6449,
                832_000, 24150, 24449));
        set.add(new LteBandInfo(21,
                1495_900, 6450, 6599,
                1447_900, 24450, 24599));
        set.add(new LteBandInfo(22,
                3510_000, 6600, 7399,
                3410_000, 24600, 25399));
        set.add(new LteBandInfo(23,
                2180_000, 7500, 7699,
                2000_000, 25500, 25699));
        set.add(new LteBandInfo(24,
                1525_000, 7700, 8039,
                1626_500, 25700, 26039));
        set.add(new LteBandInfo(25,
                1930_000, 8040, 8689,
                1850_000, 26040, 26689));
        set.add(new LteBandInfo(26,
                859_000, 8690, 9039,
                814_000, 26690, 27039));
        set.add(new LteBandInfo(27,
                852_000, 9040, 9209,
                807_000, 27040, 27209));
        set.add(new LteBandInfo(28,
                758_000, 9210, 9659,
                703_000, 27210, 27659));
        set.add(new LteBandInfo(29,
                717_000, 9660, 9769,
                INVALID_FREQ, INVALID_CHANNEL, INVALID_CHANNEL));
        set.add(new LteBandInfo(30,
                2350_000, 9770, 9869,
                2305_000, 27660, 27759));
        set.add(new LteBandInfo(31,
                462_500, 9870, 9919,
                452_500, 27760, 27809));
        set.add(new LteBandInfo(32,
                1452_000, 9920, 10359,
                INVALID_FREQ, INVALID_CHANNEL, INVALID_CHANNEL));
        set.add(new LteBandInfo(33,
                1900_000, 36000, 36199));
        set.add(new LteBandInfo(34,
                2010_000, 36200, 36349));
        set.add(new LteBandInfo(35,
                1850_000, 36350, 36949));
        set.add(new LteBandInfo(36,
                1930_000, 36950, 37549));
        set.add(new LteBandInfo(37,
                1910_000, 37550, 37749));
        set.add(new LteBandInfo(38,
                2570_000, 37750, 38249));
        set.add(new LteBandInfo(39,
                1880_000, 38250, 38649));
        set.add(new LteBandInfo(40,
                2300_000, 38650, 39649));
        set.add(new LteBandInfo(41,
                2496_000, 39650, 41589));
        set.add(new LteBandInfo(42,
                3400_000, 41590, 43589));
        set.add(new LteBandInfo(43,
                3600_000, 43590, 45589));
        set.add(new LteBandInfo(44,
                703_000, 45590, 46589));
        set.add(new LteBandInfo(45,
                1447_000, 46590, 46789));
        set.add(new LteBandInfo(46,
                5150_000, 46790, 54539));
        set.add(new LteBandInfo(47,
                5855_000, 54540, 55239));
        set.add(new LteBandInfo(48,
                3550_000, 55240, 56739));
        set.add(new LteBandInfo(49,
                3550_000, 56740, 58239));
        set.add(new LteBandInfo(50,
                1432_000, 58240, 59089));
        set.add(new LteBandInfo(51,
                1427_000, 59090, 59139));
        set.add(new LteBandInfo(52,
                3300_000, 59140, 60139));
        set.add(new LteBandInfo(53,
                2483_500, 60140, 60254));
        set.add(new LteBandInfo(65,
                2110_000, 65536, 66435,
                1920_000, 131072, 131971));
        set.add(new LteBandInfo(66,
                2110_000, 66436, 67335,
                1710_000, 131972, 132671));
        set.add(new LteBandInfo(67,
                738_000, 67336, 67535,
                INVALID_FREQ, INVALID_CHANNEL, INVALID_CHANNEL));
        set.add(new LteBandInfo(68,
                753_000, 67536, 67835,
                698_000, 132672, 132971));
        set.add(new LteBandInfo(69,
                2570_000, 67836, 68335,
                INVALID_FREQ, INVALID_CHANNEL, INVALID_CHANNEL));
        set.add(new LteBandInfo(70,
                1995_000, 68336, 68585,
                1695_000, 132972, 133121));
        set.add(new LteBandInfo(71,
                617_000, 68586, 68935,
                663_000, 133122, 133471));
        set.add(new LteBandInfo(72,
                461_000, 68936, 68985,
                451_000, 133472, 133521));
        set.add(new LteBandInfo(73,
                460_000, 68986, 69035,
                450_000, 133522, 133571));
        set.add(new LteBandInfo(74,
                1475_000, 69036, 69465,
                1427_000, 133572, 134001));
        set.add(new LteBandInfo(75,
                1432_000, 69466, 70315,
                INVALID_FREQ, INVALID_CHANNEL, INVALID_CHANNEL));
        set.add(new LteBandInfo(76,
                1427_000, 70316, 70365,
                INVALID_FREQ, INVALID_CHANNEL, INVALID_CHANNEL));
        set.add(new LteBandInfo(85,
                728_000, 70366, 70545,
                698_000, 134002, 134181));
        set.add(new LteBandInfo(87,
                420_000, 70546, 70595,
                410_000, 134182, 134231));
        set.add(new LteBandInfo(88,
                422_000, 70596, 70645,
                412_000, 134232, 134280));
        return set;
    }

    private static NavigableMap<Integer, LteBandInfo> createEarfcnOffsetToLteBandInfo() {
        NavigableMap<Integer, LteBandInfo> map = new TreeMap();
        for (LteBandInfo lteBandInfo : LTE_BAND_INFO_SET) {
            if (lteBandInfo.getDlChannelStart() != INVALID_CHANNEL) {
                map.put(lteBandInfo.getDlChannelStart(), lteBandInfo);
            }
            if (lteBandInfo.getUlChannelStart() != INVALID_CHANNEL) {
                map.put(lteBandInfo.getUlChannelStart(), lteBandInfo);
            }
        }
        return map;
    }

    private static LteBandInfo getLteBandInfoForEarfcn(int earfcn) {
        Integer earfcnOffset = EARFCN_OFFSET_TO_LTE_BAND_INFO.floorKey(earfcn);
        if (earfcnOffset == null) {
            return null;
        }
        LteBandInfo lteBandInfo = EARFCN_OFFSET_TO_LTE_BAND_INFO.get(earfcnOffset);
        if (lteBandInfo == null) {
            return null;
        }
        if (earfcn >= lteBandInfo.getDlChannelStart() && earfcn <= lteBandInfo.getDlChannelEnd()
                || earfcn >= lteBandInfo.getUlChannelStart()
                && earfcn <= lteBandInfo.getUlChannelEnd()) {
            return lteBandInfo;
        }
        // EARFCN was not in the range of UL or DL EARFCNs for the band.
        return null;
    }

    /**
     * Gets the operating band number for a PhysicalChannelConfig
     *
     * <p>See 3GPP 36.101 sec 5.7.3-1 for calculation.
     *
     * @param config The PhysicalChannelConfig to get the band of.
     * @return Operating band number, or {@link #INVALID_BAND} if no corresponding band exists
     */
    // TODO(b/153651001: Remove once band number is supported in PhysicalChannelConfig
    static int getOperatingBandForPhysicalChannelConfig(@NonNull PhysicalChannelConfig config) {
        switch(config.getNetworkType()) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                final int earfcn = config.getChannelNumber();
                LteBandInfo lteBandInfo = getLteBandInfoForEarfcn(earfcn);
                if (lteBandInfo == null) {
                    return INVALID_BAND;
                }
                return lteBandInfo.getBand();
            case TelephonyManager.NETWORK_TYPE_NR:
                // TODO(b/153651001: Add support for NR channels
            default:
                return INVALID_BAND;
        }
    }

    /**
     * Gets the carrier frequency in KHz for a given PhysicalChannelConfig.
     *
     * <p>See 3GPP 36.101 sec 5.7.3-1 for LTE calculation.
     *
     * @param config The PhysicalChannelConfig to get the carrier frequency of
     * @return Carrier frequency in Khz, or INVALID_FREQ if the config has none.
     */
    static int getCarrierFreqKhzForPhysicalChannelConfig(@NonNull PhysicalChannelConfig config) {
        switch(config.getNetworkType()) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                final int earfcn = config.getChannelNumber();
                LteBandInfo lteBandInfo = getLteBandInfoForEarfcn(earfcn);
                if (lteBandInfo == null) {
                    return INVALID_FREQ;
                }
                // DL EARFCN
                if (earfcn >= lteBandInfo.getDlChannelStart()
                        && earfcn <= lteBandInfo.getDlChannelEnd()) {
                    return lteBandInfo.getDlLowKhz()
                            + (earfcn - lteBandInfo.getDlChannelStart()) * 100;
                }
                // UL EARFCN
                return lteBandInfo.getUlLowKhz() + (earfcn - lteBandInfo.getUlChannelStart()) * 100;
            case TelephonyManager.NETWORK_TYPE_NR:
                // TODO(b/153651001: Add support for NR channels
            default:
                return INVALID_BAND;
        }
    }
}
