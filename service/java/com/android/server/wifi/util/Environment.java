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

package com.android.server.wifi.util;

import android.content.ApexContext;
import android.os.UserHandle;

import java.io.File;

/**
 * Provides access to environment variables.
 *
 * Note: @hide methods copied from android.os.Environment
 */
public class Environment {
    /**
     * Wifi apex name.
     */
    private static final String WIFI_APEX_NAME = "com.android.wifi";

    /**
     * Directory to store the wifi config store / shared preference files under.
     */
    private static final String LEGACY_WIFI_STORE_DIRECTORY_NAME = "wifi";

    /**
     * Get data/misc directory
     */
    public static File getDataMiscDirectory() {
        return new File(android.os.Environment.getDataDirectory(), "misc");
    }

    /**
     * Get data/misc_ce/<userId> directory
     */
    public static File getDataMiscCeDirectory(int userId) {
        return buildPath(android.os.Environment.getDataDirectory(), "misc_ce",
                String.valueOf(userId));
    }

    /**
     * Append path segments to given base path, returning result.
     */
    public static File buildPath(File base, String... segments) {
        File cur = base;
        for (String segment : segments) {
            if (cur == null) {
                cur = new File(segment);
            } else {
                cur = new File(cur, segment);
            }
        }
        return cur;
    }


    /**
     * Wifi shared folder.
     */
    public static File getWifiSharedDirectory() {
        return ApexContext.getApexContext(WIFI_APEX_NAME).getDeviceProtectedDataDir();
    }

    /**
     * Wifi user specific folder.
     */
    public static File getWifiUserDirectory(int userId) {
        return ApexContext.getApexContext(WIFI_APEX_NAME).getCredentialProtectedDataDirForUser(
                UserHandle.of(userId));
    }


    /**
     * Pre apex wifi shared folder.
     */
    public static File getLegacyWifiSharedDirectory() {
        return new File(getDataMiscDirectory(), LEGACY_WIFI_STORE_DIRECTORY_NAME);
    }

    /**
     * Pre apex wifi user folder.
     */
    public static File getLegacyWifiUserDirectory(int userId) {
        return new File(getDataMiscCeDirectory(userId), LEGACY_WIFI_STORE_DIRECTORY_NAME);
    }
}
