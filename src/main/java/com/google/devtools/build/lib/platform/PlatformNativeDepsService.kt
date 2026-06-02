// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.platform

import java.util.function.IntConsumer

/** Service interface for platform-specific native dependencies.  */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface PlatformNativeDepsService : com.google.devtools.build.lib.runtime.BlazeService {
    /**
     * Push a request to disable automatic sleep for hardware. Useful for making sure computers don't
     * go to sleep during long builds. Must be matched with a [.popDisableSleep] call.
     * 
     * @return 0 on success, -1 if sleep is not supported.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun pushDisableSleep(): Int

    /**
     * Pop a request to disable automatic sleep for hardware. Useful for making sure computers don't
     * go to sleep during long builds. Must be matched with a previous [.pushDisableSleep] call.
     * 
     * @return 0 on success, -1 if sleep is not supported.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun popDisableSleep(): Int

    /** Registers the JNI callbacks for the CPU speed module.  */
    fun registerCPUSpeedJni(callback: IntConsumer?)

    /**
     * Returns the current CPU speed as a percentage.
     * 
     * @return 1-100 to represent CPU speed. Returns -1 in case of error.
     */
    fun cpuSpeed(): Int

    /** Registers the JNI callbacks for the disk space module.  */
    fun registerDiskSpaceJni(callback: IntConsumer?)

    /** Registers the JNI callbacks for the load advisory module.  */
    fun registerLoadAdvisoryJni(callback: IntConsumer?)

    /** Returns the system load advisory.  */
    fun systemLoadAdvisory(): Int

    /** Registers the JNI callbacks for the memory pressure monitor.  */
    fun registerMemoryPressureJni(callback: IntConsumer?)

    /** Returns the current memory pressure.  */
    fun systemMemoryPressure(): Int

    /** Registers the JNI callbacks for the suspension module.  */
    fun registerSuspensionJni(callback: IntConsumer?)

    /** Registers the JNI callbacks for the thermal module.  */
    fun registerThermalJni(callback: IntConsumer?)

    /** Returns the current thermal load.  */
    fun thermalLoad(): Int
}
