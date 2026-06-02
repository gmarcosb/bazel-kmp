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

import com.google.devtools.build.lib.platform.PlatformNativeDepsService
import java.util.function.IntConsumer

/** Implementation of [PlatformNativeDepsService].  */
class PlatformNativeDepsServiceImpl : PlatformNativeDepsService {
    override fun pushDisableSleep(): Int {
        return pushDisableSleepNative()
    }

    private external fun pushDisableSleepNative(): Int

    override fun popDisableSleep(): Int {
        return popDisableSleepNative()
    }

    private external fun popDisableSleepNative(): Int

    override fun registerCPUSpeedJni(callback: IntConsumer?) {
        registerCPUSpeedNative(callback)
    }

    private external fun registerCPUSpeedNative(callback: IntConsumer?)

    override fun cpuSpeed(): Int {
        return cpuSpeedNative()
    }

    private external fun cpuSpeedNative(): Int

    override fun registerDiskSpaceJni(callback: IntConsumer?) {
        registerDiskSpaceNative(callback)
    }

    private external fun registerDiskSpaceNative(callback: IntConsumer?)

    override fun registerLoadAdvisoryJni(callback: IntConsumer?) {
        registerLoadAdvisoryNative(callback)
    }

    private external fun registerLoadAdvisoryNative(callback: IntConsumer?)

    override fun systemLoadAdvisory(): Int {
        return systemLoadAdvisoryNative()
    }

    private external fun systemLoadAdvisoryNative(): Int

    override fun registerMemoryPressureJni(callback: IntConsumer?) {
        registerMemoryPressureNative(callback)
    }

    private external fun registerMemoryPressureNative(callback: IntConsumer?)

    override fun systemMemoryPressure(): Int {
        return systemMemoryPressureNative()
    }

    private external fun systemMemoryPressureNative(): Int

    override fun registerSuspensionJni(callback: IntConsumer?) {
        registerSuspensionNative(callback)
    }

    private external fun registerSuspensionNative(callback: IntConsumer?)

    override fun registerThermalJni(callback: IntConsumer?) {
        registerThermalNative(callback)
    }

    private external fun registerThermalNative(callback: IntConsumer?)

    override fun thermalLoad(): Int {
        return thermalLoadNative()
    }

    private external fun thermalLoadNative(): Int

    companion object {
        init {
            com.google.devtools.build.lib.jni.JniLoader.loadJni()
        }
    }
}
