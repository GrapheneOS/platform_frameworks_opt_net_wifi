/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.NonNull;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.hotspot2.PasspointNetworkNominateHelper;
import com.android.server.wifi.util.TelephonyUtil;

import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkNominator implementation for
 * saved networks.
 */
public class SavedNetworkNominator implements WifiNetworkSelector.NetworkNominator {
    private static final String NAME = "SavedNetworkNominator";
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;
    private final TelephonyUtil mTelephonyUtil;
    private final PasspointNetworkNominateHelper mPasspointNetworkNominateHelper;

    SavedNetworkNominator(WifiConfigManager configManager,
            PasspointNetworkNominateHelper nominateHelper,
            LocalLog localLog, TelephonyUtil telephonyUtil) {
        mWifiConfigManager = configManager;
        mPasspointNetworkNominateHelper = nominateHelper;
        mLocalLog = localLog;
        mTelephonyUtil = telephonyUtil;
    }

    private void localLog(String log) {
        mLocalLog.log(log);
    }

    /**
     * Get the Nominator type.
     */
    @Override
    public @NominatorId int getId() {
        return NOMINATOR_ID_SAVED;
    }

    /**
     * Get the Nominator name.
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Update the Nominator.
     */
    @Override
    public void update(List<ScanDetail> scanDetails) { }

    /**
     * Run through all scanDetails and nominate all connectable network as candidates.
     *
     */
    @Override
    public void nominateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid, boolean connected,
                    boolean untrustedNetworkAllowed,
                    @NonNull OnConnectableListener onConnectableListener) {
        findMatchedSavedNetworks(scanDetails, onConnectableListener);
        findMatchedPasspointNetworks(scanDetails, onConnectableListener);
    }

    private void findMatchedSavedNetworks(List<ScanDetail> scanDetails,
            OnConnectableListener onConnectableListener) {
        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            // One ScanResult can be associated with more than one network, hence we calculate all
            // the scores and use the highest one as the ScanResult's score.
            // TODO(b/112196799): this has side effects, rather not do that in a nominator
            WifiConfiguration network =
                    mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail);

            if (network == null) {
                continue;
            }

            /**
             * Ignore Passpoint and Ephemeral networks. They are configured networks,
             * but without being persisted to the storage. They are nominated by
             * {@link PasspointNetworkNominator} and {@link ScoredNetworkNominator}
             * respectively.
             */
            if (network.isPasspoint() || network.isEphemeral()) {
                continue;
            }

            // Ignore networks that the user has disallowed auto-join for.
            if (!network.allowAutojoin) {
                continue;
            }

            WifiConfiguration.NetworkSelectionStatus status =
                    network.getNetworkSelectionStatus();
            // TODO (b/112196799): another side effect
            status.setSeenInLastQualifiedNetworkSelection(true);

            if (mWifiConfigManager.isNetworkTemporarilyDisabledByUser(network.SSID)) {
                mLocalLog.log("Ignoring user disabled SSID: " + network.SSID);
                continue;
            }

            if (!status.isNetworkEnabled()) {
                continue;
            } else if (network.BSSID != null &&  !network.BSSID.equals("any")
                    && !network.BSSID.equals(scanResult.BSSID)) {
                // App has specified the only BSSID to connect for this
                // configuration. So only the matching ScanResult can be a candidate.
                localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                        + " has specified BSSID " + network.BSSID + ". Skip "
                        + scanResult.BSSID);
                continue;
            } else if (network.enterpriseConfig != null
                    && network.enterpriseConfig.isAuthenticationSimBased()) {
                int subId = mTelephonyUtil.getBestMatchSubscriptionId(network);
                if (!mTelephonyUtil.isSimPresent(subId)) {
                    // Don't select if security type is EAP SIM/AKA/AKA' when SIM is not present.
                    localLog("No SIM card is good for Network "
                            + WifiNetworkSelector.toNetworkString(network));
                    continue;
                }
            }

            // If the network is marked to use external scores, or is an open network with
            // curate saved open networks enabled, do not consider it for network selection.
            if (network.useExternalScores) {
                localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                        + " has external score.");
                continue;
            }

            onConnectableListener.onConnectable(scanDetail,
                    mWifiConfigManager.getConfiguredNetwork(network.networkId));
        }
    }

    private void findMatchedPasspointNetworks(List<ScanDetail> scanDetails,
            OnConnectableListener onConnectableListener) {
        List<Pair<ScanDetail, WifiConfiguration>> candidates =
                mPasspointNetworkNominateHelper.getPasspointNetworkCandidates(scanDetails, false);
        for (Pair<ScanDetail, WifiConfiguration> candidate : candidates) {
            onConnectableListener.onConnectable(candidate.first, candidate.second);
        }
    }
}
