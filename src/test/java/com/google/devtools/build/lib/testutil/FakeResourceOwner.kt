// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** A fake implementation of ResourceOwner that does nothing except give output strings.  */
class FakeResourceOwner(val mnemonic: String) : ActionExecutionMetadata {
    val progressMessage: String?
        get() = "Progress on " + mnemonic

    public override fun describeKey(): String? {
        return "fake key"
    }

    public override fun inputsKnown(): Boolean {
        return false
    }

    public override fun discoversInputs(): Boolean {
        return false
    }

    val owner: ActionOwner
        get() = ActionOwner.createDummy( /* label= */
            null,
            net.starlark.java.syntax.Location.BUILTIN,  /* targetKind= */
            "fake target kind",  /* buildConfigurationMnemonic= */
            "fake",  /* configurationChecksum= */
            "fake",  /* buildConfigurationEvent= */
            null,  /* isToolConfiguration= */
            false,  /* executionPlatform= */
            PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
            com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )

    val isShareable: Boolean
        get() = false

    @Throws(java.lang.InterruptedException::class)
    public override fun getKey(
        actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
    ): String {
        return "fake key"
    }

    public override fun prettyPrint(): String {
        return mnemonic
    }

    public override fun describe(): String {
        return "Executing " + mnemonic
    }

    val tools: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    val inputs: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    val originalInputs: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    val schedulingDependencies: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    val clientEnvironmentVariables: MutableCollection<String?>
        get() = com.google.common.collect.ImmutableList.of<String?>()

    val outputs: com.google.common.collect.ImmutableSet<Artifact?>
        get() = com.google.common.collect.ImmutableSet.of<Artifact?>()

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext?
    ): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    val mandatoryOutputs: com.google.common.collect.ImmutableSet<Artifact?>
        get() = com.google.common.collect.ImmutableSet.of<Artifact?>()

    val primaryInput: Artifact?
        get() = null

    val primaryOutput: Artifact?
        get() = null

    val mandatoryInputs: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    val execProperties: com.google.common.collect.ImmutableMap<String?, String?>
        get() = com.google.common.collect.ImmutableMap.of<String?, String?>()

    val executionPlatform: PlatformInfo?
        get() {
            try {
                return PlatformInfo.builder().build()
            } catch (e: DuplicateConstraintException) {
                return null
            } catch (e: ExecPropertiesException) {
                return null
            }
        }
}
