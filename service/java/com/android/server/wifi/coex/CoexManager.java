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

import static android.net.wifi.WifiManager.COEX_RESTRICTION_SOFTAP;
import static android.net.wifi.WifiManager.COEX_RESTRICTION_WIFI_AWARE;
import static android.net.wifi.WifiManager.COEX_RESTRICTION_WIFI_DIRECT;
import static android.net.wifi.WifiScanner.WIFI_BAND_24_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_5_GHZ;
import static android.telephony.TelephonyManager.NETWORK_TYPE_LTE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_NR;

import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_160_MHZ;
import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_20_MHZ;
import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_40_MHZ;
import static com.android.server.wifi.coex.CoexUtils.CHANNEL_SET_5_GHZ_80_MHZ;
import static com.android.server.wifi.coex.CoexUtils.getCarrierFreqKhzForPhysicalChannelConfig;
import static com.android.server.wifi.coex.CoexUtils.getNeighboringCoexUnsafeChannels;
import static com.android.server.wifi.coex.CoexUtils.getOperatingBandForPhysicalChannelConfig;

import android.annotation.NonNull;
import android.content.Context;
import android.net.wifi.CoexUnsafeChannel;
import android.net.wifi.ICoexCallback;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.CoexRestriction;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.PhoneStateListener;
import android.telephony.PhysicalChannelConfig;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wifi.resources.R;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.NotThreadSafe;


/**
 * This class handles Wi-Fi/Cellular coexistence by dynamically generating a set of Wi-Fi channels
 * that would cause interference to/receive interference from the active cellular channels. These
 * Wi-Fi channels are represented by {@link CoexUnsafeChannel} and may be retrieved through
 * {@link #getCoexUnsafeChannels()}.
 *
 * Clients may be notified of updates to the value of #getCoexUnsafeChannels by implementing an
 * {@link CoexListener} and listening on
 * {@link CoexListener#onCoexUnsafeChannelsChanged()}
 *
 * Note: This class is not thread-safe. It needs to be invoked from the main Wifi thread only.
 */
@NotThreadSafe
public class CoexManager {
    private static final String TAG = "WifiCoexManager";

    @NonNull
    private final Context mContext;
    @NonNull
    private final TelephonyManager mTelephonyManager;
    @NonNull
    private final Set<CoexUnsafeChannel> mCurrentCoexUnsafeChannels = new HashSet<>();
    private int mCoexRestrictions;
    @NonNull
    private final Set<CoexListener> mListeners = new HashSet<>();
    @NonNull
    private final RemoteCallbackList<ICoexCallback> mRemoteCallbackList =
            new RemoteCallbackList<ICoexCallback>();
    @NonNull
    private final Map<Integer, Entry> mLteTableEntriesByBand = new HashMap<>();
    @NonNull
    private final Map<Integer, Entry> mNrTableEntriesByBand = new HashMap<>();
    // Map of band/channel pair to current CoexUnsafeChannels
    @NonNull
    private Set<Integer> mDefault2gChannels = new HashSet<>();
    @NonNull
    private Set<Integer> mDefault5gChannels = new HashSet<>();

    private CoexPhoneStateListener mCoexPhoneStateListener;

