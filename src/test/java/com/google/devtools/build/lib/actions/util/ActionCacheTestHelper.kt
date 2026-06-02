// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.util

import com.google.devtools.build.lib.actions.cache.ActionCache
import java.time.Duration
import java.util.function.Predicate

/**
 * Utilities for tests that use the action cache.
 */
object ActionCacheTestHelper {
    /** A cache which does not remember anything. Causes perpetual rebuilds!  */
    @kotlin.jvm.JvmField
    val AMNESIAC_CACHE: ActionCache = object : ActionCache() {
        public override fun put(fingerprint: String?, entry: Entry?) {}

        public override fun get(fingerprint: String?): Entry? {
            return null
        }

        public override fun remove(key: String?) {}

        public override fun removeIf(predicate: Predicate<Entry?>?) {}

        public override fun save(): Long {
            return -1
        }

        public override fun clear() {}

        public override fun trim(threshold: Float, maxAge: Duration?): ActionCache? {
            throw UnsupportedOperationException()
        }

        public override fun dump(out: PrintStream?) {}

        public override fun size(): Int {
            return 0
        }

        public override fun accountHit() {}

        public override fun accountMiss(reason: MissReason?) {}

        public override fun mergeIntoActionCacheStatistics(builder: ActionCacheStatistics.Builder?) {}

        public override fun resetStatistics() {}
    }
}
