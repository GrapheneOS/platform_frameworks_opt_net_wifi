/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.WIFI_FEATURE_MBO;
import static android.net.wifi.WifiManager.WIFI_FEATURE_OCE;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * MboOceController is responsible for controlling MBO and OCE operations.
 */
public class MboOceController {
    private static final String TAG = "MboOceController";

    /** State of MBO/OCE module. */
    private boolean mEnabled = false;
    private boolean mIsMboSupported = false;
    private boolean mIsOceSupported = false;
    private boolean mVerboseLoggingEnabled = false;

    private final TelephonyManager mTelephonyManager;
    private final ActiveModeWarden mActiveModeWarden;

    /**
     * Create new instance of MboOceController.
     */
    public MboOceController(TelephonyManager telephonyManager, ActiveModeWarden activeModeWarden) {
        mTelephonyManager = telephonyManager;
        mActiveModeWarden = activeModeWarden;
    }

    /**
     * Enable MBO and OCE functionality.
     */
    public void enable() {
        ClientModeManager clientModeManager =
                mActiveModeWarden.getPrimaryClientModeManagerNullable();
        if (clientModeManager == null) {
            return;
        }
        long supportedFeatures = clientModeManager.getSupportedFeatures();
        mIsMboSupported = (supportedFeatures & WIFI_FEATURE_MBO) != 0;
        mIsOceSupported = (supportedFeatures & WIFI_FEATURE_OCE) != 0;
        mEnabled = true;
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Enable MBO-OCE MBO support: " + mIsMboSupported
                    + " OCE support: " + mIsOceSupported);
        }
        if (mIsMboSupported) {
            // Register for data connection state change events (Cellular).
            mTelephonyManager.listen(mDataConnectionStateListener,
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
        }
    }

    /**
     * Disable MBO and OCE functionality.
     */
    public void disable() {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Disable MBO-OCE");
        }
        if (mIsMboSupported) {
            // Un-register for data connection state change events (Cellular).
            mTelephonyManager.listen(mDataConnectionStateListener, PhoneStateListener.LISTEN_NONE);
        }
        mEnabled = false;
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param verbose true to enable and false to disable.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * Listen for changes to the data connection state (Cellular).
     */
    private PhoneStateListener mDataConnectionStateListener = new PhoneStateListener(){
        public void onDataConnectionStateChanged(int state, int networkType) {
            boolean dataAvailable;

            ClientModeManager clientModeManager =
                    mActiveModeWarden.getPrimaryClientModeManagerNullable();
            if (clientModeManager == null) {
                return;
            }
            if (!mEnabled) {
                Log.e(TAG, "onDataConnectionStateChanged called when MBO is disabled!!");
                return;
            }
            if (state == TelephonyManager.DATA_CONNECTED) {
                dataAvailable = true;
            } else if (state == TelephonyManager.DATA_DISCONNECTED) {
                dataAvailable = false;
            } else {
                Log.e(TAG, "onDataConnectionStateChanged unexpected State: " + state);
                return;
            }
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Cell Data: " + dataAvailable);
            }
            clientModeManager.setMboCellularDataStatus(dataAvailable);
        }
    };

    /**
     * BtmFrameData carries the data retried from received BTM
     * request frame handled in supplicant.
     */
    public static class BtmFrameData {
        public @MboOceConstants.BtmResponseStatus int mStatus =
                MboOceConstants.BTM_RESPONSE_STATUS_INVALID;
        public int mBssTmDataFlagsMask = 0;
        public long mBlockListDurationMs = 0;
        public @MboOceConstants.MboTransitionReason int mTransitionReason =
                MboOceConstants.MBO_TRANSITION_REASON_INVALID;
        public @MboOceConstants.MboCellularDataConnectionPreference int mCellPreference =
                MboOceConstants.MBO_CELLULAR_DATA_CONNECTION_INVALID;

        @Override
        public String toString() {
            return new StringBuilder("BtmFrameData status=").append(mStatus).append(
                    ", flags=").append(mBssTmDataFlagsMask).append(
                    ", assocRetryDelay=").append(mBlockListDurationMs).append(
                    ", transitionReason=").append(mTransitionReason).append(
                    ", cellPref=").append(mCellPreference).toString();
        }
    }
}
