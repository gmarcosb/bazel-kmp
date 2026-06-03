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

import com.google.devtools.build.lib.analysis.platform.PlatformConstants

/**
 * Contains metadata used for reporting the progress and status of an action.
 * 
 * 
 * Morally an action's owner is the RuleConfiguredTarget instance responsible for creating it,
 * but to avoid storing heavyweight analysis objects in actions, and to avoid coupling between the
 * analysis and actions packages, the RuleConfiguredTarget provides an instance of this class.
 */
@Immutable
abstract class ActionOwner {
    fun getDescription(): String? {
        val label: Label? = getLabel()
        if (label == null) {
            return null
        }
        val targetDescription: String? = String.format("%s target %s", getTargetKind(), label)

        val aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?> = getAspectDescriptors()
        if (aspectDescriptors.isEmpty()) {
            return targetDescription
        }

        val aspectNames: String? =
            aspectDescriptors.stream().map<Any?>(AspectDescriptor::getDescription).collect(Collectors.joining(", "))

        return String.format(
            "aspect%s [%s] on %s",
            if (aspectDescriptors.size >= 1) "s" else "", aspectNames, targetDescription
        )
    }

    /**
     * Returns the label for this [ActionOwner], or null if the [.SYSTEM_ACTION_OWNER].
     */
    abstract fun getLabel(): Label?

    /** Returns the location for this [ActionOwner].  */
    abstract fun getLocation(): net.starlark.java.syntax.Location?

    /** Returns the target kind (rule class name) for this [ActionOwner].  */
    abstract fun getTargetKind(): String?

    /** Returns [BuildConfigurationInfo] for this [ActionOwner].  */
    abstract fun getBuildConfigurationInfo(): BuildConfigurationInfo?

    /** Returns the mnemonic for the configuration for this [ActionOwner].  */
    fun getBuildConfigurationMnemonic(): String? {
        return getBuildConfigurationInfo().getMnemonic()
    }

    /**
     * Returns the short cache key for the configuration for this [ActionOwner].
     * 
     * 
     * Special action owners that are not targets can return any string here. If the underlying
     * configuration is null, this should return "null".
     */
    fun getConfigurationChecksum(): String? {
        return getBuildConfigurationInfo().checksum()
    }

    /**
     * Return the [BuildConfigurationEvent] for this [ActionOwner], if any, as it should
     * be reported in the build event protocol.
     */
    fun getBuildConfigurationEvent(): BuildConfigurationEvent? {
        return getBuildConfigurationInfo().toBuildEvent()
    }

    /**
     * Returns true when the [BuildConfigurationInfo] for this [ActionOwner] is a
     * tool-related configuration.
     */
    fun isBuildConfigurationForTool(): Boolean {
        return getBuildConfigurationInfo().isToolConfiguration()
    }

    /**
     * Returns the [PlatformInfo] platform this action should be executed on. If the execution
     * platform is `null`, then the host platform is assumed.
     */
    abstract fun getExecutionPlatform(): PlatformInfo?

    abstract fun getAspectDescriptors(): com.google.common.collect.ImmutableList<AspectDescriptor?>

    /**
     * Returns a String to String map containing the execution properties available at the target
     * level, e.g. via the exec_properties attribute of the rule or the execution platform for the
     * exec group that the action is assigned to. This does *not* include any action-specific
     * properties.
     */
    @com.google.common.annotations.VisibleForTesting
    abstract fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?>?

    /**
     * Created when `aspectDescriptors` and `execProperties` are both empty.
     * 
     * 
     * [LiteActionOwner] is more likely to be created since both fields above are usually
     * empty. This will save 8 bytes of memory for each [ActionOwner] instance compared to
     * keeping both empty fields.
     */
    @AutoValue
    internal abstract class LiteActionOwner : ActionOwner() {
        override fun getAspectDescriptors(): com.google.common.collect.ImmutableList<AspectDescriptor?> {
            return com.google.common.collect.ImmutableList.of<AspectDescriptor?>()
        }

        override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?> {
            return com.google.common.collect.ImmutableMap.of<String?, String?>()
        }

        companion object {
            fun createInternal(
                label: Label?,
                location: net.starlark.java.syntax.Location?,
                targetKind: String?,
                buildConfigurationInfo: BuildConfigurationInfo?,
                executionPlatform: PlatformInfo?
            ): LiteActionOwner {
                return AutoValue_ActionOwner_LiteActionOwner(
                    label, location, targetKind, buildConfigurationInfo, executionPlatform
                )
            }
        }
    }

    /** Created when either `aspectDescriptors` or `execProperties` is not empty.  */
    @AutoValue
    internal object FullActionOwner : ActionOwner() {
        fun createInternal(
            label: Label?,
            location: net.starlark.java.syntax.Location?,
            targetKind: String?,
            buildConfigurationInfo: BuildConfigurationInfo?,
            executionPlatform: PlatformInfo?,
            aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?>?,
            execProperties: com.google.common.collect.ImmutableMap<String?, String?>?
        ): FullActionOwner {
            return AutoValue_ActionOwner_FullActionOwner(
                label,
                location,
                targetKind,
                buildConfigurationInfo,
                executionPlatform,
                aspectDescriptors,
                execProperties
            )
        }
    }

    companion object {
        /** An action owner for special cases. Usage is strongly discouraged.  */
        @SerializationConstant
        val SYSTEM_ACTION_OWNER: ActionOwner = createDummy( /* label= */
            PlatformConstants.INTERNAL_PLATFORM,
            net.starlark.java.syntax.Location.BUILTIN,  /* targetKind= */
            "empty target kind",  /* buildConfigurationMnemonic= */
            "system",  /* configurationChecksum= */
            "system",  /* buildConfigurationEvent= */
            null,  /* isToolConfiguration= */
            false,  /* executionPlatform= */
            PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
            com.google.common.collect.ImmutableList.of<AspectDescriptor?>(),  /* execProperties= */
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        )

        fun create(
            label: Label?,
            location: net.starlark.java.syntax.Location?,
            targetKind: String?,
            buildConfigurationValue: BuildConfigurationInfo?,
            executionPlatform: PlatformInfo?,
            aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?>,
            execProperties: com.google.common.collect.ImmutableMap<String?, String?>
        ): ActionOwner {
            if (aspectDescriptors.isEmpty() && execProperties.isEmpty()) {
                return LiteActionOwner.Companion.createInternal(
                    label, location, targetKind, buildConfigurationValue, executionPlatform
                )
            } else {
                return FullActionOwner.createInternal(
                    label,
                    location,
                    targetKind,
                    buildConfigurationValue,
                    executionPlatform,
                    aspectDescriptors,
                    execProperties
                )
            }
        }

        @com.google.common.annotations.VisibleForTesting
        fun createDummy(
            label: Label?,
            location: net.starlark.java.syntax.Location?,
            targetKind: String?,
            buildConfigurationMnemonic: String?,
            configurationChecksum: String?,
            buildConfigurationEvent: BuildConfigurationEvent?,
            isToolConfiguration: Boolean,
            executionPlatform: PlatformInfo?,
            aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?>?,
            execProperties: com.google.common.collect.ImmutableMap<String?, String?>?
        ): ActionOwner {
            return FullActionOwner.createInternal(
                label,
                location,
                targetKind,
                BuildConfigurationInfo.AutoBuildConfigurationInfo.create(
                    buildConfigurationMnemonic,
                    configurationChecksum,
                    buildConfigurationEvent,
                    isToolConfiguration
                ),
                executionPlatform,
                aspectDescriptors,
                execProperties
            )
        }
    }
}
