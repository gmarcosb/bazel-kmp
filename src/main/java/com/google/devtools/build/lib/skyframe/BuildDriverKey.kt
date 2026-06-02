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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * Wraps an [ActionLookupKey]. The evaluation of this SkyKey is the entry point of analyzing
 * the [ActionLookupKey] and executing the associated actions.
 */
class BuildDriverKey private constructor(
    actionLookupKey: ActionLookupKey,
    topLevelArtifactContext: TopLevelArtifactContext,
    explicitlyRequested: Boolean,
    skipIncompatibleExplicitTargets: Boolean,
    extraActionTopLevelOnly: Boolean,
    keepGoing: Boolean,
    isTopLevelAspectDriver: Boolean
) : SkyKey {
    private val actionLookupKey: ActionLookupKey
    private val topLevelArtifactContext: TopLevelArtifactContext
    val isExplicitlyRequested: Boolean
    private val skipIncompatibleExplicitTargets: Boolean
    val isTopLevelAspectDriver: Boolean

    val isExtraActionTopLevelOnly: Boolean

    // This key is created anew each build, so it's fine to carry this information here.
    private val keepGoing: Boolean

    init {
        this.actionLookupKey = actionLookupKey
        this.topLevelArtifactContext = topLevelArtifactContext
        this.isExplicitlyRequested = explicitlyRequested
        this.skipIncompatibleExplicitTargets = skipIncompatibleExplicitTargets
        this.isTopLevelAspectDriver = isTopLevelAspectDriver
        this.isExtraActionTopLevelOnly = extraActionTopLevelOnly
        this.keepGoing = keepGoing
    }

    fun getTopLevelArtifactContext(): TopLevelArtifactContext {
        return topLevelArtifactContext
    }

    fun getActionLookupKey(): ActionLookupKey {
        return actionLookupKey
    }

    fun shouldSkipIncompatibleExplicitTargets(): Boolean {
        return skipIncompatibleExplicitTargets
    }

    fun keepGoing(): Boolean {
        return keepGoing
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.BUILD_DRIVER
    }

    override fun equals(other: Any?): Boolean {
        if (other is BuildDriverKey) {
            return actionLookupKey.equals(other.actionLookupKey)
                    && topLevelArtifactContext.equals(other.topLevelArtifactContext)
                    && this.isExplicitlyRequested == other.isExplicitlyRequested
        }
        return false
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(actionLookupKey, topLevelArtifactContext, this.isExplicitlyRequested)
    }

    override fun toString(): String {
        return java.lang.String.format("BuildDriverKey of ActionLookupKey: %s", actionLookupKey)
    }

    override fun valueIsShareable(): Boolean {
        // BuildDriverValue is just a wrapper value that signals that the building of a top level target
        // was concluded. It's meant to be created anew each build, since BuildDriverFunction must be
        // run every build.
        return false
    }

    internal enum class TestType(msg: String) {
        NOT_TEST("not-test"),
        PARALLEL("parallel"),
        EXCLUSIVE("exclusive"),
        EXCLUSIVE_IF_LOCAL("exclusive-if-local");

        @kotlin.jvm.JvmField
        val msg: String?

        init {
            this.msg = msg
        }
    }

    companion object {
        fun ofTopLevelAspect(
            actionLookupKey: ActionLookupKey,
            topLevelArtifactContext: TopLevelArtifactContext,
            explicitlyRequested: Boolean,
            skipIncompatibleExplicitTargets: Boolean,
            extraActionTopLevelOnly: Boolean,
            keepGoing: Boolean
        ): BuildDriverKey {
            return BuildDriverKey(
                actionLookupKey,
                topLevelArtifactContext,
                explicitlyRequested,
                skipIncompatibleExplicitTargets,
                extraActionTopLevelOnly,
                keepGoing,  /* isTopLevelAspectDriver= */
                true
            )
        }

        fun ofConfiguredTarget(
            actionLookupKey: ActionLookupKey,
            topLevelArtifactContext: TopLevelArtifactContext,
            explicitlyRequested: Boolean,
            skipIncompatibleExplicitTargets: Boolean,
            extraActionTopLevelOnly: Boolean,
            keepGoing: Boolean
        ): BuildDriverKey {
            return BuildDriverKey(
                actionLookupKey,
                topLevelArtifactContext,
                explicitlyRequested,
                skipIncompatibleExplicitTargets,
                extraActionTopLevelOnly,
                keepGoing,  /* isTopLevelAspectDriver= */
                false
            )
        }
    }
}
