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

import android.annotation.NonNull;

/** Listener for events on ClientModeImpl. */
public interface ClientModeImplListener {
    /**
     * Called when a ClientModeImpl has been L2 connected.
     * @param clientModeManager the ClientModeManager associated with the ClientModeImpl
     */
    default void onL2Connected(@NonNull ConcreteClientModeManager clientModeManager) {}

    /**
     * Called when a ClientModeImpl has been L3 connected.
     * @param clientModeManager the ClientModeManager associated with the ClientModeImpl
     */
    default void onL3Connected(@NonNull ConcreteClientModeManager clientModeManager) {}

    /**
     * Called when a ClientModeImpl has been L3 validated.
     * @param clientModeManager the ClientModeManager associated with the ClientModeImpl
     */
    default void onL3Validated(@NonNull ConcreteClientModeManager clientModeManager) {}
}
