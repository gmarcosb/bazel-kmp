// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * An immutable record of the state of Skyfocus. This is recorded as a member in [ ].
 * 
 * @param enabled If true, Skyfocus may run at the end of the build, depending on the state of the
 * graph and active directories conditions.
 * @param forcedRerun If true, Skyfocus will always run at the end of the build, regardless of the
 * state of active directories or the graph.
 * @param focusedTargetLabels The set of targets focused in this server instance
 * @param activeDirectories Files/dirs representing the active directories. Can be empty, specified
 * by the command line flag, or automatically derived. Although the active directories is
 * represented as [FileStateKey], the presence of a directory path's `FileStateKey`
 * is sufficient to represent the corresponding directory listing state node.
 * @param frontierSet [SkyKey]s for nodes that are in the DIRECT deps of the UTC of the active
 * directories. The values of these nodes are sufficient to build the active directories.
 * @param verificationSet The set of files/dirs that are not in the active directories, but is in
 * the transitive closure of focusedTargetLabels.
 * @param options The latest instance of [SkyfocusOptions].
 * @param buildConfiguration The latest top level build configuration.
 */
class SkyfocusState(
  @kotlin.jvm.JvmField val enabled: Boolean,
  val forcedRerun: Boolean,
  focusedTargetLabels: com.google.common.collect.ImmutableSet<Label?>?,
  activeDirectoriesType: ActiveDirectoriesType?,
  activeDirectories: com.google.common.collect.ImmutableSet<FileStateKey?>?,
  frontierSet: com.google.common.collect.ImmutableSet<SkyKey?>?,
  verificationSet: com.google.common.collect.ImmutableSet<SkyKey?>?,
  options: SkyfocusOptions?,
  buildConfiguration: BuildConfigurationValue?
) {
    fun dumpActiveDirectories(out: PrintStream) {
        activeDirectories.forEach(java.util.function.Consumer { key: FileStateKey? -> out.println(key.getCanonicalName()) })
    }

    fun dumpFrontierSet(out: PrintStream) {
        frontierSet.forEach(java.util.function.Consumer { key: SkyKey? -> out.println(key.getCanonicalName()) })
    }

    /**
     * Builder for the `SkyfocusState` record.
     * 
     * 
     * This must reflect all parameters in the record constructor.
     */
    @AutoBuilder
    interface Builder {
        fun enabled(enable: Boolean): Builder?

        fun forcedRerun(forcedRerun: Boolean): Builder?

        fun focusedTargetLabels(focusedTargetLabels: com.google.common.collect.ImmutableSet<Label?>?): Builder?

        fun activeDirectoriesType(activeDirectoriesType: ActiveDirectoriesType?): Builder?

        fun activeDirectories(activeDirectories: com.google.common.collect.ImmutableSet<FileStateKey?>?): Builder?

        fun frontierSet(frontierSet: com.google.common.collect.ImmutableSet<SkyKey?>?): Builder?

        fun verificationSet(verificationSet: com.google.common.collect.ImmutableSet<SkyKey?>?): Builder?

        fun options(options: SkyfocusOptions?): Builder?

        fun buildConfiguration(buildConfiguration: BuildConfigurationValue?): Builder?

        fun build(): SkyfocusState?
    }

    fun toBuilder(): Builder {
        return AutoBuilder_SkyfocusState_Builder(this)
    }

    /** Describes how the active directories was constructed.  */
    enum class ActiveDirectoriesType {
        /** Automatically derived by the source state and the command line (e.g. focused targets)  */
        DERIVED,

        /** The value of --experimental_active_directories. Will override derived sets if used.  */
        USER_DEFINED
    }

    fun activeDirectoriesStrings(): com.google.common.collect.ImmutableSet<String?> {
        return activeDirectories.stream()
            .map<String?> { fsk: FileStateKey? -> fsk.argument().getRootRelativePath().toString() }
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
    }

    val focusedTargetLabels: com.google.common.collect.ImmutableSet<Label?>?
    val activeDirectoriesType: ActiveDirectoriesType?
    val activeDirectories: com.google.common.collect.ImmutableSet<FileStateKey?>?
    val frontierSet: com.google.common.collect.ImmutableSet<SkyKey?>?
    val verificationSet: com.google.common.collect.ImmutableSet<SkyKey?>?
    val options: SkyfocusOptions?
    val buildConfiguration: BuildConfigurationValue?

    init {
        this.focusedTargetLabels = focusedTargetLabels
        this.activeDirectoriesType = activeDirectoriesType
        this.activeDirectories = activeDirectories
        this.frontierSet = frontierSet
        this.verificationSet = verificationSet
        this.options = options
        this.buildConfiguration = buildConfiguration
    }

    companion object {
        /** The canonical state to completely disable Skyfocus in the build.  */
        val DISABLED: SkyfocusState = SkyfocusState(
            false,
            false,  /* focusedTargetLabels= */
            com.google.common.collect.ImmutableSet.of<Label?>(),
            ActiveDirectoriesType.DERIVED,  /* activeDirectories= */
            com.google.common.collect.ImmutableSet.of<FileStateKey?>(),  /* frontierSet= */
            com.google.common.collect.ImmutableSet.of<SkyKey?>(),  /* verificationSet= */
            com.google.common.collect.ImmutableSet.of<SkyKey?>(),
            null,
            null
        )
    }
}
