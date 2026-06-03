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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.analysis.platform.PlatformInfo

/** Base implementation of a Spawn.  */
@javax.annotation.concurrent.Immutable
open class BaseSpawn(
    arguments: MutableList<String?>,
    environment: MutableMap<String?, String?>,
    executionInfo: MutableMap<String?, String?>,
    action: ActionExecutionMetadata,
    localResources: ResourceSetOrBuilder
) : Spawn {
    private val arguments: com.google.common.collect.ImmutableList<String?>
    private val environment: com.google.common.collect.ImmutableMap<String?, String?>
    private val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>
    private val action: ActionExecutionMetadata
    private val localResources: ResourceSetOrBuilder
    private var localResourcesCached: ResourceSet? = null

    init {
        this.arguments = com.google.common.collect.ImmutableList.copyOf<String?>(arguments)
        this.environment = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(environment)
        this.executionInfo = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(executionInfo)
        this.action = action
        this.localResources = localResources
    }

    override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        return executionInfo
    }

    override fun getArguments(): com.google.common.collect.ImmutableList<String?> {
        // TODO(bazel-team): this method should be final, as the correct value of the args can be
        // injected in the ctor.
        return arguments
    }

    override fun getEnvironment(): com.google.common.collect.ImmutableMap<String?, String?> {
        return environment
    }

    override fun getToolFiles(): NestedSet<out ActionInput?>? {
        return action.getTools()
    }

    override fun getInputFiles(): NestedSet<out ActionInput?>? {
        return action.getInputs()
    }

    override fun getOutputFiles(): MutableCollection<out ActionInput?>? {
        return action.getOutputs()
    }

    override fun getResourceOwner(): ActionExecutionMetadata {
        return action
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    override fun getLocalResources(): ResourceSet? {
        var result: ResourceSet? = localResourcesCached
        if (result == null) {
            // Not expected to be called concurrently, and an idempotent computation if it is.
            result =
                localResources
                    .buildResourceSet(OS.getCurrent(), action.getInputs().memoizedFlattenAndGetSize())
                    .withResourceOverrides(
                        ExecutionRequirements.parseResources(getExecutionInfo()),
                        ExecutionRequirements.parseResources(getCombinedExecProperties())
                    )
            localResourcesCached = result
        }
        return result
    }

    override fun getMnemonic(): String? {
        return action.getMnemonic()
    }

    override fun getExecutionPlatform(): PlatformInfo? {
        return action.getExecutionPlatform()
    }

    override fun toString(): String {
        return Spawns.prettyPrint(this)
    }
}
