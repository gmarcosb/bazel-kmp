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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * Expands $(location) and $(locations) tags inside target attributes. You can specify something
 * like this in the BUILD file:
 * 
 * <pre>
 * somerule(name='some name',
 * someopt = [ '$(location //mypackage:myhelper)' ],
 * ...)
</pre> * 
 * 
 * and location will be substituted with //mypackage:myhelper executable output.
 * 
 * 
 * Note that this expander will always expand labels in srcs, deps, and tools attributes, with
 * data being optional.
 * 
 * 
 * DO NOT USE DIRECTLY! Use RuleContext.getExpander() instead.
 */
class LocationExpander @com.google.common.annotations.VisibleForTesting internal constructor(
    ruleErrorConsumer: RuleErrorConsumer,
    functions: MutableMap<String?, LocationFunction?>,
    repositoryMapping: RepositoryMapping?,
    workspaceRunfilesDirectory: String?
) {
    private val ruleErrorConsumer: RuleErrorConsumer
    private val functions: com.google.common.collect.ImmutableMap<String?, LocationFunction?>
    private val repositoryMapping: RepositoryMapping?
    private val workspaceRunfilesDirectory: String?

    init {
        this.ruleErrorConsumer = ruleErrorConsumer
        this.functions = com.google.common.collect.ImmutableMap.copyOf<String?, LocationFunction?>(functions)
        this.repositoryMapping = repositoryMapping
        this.workspaceRunfilesDirectory = workspaceRunfilesDirectory
    }

    private constructor(
        ruleContext: RuleContext,
        root: Label,
        locationMap: com.google.common.base.Supplier<MutableMap<Label?, MutableCollection<Artifact>>?>,
        execPaths: Boolean,
        repositoryMapping: RepositoryMapping?
    ) : this(
        ruleContext,
        allLocationFunctions(root, locationMap, execPaths),
        repositoryMapping,
        ruleContext.getWorkspaceName()
    )

    /**
     * Creates location expander helper bound to specific target and with default location map.
     * 
     * @param ruleContext BUILD rule
     * @param labelMap A mapping of labels to build artifacts.
     * @param execPaths If true, this expander will expand $(location)/$(locations) using
     * Artifact.getExecPath(); otherwise with Artifact.getLocationPath().
     * @param allowData If true, this expander will expand locations from the `data` attribute;
     * otherwise it will not.
     */
    private constructor(
        ruleContext: RuleContext,
        labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?,
        execPaths: Boolean,
        allowData: Boolean
    ) : this(
        ruleContext,
        ruleContext.getLabel(),  // Use a memoizing supplier to avoid eagerly building the location map.
        com.google.common.base.Suppliers.memoize<MutableMap<Label?, MutableCollection<Artifact?>?>?>(
            com.google.common.base.Supplier { buildLocationMap(ruleContext, labelMap, allowData, true) }),
        execPaths,
        ruleContext.getRule().getPackageMetadata().repositoryMapping()
    )

    fun expand(input: String): String {
        return expand(input, RuleErrorReporter(ruleErrorConsumer))
    }

    private fun expand(value: String, reporter: ErrorReporter): String {
        var restart = 0

        val result: java.lang.StringBuilder = java.lang.StringBuilder(value.length())

        while (true) {
            // (1) Find '$(<fname> '.
            val start: Int = value.indexOf("$(", restart)
            if (start == -1) {
                result.append(value.substring(restart))
                break
            }
            val nextWhitespace: Int = value.indexOf(' '.code, start)
            if (nextWhitespace == -1) {
                result.append(value, restart, start + 2)
                restart = start + 2
                continue
            }
            val fname: String = value.substring(start + 2, nextWhitespace)
            if (!functions.containsKey(fname)) {
                result.append(value, restart, start + 2)
                restart = start + 2
                continue
            }

            result.append(value, restart, start)

            val end: Int = value.indexOf(')'.code, nextWhitespace)
            if (end == -1) {
                reporter.report(
                    java.lang.String.format(
                        "unterminated $(%s) expression", value.substring(start + 2, nextWhitespace)
                    )
                )
                return value
            }

            // (2) Call appropriate function to obtain string replacement.
            val functionValue: String = value.substring(nextWhitespace + 1, end).trim()
            try {
                val replacement: String? =
                    functions
                        .get(fname)
                        .apply(functionValue, repositoryMapping, workspaceRunfilesDirectory)
                result.append(replacement)
            } catch (ise: java.lang.IllegalStateException) {
                reporter.report(ise.getMessage())
                return value
            }

            restart = end + 1
        }

        return result.toString()
    }

    /**
     * Expands attribute's location and locations tags based on the target and
     * location map.
     * 
     * @param attrName  name of the attribute; only used for error reporting
     * @param attrValue initial value of the attribute
     * @return attribute value with expanded location tags or original value in
     * case of errors
     */
    fun expandAttribute(attrName: String?, attrValue: String): String {
        return expand(attrValue, AttributeErrorReporter(ruleErrorConsumer, attrName))
    }

    @com.google.common.annotations.VisibleForTesting
    internal class LocationFunction(
        root: Label,
        locationMapSupplier: com.google.common.base.Supplier<MutableMap<Label?, MutableCollection<Artifact>>?>,
        pathType: PathType?,
        multiple: Boolean
    ) {
        internal enum class PathType {
            LOCATION,
            EXEC,
            RLOCATION,
        }

        private val root: Label
        private val locationMapSupplier: com.google.common.base.Supplier<MutableMap<Label?, MutableCollection<Artifact>>?>
        private val pathType: PathType
        private val multiple: Boolean

        init {
            this.root = root
            this.locationMapSupplier = locationMapSupplier
            this.pathType = com.google.common.base.Preconditions.checkNotNull<PathType>(pathType)
            this.multiple = multiple
        }

        /**
         * Looks up the label-like string in the locationMap and returns the resolved path string. If
         * the label-like string begins with a repository name, the repository name may be remapped
         * using the `repositoryMapping`.
         * 
         * @param arg The label-like string to be expanded, e.g. ":foo" or "//foo:bar"
         * @param repositoryMapping map of apparent repository names to `RepositoryName`s
         * @param workspaceRunfilesDirectory name of the runfiles directory corresponding to the main
         * repository
         * @return The expanded value
         */
        fun apply(
            arg: String?, repositoryMapping: RepositoryMapping?, workspaceRunfilesDirectory: String?
        ): String? {
            val label: Label?
            try {
                label =
                    Label.parseWithPackageContext(
                        arg, PackageContext.of(root.getPackageIdentifier(), repositoryMapping)
                    )
            } catch (e: LabelSyntaxException) {
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "invalid label in %s expression: %s", functionName(), e.getMessage()
                    ), e
                )
            }
            val paths = resolveLabel(label, workspaceRunfilesDirectory)
            return joinPaths(paths)
        }

        /** Returns all target location(s) of the given label.  */
        @Throws(java.lang.IllegalStateException::class)
        private fun resolveLabel(unresolved: Label?, workspaceRunfilesDirectory: String?): MutableSet<String?> {
            val artifacts: MutableCollection<Artifact> = locationMapSupplier.get().get(unresolved)

            checkNotNull(artifacts) {
                java.lang.String.format(
                    "label '%s' in %s expression is not a declared prerequisite of this rule",
                    unresolved, functionName()
                )
            }

            val paths = getPaths(artifacts, workspaceRunfilesDirectory)
            check(!paths.isEmpty()) {
                java.lang.String.format(
                    "label '%s' in %s expression expands to no files",
                    unresolved, functionName()
                )
            }

            check(!(!multiple && paths.size() > 1)) {
                java.lang.String.format(
                    "label '%s' in $(location) expression expands to more than one file, "
                            + "please use $(locations %s) instead.  Files (at most %d shown) are: %s",
                    unresolved,
                    unresolved,
                    MAX_PATHS_SHOWN,
                    com.google.common.collect.Iterables.limit<String?>(paths, MAX_PATHS_SHOWN)
                )
            }
            return paths
        }

        /**
         * Extracts list of all executables associated with given collection of label artifacts.
         * 
         * @param artifacts to get the paths of
         * @param workspaceRunfilesDirectory name of the runfiles directory corresponding to the main
         * repository
         * @return all associated executable paths
         */
        private fun getPaths(
            artifacts: MutableCollection<Artifact>, workspaceRunfilesDirectory: String?
        ): MutableSet<String?> {
            val paths: TreeSet<String?> = com.google.common.collect.Sets.newTreeSet<String?>()
            for (artifact in artifacts) {
                val path: PathFragment? = getPath(artifact, workspaceRunfilesDirectory)
                if (path != null) {
                    paths.add(path.getCallablePathString())
                }
            }
            return paths
        }

        private fun getPath(artifact: Artifact, workspaceRunfilesDirectory: String?): PathFragment? {
            return when (pathType) {
                com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.LOCATION -> artifact.getRunfilesPath()
                com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.EXEC -> artifact.getExecPath()
                com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.RLOCATION -> {
                    val runfilesPath: PathFragment = artifact.getRunfilesPath()
                    if (runfilesPath.startsWith(LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX)) {
                        runfilesPath.relativeTo(LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX)
                    } else {
                        PathFragment.create(workspaceRunfilesDirectory).getRelative(runfilesPath)
                    }
                }
            }
        }

        private fun joinPaths(paths: MutableCollection<String?>): String? {
            return paths.stream().map<Any?>(ShellEscaper::escapeString).collect(Collectors.joining(" "))
        }

        private fun functionName(): String {
            return if (multiple) "$(locations)" else "$(location)"
        }

        companion object {
            private const val MAX_PATHS_SHOWN = 5
        }
    }

    private interface ErrorReporter {
        fun report(error: String?)
    }

    private class AttributeErrorReporter(delegate: RuleErrorConsumer, attrName: String?) : ErrorReporter {
        private val delegate: RuleErrorConsumer
        private val attrName: String?

        init {
            this.delegate = delegate
            this.attrName = attrName
        }

        override fun report(error: String?) {
            delegate.attributeError(attrName, error)
        }
    }

    private class RuleErrorReporter(delegate: RuleErrorConsumer) : ErrorReporter {
        private val delegate: RuleErrorConsumer

        init {
            this.delegate = delegate
        }

        override fun report(error: String?) {
            delegate.ruleError(error)
        }
    }

    companion object {
        private const val EXACTLY_ONE = false
        private const val ALLOW_MULTIPLE = true

        /**
         * Creates an expander that expands $(location)/$(locations) using Artifact.getLocationPath().
         * 
         * 
         * The expander expands $(rootpath)/$(rootpaths) using Artifact.getLocationPath(), and
         * $(execpath)/$(execpaths) using Artifact.getExecPath().
         * 
         * @param ruleContext BUILD rule
         * @param labelMap A mapping of labels to build artifacts
         */
        fun withRunfilesPaths(
            ruleContext: RuleContext,
            labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?
        ): LocationExpander {
            return LocationExpander(ruleContext, labelMap, false, false)
        }

        /**
         * Creates an expander that expands $(location)/$(locations) using Artifact.getExecPath().
         * 
         * 
         * The expander expands $(rootpath)/$(rootpaths) using Artifact.getLocationPath(), and
         * $(execpath)/$(execpaths) using Artifact.getExecPath().
         * 
         * @param ruleContext BUILD rule
         * @param labelMap A mapping of labels to build artifacts.
         */
        fun withExecPaths(
            ruleContext: RuleContext,
            labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?
        ): LocationExpander {
            return LocationExpander(ruleContext, labelMap, true, false)
        }

        /**
         * Creates an expander that expands $(location)/$(locations) using Artifact.getExecPath().
         * 
         * 
         * The expander expands $(rootpath)/$(rootpaths) using Artifact.getLocationPath(), and
         * $(execpath)/$(execpaths) using Artifact.getExecPath().
         * 
         * @param ruleContext BUILD rule
         * @param labelMap A mapping of labels to build artifacts.
         */
        fun withExecPathsAndData(
            ruleContext: RuleContext,
            labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?
        ): LocationExpander {
            return LocationExpander(ruleContext, labelMap, true, true)
        }

        fun allLocationFunctions(
            root: Label,
            locationMap: com.google.common.base.Supplier<MutableMap<Label?, MutableCollection<Artifact>>?>,
            execPaths: Boolean
        ): com.google.common.collect.ImmutableMap<String?, LocationFunction?> {
            return com.google.common.collect.ImmutableMap.Builder<String?, LocationFunction?>()
                .put(
                    "location",
                    LocationFunction(
                        root,
                        locationMap,
                        if (execPaths) com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.EXEC else com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.LOCATION,
                        EXACTLY_ONE
                    )
                )
                .put(
                    "locations",
                    LocationFunction(
                        root,
                        locationMap,
                        if (execPaths) com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.EXEC else com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.LOCATION,
                        ALLOW_MULTIPLE
                    )
                )
                .put(
                    "rootpath",
                    LocationFunction(
                        root,
                        locationMap,
                        com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.LOCATION,
                        EXACTLY_ONE
                    )
                )
                .put(
                    "rootpaths",
                    LocationFunction(
                        root,
                        locationMap,
                        com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.LOCATION,
                        ALLOW_MULTIPLE
                    )
                )
                .put(
                    "execpath",
                    LocationFunction(
                        root,
                        locationMap,
                        com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.EXEC,
                        EXACTLY_ONE
                    )
                )
                .put(
                    "execpaths",
                    LocationFunction(
                        root,
                        locationMap,
                        com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.EXEC,
                        ALLOW_MULTIPLE
                    )
                )
                .put(
                    "rlocationpath",
                    LocationFunction(
                        root,
                        locationMap,
                        com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.RLOCATION,
                        EXACTLY_ONE
                    )
                )
                .put(
                    "rlocationpaths",
                    LocationFunction(
                        root,
                        locationMap,
                        com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType.RLOCATION,
                        ALLOW_MULTIPLE
                    )
                )
                .buildOrThrow()
        }

        /**
         * Extracts all possible target locations from target specification.
         * 
         * @param ruleContext BUILD target object
         * @param labelMap map of labels to build artifacts
         * @return map of all possible target locations
         */
        fun buildLocationMap(
            ruleContext: RuleContext,
            labelMap: MutableMap<Label?, out MutableCollection<Artifact?>?>?,
            allowDataAttributeEntriesInLabel: Boolean,
            collectSrcs: Boolean
        ): MutableMap<Label?, MutableCollection<Artifact?>?> {
            val locationMap: MutableMap<Label?, MutableCollection<Artifact?>?> =
                com.google.common.collect.Maps.newHashMap<Label?, MutableCollection<Artifact?>?>()
            if (labelMap != null) {
                for (entry in labelMap.entrySet()) {
                    Companion.mapGet<Label?, Artifact?>(locationMap, entry.getKey()).addAll(entry.getValue())
                }
            }

            // We don't want to do this if we're processing aspect rules. It will
            // create output artifacts and unbalance the input/output state, leading
            // to an error (output artifact with no action to create its inputs).
            if (ruleContext.getMainAspect() == null) {
                // Add all destination locations.
                for (out in ruleContext.getRule().getOutputFiles()) {
                    // Not in aspect processing, so explicitly build an artifact & let it verify.
                    Companion.mapGet<K?, V?>(locationMap, out.getLabel()).add(ruleContext.createOutputArtifact(out))
                }
            }

            if (collectSrcs && ruleContext.getRule().isAttrDefined("srcs", BuildType.LABEL_LIST)) {
                for (src in ruleContext
                    .getRulePrerequisitesCollection()
                    .getPrerequisitesIf<C?>("srcs", FileProvider::class.java)) {
                    for (label in AliasProvider.Companion.getDependencyLabels(src)) {
                        Companion.mapGet<Label?, Artifact?>(locationMap, label)
                            .addAll(src.getProvider(FileProvider::class.java).getFilesToBuild().toList())
                    }
                }
            }

            // Add all locations associated with dependencies and tools
            val depsDataAndTools: MutableList<TransitiveInfoCollection> =
                java.util.ArrayList<TransitiveInfoCollection>()
            if (ruleContext.getRule().isAttrDefined("deps", BuildType.LABEL_LIST)) {
                com.google.common.collect.Iterables.addAll<TransitiveInfoCollection?>(
                    depsDataAndTools,
                    ruleContext
                        .getRulePrerequisitesCollection()
                        .getPrerequisitesIf<FilesToRunProvider?>("deps", FilesToRunProvider::class.java)
                )
            }
            if (ruleContext.getRule().isAttrDefined("implementation_deps", BuildType.LABEL_LIST)) {
                com.google.common.collect.Iterables.addAll<TransitiveInfoCollection?>(
                    depsDataAndTools,
                    ruleContext
                        .getRulePrerequisitesCollection()
                        .getPrerequisitesIf<FilesToRunProvider?>("implementation_deps", FilesToRunProvider::class.java)
                )
            }
            if (allowDataAttributeEntriesInLabel
                && ruleContext.getRule().isAttrDefined("data", BuildType.LABEL_LIST)
            ) {
                com.google.common.collect.Iterables.addAll<TransitiveInfoCollection?>(
                    depsDataAndTools,
                    ruleContext
                        .getRulePrerequisitesCollection()
                        .getPrerequisitesIf<FilesToRunProvider?>("data", FilesToRunProvider::class.java)
                )
            }
            if (ruleContext.getRule().isAttrDefined("tools", BuildType.LABEL_LIST)) {
                com.google.common.collect.Iterables.addAll<TransitiveInfoCollection?>(
                    depsDataAndTools,
                    ruleContext
                        .getRulePrerequisitesCollection()
                        .getPrerequisitesIf<FilesToRunProvider?>("tools", FilesToRunProvider::class.java)
                )
            }

            for (dep in depsDataAndTools) {
                val labels: com.google.common.collect.ImmutableList<Label?> =
                    AliasProvider.Companion.getDependencyLabels(dep)
                val filesToRun: FilesToRunProvider = dep.getProvider(FilesToRunProvider::class.java)
                val executableArtifact: Artifact? = filesToRun.getExecutable()
                val fileProvider: FileProvider = dep.getProvider(FileProvider::class.java)

                // If the label has an executable artifact add that to the multimaps.
                val values: MutableCollection<Artifact?>? =
                    if (executableArtifact != null)
                        com.google.common.collect.ImmutableList.of<Artifact?>(executableArtifact)
                    else
                        fileProvider.getFilesToBuild().toList()

                for (label in labels) {
                    Companion.mapGet<Label?, Artifact?>(locationMap, label).addAll(values)
                }
            }
            return locationMap
        }

        /**
         * Returns the value in the specified map corresponding to 'key', creating and
         * inserting an empty container if absent. We use Map not Multimap because
         * we need to distinguish the cases of "empty value" and "absent key".
         * 
         * @return the value in the specified map corresponding to 'key'
         */
        private fun <K, V> mapGet(map: MutableMap<K?, MutableCollection<V?>?>, key: K?): MutableCollection<V?> {
            var values = map.get(key)
            if (values == null) {
                // We use sets not lists, because it's conceivable that the same label
                // could appear twice, in "srcs" and "deps".
                values = com.google.common.collect.Sets.newHashSet<V?>()
                map.put(key, values)
            }
            return values!!
        }
    }
}
