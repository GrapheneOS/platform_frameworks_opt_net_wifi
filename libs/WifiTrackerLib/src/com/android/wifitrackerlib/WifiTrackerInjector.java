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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.UserManager;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.Clock;
import java.util.Set;

/**
 * Wrapper class for commonly referenced objects and static data.
 */
public class WifiTrackerInjector {
    private static final String TAG = WifiTrackerInjector.class.getSimpleName();

    @NonNull private final Context mContext;
    @NonNull private final Clock mClock;
    private final boolean mIsDemoMode;
    @Nullable
    private final ConnectivityManager mConnectivityManager;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    @NonNull private final Set<String> mNoAttributionAnnotationPackages;
    private volatile boolean mCachedWifiManagerVerboseLoggingValue = false;
    private volatile boolean mIsVerboseLoggingEnabledForUserdebug;
    private volatile boolean mIsVerboseLoggingDisabledByClient = false;

    // TODO(b/201571677): Migrate the rest of the common objects to WifiTrackerInjector.
    WifiTrackerInjector(@NonNull Context context, Clock clock) {
        mContext = context;
        mClock = clock;
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mIsDemoMode = NonSdkApiWrapper.isDemoMode(context);
        mUserManager = context.getSystemService(UserManager.class);
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        mNoAttributionAnnotationPackages = new ArraySet<>();
        String[] noAttributionAnnotationPackages = context.getString(
                R.string.wifitrackerlib_no_attribution_annotation_packages).split(",");
        for (int i = 0; i < noAttributionAnnotationPackages.length; i++) {
            mNoAttributionAnnotationPackages.add(noAttributionAnnotationPackages[i]);
        }
        Resources res = context.getResources();
        mIsVerboseLoggingEnabledForUserdebug = res.getBoolean(
                R.bool.wifitrackerlib_enable_verbose_logging_for_userdebug)
                && Build.TYPE.equals("userdebug");
    }

    @NonNull Context getContext() {
        return mContext;
    }

    /**
     * Returns the common clock used for timing operations, such as scan result timeouts and
     * "recently disconnected" status.
     */
    @NonNull Clock getClock() {
        return mClock;
    }

    boolean isDemoMode() {
        return mIsDemoMode;
    }

    public UserManager getUserManager() {
        return mUserManager;
    }

    public DevicePolicyManager getDevicePolicyManager() {
        return mDevicePolicyManager;
    }

    /**
     * Returns the set of package names which we should not show attribution annotations for.
     */
    @NonNull Set<String> getNoAttributionAnnotationPackages() {
        return mNoAttributionAnnotationPackages;
    }

    /**
     * Sets the cached value for Wi-Fi verbose logging.
     *
     * @param enabled the verbose logging status.
     */
    void cacheWifiManagerVerboseLoggingValue(boolean enabled) {
        mCachedWifiManagerVerboseLoggingValue = enabled;
    }

    /**
     * Whether verbose logging is enabled.
     */
    public boolean isVerboseLoggingEnabled() {
        return !mIsVerboseLoggingDisabledByClient
                && (mCachedWifiManagerVerboseLoggingValue || mIsVerboseLoggingEnabledForUserdebug);
    }

    /**
     * Whether verbose summaries should be shown in WifiEntry.
     */
    public boolean isVerboseSummaryEnabled() {
        return !mIsVerboseLoggingDisabledByClient && mCachedWifiManagerVerboseLoggingValue;
    }

    /**
     * Permanently disables verbose logging. Intended for status bar use.
     */
    public void setVerboseLoggingDisabledByClient() {
        mIsVerboseLoggingDisabledByClient = true;
    }

    @Nullable
    public ConnectivityManager getConnectivityManager() {
        return mConnectivityManager;
    }

    /**
     * Injection wrapper for NonSdkApiWrapper.isWifiStateChangedListenerEnabled().
     */
    public boolean isWifiStateChangedListenerEnabled() {
        return NonSdkApiWrapper.isWifiStateChangedListenerEnabled();
    }

    /**
     * Injection wrapper for checking the SDK level is at least Baklava.
     */
    public boolean isAtLeastB() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }
}
