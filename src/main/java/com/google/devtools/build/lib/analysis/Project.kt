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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * Container for reading project data.
 * 
 * 
 * A "project" is a set of related packages that support a common piece of software. For example,
 * "bazel" is a project that includes packages `src/main/java/com/google/devtools/build/lib`,
 * `src/main/java/com/google/devtools/build/lib/analysis`, `src/test/cpp`, and more.
 * 
 * 
 * "Project data" is any useful information that might be associated with a project. Possible
 * consumers include [
 * Skyfocus](https://github.com/bazelbuild/bazel/commit/693215317a6732085731809266f63ff0e7fc31a5)> and project-sanctioned build flags (i.e. "these are the correct flags to use with
 * this project").
 * 
 * 
 * Projects are defined in .scl files that are checked into source control with BUILD files and
 * code. scl stands for "Starlark configuration language". This is a limited subset of Starlark
 * intended to model generic configuration without requiring Bazel to parse it (similar to JSON).
 * 
 * 
 * This is not the same as [com.google.devtools.build.lib.runtime.ProjectFile]. That's an
 * older implementation of the same idea that was built before .scl and .bzl existed. The code here
 * is a rejuvenation of these ideas with more modern APIs.
 */
// TODO: b/324127050 - Make the co-existence of this and ProjectFile less confusing. ProjectFile is
//   an outdated API that should be removed.
object Project {
    /**
     * Returns the canonical project files for a set of targets.
     * 
     * 
     * If a target matches multiple project files (like `a/PROJECT.scl` and `a/b/PROJECT.scl`), only the innermost is considered.
     * 
     * @param targets targets to resolve project files for
     * @param skyframeExecutor support for SkyFunctions that look up project files
     * @param eventHandler event handler
     * @throws ProjectResolutionException if project resolution fails for any reason
     */
    // TODO: b/324127375 - Support hierarchical project files: [foo/project.scl, foo/bar/project.scl].
    @com.google.common.annotations.VisibleForTesting
    @Throws(ProjectResolutionException::class)
    fun getProjectFiles(
        targets: MutableCollection<com.google.devtools.build.lib.cmdline.Label?>,
        skyframeExecutor: SkyframeExecutor,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler
    ): ActiveProjects? {
        // Map targets to their innermost matching project file. Omits targets with no project files.
        val targetsToProjectFiles: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?> =
        // findProjectFiles returns all project files up a target's path (and omits targets with
        // no project files). We just use the first entry, which is the innermost file. For
            // example, given [a/b/PROJECT.scl, a/PROJECT.scl], we just use a/b/PROJECT.scl.
            findProjectFiles(targets, skyframeExecutor, eventHandler).asMap().entries.stream()
                .collect()
        TODO(
            """
            |Cannot convert element
            |With text:
            |Label, Label>toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().iterator().next())
            """.trimMargin()
        )


        if (targetsToProjectFiles.isEmpty()) {
            // None of the targets have project files.
            return ActiveProjects(
                LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableCollection<com.google.devtools.build.lib.cmdline.Label?>?>(),  /* partialProjectBuild= */
                false,
                ""
            )
        }
        val targetsWithNoProjectFiles: MutableSet<com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.Sets.difference<com.google.devtools.build.lib.cmdline.Label?>(
                com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.cmdline.Label?>(
                    targets
                ), targetsToProjectFiles.keys
            )

        // Since project files can be aliases to other files, we need to parse them to potentially remap
        // to their references. Also remember the targets that resolved to that project file for clean
        // error reporting.
        val projectFileKeysToTargets: com.google.common.collect.LinkedListMultimap<com.google.devtools.build.lib.skyframe.ProjectValue.Key?, com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.Multimaps.invertFrom<com.google.devtools.build.lib.skyframe.ProjectValue.Key?, com.google.devtools.build.lib.cmdline.Label?, com.google.common.collect.LinkedListMultimap<com.google.devtools.build.lib.skyframe.ProjectValue.Key?, com.google.devtools.build.lib.cmdline.Label?>>(
                com.google.common.collect.Multimaps.forMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.skyframe.ProjectValue.Key?>(
                    targetsToProjectFiles.entries.stream()
                        .collect(
                            TODO("Cannot convert element")
                        )<java.util.Map.Entry<Label, Label>, Label, ProjectValue.Key> com . google . common . collect . ImmutableMap . toImmutableMap < kotlin . Any ?,
                    Any?, Any? > (
                    java.util.function.Function { java.util.Map.Entry.key }, java.util.function.Function { entry: Any? ->
            com.google.devtools.build.lib.skyframe.ProjectValue.Key(
                entry.getValue()
            )
        })))
        com.google.common.collect.LinkedListMultimap.create<com.google.devtools.build.lib.skyframe.ProjectValue.Key?, com.google.devtools.build.lib.cmdline.Label?>()


        // Load project file content from Skyframe.
        val evalResult: EvaluationResult<SkyValue?> =
            skyframeExecutor.evaluateSkyKeys(
                eventHandler, projectFileKeysToTargets.keySet(),  /* keepGoing= */false
            )
        if (evalResult.hasError()) {
            throw ProjectResolutionException(
                "error loading project files: " + evalResult.getError().getException().message,
                evalResult.getError().getException()
            )
        }

        // De-duplicate the projectFileKeysToTargets keys by resolving project aliases, and store the
        // resulting canonicalized project-to-targets mapping in canonicalProjectToTargets.
        val canonicalProjectsToTargets: LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableCollection<com.google.devtools.build.lib.cmdline.Label?>?> =
            LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableCollection<com.google.devtools.build.lib.cmdline.Label?>?>()
        for (keyToTargets in projectFileKeysToTargets.asMap().entries) {
            canonicalProjectsToTargets.put(
                (evalResult.get(keyToTargets.key) as ProjectValue).getActualProjectFile(),
                keyToTargets.value
            )
        }

        if (canonicalProjectsToTargets.size != 1) {
            // Targets resolve to different project files.
            return ActiveProjects(
                canonicalProjectsToTargets,
                !canonicalProjectsToTargets.keys.isEmpty() && !targetsWithNoProjectFiles.isEmpty(),
                differentProjectFilesError(canonicalProjectsToTargets, targetsWithNoProjectFiles)
            )
        } else {
            val projectFile: com.google.devtools.build.lib.cmdline.Label? =
                com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.cmdline.Label?>(
                    canonicalProjectsToTargets.keys
                )
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.info(
                    String.format(
                        "Reading project settings from %s.",
                        projectFile
                    )
                )
            )
        }
        if (targetsWithNoProjectFiles.isEmpty()) {
            // All targets resolve to the same canonical project file.
            return ActiveProjects(canonicalProjectsToTargets, false, "")
        } else {
            // Some targets have project files and some don't.
            return ActiveProjects(canonicalProjectsToTargets,  /* partialProjectBuild= */true, "")
        }
    }

    /**
     * User-friendly error message for when targets resolve to different project files or only some
     * targets have project files.
     */
    private fun differentProjectFilesError(
        canonicalProjectsToTargets: MutableMap<com.google.devtools.build.lib.cmdline.Label?, MutableCollection<com.google.devtools.build.lib.cmdline.Label?>?>,
        targetsWithNoProjectFiles: MutableSet<com.google.devtools.build.lib.cmdline.Label?>
    ): String {
        val msgBuilder: java.lang.StringBuilder = java.lang.StringBuilder("Targets have different project settings:")
        // Maximum number of "//foo:target -> //foo:PROJECT.scl" entries to show.
        val maxToShow = 5

        // Iterate through each project file group (and also the "no project file" group), adding one
        // entry from each group until we reach the max. This samples each group as evenly as possible.
        val groupedResults: com.google.common.collect.ListMultimap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.LinkedListMultimap.create<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>()
        var projectFileToNextTarget: LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableIterator<com.google.devtools.build.lib.cmdline.Label?>> =
            LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableIterator<com.google.devtools.build.lib.cmdline.Label?>>()
        for (entry in canonicalProjectsToTargets.entries) {
            projectFileToNextTarget.put(entry.key, entry.value!!.iterator())
        }
        if (!targetsWithNoProjectFiles.isEmpty()) {
            projectFileToNextTarget.put(null, targetsWithNoProjectFiles.iterator())
        }
        var totalResults = 0
        var nextGroupIteration: LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableIterator<com.google.devtools.build.lib.cmdline.Label?>> =
            projectFileToNextTarget
        while (totalResults < maxToShow && !nextGroupIteration.isEmpty()) {
            projectFileToNextTarget = nextGroupIteration
            nextGroupIteration =
                LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableIterator<com.google.devtools.build.lib.cmdline.Label?>>()
            for (entry in projectFileToNextTarget.entries) {
                val nextTarget: MutableIterator<com.google.devtools.build.lib.cmdline.Label?> = entry.value
                groupedResults.put(entry.key, nextTarget.next())
                if (nextTarget.hasNext()) {
                    nextGroupIteration.put(entry.key, nextTarget)
                }
                totalResults++
                if (totalResults == maxToShow) {
                    break
                }
            }
        }

        // Report results grouped by PROJECT file.
        for (group in groupedResults.asMap().entries) {
            val projectFile = if (group.key == null) "no project file" else group.key.toString()
            for (target in group.value) {
                msgBuilder.append(String.format("\n  - %s -> %s", target, projectFile))
            }
        }
        val resultsLeft: Int = projectFileToNextTarget.values.stream()
            .mapToInt { iterator: MutableIterator<com.google.devtools.build.lib.cmdline.Label?>? ->
                com.google.common.collect.Iterators.size(iterator)
            }.sum()
        if (resultsLeft > 0) {
            msgBuilder.append(String.format("\n  (...and %d more)", resultsLeft))
        }
        return msgBuilder.toString()
    }

    /**
     * Finds and returns the project files for a set of build targets.
     * 
     * 
     * This walks up each target's package path looking for [ ][com.google.devtools.build.lib.skyframe.ProjectFilesLookupFunction.PROJECT_FILE_NAME] files. For
     * example, for `//foo/bar/baz:mytarget`, this might look in `foo/bar/baz`, `foo/bar`, and `foo` ("might" because it skips directories that don't have BUILD files -
     * those directories aren't packages).
     * 
     * 
     * This method doesn't read project file content so it doesn't resolve project file aliases.
     * 
     * @return a map from each target to its set of project files, ordered by reverse package depth.
     * So a project file in `foo/bar` appears before a project file in `foo`. Targets
     * with no matching project files aren't in the map.
     */
    // TODO: b/324127050 - Document resolution semantics when this is less experimental.
    @com.google.common.annotations.VisibleForTesting
    @Throws(ProjectResolutionException::class)
    fun findProjectFiles(
        targets: MutableCollection<com.google.devtools.build.lib.cmdline.Label?>,
        skyframeExecutor: SkyframeExecutor,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?
    ): com.google.common.collect.ImmutableMultimap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?> {
        // TODO: b/324127050 - Support other repos.
        val targetsToSkyKeys: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key?> =
            targets.stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key?>(
                        java.util.function.Function { target: com.google.devtools.build.lib.cmdline.Label? -> target },
                        java.util.function.Function { target: com.google.devtools.build.lib.cmdline.Label? ->
                            ProjectFilesLookupValue.key(
                                target.getPackageIdentifier()
                            )
                        })
                )
        val evalResult: EvaluationResult<SkyValue?> =
            skyframeExecutor.evaluateSkyKeys(
                eventHandler, targetsToSkyKeys.values,  /* keepGoing= */false
            )
        if (evalResult.hasError()) {
            throw ProjectResolutionException(
                "Error finding project files", evalResult.getError().getException()
            )
        }

        val ans: com.google.common.collect.ImmutableMultimap.Builder<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.ImmutableMultimap.builder<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>()
        for (entry in targetsToSkyKeys.entries) {
            val containingProjects: ProjectFilesLookupValue =
                evalResult.get(entry.value) as ProjectFilesLookupValue
            ans.putAll(entry.key, containingProjects.getProjectFiles())
        }
        return ans.build()
    }

    /**
     * Returns the build options to add to this invocation from its active project files and `--scl_config` setting.
     * 
     * @param fromOptions input [BuildOptions]
     * @param activeProjects the active project files for this build. An empty [Optional] means
     * at least one of this build's targets has no project file. If multiple project files are
     * active or some targets have project files and others don't, this method checks there's a
     * sound way to set the desired config and throws an [InvalidConfigurationException] if
     * not.
     * @param sclConfig the [] to apply
     * @param allOptionNames the names of every native option the parser recognizes, in `"name"`
     * form. Not all entries are [BuildOptions].
     * @param userOptions options that were set by users (vs. global bazelrcs), in name=value form
     * @param configFlagDefinitions definitions of `--config=foo` for this build. Null or an
     * empty string means use the project-default config if set, otherwise no-op.
     * @param enforceCanonicalConfigs if false, project-based flag resolution is disabled
     * @param eventHandler handler for non-fatal project-parsing messaging
     * @param skyframeExecutor executor for Skyframe evaluation
     * @return the options to add to. Caller is responsible for modifying the original build options
     * with these additions.
     * @throws InvalidConfigurationException if the desired `--scl_config` can't be applied in a
     * supported way
     */
    @Throws(InvalidConfigurationException::class)
    fun applySclConfig(
        fromOptions: BuildOptions?,
        activeProjects: ActiveProjects,
        sclConfig: String?,
        allOptionNames: com.google.common.collect.ImmutableSet<String?>?,
        userOptions: com.google.common.collect.ImmutableMap<String?, String?>?,
        configFlagDefinitions: ConfigFlagDefinitions?,
        enforceCanonicalConfigs: Boolean,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        skyframeExecutor: SkyframeExecutor
    ): com.google.common.collect.ImmutableSet<String?>? {
        val flagSetKeys: com.google.common.collect.ImmutableSet<FlagSetValue.Key?> =
            activeProjects.projectFilesToTargetLabels.keys.stream()
                .map<FlagSetValue.Key?> { p: com.google.devtools.build.lib.cmdline.Label? ->
                    FlagSetValue.Key.create(
                        com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.cmdline.Label?>(
                            activeProjects.projectFilesToTargetLabels.get(p)
                        ),
                        p,
                        sclConfig,
                        fromOptions,
                        allOptionNames,
                        userOptions,
                        configFlagDefinitions,
                        enforceCanonicalConfigs
                    )
                }
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<FlagSetValue.Key?>())
        val result: EvaluationResult<SkyValue?> =
            skyframeExecutor.evaluateSkyKeys(eventHandler, flagSetKeys,  /* keepGoing= */false)
        if (result.hasError()) {
            throw InvalidConfigurationException(
                "Cannot parse options: " + result.getError().getException().message,
                Code.INVALID_BUILD_OPTIONS
            )
        }

        // Permit multiple configs as long as they all produce the same value, ignoring projects with
        // no project files.
        val uniqueConfigs: com.google.common.collect.ImmutableSet<com.google.common.collect.ImmutableSet<String?>?> =
            result.values().stream()
                .map<com.google.common.collect.ImmutableSet<String?>?> { v: SkyValue? -> (v as FlagSetValue).getOptionsFromFlagset() }
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<com.google.common.collect.ImmutableSet<String?>?>())
        if (uniqueConfigs.size > 1) {
            if (!com.google.common.base.Strings.isNullOrEmpty(sclConfig)) {
                throw InvalidConfigurationException(
                    "--scl_config=%s resolves to conflicting flagsets: %s"
                        .formatted(sclConfig, activeProjects.differentProjectsDetails),
                    Code.INVALID_BUILD_OPTIONS
                )
            }
            throw InvalidConfigurationException(
                "Mismatching default configs for a %s. %s"
                    .formatted(activeProjects.buildType(), activeProjects.differentProjectsDetails),
                Code.INVALID_BUILD_OPTIONS
            )
        }

        val value: FlagSetValue = result.values().iterator().next() as FlagSetValue
        value.getPersistentMessages()
            .forEach(java.util.function.Consumer { event: com.google.devtools.build.lib.events.Event? ->
                eventHandler.handle(event)
            })
        // Options from the selected project config.
        return value.getOptionsFromFlagset()
    }

    /**
     * The active PROJECT.scls for this build.
     * 
     * 
     * For example, `$ bazel build //foo //bar //baz` could resolve to one, two, or three
     * PROJECT.scls, or a mixed state where only some targets have PROJECT.scls.
     * 
     * 
     * Consuming code needs to determine if mixed states are valid. People often build multiple
     * projects in a single invocation. We don't want to automatically break those builds if there's
     * still a sound way to build them.
     * 
     * @param projectFilesToTargetLabels map of PROJECT.scls to the targets that resolve to them.
     * @param partialProjectBuild true if some of this build's targets have PROJECT.scls and others
     * don't.
     * @param differentProjectsDetails A descriptive message explaining how different targets resolve
     * to different PROJECT.scls. Consuming code can use this to provide helpful errors if it
     * determines the build isn't valid because of this.
     */
    class ActiveProjects(
        projectFilesToTargetLabels: LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableCollection<com.google.devtools.build.lib.cmdline.Label?>?>?,
        partialProjectBuild: Boolean,
        differentProjectsDetails: String?
    ) {
        val isEmpty: Boolean
            get() = projectFilesToTargetLabels.isEmpty()

        /** User-friendly description of this build type, for consumer info/error messaging.  */
        fun buildType(): String {
            if (projectFilesToTargetLabels.size > 1) {
                return "multi-project build"
            } else if (partialProjectBuild) {
                return "build where only some targets have projects"
            } else if (projectFilesToTargetLabels.size == 1) {
                return "single-project build"
            } else {
                return "build with no projects"
            }
        }

        val projectFilesToTargetLabels: LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, MutableCollection<com.google.devtools.build.lib.cmdline.Label?>?>?
        val partialProjectBuild: Boolean
        val differentProjectsDetails: String?

        init {
            this.projectFilesToTargetLabels = projectFilesToTargetLabels
            this.partialProjectBuild = partialProjectBuild
            this.differentProjectsDetails = differentProjectsDetails
        }
    }
}
