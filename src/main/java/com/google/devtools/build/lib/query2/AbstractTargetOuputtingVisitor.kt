// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.cmdline.Label

/**
 * Helper class to traverse a visitation graph where the outputs are [Target]s and there is a
 * simple mapping between visitation keys and output keys.
 */
abstract class AbstractTargetOuputtingVisitor<VisitKeyT>
protected constructor(
    env: SkyQueryEnvironment,
    callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
) : ParallelQueryVisitor<VisitKeyT?, SkyKey?, Target?>(
    callback,
    env.getVisitBatchSizeForParallelVisitation(),
    PROCESS_RESULTS_BATCH_SIZE,
    env.getVisitTaskStatusCallback()
) {
    protected val env: SkyQueryEnvironment

    init {
        this.env = env
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected override fun outputKeysToOutputValues(targetKeys: Iterable<SkyKey?>): Iterable<Target?>? {
        val targets: MutableMap<Label?, Target?> =
            env.getTargets(
                com.google.common.collect.Iterables.transform<SkyKey?, Label?>(
                    targetKeys,
                    SkyQueryEnvironment.Companion.SKYKEY_TO_LABEL
                )
            )

        handleMissingTargets(targets, com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(targetKeys))
        return targets.values
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    open fun handleMissingTargets(
        keysWithTargets: MutableMap<out SkyKey?, Target?>?,
        targetKeys: MutableSet<SkyKey?>?
    ) {
        // Do nothing by default, as an optimization if we don't expect any missing targets.
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected override fun getVisitTasks(pendingVisits: MutableCollection<VisitKeyT?>): Iterable<Task<com.google.devtools.build.lib.query2.engine.QueryException?>?> {
        // Group pending visitation by the package of the new node, since we'll be targetfying the
        // node during the visitation.
        val visitsByPackage: com.google.common.collect.ListMultimap<PackageIdentifier?, VisitKeyT?> =
            com.google.common.collect.ArrayListMultimap.create<PackageIdentifier?, VisitKeyT?>()
        for (visitationKey in pendingVisits) {
            // Overrides of visitationKeyToOutputKey are non-blocking.
            val skyKey: SkyKey? = visitationKeyToOutputKey(visitationKey)
            if (skyKey != null) {
                val label: Label? = SkyQueryEnvironment.Companion.SKYKEY_TO_LABEL.apply(skyKey)
                visitsByPackage.put(label.getPackageIdentifier(), visitationKey)
            }
        }

        val builder: com.google.common.collect.ImmutableList.Builder<Task<com.google.devtools.build.lib.query2.engine.QueryException?>?> =
            com.google.common.collect.ImmutableList.builder<Task<com.google.devtools.build.lib.query2.engine.QueryException?>?>()

        // A couple notes here:
        // (i)  ArrayListMultimap#values returns the values grouped by key, which is exactly what we
        //      want.
        // (ii) ArrayListMultimap#values returns a Collection view, so we make a copy to avoid
        //      accidentally retaining the entire ArrayListMultimap object.
        for (visitBatch in com.google.common.collect.Iterables.partition<VisitKeyT?>(
            com.google.common.collect.ImmutableList.copyOf<VisitKeyT?>(visitsByPackage.values()),
            ParallelSkyQueryUtils.VISIT_BATCH_SIZE
        )) {
            builder.add(VisitTask(visitBatch, com.google.devtools.build.lib.query2.engine.QueryException::class.java))
        }

        return builder.build()
    }

    val packageSemaphore: MultisetSemaphore<PackageIdentifier?>?
        get() = env.getPackageMultisetSemaphore()

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected abstract fun visitationKeyToOutputKey(visitationKey: VisitKeyT?): SkyKey?

    companion object {
        private val PROCESS_RESULTS_BATCH_SIZE: Int = SkyQueryEnvironment.Companion.BATCH_CALLBACK_SIZE
    }
}
