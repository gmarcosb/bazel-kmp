// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import ExtendedEventHandler.Postable
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable

/** Posted when there is a configuration transition.  */
@kotlin.jvm.JvmRecord
data class ConfigurationTransitionEvent(val parentChecksum: String?, val childChecksum: String?) :
    Comparable<ConfigurationTransitionEvent?>, Postable {
    override fun compareTo(that: ConfigurationTransitionEvent): Int {
        val result = this.parentChecksum!!.compareTo(that.parentChecksum!!)
        if (result != 0) {
            return result
        }
        return this.childChecksum!!.compareTo(that.childChecksum!!)
    }

    init {
        java.util.Objects.requireNonNull<String?>(parentChecksum, "parentChecksum")
        java.util.Objects.requireNonNull<String?>(childChecksum, "childChecksum")
    }

    companion object {
        fun create(parentChecksum: String?, childChecksum: String?): ConfigurationTransitionEvent {
            return ConfigurationTransitionEvent(parentChecksum, childChecksum)
        }
    }
}
