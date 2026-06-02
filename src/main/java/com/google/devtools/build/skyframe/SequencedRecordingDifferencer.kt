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
package com.google.devtools.build.skyframe

/** A simple Differencer which just records the invalidated values it's been given.  */
@ThreadCompatible
class SequencedRecordingDifferencer : RecordingDifferencer {
    private var valuesToInvalidate: MutableList<SkyKey?>? = null
    private var valuesToInject: MutableMap<SkyKey?, Delta?>? = null

    init {
        clear()
    }

    private fun clear() {
        valuesToInvalidate = java.util.ArrayList<SkyKey?>()
        valuesToInject = HashMap<SkyKey?, Delta?>()
    }

    override fun getDiff(
        fromGraph: WalkableGraph?,
        fromVersion: com.google.devtools.build.skyframe.Version?,
        toVersion: com.google.devtools.build.skyframe.Version?
    ): com.google.devtools.build.skyframe.Differencer.Diff {
        val diff: com.google.devtools.build.skyframe.Differencer.Diff =
            ImmutableDiff(valuesToInvalidate, valuesToInject)
        clear()
        return diff
    }

    override fun invalidate(values: Iterable<SkyKey?>) {
        com.google.common.collect.Iterables.addAll<SkyKey?>(valuesToInvalidate, values)
    }

    override fun inject(deltas: MutableMap<SkyKey?, Delta?>?) {
        valuesToInject!!.putAll(deltas)
    }

    override fun inject(key: SkyKey?, delta: Delta?) {
        valuesToInject!!.put(key, delta)
    }
}
