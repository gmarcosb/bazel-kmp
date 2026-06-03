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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.util.Pair

/** Fake sleeper for testing.  */
class ManualSleeper(clock: com.google.devtools.build.lib.testutil.ManualClock?) : Sleeper {
    private val clock: com.google.devtools.build.lib.testutil.ManualClock
    private val scheduledRunnables: MutableList<Pair<Long?, java.lang.Runnable?>> =
        java.util.ArrayList<Pair<Long?, java.lang.Runnable?>>(0)

    init {
        this.clock =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.testutil.ManualClock>(clock)
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun sleepMillis(milliseconds: Long) {
        com.google.common.base.Preconditions.checkArgument(milliseconds >= 0, "sleeper can't time travel")
        val resultedCurrentTimeMillis: Long = clock.advanceMillis(milliseconds)

        val iterator: MutableIterator<Pair<Long?, java.lang.Runnable?>> = scheduledRunnables.iterator()

        // Run those scheduled Runnables who's time has come.
        while (iterator.hasNext()) {
            val scheduledRunnable: Pair<Long?, java.lang.Runnable?> = iterator.next()

            if (resultedCurrentTimeMillis >= scheduledRunnable.first) {
                iterator.remove()
                scheduledRunnable.second.run()
            }
        }
    }

    /**
     * Schedules a given [Runnable] to run when this Sleeper's clock has been adjusted with
     * [.sleepMillis] by `delayMilliseconds` or greater.
     * 
     * @param runnable runnable to run, must not throw exceptions.
     * @param delayMilliseconds delay in milliseconds from current value of [ManualClock] used
     * by this [ManualSleeper].
     */
    fun scheduleRunnable(runnable: java.lang.Runnable?, delayMilliseconds: Long) {
        com.google.common.base.Preconditions.checkArgument(delayMilliseconds >= 0, "sleeper can't time travel")
        scheduledRunnables.add(Pair(clock.currentTimeMillis() + delayMilliseconds, runnable))
    }
}
