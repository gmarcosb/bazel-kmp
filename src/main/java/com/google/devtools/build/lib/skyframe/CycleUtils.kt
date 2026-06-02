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
package com.google.devtools.build.lib.skyframe

/** Simple utility for separating path and cycle from a combined iterable.  */
internal object CycleUtils {
    fun <S> splitIntoPathAndChain(
        startOfCycle: com.google.common.base.Predicate<S?>, pathAndCycle: Iterable<S?>
    ): com.google.devtools.build.lib.util.Pair<com.google.common.collect.ImmutableList<S?>?, com.google.common.collect.ImmutableList<S?>?> {
        var inPathToCycle = true
        val pathToCycleBuilder: com.google.common.collect.ImmutableList.Builder<S?> =
            com.google.common.collect.ImmutableList.builder<S?>()
        val cycleBuilder: com.google.common.collect.ImmutableList.Builder<S?> =
            com.google.common.collect.ImmutableList.builder<S?>()
        for (elt in pathAndCycle) {
            if (startOfCycle.apply(elt)) {
                inPathToCycle = false
            }
            if (inPathToCycle) {
                pathToCycleBuilder.add(elt)
            } else {
                cycleBuilder.add(elt)
            }
        }
        return com.google.devtools.build.lib.util.Pair.of<com.google.common.collect.ImmutableList<S?>?, com.google.common.collect.ImmutableList<S?>?>(
            pathToCycleBuilder.build(),
            cycleBuilder.build()
        )
    }
}
