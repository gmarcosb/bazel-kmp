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
package com.google.devtools.build.lib.skyframe.util

import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Maps
import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * Helper functions for manually dealing with a [SkyframeExecutor]'s graph in tests.
 */
object SkyframeExecutorTestUtils {
    /** Returns an existing value, or `null` if the given key is not currently in the graph.  */
    @Throws(InterruptedException::class)
    fun getExistingValue(skyframeExecutor: SkyframeExecutor, key: SkyKey?): SkyValue? {
        return skyframeExecutor.getEvaluator().getExistingValue(key)
    }

    /**
     * Returns an existing error info, or `null` if the given key is not currently in the graph.
     */
    @Throws(InterruptedException::class)
    fun getExistingError(skyframeExecutor: SkyframeExecutor, key: SkyKey?): ErrorInfo? {
        return skyframeExecutor.getEvaluator().getExistingErrorForTesting(key)
    }

    /** Calls [MemoizingEvaluator.evaluate] on the given [SkyframeExecutor]'s graph.  */
    @Throws(InterruptedException::class)
    fun <T : SkyValue?> evaluate(
        skyframeExecutor: SkyframeExecutor,
        key: SkyKey,
        keepGoing: Boolean,
        errorEventListener: ExtendedEventHandler?
    ): EvaluationResult<T?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(keepGoing)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(errorEventListener)
                .build()
        return skyframeExecutor.getEvaluator().evaluate(ImmutableList.of<E?>(key), evaluationContext)
    }

    /**
     * Returns an existing configured target value, or `null` if there is not an appropriate
     * configured target value key in the graph.
     * 
     * 
     * This helper is provided so legacy tests don't need to know about details of skyframe keys.
     */
    @Throws(InterruptedException::class)
    fun getExistingConfiguredTargetValue(
        skyframeExecutor: SkyframeExecutor, label: Label?, config: BuildConfigurationValue?
    ): ConfiguredTargetValue? {
        return getExistingValue(
            skyframeExecutor,
            ConfiguredTargetKey.builder().setLabel(label).setConfiguration(config).build()
        ) as ConfiguredTargetValue?
    }

    /**
     * Returns the configured target for an existing configured target value, or `null` if there
     * is not an appropriate configured target value key in the graph.
     * 
     * 
     * This helper is provided so legacy tests don't need to know about details of skyframe keys.
     */
    @Throws(InterruptedException::class)
    fun getExistingConfiguredTarget(
        skyframeExecutor: SkyframeExecutor, label: Label?, config: BuildConfigurationValue?
    ): ConfiguredTarget? {
        val value: ConfiguredTargetValue? = getExistingConfiguredTargetValue(skyframeExecutor, label, config)
        if (value == null) {
            return null
        }
        return value.getConfiguredTarget()
    }

    /**
     * Returns all configured targets currently in the graph with the given label.
     * 
     * 
     * Unlike [.getExistingConfiguredTarget], this doesn't make the caller request a specific configuration.
     */
    fun getExistingConfiguredTargets(
        skyframeExecutor: SkyframeExecutor, label: Label?
    ): Iterable<ConfiguredTarget?> {
        return Iterables.filter<ConfiguredTarget?>(
            getAllExistingConfiguredTargets(skyframeExecutor),
            Predicate { input: ConfiguredTarget? -> input.getLabel().equals(label) })
    }

    /** Returns all configured targets currently in the graph.  */
    fun getAllExistingConfiguredTargets(
        skyframeExecutor: SkyframeExecutor
    ): ImmutableList<ConfiguredTarget?> {
        val values: MutableCollection<SkyValue?> =
            Maps.filterKeys(
                skyframeExecutor.getEvaluator().getValues(),
                SkyFunctions.isSkyFunction(SkyFunctions.CONFIGURED_TARGET)
            )
                .values
        val cts: ImmutableList.Builder<ConfiguredTarget?> = ImmutableList.builder<ConfiguredTarget?>()
        for (value in values) {
            if (value != null) {
                cts.add((value as ConfiguredTargetValue).getConfiguredTarget())
            }
        }
        return cts.build()
    }

    /**
     * Returns the target for an existing target value, or `null` if there is not an appropriate
     * target value key in the graph.
     * 
     * 
     * This helper is provided so legacy tests don't need to know about details of skyframe keys.
     */
    @Throws(InterruptedException::class)
    fun getExistingTarget(skyframeExecutor: SkyframeExecutor, label: Label): Target? {
        val value: PackageValue? =
            getExistingValue(skyframeExecutor, label.getPackageIdentifier()) as PackageValue?
        if (value == null) {
            return null
        }
        try {
            return value.getPackage().getTarget(label.name)
        } catch (e: NoSuchTargetException) {
            return null
        }
    }

    /**
     * Returns the error info for an existing target value, or `null` if there is not an
     * appropriate target value key in the graph.
     * 
     * 
     * This helper is provided so legacy tests don't need to know about details of skyframe keys.
     */
    @Throws(InterruptedException::class)
    fun getExistingFailedPackage(skyframeExecutor: SkyframeExecutor, label: Label): ErrorInfo? {
        val key: SkyKey? = label.getPackageIdentifier()
        return getExistingError(skyframeExecutor, key)
    }
}
