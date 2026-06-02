// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.ConfiguredObjectValue
import com.google.devtools.build.lib.analysis.ConfiguredTarget
import com.google.devtools.build.lib.packages.TargetData

/** A [com.google.devtools.build.skyframe.SkyValue] for a [ConfiguredTarget].  */
interface ConfiguredTargetValue : ConfiguredObjectValue {
    /** Returns the configured target for this value.  */
    fun getConfiguredTarget(): ConfiguredTarget?

    /**
     * Clears data from this value.
     * 
     * 
     * Should only be used when user specifies --discard_analysis_cache. Must be called at most
     * once per value, after which this object's other methods cannot be called.
     */
    fun clear(clearEverything: Boolean)

    /**
     * Returns the [TargetData] projection for this value.
     * 
     * 
     * This is only present for remote cached configured targets.
     */
    fun getTargetData(): TargetData? {
        return null
    }

    override fun getConfiguredObject(): ConfiguredTarget? {
        return getConfiguredTarget()
    }
}
