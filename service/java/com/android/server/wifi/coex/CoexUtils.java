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
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Class containing the unsafe channel algorithms and other utility methods for Wi-Fi/Cellular coex.
 */
public class CoexUtils {
    private static final int INVALID_CHANNEL = -1;
    @VisibleForTesting
    /* package */ static final int INVALID_FREQ = -1;

    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_20_MHZ = create5g20MhzChannels();
    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_40_MHZ = create5g40MhzChannels();
    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_80_MHZ = create5g80MhzChannels();
    private static final NavigableSet<Integer> CHANNEL_SET_5_GHZ_160_MHZ = create5g160MhzChannels();
    private static final SparseIntArray DEPENDENT_MAP_5_GHZ = create5gDependentChannelMap();

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
            for (int channel = lowest2gChannel; channel <= highest2gChannel; channel += 1) {
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
}
