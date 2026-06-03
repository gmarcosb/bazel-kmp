// Copyright 2017 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec.util

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.actions.ActionExecutionMetadata
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/** Builder class to create [Spawn] instances for testing.  */
class SpawnBuilder(vararg args: String?) {
    private var mnemonic = "Mnemonic"
    private var progressMessage: String? = "progress message"
    private var ownerLabel = "//dummy:label"
    private var ownerRuleKind = "dummy-target-kind"
    private var ownerPrimaryOutput: Artifact? = null
    private var platform: PlatformInfo? = null
    private val args: MutableList<String?>
    private val environment: MutableMap<String?, String?> = HashMap<String?, String?>()
    private val executionInfo: MutableMap<String?, String?> = HashMap<String?, String?>()
    private var execProperties: ImmutableMap<String?, String?>? = ImmutableMap.of<String?, String?>()
    private val inputs: NestedSetBuilder<ActionInput?> = NestedSetBuilder.stableOrder()
    private val outputs: MutableList<ActionInput?> = ArrayList<ActionInput?>()
    private var mandatoryOutputs: MutableSet<out ActionInput?>? = null
    private val tools: NestedSetBuilder<ActionInput?> = NestedSetBuilder.stableOrder()

    private var resourceSet: ResourceSet? = ResourceSet.ZERO
    private var pathMapper: PathMapper? = PathMapper.NOOP
    private var builtForToolConfiguration = false

    init {
        this.args = ImmutableList.copyOf<String?>(args)
    }

    fun build(): Spawn {
        val owner: ActionExecutionMetadata =
            FakeOwner(
                mnemonic,
                progressMessage,
                ownerLabel,
                ownerRuleKind,
                ownerPrimaryOutput,
                platform,
                execProperties,
                builtForToolConfiguration
            )
        return SimpleSpawn(
            owner,
            ImmutableList.< E > copyOf < E ? > (args),
            ImmutableMap.< K, V > copyOf<K?, V?>(environment),
            ImmutableMap.< K, V > copyOf<K?, V?>(executionInfo),
            inputs.build(),
            tools.build(),
            ImmutableSet.< E > copyOf < E ? > (outputs),
            mandatoryOutputs,
            resourceSet,
            pathMapper
        )
    }

    @CanIgnoreReturnValue
    fun withPlatform(platform: PlatformInfo?): SpawnBuilder {
        this.platform = platform
        return this
    }

    @CanIgnoreReturnValue
    fun withMnemonic(mnemonic: String?): SpawnBuilder {
        this.mnemonic = Preconditions.checkNotNull<String>(mnemonic)
        return this
    }

    @CanIgnoreReturnValue
    fun withProgressMessage(progressMessage: String?): SpawnBuilder {
        this.progressMessage = progressMessage
        return this
    }

    @CanIgnoreReturnValue
    fun withOwnerLabel(ownerLabel: String?): SpawnBuilder {
        this.ownerLabel = Preconditions.checkNotNull<String>(ownerLabel)
        return this
    }

    @CanIgnoreReturnValue
    fun withOwnerRuleKind(ownerRuleKind: String?): SpawnBuilder {
        this.ownerRuleKind = Preconditions.checkNotNull<String>(ownerRuleKind)
        return this
    }

    @CanIgnoreReturnValue
    fun withOwnerPrimaryOutput(output: Artifact?): SpawnBuilder {
        ownerPrimaryOutput = Preconditions.checkNotNull<Artifact?>(output)
        return this
    }

    @CanIgnoreReturnValue
    fun withEnvironment(key: String?, value: String?): SpawnBuilder {
        this.environment.put(key, value)
        return this
    }

    @CanIgnoreReturnValue
    fun withExecutionInfo(key: String?, value: String?): SpawnBuilder {
        this.executionInfo.put(key, value)
        return this
    }

    @CanIgnoreReturnValue
    fun withCombinedExecProperties(execProperties: ImmutableMap<String?, String?>?): SpawnBuilder {
        this.execProperties = execProperties
        return this
    }

    @CanIgnoreReturnValue
    fun withInput(input: ActionInput?): SpawnBuilder {
        this.inputs.add(input)
        return this
    }

    @CanIgnoreReturnValue
    fun withInput(name: String?): SpawnBuilder {
        this.inputs.add(ActionInputHelper.fromPath(name))
        return this
    }

    @CanIgnoreReturnValue
    fun withInputs(vararg inputs: ActionInput?): SpawnBuilder {
        for (input in inputs) {
            this.inputs.add(input)
        }
        return this
    }

    @CanIgnoreReturnValue
    fun withInputs(inputs: Iterable<out ActionInput?>): SpawnBuilder {
        for (input in inputs) {
            this.inputs.add(input)
        }
        return this
    }

    @CanIgnoreReturnValue
    fun withInputs(vararg names: String?): SpawnBuilder {
        for (name in names) {
            this.inputs.add(ActionInputHelper.fromPath(name))
        }
        return this
    }

    @CanIgnoreReturnValue
    fun withInputs(inputs: NestedSet<ActionInput?>?): SpawnBuilder {
        this.inputs.addTransitive(inputs)
        return this
    }

    @CanIgnoreReturnValue
    fun withOutput(output: ActionInput?): SpawnBuilder {
        outputs.add(output)
        return this
    }

    fun withOutput(name: String?): SpawnBuilder? {
        return withOutput(ActionInputHelper.fromPath(name))
    }

    @CanIgnoreReturnValue
    fun withOutputs(vararg outputs: ActionInput?): SpawnBuilder {
        for (output in outputs) {
            withOutput(output)
        }
        return this
    }

    @CanIgnoreReturnValue
    fun withOutputs(vararg names: String?): SpawnBuilder {
        for (name in names) {
            this.outputs.add(ActionInputHelper.fromPath(name))
        }
        return this
    }

    @CanIgnoreReturnValue
    fun withMandatoryOutputs(mandatoryOutputs: MutableSet<out ActionInput?>?): SpawnBuilder {
        this.mandatoryOutputs = mandatoryOutputs
        return this
    }

    @CanIgnoreReturnValue
    fun withTool(tool: ActionInput?): SpawnBuilder {
        tools.add(tool)
        return this
    }

    @CanIgnoreReturnValue
    fun withTools(vararg tools: ActionInput?): SpawnBuilder {
        for (tool in tools) {
            this.tools.add(tool)
        }
        return this
    }

    @CanIgnoreReturnValue
    fun withTools(tools: NestedSet<ActionInput?>?): SpawnBuilder {
        this.tools.addTransitive(tools)
        return this
    }

    @CanIgnoreReturnValue
    fun withLocalResources(resourceSet: ResourceSet?): SpawnBuilder {
        this.resourceSet = resourceSet
        return this
    }

    @CanIgnoreReturnValue
    fun setPathMapper(pathMapper: PathMapper?): SpawnBuilder {
        this.pathMapper = pathMapper
        return this
    }

    @CanIgnoreReturnValue
    fun setBuiltForToolConfiguration(builtForToolConfiguration: Boolean): SpawnBuilder {
        this.builtForToolConfiguration = builtForToolConfiguration
        return this
    }
}
