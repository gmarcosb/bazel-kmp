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
import com.google.devtools.build.lib.actions.ActionExecutionContext
import net.starlark.java.syntax.Location

/** Fake implementation of [ActionExecutionMetadata] for testing.  */
open class FakeOwner internal constructor(
    val mnemonic: String?,
    val progressMessage: String,
    ownerLabel: String?,
    ownerRuleKind: String?,
    primaryOutput: Artifact?,
    platform: PlatformInfo?,
    combinedExecProperties: ImmutableMap<String?, String?>?,
    isBuiltForToolConfiguration: Boolean
) : ActionExecutionMetadata {
    private val ownerLabel: String
    private val ownerRuleKind: String
    private val primaryOutput: Artifact?
    private val platform: PlatformInfo?
    private val combinedExecProperties: ImmutableMap<String?, String?>?
    private val isBuiltForToolConfiguration: Boolean

    init {
        this.ownerLabel = Preconditions.checkNotNull<String>(ownerLabel)
        this.ownerRuleKind = Preconditions.checkNotNull<String>(ownerRuleKind)
        this.primaryOutput = primaryOutput
        this.platform = platform
        this.combinedExecProperties = combinedExecProperties
        this.isBuiltForToolConfiguration = isBuiltForToolConfiguration
    }

    private constructor(
        mnemonic: String?,
        progressMessage: String,
        ownerLabel: String?,
        platform: PlatformInfo?
    ) : this(
        mnemonic,
        progressMessage,
        ownerLabel,  /* ownerRuleKind= */
        "dummy-target-kind",  /* primaryOutput= */
        null,
        platform,
        ImmutableMap.of<String?, String?>(),  /* isBuiltForToolConfiguration= */
        false
    )

    constructor(mnemonic: String?, progressMessage: String, ownerLabel: String?) : this(
        mnemonic,
        progressMessage,
        Preconditions.checkNotNull<String?>(ownerLabel),
        PlatformInfo.EMPTY_PLATFORM_INFO
    )

    val owner: ActionOwner
        get() = ActionOwner.createDummy(
            Label.parseCanonicalUnchecked(ownerLabel),
            Location("dummy-file", 0, 0),
            ownerRuleKind,
            mnemonic,  /* configurationChecksum= */
            "configurationChecksum",
            BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()
            ),  /* isToolConfiguration= */
            isBuiltForToolConfiguration,  /* executionPlatform= */
            PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
            ImmutableList.of<E?>(),  /* execProperties= */
            combinedExecProperties
        )

    val isShareable: Boolean
        get() = false

    public override fun inputsKnown(): Boolean {
        throw UnsupportedOperationException()
    }

    public override fun discoversInputs(): Boolean {
        throw UnsupportedOperationException()
    }

    val tools: NestedSet<Artifact?>?
        get() {
            throw UnsupportedOperationException()
        }

    val inputs: NestedSet<Artifact?>?
        get() {
            throw UnsupportedOperationException()
        }

    val originalInputs: NestedSet<Artifact?>?
        get() {
            throw UnsupportedOperationException()
        }

    val schedulingDependencies: NestedSet<Artifact?>?
        get() {
            throw UnsupportedOperationException()
        }

    val outputs: ImmutableSet<Artifact>?
        get() {
            throw UnsupportedOperationException()
        }

    val clientEnvironmentVariables: MutableCollection<String?>?
        get() {
            throw UnsupportedOperationException()
        }

    val primaryInput: Artifact?
        get() {
            throw UnsupportedOperationException()
        }

    public override fun getPrimaryOutput(): Artifact? {
        Preconditions.checkState(primaryOutput != null, "primaryOutput not set")
        return primaryOutput
    }

    val mandatoryInputs: NestedSet<Artifact?>?
        get() {
            throw UnsupportedOperationException()
        }

    public override fun getKey(
        actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
    ): String {
        return "MockOwner.getKey"
    }

    public override fun describeKey(): String? {
        throw UnsupportedOperationException()
    }

    public override fun prettyPrint(): String {
        return "action '" + describe() + "'"
    }

    public override fun describe(): String {
        return this.progressMessage
    }

    public override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext?
    ): NestedSet<Artifact?> {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    }

    val mandatoryOutputs: ImmutableSet<Artifact>?
        get() {
            throw UnsupportedOperationException()
        }

    val execProperties: ImmutableMap<String?, String?>
        get() = ImmutableMap.of<String?, String?>()

    val executionPlatform: PlatformInfo?
        get() = platform
}
