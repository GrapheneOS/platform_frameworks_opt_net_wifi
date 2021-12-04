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

package com.android.wifitrackerlib;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

/**
 * Wrapper class to decouple WifiTrackerLibDefaults from @hide API usage at build time.
 * This version avoids @hide APIs for usage in apps that can only access SDK APIs, e.g. SetupWizard.
 *
 * Clients may wish to provide their own implementation of these methods when copying this
 * library over to their own codebase.
 */
class HiddenApiWrapper {
    /**
     * Starts the System captive portal app.
     */
    static void startCaptivePortalApp(
            @NonNull ConnectivityManager connectivityManager, @NonNull Network network) {
        return;
    }

    /**
     * Find the annotation of specified id in rawText and linkify it with helpUriString.
     */
    static CharSequence linkifyAnnotation(Context context, CharSequence rawText, String id,
            String helpUriString) {
        // TODO(b/162368129): Should SUW be concerned with annotation linking?
        return rawText;
    }

    /**
     * Returns whether or not the network capabilities is determined to be VCN over Wi-Fi or not.
     */
    static boolean isVcnOverWifi(@NonNull NetworkCapabilities networkCapabilities) {
        // This is only useful for treating CELLULAR over WIFI as a carrier merged network in
        // provider model Settings. Since SUW doesn't use the provider model, this is not used.
        return false;
    }

    /**
     * Returns whether or not the device is in retail demo mode.
     */
    static boolean isDemoMode(@NonNull Context context) {
        // This should be false since SUW is not used in demo mode.
        return false;
    }
}
