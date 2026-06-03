// Copyright 2017 The Bazel Authors. All rights reserved.
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

/** Immutable implementation of a Spawn that does not perform any processing on the parameters.  */
@javax.annotation.concurrent.Immutable
class SimpleSpawn private constructor(
    owner: ActionExecutionMetadata?,
    arguments: com.google.common.collect.ImmutableList<String?>?,
    environment: com.google.common.collect.ImmutableMap<String?, String?>?,
    executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
    inputs: NestedSet<out ActionInput?>?,
    tools: NestedSet<out ActionInput?>?,
    outputs: MutableCollection<out ActionInput?>,
    mandatoryOutputs: MutableSet<out ActionInput?>?,
    localResources: ResourceSet?,
    localResourcesSupplier: LocalResourcesSupplier?,
    pathMapper: PathMapper?
) : Spawn {
    private val owner: ActionExecutionMetadata
    private val arguments: com.google.common.collect.ImmutableList<String?>
    private val environment: com.google.common.collect.ImmutableMap<String?, String?>
    private val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>
    private val inputs: NestedSet<out ActionInput?>
    private val tools: NestedSet<out ActionInput?>
    private val outputs: com.google.common.collect.ImmutableList<ActionInput?>

    // If null, all outputs are mandatory.
    private val mandatoryOutputs: MutableSet<out ActionInput?>?
    private val pathMapper: PathMapper?
    private val localResourcesSupplier: LocalResourcesSupplier?
    private var localResourcesCached: ResourceSet? = null

    init {
        this.owner = com.google.common.base.Preconditions.checkNotNull<ActionExecutionMetadata>(owner)
        this.arguments =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                arguments
            )
        this.environment =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, String?>>(
                environment
            )
        this.executionInfo =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, String?>>(
                executionInfo
            )
        this.inputs = com.google.common.base.Preconditions.checkNotNull<NestedSet<out ActionInput?>>(inputs)
        this.tools = com.google.common.base.Preconditions.checkNotNull<NestedSet<out ActionInput?>>(tools)
        this.outputs = com.google.common.collect.ImmutableList.copyOf<ActionInput?>(outputs)
        this.mandatoryOutputs = mandatoryOutputs
        com.google.common.base.Preconditions.checkState(
            (localResourcesSupplier == null) != (localResources == null),
            "Exactly one must be null: %s %s",
            localResources,
            localResourcesSupplier
        )
        if (localResources != null) {
            this.localResourcesSupplier = LocalResourcesSupplier? { localResources }
        } else {
            this.localResourcesSupplier = localResourcesSupplier
        }
        this.localResourcesCached = null
        this.pathMapper = pathMapper
    }

    constructor(
        owner: ActionExecutionMetadata?,
        arguments: com.google.common.collect.ImmutableList<String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        inputs: NestedSet<out ActionInput?>?,
        tools: NestedSet<out ActionInput?>?,
        outputs: MutableCollection<out ActionInput?>,
        mandatoryOutputs: MutableSet<out ActionInput?>?,
        localResources: ResourceSet?
    ) : this(
        owner,
        arguments,
        environment,
        executionInfo,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,
        localResources,  /* localResourcesSupplier= */
        null,
        PathMapper.Companion.NOOP
    )

    constructor(
        owner: ActionExecutionMetadata?,
        arguments: com.google.common.collect.ImmutableList<String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        inputs: NestedSet<out ActionInput?>?,
        tools: NestedSet<out ActionInput?>?,
        outputs: MutableCollection<out ActionInput?>,
        mandatoryOutputs: MutableSet<out ActionInput?>?,
        localResourcesSupplier: LocalResourcesSupplier?
    ) : this(
        owner,
        arguments,
        environment,
        executionInfo,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,  /* localResources= */
        null,
        localResourcesSupplier,
        PathMapper.Companion.NOOP
    )

    constructor(
        owner: ActionExecutionMetadata?,
        arguments: com.google.common.collect.ImmutableList<String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        inputs: NestedSet<out ActionInput?>?,
        tools: NestedSet<out ActionInput?>?,
        outputs: MutableCollection<out ActionInput?>,
        mandatoryOutputs: MutableSet<out ActionInput?>?,
        localResourcesSupplier: LocalResourcesSupplier?,
        pathMapper: PathMapper?
    ) : this(
        owner,
        arguments,
        environment,
        executionInfo,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,
        null,
        localResourcesSupplier,
        pathMapper
    )

    constructor(
        owner: ActionExecutionMetadata?,
        arguments: com.google.common.collect.ImmutableList<String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        inputs: NestedSet<out ActionInput?>?,
        tools: NestedSet<out ActionInput?>?,
        outputs: MutableCollection<out ActionInput?>,
        mandatoryOutputs: MutableSet<out ActionInput?>?,
        localResources: ResourceSet?,
        pathMapper: PathMapper?
    ) : this(
        owner,
        arguments,
        environment,
        executionInfo,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,
        localResources,
        null,
        pathMapper
    )

    constructor(
        owner: ActionExecutionMetadata?,
        arguments: com.google.common.collect.ImmutableList<String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        inputs: NestedSet<out ActionInput?>?,
        outputs: MutableCollection<Artifact?>?,
        localResourcesSupplier: LocalResourcesSupplier?
    ) : this(
        owner,
        arguments,
        environment,
        executionInfo,
        inputs,  /* tools= */
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        outputs,  /* mandatoryOutputs= */
        null,
        localResourcesSupplier
    )

    constructor(
        owner: ActionExecutionMetadata?,
        arguments: com.google.common.collect.ImmutableList<String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?,
        inputs: NestedSet<out ActionInput?>?,
        outputs: MutableCollection<out ActionInput?>?,
        resourceSet: ResourceSet?
    ) : this(
        owner,
        arguments,
        environment,
        executionInfo,
        inputs,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        outputs,  /* mandatoryOutputs= */
        null,
        resourceSet
    )

    override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        return executionInfo
    }

    override fun getArguments(): com.google.common.collect.ImmutableList<String?> {
        return arguments
    }

    override fun getEnvironment(): com.google.common.collect.ImmutableMap<String?, String?> {
        return environment
    }

    override fun getInputFiles(): NestedSet<out ActionInput?> {
        return inputs
    }

    override fun getToolFiles(): NestedSet<out ActionInput?> {
        return tools
    }

    override fun getOutputFiles(): com.google.common.collect.ImmutableList<ActionInput?> {
        return outputs
    }

    override fun isMandatoryOutput(output: ActionInput?): Boolean {
        return mandatoryOutputs == null || mandatoryOutputs.contains(output)
    }

    override fun getResourceOwner(): ActionExecutionMetadata {
        return owner
    }

    @Throws(ExecException::class)
    override fun getLocalResources(): ResourceSet? {
        var result: ResourceSet? = localResourcesCached
        if (result == null) {
            // Not expected to be called concurrently, and an idempotent computation if it is.
            result =
                localResourcesSupplier!!
                    .get()
                    .withResourceOverrides(
                        ExecutionRequirements.parseResources(getExecutionInfo()),
                        ExecutionRequirements.parseResources(getCombinedExecProperties())
                    )
            localResourcesCached = result
        }
        return result
    }

    override fun getPathMapper(): PathMapper? {
        return pathMapper
    }

    override fun getMnemonic(): String? {
        return owner.getMnemonic()
    }

    override fun getExecutionPlatform(): PlatformInfo? {
        return owner.getExecutionPlatform()
    }

    override fun toString(): String {
        return Spawns.prettyPrint(this)
    }

    /** Supplies resources needed for local execution. Result will be cached.  */
    interface LocalResourcesSupplier {
        @Throws(ExecException::class)
        fun get(): ResourceSet?
    }
}
