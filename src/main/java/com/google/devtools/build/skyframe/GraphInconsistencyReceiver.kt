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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.proto.GraphInconsistency.Inconsistency

/**
 * A receiver that can be informed of inconsistencies detected in Skyframe. Such inconsistencies are
 * usually the result of external data loss (such as nodes in the graph, or the results of external
 * computations stored in a remote execution service).
 * 
 * 
 * The receiver can tolerate such inconsistencies, or throw hard if they are unexpected.
 */
interface GraphInconsistencyReceiver {
    fun noteInconsistencyAndMaybeThrow(
        key: SkyKey?, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency?
    )

    val inconsistencyStats: InconsistencyStats
        get() = InconsistencyStats.getDefaultInstance()

    fun reset() {}

    companion object {
        /** A [GraphInconsistencyReceiver] that crashes on any inconsistency.  */
        @kotlin.jvm.JvmField
        val THROWING: GraphInconsistencyReceiver =
            GraphInconsistencyReceiver { key: SkyKey?, otherKey: MutableCollection<SkyKey?>?, inconsistency: Inconsistency? ->
                throw java.lang.IllegalStateException(
                    "Unexpected inconsistency: " + key + ", " + otherKey + ", " + inconsistency
                )
            }
    }
}
