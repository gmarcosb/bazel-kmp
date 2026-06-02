// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ConfiguredAspect

/**
 * A collection of events that mark the completion of the analysis/building of top level targets or
 * aspects.
 * 
 * 
 * These events are used to generate the final results summary.
 */
class TopLevelStatusEvents private constructor() {
    internal interface TopLevelStatusEventWithType : Postable {
        val type: Type?
    }

    /**
     * An event that marks the successful analysis of a top-level target, including tests. A skipped
     * target is still considered analyzed and a TopLevelTargetAnalyzedEvent is expected for it.
     */
    class TopLevelTargetAnalyzedEvent(configuredTarget: ConfiguredTarget?) : TopLevelStatusEventWithType {
        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_ANALYZED
        }

        val configuredTarget: ConfiguredTarget?

        init {
            this.configuredTarget = configuredTarget
            java.util.Objects.requireNonNull<Any?>(configuredTarget, "configuredTarget")
        }

        companion object {
            fun create(configuredTarget: ConfiguredTarget?): TopLevelTargetAnalyzedEvent {
                return TopLevelTargetAnalyzedEvent(configuredTarget)
            }
        }
    }

    /**
     * An event that signals that we can start planting the symlinks for the transitive packages under
     * a top level target.
     * 
     * 
     * Should always be sent out before [TopLevelEntityAnalysisConcludedEvent] to ensure
     * consistency.
     */
    class TopLevelTargetReadyForSymlinkPlanting(transitivePackagesForSymlinkPlanting: NestedSet<Package.Metadata?>?) :
        TopLevelStatusEventWithType {
        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_READY_FOR_SYMLINK_PLANTING
        }

        val transitivePackagesForSymlinkPlanting: NestedSet<Package.Metadata?>?

        init {
            this.transitivePackagesForSymlinkPlanting = transitivePackagesForSymlinkPlanting
            java.util.Objects.requireNonNull<Any?>(
                transitivePackagesForSymlinkPlanting,
                "transitivePackagesForSymlinkPlanting"
            )
        }

        companion object {
            fun create(
                transitivePackagesForSymlinkPlanting: NestedSet<Package.Metadata?>?
            ): TopLevelTargetReadyForSymlinkPlanting {
                return TopLevelTargetReadyForSymlinkPlanting(transitivePackagesForSymlinkPlanting)
            }
        }
    }

    /** An event that marks the skipping of a top-level target, including skipped tests.  */
    class TopLevelTargetSkippedEvent(configuredTarget: ConfiguredTarget?) : TopLevelStatusEventWithType {
        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_SKIPPED
        }

        val configuredTarget: ConfiguredTarget?

        init {
            this.configuredTarget = configuredTarget
            java.util.Objects.requireNonNull<Any?>(configuredTarget, "configuredTarget")
        }

        companion object {
            fun create(configuredTarget: ConfiguredTarget?): TopLevelTargetSkippedEvent {
                return TopLevelTargetSkippedEvent(configuredTarget)
            }
        }
    }

    /**
     * An event that marks the conclusion of the analysis of a top level target/aspect, successful or
     * otherwise.
     */
    class TopLevelEntityAnalysisConcludedEvent(getAnalyzedTopLevelKey: SkyKey?, val succeeded: Boolean) :
        TopLevelStatusEventWithType {
        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.TOP_LEVEL_ENTITY_ANALYSIS_CONCLUDED
        }

        val getAnalyzedTopLevelKey: SkyKey?

        init {
            this.getAnalyzedTopLevelKey = getAnalyzedTopLevelKey
            java.util.Objects.requireNonNull<SkyKey?>(getAnalyzedTopLevelKey, "getAnalyzedTopLevelKey")
        }

        companion object {
            fun create(
                analyzedTopLevelKey: SkyKey?, succeeded: Boolean
            ): TopLevelEntityAnalysisConcludedEvent {
                return TopLevelEntityAnalysisConcludedEvent(analyzedTopLevelKey, succeeded)
            }
        }
    }

    /**
     * An event that marks that a top-level target won't be skipped and is pending execution,
     * including test targets.
     */
    class TopLevelTargetPendingExecutionEvent(configuredTarget: ConfiguredTarget?, val isTest: Boolean) :
        TopLevelStatusEventWithType {
        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_PENDING_EXECUTION
        }

        val configuredTarget: ConfiguredTarget?

        init {
            this.configuredTarget = configuredTarget
            java.util.Objects.requireNonNull<Any?>(configuredTarget, "configuredTarget")
        }

        companion object {
            fun create(
                configuredTarget: ConfiguredTarget?, isTest: Boolean
            ): TopLevelTargetPendingExecutionEvent {
                return TopLevelTargetPendingExecutionEvent(configuredTarget, isTest)
            }
        }
    }

    /**
     * An event that denotes that some execution has started in this build.
     * 
     * 
     * Some special actions e.g. the WorkspaceStatusAction should be excluded from the execution
     * time.
     */
    @kotlin.jvm.JvmRecord
    data class SomeExecutionStartedEvent(val countedInExecutionTime: Boolean) : TopLevelStatusEventWithType {
        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.SOME_EXECUTION_STARTED
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun create(): SomeExecutionStartedEvent {
                return SomeExecutionStartedEvent( /* countedInExecutionTime= */true)
            }

            fun notCountedInExecutionTime(): SomeExecutionStartedEvent {
                return SomeExecutionStartedEvent( /* countedInExecutionTime= */false)
            }
        }
    }

    /** An event that marks the successful build of a top-level target, including tests.  */
    @AutoValue
    abstract class TopLevelTargetBuiltEvent : TopLevelStatusEventWithType {
        abstract fun configuredTargetKey(): ConfiguredTargetKey?

        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_BUILT
        }

        companion object {
            fun create(configuredTargetKey: ConfiguredTargetKey?): TopLevelTargetBuiltEvent {
                return AutoValue_TopLevelStatusEvents_TopLevelTargetBuiltEvent(configuredTargetKey)
            }
        }
    }

    /** An event that marks the successful analysis of a test target.  */
    class TestAnalyzedEvent(
        configuredTarget: ConfiguredTarget?,
        buildConfigurationValue: BuildConfigurationValue?,
        val isSkipped: Boolean
    ) : TopLevelStatusEventWithType {
        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.TEST_ANALYZED
        }

        val configuredTarget: ConfiguredTarget?
        val buildConfigurationValue: BuildConfigurationValue?

        init {
            this.buildConfigurationValue = buildConfigurationValue
            this.configuredTarget = configuredTarget
            java.util.Objects.requireNonNull<Any?>(configuredTarget, "configuredTarget")
            java.util.Objects.requireNonNull<Any?>(buildConfigurationValue, "buildConfigurationValue")
        }

        companion object {
            fun create(
                configuredTarget: ConfiguredTarget?,
                buildConfigurationValue: BuildConfigurationValue?,
                isSkipped: Boolean
            ): TestAnalyzedEvent {
                return TestAnalyzedEvent(configuredTarget, buildConfigurationValue, isSkipped)
            }
        }
    }

    /** An event that marks the successful analysis of an aspect.  */
    @AutoValue
    abstract class AspectAnalyzedEvent : TopLevelStatusEventWithType {
        abstract fun aspectKey(): AspectKey?

        abstract fun configuredAspect(): ConfiguredAspect?

        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.ASPECT_ANALYZED
        }

        companion object {
            fun create(
                aspectKey: AspectKey?, configuredAspect: ConfiguredAspect?
            ): AspectAnalyzedEvent {
                return AutoValue_TopLevelStatusEvents_AspectAnalyzedEvent(aspectKey, configuredAspect)
            }
        }
    }

    /** An event that marks the successful building of an aspect.  */
    @AutoValue
    abstract class AspectBuiltEvent : TopLevelStatusEventWithType {
        abstract fun aspectKey(): AspectKey?

        override fun getType(): Type {
            return com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type.ASPECT_BUILT
        }

        companion object {
            fun create(aspectKey: AspectKey?): AspectBuiltEvent {
                return AutoValue_TopLevelStatusEvents_AspectBuiltEvent(aspectKey)
            }
        }
    }

    internal enum class Type {
        TOP_LEVEL_TARGET_CONFIGURED,
        TOP_LEVEL_TARGET_ANALYZED,
        TOP_LEVEL_TARGET_READY_FOR_SYMLINK_PLANTING,
        TOP_LEVEL_TARGET_SKIPPED,
        TOP_LEVEL_ENTITY_ANALYSIS_CONCLUDED,
        TOP_LEVEL_TARGET_PENDING_EXECUTION,
        SOME_EXECUTION_STARTED,
        TOP_LEVEL_TARGET_BUILT,
        TEST_ANALYZED,
        ASPECT_ANALYZED,
        ASPECT_BUILT
    }
}
