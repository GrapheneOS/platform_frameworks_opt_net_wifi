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

import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_PRIMARY;

import android.annotation.NonNull;
import android.util.ArrayMap;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Used to buffer public broadcasts when multiple concurrent client interfaces are active to
 * preserve legacy behavior expected by apps when there is a single client interface active.
 */
public class ClientModeManagerBroadcastQueue {

    private static final String TAG = "BroadcastQueue";

    /** List of buffered broadcasts, per-ClientModeManager. */
    private final Map<ClientModeManager, List<QueuedBroadcast>> mBufferedBroadcasts =
            new ArrayMap<>();

    /** Lambda representing a broadcast to be sent. */
    public interface QueuedBroadcast {
        /** Send the broadcast using one of the many different Context#send* implementations. */
        void send();
    }

    public ClientModeManagerBroadcastQueue(@NonNull ActiveModeWarden activeModeWarden) {
        activeModeWarden.registerModeChangeCallback(new ModeChangeCallback());
        activeModeWarden.registerPrimaryClientModeManagerChangedCallback(
                new PrimaryClientModeManagerChangedCallback());
    }

    /**
     * If the ClientModeManager is primary, the broadcast will be sent immediately. Otherwise, the
     * broadcast will be queued, and sent out if and when the ClientModeManager becomes primary.
     */
    public void queueOrSendBroadcast(
            @NonNull ClientModeManager manager,
            @NonNull QueuedBroadcast broadcast) {
        if (manager.getRole() == ROLE_CLIENT_PRIMARY) {
            // primary, send existing queued broadcasts and send the new broadcast immediately
            sendAllBroadcasts(manager);
            broadcast.send();
        } else {
            // buffer the broadcast until the ClientModeManager becomes primary.
            mBufferedBroadcasts
                    .computeIfAbsent(manager, k -> new ArrayList<>())
                    .add(broadcast);
        }
    }

    private void sendAllBroadcasts(ClientModeManager manager) {
        List<QueuedBroadcast> queuedBroadcasts = mBufferedBroadcasts.getOrDefault(
                manager, Collections.emptyList());
        for (QueuedBroadcast broadcast : queuedBroadcasts) {
            broadcast.send();
        }
        // clear the sent broadcasts
        clearQueue(manager);
    }

    /**
     * Clear the broadcast queue for the given manager when e.g. the Make-Before-Break attempt
     * fails, or the ClientModeManager is deleted.
     *
     * TODO(b/174041877): Call this when connection fails during Make Before Break
     */
    public void clearQueue(@NonNull ClientModeManager manager) {
        mBufferedBroadcasts.remove(manager);
    }

    private class PrimaryClientModeManagerChangedCallback
            implements ActiveModeWarden.PrimaryClientModeManagerChangedCallback {

        // TODO(b/174041877): Make Before Break: Need to send fake disconnection broadcasts for
        //  previous primary ClientModeManager before sending out queued connection broadcasts for
        //  new primary ClientModeManager
        @Override
        public void onChange(
                @Nullable ConcreteClientModeManager prevPrimaryClientModeManager,
                @Nullable ConcreteClientModeManager newPrimaryClientModeManager) {
            if (newPrimaryClientModeManager == null) {
                return;
            }
            // when the a ClientModeManager becomes primary, send all its queued broadcasts
            sendAllBroadcasts(newPrimaryClientModeManager);
        }
    }

    private class ModeChangeCallback implements ActiveModeWarden.ModeChangeCallback {

        @Override
        public void onActiveModeManagerAdded(@NonNull ActiveModeManager activeModeManager) {
            // no-op
        }

        @Override
        public void onActiveModeManagerRoleChanged(@NonNull ActiveModeManager activeModeManager) {
            // no-op
        }

        @Override
        public void onActiveModeManagerRemoved(@NonNull ActiveModeManager activeModeManager) {
            if (!(activeModeManager instanceof ClientModeManager)) {
                return;
            }
            ClientModeManager clientModeManager = (ClientModeManager) activeModeManager;
            clearQueue(clientModeManager);
        }
    }
}