    public CoexManager(@NonNull Context context, @NonNull TelephonyManager telephonyManager,
            @NonNull Handler handler) {
        mContext = context;
        mTelephonyManager = telephonyManager;
        if (mContext.getResources().getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled)
                && readTableFromXml()) {
            mCoexPhoneStateListener = new CoexPhoneStateListener(new HandlerExecutor(handler));
            mTelephonyManager.registerPhoneStateListener(new HandlerExecutor(handler),
                    mCoexPhoneStateListener);
        }
    }

    /**
     * Returns the set of current {@link CoexUnsafeChannel} being used for Wi-Fi/Cellular coex
     * channel avoidance supplied in {@link #setCoexUnsafeChannels(Set, int)}.
     *
     * If any {@link CoexRestriction} flags are set in {@link #getCoexRestrictions()}, then the
     * CoexUnsafeChannels should be totally avoided (i.e. not best effort) for the Wi-Fi modes
     * specified by the flags.
     *
     * @return Set of current CoexUnsafeChannels.
     */
    @NonNull
    public Set<CoexUnsafeChannel> getCoexUnsafeChannels() {
        return mCurrentCoexUnsafeChannels;
    }

    /**
     * Returns the current coex restrictions being used for Wi-Fi/Cellular coex
     * channel avoidance supplied in {@link #setCoexUnsafeChannels(Set, int)}.
     *
     * @return int containing a bitwise-OR combination of {@link CoexRestriction}.
     */
    public int getCoexRestrictions() {
        return mCoexRestrictions;
    }

    /**
     * Sets the current CoexUnsafeChannels and coex restrictions returned by
     * {@link #getCoexUnsafeChannels()} and {@link #getCoexRestrictions()} and notifies each
     * listener with {@link CoexListener#onCoexUnsafeChannelsChanged()} and each
     * remote callback with {@link ICoexCallback#onCoexUnsafeChannelsChanged()}.
     *
     * @param coexUnsafeChannels Set of CoexUnsafeChannels to return in
     *                           {@link #getCoexUnsafeChannels()}
     * @param coexRestrictions int to return in {@link #getCoexRestrictions()}
     */
    public void setCoexUnsafeChannels(@NonNull Set<CoexUnsafeChannel> coexUnsafeChannels,
            int coexRestrictions) {
        if (coexUnsafeChannels == null) {
            Log.e(TAG, "setCoexUnsafeChannels called with null unsafe channel set");
            return;
        }
        if ((~(COEX_RESTRICTION_WIFI_DIRECT | COEX_RESTRICTION_SOFTAP
                | COEX_RESTRICTION_WIFI_AWARE) & coexRestrictions) != 0) {
            Log.e(TAG, "setCoexUnsafeChannels called with undefined restriction flags");
            return;
        }
        mCurrentCoexUnsafeChannels.clear();
        mCurrentCoexUnsafeChannels.addAll(coexUnsafeChannels);
        mCoexRestrictions = coexRestrictions;
        notifyListeners();
        notifyRemoteCallbacks();
    }

    /**
     * Registers a {@link CoexListener} to be notified with updates.
     * @param listener CoexListener to be registered.
     */
    public void registerCoexListener(@NonNull CoexListener listener) {
        if (listener == null) {
            Log.e(TAG, "registerCoexListener called with null listener");
            return;
        }
        mListeners.add(listener);
    }

    /**
     * Unregisters a {@link CoexListener}.
     * @param listener CoexListener to be unregistered.
     */
    public void unregisterCoexListener(@NonNull CoexListener listener) {
        if (listener == null) {
            Log.e(TAG, "unregisterCoexListener called with null listener");
            return;
        }
        if (!mListeners.remove(listener)) {
            Log.e(TAG, "unregisterCoexListener called on listener that was not registered: "
                    + listener);
        }
    }

    /**
     * Registers a remote ICoexCallback from an external app.
     * see {@link WifiManager#registerCoexCallback(Executor, WifiManager.CoexCallback)}
     * @param callback ICoexCallback instance to register
     */
    public void registerRemoteCoexCallback(ICoexCallback callback) {
        mRemoteCallbackList.register(callback);
    }

    /**
     * Unregisters a remote ICoexCallback from an external app.
     * see {@link WifiManager#unregisterCoexCallback(WifiManager.CoexCallback)}
     * @param callback ICoexCallback instance to unregister
     */
    public void unregisterRemoteCoexCallback(ICoexCallback callback) {
        mRemoteCallbackList.unregister(callback);
    }

    private void notifyListeners() {
        for (CoexListener listener : mListeners) {
            listener.onCoexUnsafeChannelsChanged();
        }
    }

    private void notifyRemoteCallbacks() {
        final int itemCount = mRemoteCallbackList.beginBroadcast();
        for (int i = 0; i < itemCount; i++) {
            try {
                mRemoteCallbackList.getBroadcastItem(i).onCoexUnsafeChannelsChanged();
            } catch (RemoteException e) {
                Log.e(TAG, "onCoexUnsafeChannelsChanged: remote exception -- " + e);
            }
        }
        mRemoteCallbackList.finishBroadcast();
    }

    /**
     * Listener interface for internal Wi-Fi clients to listen to updates to
     * {@link #getCoexUnsafeChannels()} and {@link #getCoexRestrictions()}
     */
    public interface CoexListener {
        /**
         * Called to notify the listener that the values of
         * {@link CoexManager#getCoexUnsafeChannels()} and/or
         * {@link CoexManager#getCoexRestrictions()} have changed and should be
         * retrieved again.
         */
        void onCoexUnsafeChannelsChanged();
    }

    @VisibleForTesting
    /* package */ class CoexPhoneStateListener extends PhoneStateListener
            implements PhoneStateListener.PhysicalChannelConfigChangedListener {
        private CoexPhoneStateListener(Executor executor) {
            super(executor);
        }

        @java.lang.Override
        public void onPhysicalChannelConfigChanged(
                @NonNull List<PhysicalChannelConfig> configs) {
            if (configs == null) {
                Log.e(TAG, "onPhysicalChannelConfigurationChanged called with null config list");
                return;
            }
            Log.i(TAG, "PhysicalChannelConfigs changed: " + configs);
            int numUnsafe2gChannels = 0;
            int numUnsafe5gChannels = 0;
            int default2gChannel = Integer.MAX_VALUE;
            int default5gChannel = Integer.MAX_VALUE;
            int coexRestrictions = 0;
            Map<Pair<Integer, Integer>, CoexUnsafeChannel> coexUnsafeChannelsByBandChannelPair =
                    new HashMap<>();
            // Gather all of the CoexUnsafeChannels calculated from each cell channel.
            for (PhysicalChannelConfig config : configs) {
                final Entry entry;
                switch (config.getNetworkType()) {
                    case NETWORK_TYPE_LTE:
                        entry = mLteTableEntriesByBand.get(
                                getOperatingBandForPhysicalChannelConfig(config));
                        break;
                    case NETWORK_TYPE_NR:

                        /*
                        TODO(b/153651001): Add NR channel handling here once we have the band
                                           number in PhysicalChannelConfig for coex table lookup.
                        entry = mNrTableEntriesByBand.get(
                                getOperatingBandForPhysicalChannelConfig(config));
                        break;
                        */
                    default:
                        entry = null;
                }
                if (entry == null) {
                    continue;
                }
                final Set<CoexUnsafeChannel> currentBandUnsafeChannels = new HashSet<>();
                final Params params = entry.getParams();
                final Override override = entry.getOverride();
                if (params != null) {
                    // Add all of the CoexUnsafeChannels calculated with the given parameters.
                    final int centerFreqKhz = getCarrierFreqKhzForPhysicalChannelConfig(config);
                    final int cellBandwidthDownlinkKhz = config.getCellBandwidthDownlink();
                    /*
                    TODO(b/153651001): Uncomment this once cell uplink info is available in
                                       PhysicalChannelConfig.
                    final int cellBandwidthUplinkKhz = config.getCellBandwidthUplink();
                    */
                    final NeighborThresholds neighborThresholds = params.getNeighborThresholds();
                    final HarmonicParams harmonicParams2g = params.getHarmonicParams2g();
                    final HarmonicParams harmonicParams5g = params.getHarmonicParams5g();
                    final IntermodParams intermodParams2g = params.getIntermodParams2g();
                    final IntermodParams intermodParams5g = params.getIntermodParams2g();
                    final DefaultChannels defaultChannels = params.getDefaultChannels();
                    // Calculate interference from cell downlink.
                    if (cellBandwidthDownlinkKhz > 0) {
                        if (neighborThresholds != null && neighborThresholds.hasCellVictimMhz()) {
                            currentBandUnsafeChannels.addAll(getNeighboringCoexUnsafeChannels(
                                    centerFreqKhz,
                                    cellBandwidthDownlinkKhz,
                                    neighborThresholds.getCellVictimMhz() * 1000));
                        }
                    }
                    // Calculate interference from cell uplink.
                    /*
                    TODO(b/153651001): Uncomment this once cell uplink info is available in
                                       PhysicalChannelConfig.
                    if (cellBandwidthUplinkKhz > 0) {
                        if (neighborThresholds != null && neighborThresholds.hasWifiVictimMhz()) {
                            currentBandUnsafeChannels.addAll(getNeighboringCoexUnsafeChannels(
                                    centerFreqKhz,
                                    cellBandwidthUplinkKhz,
                                    neighborThresholds.getWifiVictimMhz() * 1000));
                        }
                        if (harmonicParams2g != null) {
                            currentBandUnsafeChannels.addAll(get2gHarmonicCoexUnsafeChannels(
                                    centerFreqKhz,
                                    cellBandwidthUplinkKhz,
                                    harmonicParams2g.getN(),
                                    harmonicParams2g.getOverlap()));
                        }
                        if (harmonicParams5g != null) {
                            currentBandUnsafeChannels.addAll(get5gHarmonicCoexUnsafeChannels(
                                    centerFreqKhz,
                                    cellBandwidthUplinkKhz,
                                    harmonicParams5g.getN(),
                                    harmonicParams5g.getOverlap()));
                        }
                        if (intermodParams2g != null) {
                            for (PhysicalChannelConfig dlConfig : configs) {
                                if (dlConfig.getCellBandwidthDownlink() == 0) {
                                    continue;
                                }
                                currentBandUnsafeChannels.addAll(getIntermodCoexUnsafeChannels(
                                        centerFreqKhz,
                                        cellBandwidthUplinkKhz,
                                        getCarrierFreqKhzForPhysicalChannelConfig(dlConfig),
                                        dlConfig.getCellBandwidthDownlink(),
                                        intermodParams2g.getN(),
                                        intermodParams2g.getM(),
                                        intermodParams2g.getOverlap(),
                                        WIFI_BAND_24_GHZ));
                            }
                        }
                        if (intermodParams5g != null) {
                            for (PhysicalChannelConfig dlConfig : configs) {
                                if (dlConfig.getCellBandwidthDownlink() == 0) {
                                    continue;
                                }
                                currentBandUnsafeChannels.addAll(getIntermodCoexUnsafeChannels(
                                        centerFreqKhz,
                                        cellBandwidthUplinkKhz,
                                        getCarrierFreqKhzForPhysicalChannelConfig(dlConfig),
                                        dlConfig.getCellBandwidthDownlink(),
                                        intermodParams5g.getN(),
                                        intermodParams5g.getM(),
                                        intermodParams5g.getOverlap(),
                                        WIFI_BAND_5_GHZ));
                            }
                        }
                    }
                    */
                    // Collect the lowest number default channel for each band to extract from
                    // calculated set of CoexUnsafeChannels later.
                    if (defaultChannels != null) {
                        if (defaultChannels.hasDefault2g()) {
                            int channel = defaultChannels.getDefault2g();
                            if (channel < default2gChannel) {
                                default2gChannel = channel;
                            }
                        }
                        if (defaultChannels.hasDefault5g()) {
                            int channel = defaultChannels.getDefault5g();
                            if (channel < default5gChannel) {
                                default5gChannel = channel;
                            }
                        }
                    }
                } else if (override != null) {
                    // Add all of the CoexUnsafeChannels defined by the override lists.
                    final Override2g override2g = override.getOverride2g();
                    if (override2g != null) {
                        final List<Integer> channelList2g = override2g.getChannel();
                        for (OverrideCategory2g category : override2g.getCategory()) {
                            if (OverrideCategory2g.all.equals(category)) {
                                for (int i = 1; i <= 14; i++) {
                                    channelList2g.add(i);
                                }
                            }
                        }
                        for (int channel : channelList2g) {
                            currentBandUnsafeChannels.add(
                                    new CoexUnsafeChannel(WIFI_BAND_24_GHZ, channel));
                        }
                    }
                    final Override5g override5g = override.getOverride5g();
                    if (override5g != null) {
                        final List<Integer> channelList5g = override5g.getChannel();
                        for (OverrideCategory5g category : override5g.getCategory()) {
                            if (OverrideCategory5g._20Mhz.equals(category)) {
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_20_MHZ);
                            } else if (OverrideCategory5g._40Mhz.equals(category)) {
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_40_MHZ);
                            } else if (OverrideCategory5g._80Mhz.equals(category)) {
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_80_MHZ);
                            } else if (OverrideCategory5g._160Mhz.equals(category)) {
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_160_MHZ);
                            } else if (OverrideCategory5g.all.equals(category)) {
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_20_MHZ);
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_40_MHZ);
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_80_MHZ);
                                channelList5g.addAll(CHANNEL_SET_5_GHZ_160_MHZ);
                            }
                        }
                        for (int channel : channelList5g) {
                            currentBandUnsafeChannels.add(
                                    new CoexUnsafeChannel(WIFI_BAND_5_GHZ, channel));
                        }
                    }
                }
                // Add the power cap for the band, if there is one.
                if (entry.hasPowerCapDbm()) {
                    for (CoexUnsafeChannel unsafeChannel : currentBandUnsafeChannels) {
                        unsafeChannel.setPowerCapDbm(entry.getPowerCapDbm());
                    }
                }
                // Add all of the CoexUnsafeChannels calculated from this cell channel to the total.
                // If the total already contains a CoexUnsafeChannel for the same band and channel,
                // keep the one that has the lower power cap.
                for (CoexUnsafeChannel unsafeChannel : currentBandUnsafeChannels) {
                    final int band = unsafeChannel.getBand();
                    final int channel = unsafeChannel.getChannel();
                    final Pair<Integer, Integer> bandChannelPair = new Pair<>(band, channel);
                    final CoexUnsafeChannel existingUnsafeChannel =
                            coexUnsafeChannelsByBandChannelPair.get(bandChannelPair);
                    if (existingUnsafeChannel != null) {
                        if (!unsafeChannel.isPowerCapAvailable()) {
                            continue;
                        }
                        if (existingUnsafeChannel.isPowerCapAvailable()
                                && existingUnsafeChannel.getPowerCapDbm()
                                < unsafeChannel.getPowerCapDbm()) {
                            continue;
                        }
                    }
                    // Count the number of unsafe channels for each band to determine if we need to
                    // remove the default channels before returning.
                    if (band == WIFI_BAND_24_GHZ) {
                        numUnsafe2gChannels++;
                    } else if (band == WIFI_BAND_5_GHZ) {
                        numUnsafe5gChannels++;
                    }
                    coexUnsafeChannelsByBandChannelPair.put(bandChannelPair, unsafeChannel);
                }
            }

            // Omit the default channel from each band if the entire band is unsafe and there are
            // no coex restrictions set.
            if (coexRestrictions == 0) {
                if (numUnsafe2gChannels == 14) {
                    coexUnsafeChannelsByBandChannelPair.remove(
                            new Pair<>(WIFI_BAND_24_GHZ, default2gChannel));
                }
                if (numUnsafe5gChannels == CHANNEL_SET_5_GHZ_20_MHZ.size()
                        + CHANNEL_SET_5_GHZ_40_MHZ.size()
                        + CHANNEL_SET_5_GHZ_80_MHZ.size()
                        + CHANNEL_SET_5_GHZ_160_MHZ.size()) {
                    coexUnsafeChannelsByBandChannelPair.remove(
                            new Pair<>(WIFI_BAND_5_GHZ, default5gChannel));
                }
            }
            setCoexUnsafeChannels(new HashSet<>(coexUnsafeChannelsByBandChannelPair.values()),
                    coexRestrictions);
        }
    }

    /**
     * Parses a coex table xml from the specified File and populates the table entry maps.
     * Returns {@code true} if the file was found and read successfully, {@code false} otherwise.
     */
    @VisibleForTesting
    boolean readTableFromXml() {
        final String filepath = mContext.getResources().getString(
                R.string.config_wifiCoexTableFilepath);
        if (filepath == null) {
            Log.e(TAG, "Coex table filepath was null");
            return false;
        }
        final File file = new File(filepath);
        try (InputStream str = new BufferedInputStream(new FileInputStream(file))) {
            mLteTableEntriesByBand.clear();
            mNrTableEntriesByBand.clear();
            for (Entry entry : XmlParser.readTable(str).getEntry()) {
                if (RatType.LTE.equals(entry.getRat())) {
                    mLteTableEntriesByBand.put(entry.getBand(), entry);
                } else if (RatType.NR.equals(entry.getRat())) {
                    mNrTableEntriesByBand.put(entry.getBand(), entry);
                }
            }
            Log.i(TAG, "Successfully read coex table from file");
            return true;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "No coex table file found at " + file);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read coex table file: " + e);
        }
        return false;
    }
}
