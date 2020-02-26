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

import android.annotation.Nullable;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiMigration;
import android.net.wifi.WifiMigration.ConfigStoreMigrationData;

import java.util.List;

/**
 * Caches the data migrated out of OEM config store. This class helps to avoid invoking the
 * {@link WifiMigration#loadFromConfigStore()} multiple times from different instances of
 * {@link WifiConfigStore.StoreData}.
 *
 */
public class WifiConfigStoreMigrationDataHolder {
    private ConfigStoreMigrationData mData;
    private boolean mLoaded = false;

    private void loadOemMigrationData() {
        if (!mLoaded) {
            mData = WifiMigration.loadFromConfigStore();
            mLoaded = true;
        }
    }

    /**
     * Helper method to load saved network configuration from OEM migration code.
     */
    @Nullable
    public List<WifiConfiguration> getUserSavedNetworks() {
        loadOemMigrationData();
        if (mData == null) return null;
        return mData.getUserSavedNetworkConfigurations();
    }

    /**
     * Helper method to load saved softap configuration from OEM migration code.
     */
    @Nullable
    public SoftApConfiguration getUserSoftApConfiguration() {
        loadOemMigrationData();
        if (mData == null) return null;
        return mData.getUserSoftApConfiguration();
    }

}
