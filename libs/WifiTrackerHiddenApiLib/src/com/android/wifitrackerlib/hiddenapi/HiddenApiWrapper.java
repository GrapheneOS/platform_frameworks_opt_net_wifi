/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wifitrackerlib.hiddenapi;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Wrapper class for WifiTrackerLib to access hidden apis.
 */
public class HiddenApiWrapper {
    /**
     * Returns whether or not this wrapper uses hidden apis. If this is false, then the rest of the
     * methods will return fake values.
     */
    public static boolean canUseHiddenApis() {
        return true;
    }

    /**
     * Wrapper for NetworkCapabilities#isPrivateDnsBroken()
     */
    public static boolean isPrivateDnsBroken(@NonNull NetworkCapabilities networkCapabilities) {
        return networkCapabilities.isPrivateDnsBroken();
    }

    /**
     * Wrapper for ConnectivityManager#startCaptivePortalApp(Network network)
     */
    public static void startCaptivePortalApp(@NonNull ConnectivityManager connectivityManager,
            @NonNull Network network) {
        connectivityManager.startCaptivePortalApp(network);
    }

    /**
     * Wrapper for getting the recommendation service label from
     * NetworkScoreManager#getActiveScorer()
     */
    @Nullable
    public static String getActiveScorerRecommendationServiceLabel(
            @NonNull NetworkScoreManager networkScoreManager) {
        final NetworkScorerAppData scorer = networkScoreManager.getActiveScorer();
        if (scorer != null) {
            return scorer.getRecommendationServiceLabel();
        }
        return null;
    }
}
