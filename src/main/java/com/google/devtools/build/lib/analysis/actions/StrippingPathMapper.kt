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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.AbstractAction
import com.google.devtools.build.lib.analysis.actions.StrippingPathMapper.Companion.isOutputPath

/**
 * Main logic for experimental config-stripped execution paths:
 * https://github.com/bazelbuild/bazel/issues/6526.
 * 
 * 
 * The actions executors run look like: `tool_pkg/mytool src/source.file bazel-out/x86-opt/pkg/gen.file -o bazel-out/x86-opt/pkg/myout`.
 * 
 * 
 * The "x86-opt" part is a path's "configuration prefix": information describing the build
 * configuration of the action creating the artifact. This example shows artifacts created with
 * `--cpu=x86 --compilation_mode=opt`.
 * 
 * 
 * Executors cache actions based on their a) command line, b) input and output paths, c) input
 * digests. Configuration prefixes harm caching because even if an action behaves exactly the same
 * for different CPU architectures, `<cpu>-opt` guarantees the paths will differ.
 * 
 * 
 * Config-stripping is an experimental feature that strips the configuration prefix from
 * qualifying actions before running them, thus improving caching. "Qualifying" actions are actions
 * known not to depend on the names of their input and output paths. Non-qualifying actions include
 * manifest generators and compilers that store debug symbol source paths.
 * 
 * 
 * As an experimental feature, most logic is centralized here to provide easy hooks into executor
 * and action code and avoid complicating large swaths of the code base.
 * 
 * 
 * Enable this feature by setting `--experimental_output_paths=strip`. This activates two
 * effects:
 * 
 * 
 *  1. "Qualifying" actions strip config paths from their command lines. An action qualifies if
 * its implementation logic uses [PathMappers.create] as described in its javadocs and
 * has its mnemonic listed in [PathMappers.SUPPORTED_MNEMONICS]. Such an action must
 * pass the [PathMapper] to all structured command line constructions. If any
 * unstructured command line arguments refer to artifact paths, custom handling needs to be
 * added to `mapCustomStarlarkArgv` or `getMapFn` below.
 *  1. A supporting executor strips paths from qualifying actions' inputs and outputs before
 * staging for execution by taking [Spawn.getPathMapper] into account.
 * 
 * 
 * 
 * So an action is responsible for declaring that it strips paths and adjusting its command line
 * accordingly. The executor is responsible for remapping action inputs and outputs to match.
 */
