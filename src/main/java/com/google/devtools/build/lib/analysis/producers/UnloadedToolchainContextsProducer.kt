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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.ToolchainCollection
import com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsInputs
import com.google.devtools.build.lib.packages.DeclaredExecGroup
import com.google.devtools.build.lib.skyframe.BaseTargetPrerequisitesSupplier
import com.google.devtools.build.lib.skyframe.toolchains.NoMatchingPlatformData
import com.google.devtools.build.lib.skyframe.toolchains.NoMatchingPlatformException
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainContextKey
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainException
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.state.StateMachine
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrExceptionSink

/**
 * Determines `ToolchainCollection<UnloadedToolchainContext>` from [ ].
 */
class UnloadedToolchainContextsProducer : StateMachine {
    /** Interface for accepting values produced by this class.  */
    interface ResultSink {
        fun acceptUnloadedToolchainContexts(
            unloadedToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?
        )

        fun acceptUnloadedToolchainContextsError(error: ToolchainException?)
    }

    // -------------------- Input --------------------
    private val unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs

    /**
     * Cache for [UnloadedToolchainContext]. Not null only for aspects evaluation.
     * 
     * 
     * Check [AspectFunction.baseTargetPrerequisitesSupplier] for more details
     */
    private val baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?

    // -------------------- Output --------------------
    private val sink: ResultSink

    // -------------------- Sequencing --------------------
    private val runAfter: StateMachine?

    // -------------------- Internal State --------------------
    private var toolchainContextsBuilder: com.google.devtools.build.lib.analysis.ToolchainCollection.Builder<UnloadedToolchainContext?>? =
        null
    private var toolchainContextsHasError = false

    internal constructor(
        unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs,
        sink: ResultSink,
        runAfter: StateMachine?
    ) {
        this.unloadedToolchainContextsInputs = unloadedToolchainContextsInputs
        this.sink = sink
        this.runAfter = runAfter
        this.baseTargetPrerequisitesSupplier = null
    }

    /**
     * Constructor for [UnloadedToolchainContextsProducer] with `baseTargetPrerequisitesSupplier` used by [AspectFunction].
     */
    constructor(
        unloadedToolchainContextsInputs: UnloadedToolchainContextsInputs,
        baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?,
        sink: ResultSink,
        runAfter: StateMachine?
    ) {
        this.unloadedToolchainContextsInputs = unloadedToolchainContextsInputs
        this.baseTargetPrerequisitesSupplier = baseTargetPrerequisitesSupplier
        this.sink = sink
        this.runAfter = runAfter
    }

    @Throws(java.lang.InterruptedException::class)
    override fun step(tasks: StateMachine.Tasks): StateMachine? {
        val defaultToolchainContextKey: ToolchainContextKey? =
            unloadedToolchainContextsInputs.targetToolchainContextKey()
        if (defaultToolchainContextKey == null) {
            // Doesn't use toolchain resolution and short-circuits.
            // TODO(bazel-team): return empty {@link ToolchainCollection} instead of {@code null} to help
            // consumers distinguish between not yet evaluated collections and collections evaluated to be
            // empty.
            sink.acceptUnloadedToolchainContexts(null)
            return runAfter
        }

        this.toolchainContextsBuilder =
            ToolchainCollection.builderWithExpectedSize<UnloadedToolchainContext?>(
                unloadedToolchainContextsInputs.execGroups().size() + 1
            )

        lookupToolchainContext(
            baseTargetPrerequisitesSupplier,
            defaultToolchainContextKey,
            DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME,
            tasks
        )

        val keyBuilder: com.google.devtools.build.lib.skyframe.toolchains.ToolchainContextKey.Builder =
            ToolchainContextKey.key()
                .configurationKey(defaultToolchainContextKey.configurationKey())
                .debugTarget(defaultToolchainContextKey.debugTarget())

        for (entry in unloadedToolchainContextsInputs.execGroups().entrySet()) {
            val execGroup: DeclaredExecGroup = entry.value
            val key: ToolchainContextKey? =
                keyBuilder
                    .toolchainTypes(execGroup.toolchainTypes())
                    .execConstraintLabels(execGroup.execCompatibleWith)
                    .build()
            lookupToolchainContext(baseTargetPrerequisitesSupplier, key, entry.key, tasks)
        }

        return StateMachine { tasks: StateMachine.Tasks? -> this.buildToolchainContexts(tasks) }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun lookupToolchainContext(
        baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?,
        key: ToolchainContextKey?,
        execGroupName: String?,
        tasks: StateMachine.Tasks
    ) {
        val toolchainContext: UnloadedToolchainContext? =
            if (baseTargetPrerequisitesSupplier == null)
                null
            else
                baseTargetPrerequisitesSupplier.getUnloadedToolchainContext(key)

        if (toolchainContext != null) {
            ToolchainContextLookupCallback(execGroupName)
                .acceptValueOrException(toolchainContext, null)
        } else {
            tasks.lookUp<ToolchainException?>(
                key, ToolchainException::class.java, ToolchainContextLookupCallback(execGroupName)
            )
        }
    }

    private inner class ToolchainContextLookupCallback
        (private val execGroupName: String?) : ValueOrExceptionSink<ToolchainException?> {
        override fun acceptValueOrException(
            value: SkyValue?, error: ToolchainException?
        ) {
            if (value != null) {
                val unloadedToolchainContext: UnloadedToolchainContext = value as UnloadedToolchainContext
                val errorData: NoMatchingPlatformData? = unloadedToolchainContext.errorData()
                if (errorData != null) {
                    handleError(NoMatchingPlatformException(errorData))
                    return
                }
                toolchainContextsBuilder.addContext(execGroupName, unloadedToolchainContext)
                return
            }
            if (error != null) {
                handleError(error)
                return
            }
            throw java.lang.IllegalArgumentException("both inputs were null")
        }
    }

    private fun handleError(error: ToolchainException?) {
        if (!toolchainContextsHasError) { // Only propagates the first error.
            toolchainContextsHasError = true
            sink.acceptUnloadedToolchainContextsError(error)
        }
    }

    private fun buildToolchainContexts(tasks: StateMachine.Tasks?): StateMachine? {
        if (toolchainContextsHasError) {
            return runAfter
        }
        sink.acceptUnloadedToolchainContexts(toolchainContextsBuilder.build())
        return runAfter
    }
}
