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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey

/**
 * Interface for [com.google.devtools.build.lib.skyframe.BzlLoadValue.Key].
 * 
 * 
 * This exists to break what would otherwise be a circular dependency between [Label],
 * [BazelModuleContext] and [com.google.devtools.build.lib.skyframe.BzlLoadValue.Key].
 */
interface BazelModuleKey : SkyKey {
    /** Absolute label of the .bzl file to be loaded.  */
    val label: com.google.devtools.build.lib.cmdline.Label?

    override fun functionName(): SkyFunctionName? {
        return SkyFunctions.BZL_LOAD
    }

    /** Key for [BazelModuleContext]s created outside of Skyframe for testing  */
    class FakeModuleKey private constructor(label: com.google.devtools.build.lib.cmdline.Label?) : BazelModuleKey {
        private val label: com.google.devtools.build.lib.cmdline.Label?

        init {
            this.label = label
        }

        override fun getLabel(): com.google.devtools.build.lib.cmdline.Label? {
            return label
        }

        override fun functionName(): SkyFunctionName? {
            throw java.lang.UnsupportedOperationException()
        }
    }

    companion object {
        /** Creates a fake instance for testing.  */
        fun createFakeModuleKeyForTesting(label: com.google.devtools.build.lib.cmdline.Label?): BazelModuleKey {
            return FakeModuleKey(label)
        }
    }
}
