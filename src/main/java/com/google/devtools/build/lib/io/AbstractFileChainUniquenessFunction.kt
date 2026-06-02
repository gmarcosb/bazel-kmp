// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.io

import com.google.devtools.build.lib.events.Event

/**
 * Given a "cycle" of [RootedPath] files, emits an error message for this cycle. The keys for
 * this SkyFunction are assumed to deduplicate cycles that differ only in which element of the cycle
 * they start at, so multiple paths to the cycle will be reported by a single execution of this
 * function.
 * 
 * 
 * The cycle need not actually be a cycle -- any iterable exhibiting an error that is independent
 * of the iterable's starting point can be an argument to this function.
 */
internal abstract class AbstractFileChainUniquenessFunction : SkyFunction {
    protected abstract val conciseDescription: String?

    protected abstract val headerMessage: String?

    protected abstract val footerMessage: String?

    protected abstract fun elementToString(path: RootedPath?): String?

    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val errorMessage: java.lang.StringBuilder = java.lang.StringBuilder()
        errorMessage.append(this.conciseDescription + " detected\n")
        errorMessage.append(this.headerMessage + "\n")
        val chain: com.google.common.collect.ImmutableList<RootedPath?> =
            skyKey.argument() as com.google.common.collect.ImmutableList<RootedPath?>
        for (elt in chain) {
            errorMessage.append(elementToString(elt) + "\n")
        }
        errorMessage.append(this.footerMessage + "\n")
        // The purpose of this SkyFunction is the side effect of emitting an error message exactly
        // once per build per unique error.
        env.getListener().handle(Event.error(errorMessage.toString()))
        return EmptySkyValue.INSTANCE
    }

    companion object {
        /**
         * Creates a canonicalized representation of the cycle specified by `chain`. `chain`
         * must be non-empty. The representation may not be unique if cycle has duplicate elements.
         */
        fun canonicalize(cycle: com.google.common.collect.ImmutableList<RootedPath>): com.google.common.collect.ImmutableList<RootedPath?> {
            var minPos = 0
            var min: RootedPath = cycle.get(0)
            for (i in 1..<cycle.size) {
                val cur: RootedPath = cycle.get(i)
                if (cur.compareTo(min) < 0) {
                    minPos = i
                    min = cur
                }
            }
            return com.google.common.collect.ImmutableList.builderWithExpectedSize<RootedPath?>(cycle.size)
                .addAll(cycle.subList(minPos, cycle.size))
                .addAll(cycle.subList(0, minPos))
                .build()
        }
    }
}
