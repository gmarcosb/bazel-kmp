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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.MachineLoadProvider
import com.google.devtools.build.lib.clock.BlazeClock.instance

/** A provider that collects the load of a machine for the resource manager.  */
class MachineLoadProvider private constructor() {
    private object Singleton {
        val instance: MachineLoadProvider = MachineLoadProvider()
    }

    /** Returns "recent" CPU load of the machine as number between 0 and number of cores.  */
    fun getCurrentCpuUsage(): Double {
        val cpuLoad: Double = osBean.getCpuLoad()
        val numProcessors: Int = java.lang.Runtime.getRuntime().availableProcessors()
        return cpuLoad * numProcessors
    }

    companion object {
        // Operating system bean used to collect statistic about CPU load of system.
        private val osBean: com.sun.management.OperatingSystemMXBean =
            java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

        /** Returns singleton instance of the machine load provider.  */
        fun instance(): MachineLoadProvider {
            return com.google.devtools.build.lib.actions.MachineLoadProvider.Singleton.instance
        }
    }
}
