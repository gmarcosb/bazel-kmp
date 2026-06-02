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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.query2.AbstractSkyKeyParallelVisitor
import com.google.devtools.build.lib.query2.SkyQueryEnvironment
import com.google.devtools.build.lib.query2.engine.Uniquifier
import com.google.devtools.build.lib.query2.query.PathLabelVisitor.Visit
import com.google.devtools.build.skyframe.SkyKey

/**
 * Helper class for visiting the label-only DTC of some given label keys, via BFS following all
 * target label -> target label dep edges. Disallowed edge filtering is *not* performed.
 */
abstract class AbstractUnfilteredLabelDTCVisitor<T>
protected constructor(
    env: SkyQueryEnvironment,
    uniquifier: Uniquifier<SkyKey?>?,
    processResultsBatchSize: Int,
    extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>?,
    callback: com.google.devtools.build.lib.query2.engine.Callback<T?>?
) : AbstractSkyKeyParallelVisitor<T?>(
    uniquifier,
    callback,
    env.getVisitBatchSizeForParallelVisitation(),
    processResultsBatchSize,
    env.getVisitTaskStatusCallback()
) {
    protected val env: SkyQueryEnvironment

    private val extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>?

    init {
        this.env = env
        this.extraGlobalDeps = extraGlobalDeps
    }

    @Throws(java.lang.InterruptedException::class)
    protected override fun getVisitResult(labelKeys: Iterable<SkyKey?>?): Visit? {
        val depsMap: com.google.common.collect.ImmutableMap<SkyKey?, Iterable<SkyKey?>?> =
            env.getFwdDepLabels(labelKeys, extraGlobalDeps)
        return Visit(labelKeys, com.google.common.collect.Iterables.< T > concat < T ? > (depsMap.values))
    }

    protected override fun preprocessInitialVisit(visitationKeys: Iterable<SkyKey?>): Iterable<SkyKey?> {
        // ParallelTargetVisitorCallback passes in labels.
        com.google.common.base.Preconditions.checkState(
            com.google.common.collect.Iterables.all<SkyKey?>(visitationKeys, SkyQueryEnvironment.Companion.IS_LABEL),
            visitationKeys
        )
        return visitationKeys
    }
}
