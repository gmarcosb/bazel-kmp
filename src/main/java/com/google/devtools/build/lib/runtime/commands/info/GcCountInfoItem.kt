// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands.info

import com.google.common.base.Supplier
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue
import java.lang.management.ManagementFactory

/** Info item for the gc-count.  */
class GcCountInfoItem : InfoItem("gc-count", "Number of garbage collection runs.", false) {
    public override fun get(
        configurationSupplier: Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
    ): ByteArray {
        // The documentation is not very clear on what it means to have more than
        // one GC MXBean, so we just sum them up.
        var gcCount: Long = 0
        for (gcBean in ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gcBean.getCollectionCount()
        }
        return print(gcCount.toString() + "")
    }
}
