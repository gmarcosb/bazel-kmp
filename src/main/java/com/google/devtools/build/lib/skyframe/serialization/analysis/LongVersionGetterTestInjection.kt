// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.util.TestType
import com.google.devtools.build.lib.versioning.LongVersionGetter

/**
 * Allows injecting a [LongVersionGetter] implementation to [FrontierSerializer] in
 * tests.
 */
object LongVersionGetterTestInjection {
    private var versionGetter: LongVersionGetter? = null
    private var wasAccessed = false

    val versionGetterForTesting: LongVersionGetter
        get() {
            com.google.common.base.Preconditions.checkState(TestType.isInTest())
            wasAccessed = true
            return com.google.common.base.Preconditions.checkNotNull<LongVersionGetter>(
                versionGetter,
                "injectVersionGetterForTesting must be called first"
            )
        }

    fun injectVersionGetterForTesting(versionGetter: LongVersionGetter?) {
        com.google.common.base.Preconditions.checkState(TestType.isInTest())
        LongVersionGetterTestInjection.versionGetter = versionGetter
    }

    fun wasGetterAccessed(): Boolean {
        return wasAccessed
    }
}
