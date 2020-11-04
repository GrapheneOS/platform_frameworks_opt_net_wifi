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

package com.android.server.wifi;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.os.Looper;
import android.os.WorkSource;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Network factory to handle oem paid wifi network requests.
 */
public class OemPaidWifiNetworkFactory extends NetworkFactory {
    private static final String TAG = "OemPaidWifiNetworkFactory";
    private static final int SCORE_FILTER = Integer.MAX_VALUE;

    private final WifiConnectivityManager mWifiConnectivityManager;
    private int mConnectionReqCount = 0;

    public OemPaidWifiNetworkFactory(Looper l, Context c, NetworkCapabilities f,
                                       WifiConnectivityManager connectivityManager) {
        super(l, c, TAG, f);
        mWifiConnectivityManager = connectivityManager;

        setScoreFilter(SCORE_FILTER);
    }

    @Override
    protected void needNetworkFor(NetworkRequest networkRequest, int score) {
        if (networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID)) {
            if (++mConnectionReqCount == 1) {
                mWifiConnectivityManager.setOemPaidConnectionAllowed(
                        true, new WorkSource(networkRequest.getRequestorUid(),
                                networkRequest.getRequestorPackageName()));
            }
        }
    }

    @Override
    protected void releaseNetworkFor(NetworkRequest networkRequest) {
        if (networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID)) {
            if (mConnectionReqCount == 0) {
                Log.e(TAG, "No valid network request to release");
                return;
            }
            if (--mConnectionReqCount == 0) {
                mWifiConnectivityManager.setOemPaidConnectionAllowed(false, null);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println(TAG + ": mConnectionReqCount " + mConnectionReqCount);
    }

    /**
     * Check if there is at-least one connection request.
     */
    public boolean hasConnectionRequests() {
        return mConnectionReqCount > 0;
    }
}

