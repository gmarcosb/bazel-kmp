// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/** Contains state that aids in action key computation via [AbstractAction.computeKey].  */
class ActionKeyContext {
    private val nestedSetFingerprintCache: NestedSetFingerprintCache = NestedSetFingerprintCache()

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun <T> addNestedSetToFingerprint(fingerprint: Fingerprint?, nestedSet: NestedSet<T?>?) {
        nestedSetFingerprintCache.addNestedSetToFingerprint(fingerprint, nestedSet)
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun <T> addNestedSetToFingerprint(
        mapFn: MapFn<in T?>?, fingerprint: Fingerprint?, nestedSet: NestedSet<T?>?
    ) {
        nestedSetFingerprintCache.addNestedSetToFingerprint(mapFn, fingerprint, nestedSet)
    }

    fun <T> addNestedSetToFingerprint(
        mapFn: ExceptionlessMapFn<in T?>?,
        fingerprint: Fingerprint?,
        nestedSet: NestedSet<T?>?
    ) {
        nestedSetFingerprintCache.addNestedSetToFingerprintExceptionless(mapFn, fingerprint, nestedSet)
    }

    fun clear() {
        nestedSetFingerprintCache.clear()
    }

    companion object {
        fun <T> describeNestedSetFingerprint(
            mapFn: ExceptionlessMapFn<in T?>?, nestedSet: NestedSet<T?>?
        ): String {
            return NestedSetFingerprintCache.describedNestedSetFingerprint(mapFn, nestedSet)
        }
    }
}
