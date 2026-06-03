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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationId

/** This event is fired as soon as a top-level aspect is either built or fails.  */
class AspectCompleteEvent
private constructor(
    aspectKey: AspectKey,
    rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
    completionContext: CompletionContext?,
    artifactOutputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>,
    printToMasterLog: Boolean
) : SkyValue, BuildEventWithOrderConstraint, EventReportingArtifacts {
    private val aspectKey: AspectKey
    private val rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>
    private val postedAfter: MutableCollection<BuildEventId?>
    private val completionContext: CompletionContext?
    private val artifactOutputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>
    private val printToMasterLog: Boolean

    init {
        this.aspectKey = aspectKey
        this.rootCauses =
            if (rootCauses == null) NestedSetBuilder.emptySet(Order.STABLE_ORDER) else rootCauses
        val postedAfterBuilder: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
            com.google.common.collect.ImmutableList.builder<BuildEventId?>()
        for (cause in this.rootCauses.toList()) {
            postedAfterBuilder.add(cause.idProto)
        }
        this.postedAfter = postedAfterBuilder.build()
        this.completionContext = completionContext
        this.artifactOutputGroups = artifactOutputGroups
        this.printToMasterLog = printToMasterLog
    }

    /** Returns the key of the completed aspect.  */
    fun getAspectKey(): AspectKey {
        return aspectKey
    }

    /**
     * Determines whether the target has failed or succeeded.
     */
    fun failed(): Boolean {
        return !rootCauses.isEmpty()
    }

    /** Get the root causes of the target. May be empty.  */
    fun getRootCauses(): NestedSet<com.google.devtools.build.lib.causes.Cause?> {
        return rootCauses
    }

    fun getLabel(): Label {
        return aspectKey.getLabel()
    }

    fun getAspectName(): String {
        return aspectKey.getAspectDescriptor().getAspectClass().getName()
    }

    fun getArtifacts(outputGroup: String?): ArtifactsInOutputGroup? {
        return artifactOutputGroups.get(outputGroup)
    }

    fun getCompletionContext(): CompletionContext? {
        return completionContext
    }

    fun getLegacyFilteredImportantArtifacts(): Iterable<Artifact?> {
        if (!printToMasterLog) {
            return com.google.common.collect.ImmutableList.of<Artifact?>()
        }
        val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        for (artifactsInOutputGroup in artifactOutputGroups.values) {
            if (artifactsInOutputGroup.areImportant()) {
                builder.addTransitive(artifactsInOutputGroup.getArtifacts())
            }
        }
        // An aspect could potentially return a source artifact if it added it to its provider.
        return com.google.common.collect.Iterables.filter<T?>(
            builder.build().toList(),
            com.google.common.base.Predicate { artifact: T? -> !artifact.isSourceArtifact() })
    }

    public override fun getEventId(): BuildEventId {
        return BuildEventIdUtil.aspectCompleted(
            aspectKey.getLabel(),
            configurationId(aspectKey.getConfigurationKey()),
            aspectKey.getAspectDescriptor().getDescription()
        )
    }

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return postedAfter
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<BuildEventId?>()
    }

    public override fun reportedArtifacts(outputGroupFileModes: OutputGroupFileModes?): ReportedArtifacts {
        return TargetCompleteEvent.Companion.toReportedArtifacts(
            artifactOutputGroups,
            completionContext,
            outputGroupFileModes
        )
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val builder: BuildEventStreamProtos.TargetComplete.Builder =
            BuildEventStreamProtos.TargetComplete.newBuilder()
        builder.setSuccess(!failed())
        builder.addAllOutputGroup(
            TargetCompleteEvent.Companion.toOutputGroupProtos(
                artifactOutputGroups,
                completionContext,
                converters
            )
        )
        return GenericBuildEvent.protoChaining(this).setCompleted(builder.build()).build()
    }

    public override fun storeForReplay(): Boolean {
        return true
    }

    companion object {
        /** Construct a successful target completion event.  */
        fun createSuccessful(
            key: AspectKey,
            completionContext: CompletionContext?,
            artifacts: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>,
            printToMasterLog: Boolean
        ): AspectCompleteEvent {
            return AspectCompleteEvent(key, null, completionContext, artifacts, printToMasterLog)
        }

        /**
         * Construct a target completion event for a failed target, with the given non-empty root causes.
         */
        fun createFailed(
            key: AspectKey,
            ctx: CompletionContext?,
            rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>,
            outputs: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>,
            printToMasterLog: Boolean
        ): AspectCompleteEvent {
            com.google.common.base.Preconditions.checkArgument(!rootCauses.isEmpty())
            return AspectCompleteEvent(key, rootCauses, ctx, outputs, printToMasterLog)
        }
    }
}
