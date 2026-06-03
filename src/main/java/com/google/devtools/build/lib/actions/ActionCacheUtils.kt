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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.cache.ActionCache
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import java.util.AbstractMap

/** Utility functions for [ActionCache].  */
object ActionCacheUtils {
    fun getCacheEntryWithKey(
        actionCache: ActionCache, action: com.google.devtools.build.lib.actions.Action
    ): MutableMap.MutableEntry<String?, com.google.devtools.build.lib.actions.cache.ActionCache.Entry?>? {
        for (output in action.getOutputs()) {
            val entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry? =
                actionCache.get(output.getExecPathString())
            if (entry != null) {
                return AbstractMap.SimpleEntry<String?, com.google.devtools.build.lib.actions.cache.ActionCache.Entry?>(
                    output.getExecPathString(),
                    entry
                )
            }
        }
        return null
    }

    /** Checks whether one of existing output paths is already used as a key.  */
    fun getCacheEntry(
        actionCache: ActionCache,
        action: com.google.devtools.build.lib.actions.Action
    ): com.google.devtools.build.lib.actions.cache.ActionCache.Entry? {
        for (output in action.getOutputs()) {
            val entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry? =
                actionCache.get(output.getExecPathString())
            if (entry != null) {
                return entry
            }
        }
        return null
    }

    fun removeCacheEntry(actionCache: ActionCache, action: com.google.devtools.build.lib.actions.Action) {
        for (output in action.getOutputs()) {
            actionCache.remove(output.getExecPathString())
        }
    }
}
