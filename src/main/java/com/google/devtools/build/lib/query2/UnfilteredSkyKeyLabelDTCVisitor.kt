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

import com.google.devtools.build.lib.query2.AbstractUnfilteredLabelDTCVisitor
import com.google.devtools.build.lib.query2.ParallelVisitorUtils.QueryVisitorFactory
import com.google.devtools.build.lib.query2.SkyQueryEnvironment
import com.google.devtools.build.lib.query2.engine.QueryUtil.AggregateAllCallback
import com.google.devtools.build.lib.query2.engine.Uniquifier
import com.google.devtools.build.skyframe.SkyKey

/**
 * Helper class for visiting the TTV-only DTC of some given TTV keys, and feeding those TTVs to a
 * callback.
 */
internal class UnfilteredSkyKeyLabelDTCVisitor private constructor(
    env: SkyQueryEnvironment,
    uniquifier: Uniquifier<SkyKey?>?,
    processResultsBatchSize: Int,
    extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>?,
    aggregateAllCallback: AggregateAllCallback<SkyKey?, com.google.common.collect.ImmutableSet<SkyKey?>?>?
) : AbstractUnfilteredLabelDTCVisitor<SkyKey?>(
    env,
    uniquifier,
    processResultsBatchSize,
    extraGlobalDeps,
    aggregateAllCallback
) {
    protected override fun outputKeysToOutputValues(targetKeys: Iterable<SkyKey?>?): Iterable<SkyKey?>? {
        return targetKeys
    }

    internal class Factory(
        env: SkyQueryEnvironment,
        uniquifier: Uniquifier<SkyKey?>?,
        processResultsBatchSize: Int,
        extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>?,
        aggregateAllCallback: AggregateAllCallback<SkyKey?, com.google.common.collect.ImmutableSet<SkyKey?>?>?
    ) : QueryVisitorFactory<SkyKey?, SkyKey?, SkyKey?> {
        private val env: SkyQueryEnvironment
        private val uniquifier: Uniquifier<SkyKey?>?
        private val aggregateAllCallback: AggregateAllCallback<SkyKey?, com.google.common.collect.ImmutableSet<SkyKey?>?>?
        private val processResultsBatchSize: Int
        private val extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>?

        init {
            this.env = env
            this.uniquifier = uniquifier
            this.processResultsBatchSize = processResultsBatchSize
            this.extraGlobalDeps = extraGlobalDeps
            this.aggregateAllCallback = aggregateAllCallback
        }

        public override fun create(): UnfilteredSkyKeyLabelDTCVisitor {
            return UnfilteredSkyKeyLabelDTCVisitor(
                env, uniquifier, processResultsBatchSize, extraGlobalDeps, aggregateAllCallback
            )
        }
    }
}
