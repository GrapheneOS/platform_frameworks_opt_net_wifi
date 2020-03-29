/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;

import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.net.wifi.IActionListener;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiScanner;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.os.BasicShellCommandHandler;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;

import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.ArrayUtils;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Interprets and executes 'adb shell cmd wifi [args]'.
 *
 * To add new commands:
 * - onCommand: Add a case "<command>" execute. Return a 0
 *   if command executed successfully.
 * - onHelp: add a description string.
 *
 * If additional state objects are necessary add them to the
 * constructor.
 *
 * Permissions: currently root permission is required for most
 * commands, which is checked using {@link #checkRootPermission()}.
 */
public class WifiShellCommand extends BasicShellCommandHandler {
    private static String SHELL_PACKAGE_NAME = "com.android.shell";
    // These don't require root access.
    // However, these do perform permission checks in the corresponding WifiService methods.
    private static final String[] NON_PRIVILEGED_COMMANDS = {
            "connect-network",
            "forget-network",
            "list-scan-results",
            "list-networks",
            "set-verbose-logging",
            "set-wifi-enabled",
            "start-scan",
            "status",
    };

    private final ClientModeImpl mClientModeImpl;
    private final WifiLockManager mWifiLockManager;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiNative mWifiNative;
    private final HostapdHal mHostapdHal;
    private final WifiCountryCode mWifiCountryCode;
    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final WifiServiceImpl mWifiService;
    private final Context mContext;

    WifiShellCommand(WifiInjector wifiInjector, WifiServiceImpl wifiService, Context context) {
        mClientModeImpl = wifiInjector.getClientModeImpl();
        mWifiLockManager = wifiInjector.getWifiLockManager();
        mWifiNetworkSuggestionsManager = wifiInjector.getWifiNetworkSuggestionsManager();
        mWifiConfigManager = wifiInjector.getWifiConfigManager();
        mHostapdHal = wifiInjector.getHostapdHal();
        mWifiNative = wifiInjector.getWifiNative();
        mWifiCountryCode = wifiInjector.getWifiCountryCode();
        mWifiLastResortWatchdog = wifiInjector.getWifiLastResortWatchdog();
        mWifiService = wifiService;
        mContext = context;
    }

    @Override
    public int onCommand(String cmd) {
        // Explicit exclusion from root permission
        // Do not require root permission to maintain backwards compatibility with
        // `svc wifi [enable|disable]`.
        if (ArrayUtils.indexOf(NON_PRIVILEGED_COMMANDS, cmd) == -1) {
            final int uid = Binder.getCallingUid();
            if (uid != Process.ROOT_UID) {
                throw new SecurityException(
                        "Uid " + uid + " does not have access to " + cmd + " wifi command");
            }
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd != null ? cmd : "") {
                case "set-ipreach-disconnect": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'set-ipreach-disconnect' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    mClientModeImpl.setIpReachabilityDisconnectEnabled(enabled);
                    return 0;
                }
                case "get-ipreach-disconnect":
                    pw.println("IPREACH_DISCONNECT state is "
                            + mClientModeImpl.getIpReachabilityDisconnectEnabled());
                    return 0;
                case "set-poll-rssi-interval-msecs":
                    int newPollIntervalMsecs;
                    try {
                        newPollIntervalMsecs = Integer.parseInt(getNextArgRequired());
                    } catch (NumberFormatException e) {
                        pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                        + "- must be a positive integer");
                        return -1;
                    }

                    if (newPollIntervalMsecs < 1) {
                        pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                        + "- must be a positive integer");
                        return -1;
                    }

                    mClientModeImpl.setPollRssiIntervalMsecs(newPollIntervalMsecs);
                    return 0;
                case "get-poll-rssi-interval-msecs":
                    pw.println("ClientModeImpl.mPollRssiIntervalMsecs = "
                            + mClientModeImpl.getPollRssiIntervalMsecs());
                    return 0;
                case "force-hi-perf-mode": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-hi-perf-mode' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    if (!mWifiLockManager.forceHiPerfMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "force-low-latency-mode": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-low-latency-mode' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    if (!mWifiLockManager.forceLowLatencyMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "network-suggestions-set-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean approved;
                    String nextArg = getNextArgRequired();
                    if ("yes".equals(nextArg)) {
                        approved = true;
                    } else if ("no".equals(nextArg)) {
                        approved = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'network-suggestions-set-user-approved' "
                                        + "- must be 'yes' or 'no'");
                        return -1;
                    }
                    mWifiNetworkSuggestionsManager.setHasUserApprovedForApp(approved, packageName);
                    return 0;
                }
                case "network-suggestions-has-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean hasUserApproved =
                            mWifiNetworkSuggestionsManager.hasUserApprovedForApp(packageName);
                    pw.println(hasUserApproved ? "yes" : "no");
                    return 0;
                }
                case "imsi-protection-exemption-set-user-approved-for-carrier": {
                    String arg1 = getNextArgRequired();
                    String arg2 = getNextArgRequired();
                    int carrierId = -1;
                    boolean approved;
                    try {
                        carrierId = Integer.parseInt(arg1);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid argument to "
                                + "'imsi-protection-exemption-set-user-approved-for-carrier' "
                                + "- carrierId must be an Integer");
                        return -1;
                    }
                    if ("yes".equals(arg2)) {
                        approved = true;
                    } else if ("no".equals(arg2)) {
                        approved = false;
                    } else {
                        pw.println("Invalid argument to "
                                + "'imsi-protection-exemption-set-user-approved-for-carrier' "
                                + "- must be 'yes' or 'no'");
                        return -1;
                    }
                    mWifiNetworkSuggestionsManager
                            .setHasUserApprovedImsiPrivacyExemptionForCarrier(approved, carrierId);
                    return 0;
                }
                case "imsi-protection-exemption-has-user-approved-for-carrier": {
                    String arg1 = getNextArgRequired();
                    int carrierId = -1;
                    try {
                        carrierId = Integer.parseInt(arg1);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid argument to "
                                + "'imsi-protection-exemption-has-user-approved-for-carrier' "
                                + "- 'carrierId' must be an Integer");
                        return -1;
                    }
                    boolean hasUserApproved = mWifiNetworkSuggestionsManager
                            .hasUserApprovedImsiPrivacyExemptionForCarrier(carrierId);
                    pw.println(hasUserApproved ? "yes" : "no");
                    return 0;
                }
                case "imsi-protection-exemption-clear-user-approved-for-carrier": {
                    String arg1 = getNextArgRequired();
                    int carrierId = -1;
                    try {
                        carrierId = Integer.parseInt(arg1);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid argument to "
                                + "'imsi-protection-exemption-clear-user-approved-for-carrier' "
                                + "- 'carrierId' must be an Integer");
                        return -1;
                    }
                    mWifiNetworkSuggestionsManager.clearImsiPrivacyExemptionForCarrier(carrierId);
                    return 0;
                }
                case "network-requests-remove-user-approved-access-points": {
                    String packageName = getNextArgRequired();
                    mClientModeImpl.removeNetworkRequestUserApprovedAccessPointsForApp(packageName);
                    return 0;
                }
                case "clear-user-disabled-networks": {
                    mWifiConfigManager.clearUserTemporarilyDisabledList();
                    return 0;
                }
                case "send-link-probe": {
                    return sendLinkProbe(pw);
                }
                case "force-softap-channel": {
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg))  {
                        int apChannelMHz;
                        try {
                            apChannelMHz = Integer.parseInt(getNextArgRequired());
                        } catch (NumberFormatException e) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- must be a positive integer");
                            return -1;
                        }
                        int apChannel = ApConfigUtil.convertFrequencyToChannel(apChannelMHz);
                        int band = ApConfigUtil.convertFrequencyToBand(apChannelMHz);
                        if (apChannel == -1 || band == -1 || !isApChannelMHzValid(apChannelMHz)) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- must be a valid WLAN channel");
                            return -1;
                        }

                        if ((band == SoftApConfiguration.BAND_5GHZ
                                && !mWifiService.is5GHzBandSupported())
                                || (band == SoftApConfiguration.BAND_6GHZ
                                && !mWifiService.is6GHzBandSupported())) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- channel band is not supported by the device");
                            return -1;
                        }

                        mHostapdHal.enableForceSoftApChannel(apChannel, band);
                        return 0;
                    } else if ("disabled".equals(nextArg)) {
                        mHostapdHal.disableForceSoftApChannel();
                        return 0;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-softap-channel' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                }
                case "force-country-code": {
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg))  {
                        String countryCode = getNextArgRequired();
                        if (!(countryCode.length() == 2
                                && countryCode.chars().allMatch(Character::isLetter))) {
                            pw.println("Invalid argument to 'force-country-code enabled' "
                                    + "- must be a two-letter string");
                            return -1;
                        }
                        mWifiCountryCode.enableForceCountryCode(countryCode);
                        return 0;
                    } else if ("disabled".equals(nextArg)) {
                        mWifiCountryCode.disableForceCountryCode();
                        return 0;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-country-code' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                }
                case "get-country-code": {
                    pw.println("Wifi Country Code = "
                            + mWifiCountryCode.getCountryCode());
                    return 0;
                }
                case "set-wifi-watchdog": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'set-wifi-watchdog' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    mWifiLastResortWatchdog.setWifiWatchdogFeature(enabled);
                    return 0;
                }
                case "get-wifi-watchdog": {
                    pw.println("wifi watchdog state is "
                            + mWifiLastResortWatchdog.getWifiWatchdogFeature());
                    return 0;
                }
                case "set-wifi-enabled": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'set-wifi-enabled' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    mWifiService.setWifiEnabled(SHELL_PACKAGE_NAME, enabled);
                    return 0;
                }
                case "get-softap-supported-features":
                    // This command is used for vts to check softap supported features.
                    if (ApConfigUtil.isAcsSupported(mContext)) {
                        pw.println("wifi_softap_acs_supported");
                    }
                    if (ApConfigUtil.isWpa3SaeSupported(mContext)) {
                        pw.println("wifi_softap_wpa3_sae_supported");
                    }
                    break;
                case "settings-reset":
                    mWifiService.factoryReset(SHELL_PACKAGE_NAME);
                    break;
                case "list-scan-results":
                    List<ScanResult> scanResults =
                            mWifiService.getScanResults(SHELL_PACKAGE_NAME, null);
                    if (scanResults.isEmpty()) {
                        pw.println("No scan results");
                    } else {
                        ScanResultUtil.dumpScanResults(pw, scanResults,
                                SystemClock.elapsedRealtime());
                    }
                    break;
                case "start-scan":
                    mWifiService.startScan(SHELL_PACKAGE_NAME, null);
                    break;
                case "list-networks":
                    ParceledListSlice<WifiConfiguration> networks =
                            mWifiService.getConfiguredNetworks(SHELL_PACKAGE_NAME, null);
                    if (networks == null || networks.getList().isEmpty()) {
                        pw.println("No networks");
                    } else {
                        pw.println("Network Id      SSID                         Security type");
                        for (WifiConfiguration network : networks.getList()) {
                            String securityType = null;
                            if (WifiConfigurationUtil.isConfigForSaeNetwork(network)) {
                                securityType = "wpa3";
                            } else if (WifiConfigurationUtil.isConfigForPskNetwork(network)) {
                                securityType = "wpa2";
                            } else if (WifiConfigurationUtil.isConfigForEapNetwork(network)) {
                                securityType = "eap";
                            } else if (WifiConfigurationUtil.isConfigForOweNetwork(network)) {
                                securityType = "owe";
                            } else if (WifiConfigurationUtil.isConfigForOpenNetwork(network)) {
                                securityType = "open";
                            }
                            pw.println(String.format("%-12d %-32s %-4s",
                                    network.networkId, WifiInfo.sanitizeSsid(network.SSID),
                                    securityType));
                        }
                    }
                    break;
                case "connect-network": {
                    String ssid = getNextArgRequired();
                    String type = getNextArgRequired();
                    String optionalPassphrase = getNextArg();
                    WifiConfiguration configuration = new WifiConfiguration();
                    configuration.SSID = "\"" + ssid + "\"";
                    if (TextUtils.equals(type, "wpa3")) {
                        configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                    } else if (TextUtils.equals(type, "wpa2")) {
                        configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
                    } else if (TextUtils.equals(type, "owe")) {
                        configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);
                    } else if (TextUtils.equals(type, "open")) {
                        configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
                    }
                    if (optionalPassphrase != null) {
                        configuration.preSharedKey = "\"" + optionalPassphrase + "\"";
                    }
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    IActionListener.Stub actionListener = new IActionListener.Stub() {
                        @Override
                        public void onSuccess() throws RemoteException {
                            pw.println("Connection initiated ");
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onFailure(int i) throws RemoteException {
                            pw.println("Connection failed");
                            countDownLatch.countDown();
                        }
                    };
                    mWifiService.connect(
                            configuration, -1, new Binder(), actionListener,
                            actionListener.hashCode());
                    // wait for status.
                    countDownLatch.await(500, TimeUnit.MILLISECONDS);
                    break;
                }
                case "forget-network": {
                    String networkId = getNextArgRequired();
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    IActionListener.Stub actionListener = new IActionListener.Stub() {
                        @Override
                        public void onSuccess() throws RemoteException {
                            pw.println("Forget successful");
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onFailure(int i) throws RemoteException {
                            pw.println("Forget failed");
                            countDownLatch.countDown();
                        }
                    };
                    mWifiService.forget(
                            Integer.parseInt(networkId), new Binder(), actionListener,
                            actionListener.hashCode());
                    // wait for status.
                    countDownLatch.await(500, TimeUnit.MILLISECONDS);
                    break;
                }
                case "status":
                    boolean wifiEnabled = mWifiService.getWifiEnabledState() == WIFI_STATE_ENABLED;
                    pw.println("Wifi is " + (wifiEnabled ? "enabled" : "disabled"));
                    WifiInfo info =
                            mWifiService.getConnectionInfo(SHELL_PACKAGE_NAME, null);
                    if (wifiEnabled) {
                        if (info.getSupplicantState() == SupplicantState.COMPLETED) {
                            pw.println("Wifi is connected to " + info.getSSID());
                            pw.println("Connection Details: " + info);
                        } else {
                            pw.println("Wifi is not connected");
                        }
                    }
                    break;
                case "set-verbose-logging":
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'set-verbose-logging' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    mWifiService.enableVerboseLogging(enabled ? 1 : 0);
                    break;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception while executing WifiShellCommand: ");
            e.printStackTrace(pw);
        }
        return -1;
    }

    private int sendLinkProbe(PrintWriter pw) throws InterruptedException {
        // Note: should match WifiNl80211Manager#SEND_MGMT_FRAME_TIMEOUT_MS
        final int sendMgmtFrameTimeoutMs = 1000;

        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        mClientModeImpl.probeLink(new WifiNl80211Manager.SendMgmtFrameCallback() {
            @Override
            public void onAck(int elapsedTimeMs) {
                queue.offer("Link probe succeeded after " + elapsedTimeMs + " ms");
            }

            @Override
            public void onFailure(int reason) {
                queue.offer("Link probe failed with reason " + reason);
            }
        }, -1);

        // block until msg is received, or timed out
        String msg = queue.poll(sendMgmtFrameTimeoutMs + 1000, TimeUnit.MILLISECONDS);
        if (msg == null) {
            pw.println("Link probe timed out");
        } else {
            pw.println(msg);
        }
        return 0;
    }

    private boolean isApChannelMHzValid(int apChannelMHz) {
        int[] allowed2gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
        int[] allowed5gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
        int[] allowed5gDfsFreq =
            mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY);
        int[] allowed6gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ);
        if (allowed2gFreq == null) {
            allowed2gFreq = new int[0];
        }
        if (allowed5gFreq == null) {
            allowed5gFreq = new int[0];
        }
        if (allowed5gDfsFreq == null) {
            allowed5gDfsFreq = new int[0];
        }
        if (allowed6gFreq == null) {
            allowed6gFreq = new int[0];
        }

        return (Arrays.binarySearch(allowed2gFreq, apChannelMHz) >= 0
                || Arrays.binarySearch(allowed5gFreq, apChannelMHz) >= 0
                || Arrays.binarySearch(allowed5gDfsFreq, apChannelMHz) >= 0)
                || Arrays.binarySearch(allowed6gFreq, apChannelMHz) >= 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("Wi-Fi (wifi) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-ipreach-disconnect enabled|disabled");
        pw.println("    Sets whether CMD_IP_REACHABILITY_LOST events should trigger disconnects.");
        pw.println("  get-ipreach-disconnect");
        pw.println("    Gets setting of CMD_IP_REACHABILITY_LOST events triggering disconnects.");
        pw.println("  set-poll-rssi-interval-msecs <int>");
        pw.println("    Sets the interval between RSSI polls to <int> milliseconds.");
        pw.println("  get-poll-rssi-interval-msecs");
        pw.println("    Gets current interval between RSSI polls, in milliseconds.");
        pw.println("  force-hi-perf-mode enabled|disabled");
        pw.println("    Sets whether hi-perf mode is forced or left for normal operation.");
        pw.println("  force-low-latency-mode enabled|disabled");
        pw.println("    Sets whether low latency mode is forced or left for normal operation.");
        pw.println("  network-suggestions-set-user-approved <package name> yes|no");
        pw.println("    Sets whether network suggestions from the app is approved or not.");
        pw.println("  network-suggestions-has-user-approved <package name>");
        pw.println("    Queries whether network suggestions from the app is approved or not.");
        pw.println("  imsi-protection-exemption-set-user-approved-for-carrier <carrier id> yes|no");
        pw.println("    Sets whether Imsi protection exemption for carrier is approved or not");
        pw.println("  imsi-protection-exemption-has-user-approved-for-carrier <carrier id>");
        pw.println("    Queries whether Imsi protection exemption for carrier is approved or not");
        pw.println("  imsi-protection-exemption-clear-user-approved-for-carrier <carrier id>");
        pw.println("    Clear the user choice on Imsi protection exemption for carrier");
        pw.println("  network-requests-remove-user-approved-access-points <package name>");
        pw.println("    Removes all user approved network requests for the app.");
        pw.println("  clear-user-disabled-networks");
        pw.println("    Clears the user disabled networks list.");
        pw.println("  send-link-probe");
        pw.println("    Manually triggers a link probe.");
        pw.println("  force-softap-channel enabled <int> | disabled");
        pw.println("    Sets whether soft AP channel is forced to <int> MHz");
        pw.println("    or left for normal   operation.");
        pw.println("  force-country-code enabled <two-letter code> | disabled ");
        pw.println("    Sets country code to <two-letter code> or left for normal value");
        pw.println("  get-country-code");
        pw.println("    Gets country code as a two-letter string");
        pw.println("  set-wifi-watchdog enabled|disabled");
        pw.println("    Sets whether wifi watchdog should trigger recovery");
        pw.println("  get-wifi-watchdog");
        pw.println("    Gets setting of wifi watchdog trigger recovery.");
        pw.println("  set-wifi-enabled enabled|disabled");
        pw.println("    Enables/disables Wifi on this device.");
        pw.println("  get-softap-supported-features");
        pw.println("    Gets softap supported features. Will print 'wifi_softap_acs_supported'");
        pw.println("    and/or 'wifi_softap_wpa3_sae_supported', each on a separate line.");
        pw.println("  settings-reset");
        pw.println("    Initiates wifi settings reset");
        pw.println("  list-scan-results");
        pw.println("    Lists the latest scan results");
        pw.println("  start-scan");
        pw.println("    Start a new scan");
        pw.println("  list-networks");
        pw.println("    Lists the saved networks");
        pw.println("  connect-network <ssid> open|owe|wpa2|wpa3 [<passphrase>]");
        pw.println("    Connect to a network with provided params and save");
        pw.println("    <ssid> - SSID of the network");
        pw.println("    open|owe|wpa2|wpa3 - Security type of the network.");
        pw.println("        - Use 'open' or 'owe' for networks with no passphrase");
        pw.println("           - 'open' - Open networks (Most prevalent)");
        pw.println("           - 'owe' - Enhanced open networks");
        pw.println("        - Use 'wpa2' or 'wpa3' for networks with passphrase");
        pw.println("           - 'wpa2' - WPA-2 PSK networks (Most prevalent)");
        pw.println("           - 'wpa3' - WPA-3 PSK networks");
        pw.println("  forget-network <networkId>");
        pw.println("    Remove the network mentioned by <networkId>");
        pw.println("        - Use list-networks to retrieve <networkId> for the network");
        pw.println("  status");
        pw.println("    Current wifi status");
        pw.println("  set-verbose-logging enabled|disabled ");
        pw.println("    Set the verbose logging enabled or disabled");
        pw.println();
    }
}
