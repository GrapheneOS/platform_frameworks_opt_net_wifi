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

import com.android.wifi.resources.R;

import java.util.HashSet;
import java.util.List;
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

    private CoexPhoneStateListener mCoexPhoneStateListener;

    public CoexManager(@NonNull Context context, @NonNull TelephonyManager telephonyManager,
            @NonNull Handler handler) {
        mContext = context;
        mTelephonyManager = telephonyManager;

        if (mContext.getResources().getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled)) {
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

    private class CoexPhoneStateListener extends PhoneStateListener
            implements PhoneStateListener.PhysicalChannelConfigChangedListener {
        private CoexPhoneStateListener(Executor executor) {
            super(executor);
        }

        @java.lang.Override
        public void onPhysicalChannelConfigChanged(
                @NonNull List<PhysicalChannelConfig> configs) {
            // TODO(b/153651001): Extract cell band and channel info here and run through the
            //                    channel avoidance algorithm to update mCurrentCoexUnsafeChannels
        }
    }
}