class StrippingPathMapper private constructor(
    primaryOutput: Artifact,
    private val mnemonic: String,
    private val isStarlarkAction: Boolean
) : PathMapper {
    private val outputRoot: PathFragment
    private val isJavaAction: Boolean
    private val structuredArgStripper: ExceptionlessMapFn<Any?>
    private val argStripper: StringStripper
    private val outputArtifactRoot: ArtifactRoot
    private val strippedOutputArtifactRoot: MappedArtifactRoot

    init {
        // This is expected to always be "(bazel|blaze)-out".
        this.outputRoot = primaryOutput.getExecPath().subFragment(0, 1)
        this.argStripper = StringStripper(outputRoot.getPathString())
        this.structuredArgStripper =
            ExceptionlessMapFn { `object`, args ->
                if (`object` is String) {
                    args.accept(this.argStripper.strip(`object`))
                } else {
                    args.accept(CommandLineItem.expandToCommandLine(`object`))
                }
            }
        // This kind of special handling should not be extended. It is a hack that works around a
        // limitation of the native implementation of location expansion: The output is just a list of
        // strings, not a structured command line that would allow transparent path mapping.
        // Instead, reimplement location expansion in Starlark and have it return an Args object.
        this.isJavaAction =
            mnemonic == "Javac"
                    || mnemonic == "JavacTurbine"
                    || mnemonic == "Turbine"
                    || mnemonic == "JavaResourceJar"
        this.outputArtifactRoot = primaryOutput.getRoot()
        this.strippedOutputArtifactRoot = MappedArtifactRoot(map(outputArtifactRoot.getExecPath()))
    }

    public override fun getMappedExecPathString(artifact: ActionInput): String {
        if (isSupported(artifact)) {
            return strip(artifact.getExecPath()).getPathString()
        } else {
            return artifact.getExecPathString()
        }
    }

    public override fun map(execPath: PathFragment): PathFragment? {
        return if (Companion.isOutputPath(execPath, outputRoot)) strip(execPath) else execPath
    }

    public override fun computeExecPathLengthDiff(artifact: DerivedArtifact): Int {
        val execPath: PathFragment = artifact.getExecPath()
        val configIndex = getConfigSegmentIndex(execPath)
        return execPath.getSegment(configIndex).length() - FIXED_CONFIG_SEGMENT.length
    }

    public override fun mapCustomStarlarkArgs(chunk: ArgChunk): ArgChunk? {
        if (!isStarlarkAction) {
            return chunk
        }
        // Add your favorite Starlark mnemonic that needs custom arg processing here.
        if (!mnemonic.contains("Android") && (mnemonic != "MergeManifests") && (mnemonic != "StarlarkRClassGenerator") && (mnemonic != "StarlarkAARGenerator") && (mnemonic != "JetifySrcs") && (mnemonic != "Desugar")) {
            return chunk
        }

        // TODO: b/327187486 - This materializes strings when totalArgLength() is called. Can it
        //  compute the total arg length without creating garbage strings?
        val args: Iterable<String?> = chunk.arguments(this)
        return SimpleArgChunk({ CustomStarlarkArgsIterator(args.iterator(), argStripper) })
    }

    public override fun getMapFn(previousFlag: String?): ExceptionlessMapFn<Any?> {
        if (isJavaAction) {
            if (previousFlag == "--javacopts"
                || previousFlag == "--resources"
            ) {
                return structuredArgStripper
            }
        }
        return MapFn.DEFAULT
    }

    public override fun mapHeuristically(arg: String?): String? {
        return argStripper.strip(arg)
    }

    public override fun mapRoot(artifact: Artifact): FileRootApi {
        if (artifact.getRoot() == outputArtifactRoot) {
            // The mapped root's path does not depend on the artifact, so we can share an instance.
            return strippedOutputArtifactRoot
        }
        // Fall back for source roots as well as runfiles tree artifacts, which should be very rare.
        return super.mapRoot(artifact)
    }

    private fun isSupported(artifact: ActionInput): Boolean {
        if (artifact is DerivedArtifact) {
            return true
        }
        if (artifact is BasicActionInput || artifact is VirtualActionInput) {
            return Companion.isOutputPath(artifact, outputRoot)
        }
        return false
    }

    private class CustomStarlarkArgsIterator(
        private val args: MutableIterator<String?>,
        private val argStripper: StringStripper
    ) : MutableIterator<String?> {
        private var stripNext = false

        override fun hasNext(): Boolean {
            return args.hasNext()
        }

        override fun next(): String? {
            var next = args.next()
            if (stripNext) {
                next = argStripper.strip(next)
            }
            stripNext = STARLARK_ARGS_TO_STRIP.contains(next)
            return next
        }

        companion object {
            // Add your favorite arg to custom-process here. When Bazel finds one of these in the argument
            // list (an argument name), it strips output path prefixes from the following argument (the
            // argument value).
            private val STARLARK_ARGS_TO_STRIP: com.google.common.collect.ImmutableSet<String?> =
                com.google.common.collect.ImmutableSet.of<String?>(
                    "--mainData",
                    "--primaryData",
                    "--directData",
                    "--data",
                    "--resources",
                    "--mergeManifests",
                    "--library",
                    "-i",
                    "--input"
                )
        }
    }

    /** Utility class to strip output path configuration prefixes from arbitrary strings.  */
    private class StringStripper(private val outputRoot: String?) {
        private val pattern: java.util.regex.Pattern

        init {
            this.pattern = stripPathsPattern(outputRoot)
        }

        fun strip(str: String?): String? {
            return pattern.matcher(str).replaceAll(outputRoot + "/$1" + FIXED_CONFIG_SEGMENT + "/")
        }

        companion object {
            /**
             * Returns the regex to strip output paths from a string.
             * 
             * 
             * Supports strings with multiple output paths in arbitrary places. For example
             * "/path/to/compiler bazel-out/x86-fastbuild/foo src/my.src -Dbazel-out/arm-opt/bar".
             * 
             * 
             * Also supports special archived tree artifact paths containing colons (e.g.,
             * "bazel-out/:archived_tree_artifacts/k8-fastbuild/...").
             * 
             * 
             * Doesn't strip paths that would be non-existent without config prefixes. For example, these
             * are unchanged: "bazel-out/x86-fastbuild", "bazel-out;foo", "/path/to/compiler bazel-out".
             * 
             * @param outputRoot root segment of output paths (e.g. "bazel-out")
             */
            private fun stripPathsPattern(outputRoot: String?): java.util.regex.Pattern {
                // Match "bazel-out" followed by a slash, an optional ":archived_tree_artifacts/" prefix group
                // captured in group 1, followed by the configuration segment (any combination of word
                // characters, "_", ".", and "-"), and another slash.
                return java.util.regex.Pattern.compile(outputRoot + "/(" + ARCHIVED_TREE_ARTIFACTS_SEGMENT + "/)?[\\w_.-]+/")
            }
        }
    }

    companion object {
        const val GUID: String = "8eb2ad5a-85d4-435b-858f-5c192e91997d"

        private const val FIXED_CONFIG_SEGMENT = "cfg"
        private const val ARCHIVED_TREE_ARTIFACTS_SEGMENT = ":archived_tree_artifacts"

        /**
         * Creates a new [PathMapper] that strips config prefixes if the particular action instance
         * supports it.
         * 
         * @param action the action to potentially strip paths from
         * @param isStarlarkAction whether the action is a Starlark action
         * @return a [StrippingPathMapper] if the action supports it, else [Optional.empty].
         */
        fun tryCreate(action: AbstractAction, isStarlarkAction: Boolean): java.util.Optional<PathMapper?> {
            val outputRoot: PathFragment? = action.getPrimaryOutput().getExecPath().subFragment(0, 1)
            // Additional artifacts to map are not part of the action's inputs, but may still lead to
            // path collisions after stripping. It is thus important to include them in this check.
            if (isPathStrippable(
                    com.google.common.collect.Iterables.concat(
                        action.getInputs().toList(), action.getAdditionalArtifactsForPathMapping().toList()
                    ),
                    outputRoot
                )
            ) {
                return java.util.Optional.of<T?>(
                    StrippingPathMapper(
                        action.getPrimaryOutput(), action.getMnemonic(), isStarlarkAction
                    )
                )
            }
            return java.util.Optional.empty<PathMapper?>()
        }

        /**
         * Is this a strippable path?
         * 
         * @param artifact artifact whose path to check
         * @param outputRoot the output tree's execPath-relative root (e.g. "bazel-out")
         */
        private fun isOutputPath(artifact: ActionInput, outputRoot: PathFragment?): Boolean {
            // We can't simply check for DerivedArtifact. Output paths can also appear, for example, in
            // ParamFileActionInput and ActionInputHelper.BasicActionInput.
            return isOutputPath(artifact.getExecPath(), outputRoot)
        }

        /** Private utility method: Is this a strippable path?  */
        private fun isOutputPath(pathFragment: PathFragment, outputRoot: PathFragment?): Boolean {
            return pathFragment.startsWith(outputRoot)
        }

        /**
         * Returns whether the given execution path belongs to an archived tree artifact.
         * 
         * 
         * Archived tree artifacts are compressed directory outputs stored in zip format that prepend a
         * virtual directory prefix segment {@value #ARCHIVED_TREE_ARTIFACTS_SEGMENT} immediately after
         * the output root (e.g., `bazel-out/:archived_tree_artifacts/k8-fastbuild/...`).
         */
        private fun isArchivedTreeArtifactPath(execPath: PathFragment): Boolean {
            return execPath.segmentCount() > 2
                    && execPath.getSegment(1).equals(ARCHIVED_TREE_ARTIFACTS_SEGMENT)
        }

        /**
         * Returns the segment index inside the execution path where the configuration prefix is located.
         * 
         * 
         * For standard output artifacts, the configuration segment is at index 1 (e.g. `bazel-out/k8-fastbuild/bin/...`). For archived tree artifacts, the configuration segment is
         * shifted to index 2 due to the injected virtual prefix directory.
         */
        private fun getConfigSegmentIndex(execPath: PathFragment): Int {
            return if (isArchivedTreeArtifactPath(execPath)) 2 else 1
        }

        /**
         * Is this action safe to strip?
         * 
         * 
         * This is distinct from whether we **should** strip it. An action is stripped if a) the
         * action is explicitly supported (see [PathMappers.SUPPORTED_MNEMONICS]) and b) it's safe
         * to do that (for example, the action doesn't have two inputs in different configurations that
         * would resolve to the same path if prefixes were removed).
         * 
         * 
         * This method checks b).
         */
        private fun isPathStrippable(
            actionInputs: Iterable<out ActionInput>, outputRoot: PathFragment?
        ): Boolean {
            // For qualifying action types, check that no inputs or outputs would clash if config segments
            // were removed, e.g. "bazel-out/k8-fastbuild/bin/foo" and
            // "bazel-out/k8-fastbuild-ST-1234/bin/foo".
            //
            // A more clever algorithm could remap these with custom prefixes - "bazel-out/1/bin/foo" and
            // "bazel-out/2/bin/foo" - if experience shows that would help.
            val rootRelativePaths: HashMap<PathFragment?, ActionInput?> = HashMap<PathFragment?, ActionInput?>()
            for (input in actionInputs) {
                if (!Companion.isOutputPath(input, outputRoot)) {
                    continue
                }
                val execPath: PathFragment = input.getExecPath()
                val configIndex = getConfigSegmentIndex(execPath)
                // Extract root-relative path after the configuration segment.
                // For "bazel-out/k8-fastbuild/bin/foo/bar", get "bin/foo/bar".
                val rootRelativePath: PathFragment? = execPath.subFragment(configIndex + 1)
                if (!rootRelativePaths.computeIfAbsent(rootRelativePath) { k: PathFragment? -> input }.equals(input)) {
                    return false
                }
            }
            return true
        }

        /*
   * Strips the configuration prefix from an output artifact's exec path.
   */
        private fun strip(execPath: PathFragment): PathFragment {
            val configIndex = getConfigSegmentIndex(execPath)
            return execPath
                .subFragment(
                    0,
                    configIndex
                ) // Keep the config segment, but replace it with a fixed string to improve cacheability while
                // still preserving the general segment structure of the execpath.
                .getRelative(FIXED_CONFIG_SEGMENT)
                .getRelative(execPath.subFragment(configIndex + 1))
        }
    }
}
