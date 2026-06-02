// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.Sets
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.profiler.ProfilerTask
import com.google.devtools.build.lib.vfs.FileSystemUtils
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/** Include scanning context implementation.  */
class CppIncludeScanningContextImpl(private val includeScannerSupplier: Supplier<IncludeScannerSupplier?>?) :
    CppIncludeScanningContext {
    @Throws(ExecException::class, InterruptedException::class)
    override fun findAdditionalInputs(
        action: CppCompileAction,
        actionExecutionContext: ActionExecutionContext,
        includeScanningHeaderData: IncludeScanningHeaderData
    ): MutableList<Artifact?>? {
        Preconditions.checkNotNull<Supplier<IncludeScannerSupplier?>?>(includeScannerSupplier, action)

        val includes: MutableSet<Artifact> = Sets.newConcurrentHashSet<Artifact>()
        includes.addAll(action.getBuiltInIncludeFiles())

        // Deduplicate include directories. This can occur especially with "built-in" and "system"
        // include directories because of the way we retrieve them. Duplicate include directories
        // really mess up #include_next directives.
        val includeDirs: MutableSet<PathFragment?> = LinkedHashSet<PathFragment?>(action.getIncludeDirs())
        val quoteIncludeDirs: MutableList<PathFragment?>? = action.getQuoteIncludeDirs()
        val frameworkIncludeDirs: MutableList<PathFragment?>? = action.getFrameworkIncludeDirs()
        val cmdlineIncludes: MutableList<String?>? = includeScanningHeaderData.getCmdlineIncludes()

        includeDirs.addAll(includeScanningHeaderData.getSystemIncludeDirs())

        // Add the system include paths to the list of include paths.
        val absoluteBuiltInIncludeDirs: MutableList<PathFragment?> = ArrayList<PathFragment?>()
        for (pathFragment in action.getBuiltInIncludeDirectories()) {
            if (pathFragment.isAbsolute()) {
                absoluteBuiltInIncludeDirs.add(pathFragment)
            }
            includeDirs.add(pathFragment)
        }

        val includeDirList: ImmutableList<PathFragment?> = ImmutableList.copyOf<PathFragment?>(includeDirs)
        val scanner: IncludeScanner =
            includeScannerSupplier!!
                .get()!!
                .scannerFor(quoteIncludeDirs, includeDirList, frameworkIncludeDirs)

        val mainSource: Artifact? = action.getMainIncludeScannerSource()
        val sources: ImmutableList<Artifact>? =
            expandTreeArtifacts(
                action.getIncludeScannerSources(),
                actionExecutionContext.getEnvironmentForDiscoveringInputs()
            )
        if (sources == null) {
            return null
        }

        try {
            Profiler.instance()
                .profile(ProfilerTask.SCANNER, action.getSourceFile().getExecPathString()).use { c ->
                    scanner.processAsync(
                        mainSource,
                        sources,
                        includeScanningHeaderData,
                        cmdlineIncludes,
                        includes,
                        action,
                        actionExecutionContext,
                        action.getGrepIncludes(),
                        action.getExecutionPlatform()
                    )
                    if (actionExecutionContext.getEnvironmentForDiscoveringInputs().valuesMissing()) {
                        return null
                    }
                    return collect(actionExecutionContext, includes, absoluteBuiltInIncludeDirs)
                }
        } catch (e: IOException) {
            throw EnvironmentalExecException(
                e, createFailureDetail("Include scanning IOException", Code.SCANNING_IO_EXCEPTION)
            )
        } catch (e: NoSuchPackageException) {
            throw EnvironmentalExecException(
                e,
                createFailureDetail(
                    "Error for BUILD file during include scanning: " + e.getMessage(),
                    Code.PACKAGE_LOAD_FAILURE
                )
            )
        }
    }

    companion object {
        /**
         * Returns a list of artifacts with all tree artifacts replaced by their expansion or null if we
         * are missing a Skyframe dependency.
         * 
         * 
         * We take an ad-hoc approach, which consults Skyframe to retrieve the tree expansions. This is
         * necessary because include scanning may include tree artifacts which are not inputs of the
         * original action.
         * 
         * 
         * Normally, we expand tree artifacts using a tree artifact expander from the [ ], however the expander available before include scanning only captures
         * tree artifacts from the action inputs, which is insufficient. In fact, the action execution
         * context for include scanning has a null expander in it.
         */
        @Throws(InterruptedException::class)
        private fun expandTreeArtifacts(
            artifacts: ImmutableList<Artifact>, env: SkyFunction.Environment
        ): ImmutableList<Artifact>? {
            val trees: ImmutableList<Artifact?> =
                artifacts.stream().filter(Artifact::isTreeArtifact).collect(ImmutableList.toImmutableList<Artifact?>())
            if (trees.isEmpty()) {
                return artifacts
            }

            val expansions: SkyframeLookupResult = env.getValuesAndExceptions(trees)
            val expanded: ImmutableList.Builder<Artifact?> = ImmutableList.builder<Artifact?>()
            for (artifact in artifacts) {
                if (!artifact.isTreeArtifact()) {
                    expanded.add(artifact)
                    continue
                }

                val treeArtifactValue: TreeArtifactValue? = expansions.get(artifact) as TreeArtifactValue?
                if (treeArtifactValue == null) {
                    return null
                }
                expanded.addAll(treeArtifactValue.getChildren())
            }
            return expanded.build()
        }

        @Throws(ExecException::class)
        private fun collect(
            actionExecutionContext: ActionExecutionContext,
            includes: MutableSet<Artifact>,
            absoluteBuiltInIncludeDirs: MutableList<PathFragment?>?
        ): MutableList<Artifact?> {
            // Collect inputs and output
            val inputs: MutableList<Artifact?> = ArrayList<Artifact?>(includes.size())
            for (included in includes) {
                // Check for absolute includes -- we assign the file system root as
                // the root path for such includes
                if (included.getRoot().getRoot().isAbsolute()) {
                    if (FileSystemUtils.startsWithAny(
                            actionExecutionContext.getInputPath(included).asFragment(),
                            absoluteBuiltInIncludeDirs
                        )
                    ) {
                        // Skip include files found in absolute include directories.
                        continue
                    }
                    throw UserExecException(
                        createFailureDetail(
                            "illegal absolute path to include file: "
                                    + actionExecutionContext.getInputPath(included),
                            Code.ILLEGAL_ABSOLUTE_PATH
                        )
                    )
                }
                if (included.hasParent() && included.getParent().isTreeArtifact()) {
                    // Note that this means every file in the TreeArtifact becomes an input to the action, and
                    // we have spurious rebuilds if non-included files change.
                    Preconditions.checkArgument(
                        included is TreeFileArtifact, "Not a TreeFileArtifact: %s", included
                    )
                    inputs.add(included.getParent())
                } else {
                    inputs.add(included)
                }
            }
            return inputs
        }

        private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setIncludeScanning(FailureDetails.IncludeScanning.newBuilder().setCode(detailedCode))
                .build()
        }
    }
}
