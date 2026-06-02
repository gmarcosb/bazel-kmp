// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.starlarkdocextract

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Verify
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.io.ByteSource
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates
import com.google.devtools.build.lib.profiler.Profiler
import com.google.errorprone.annotations.CanIgnoreReturnValue
import net.starlark.java.eval.Module
import java.lang.String
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import kotlin.Any
import kotlin.Boolean
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/** Implementation of the `starlark_doc_extract` rule.  */
class StarlarkDocExtract : RuleConfiguredTargetFactory {
    @Throws(ActionConflictException::class, InterruptedException::class, RuleErrorException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val mainRepositoryMappingValue: RepositoryMappingValue = getMainRepositoryMappingValue(ruleContext)
        val repositoryMapping: RepositoryMapping? = mainRepositoryMappingValue.repositoryMapping
        val module: Module? = loadModule(ruleContext, repositoryMapping)
        if (module == null) {
            // Skyframe restart
            Verify.verify(
                ruleContext.getAnalysisEnvironment().getSkyframeEnv().valuesMissing()
                        && !ruleContext.hasErrors()
            )
            return null
        }
        verifyModuleDeps(ruleContext, module, repositoryMapping)
        var mainRepoName: Optional<String?> = Optional.empty<String?>()
        if (ruleContext.attributes().get(RENDER_MAIN_REPO_NAME, BOOLEAN)) {
            mainRepoName = mainRepositoryMappingValue.associatedModuleName
            if (mainRepoName.isEmpty()) {
                mainRepoName = Optional.of<T?>(ruleContext.getWorkspaceName())
            }
        }
        val moduleInfo: ModuleInfo =
            getModuleInfo(ruleContext, module, LabelRenderer(repositoryMapping, mainRepoName))

        val filesToBuild: NestedSet<Artifact?>? =
            NestedSet.< Artifact > builder < Artifact ? > (Order.STABLE_ORDER)
                .add(createBinaryProtoOutput(ruleContext, moduleInfo))
                .build()
        // Textproto output isn't in filesToBuild: we want to create it only if explicitly requested.
        createTextProtoOutput(ruleContext, moduleInfo)

        return RuleConfiguredTargetBuilder(ruleContext)
            .setFilesToBuild(filesToBuild)
            .addProvider(
                RunfilesProvider::class.java,
                RunfilesProvider.simple(
                    Builder(ruleContext.getWorkspaceName())
                        .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
                        .addTransitiveArtifacts(filesToBuild)
                        .build()
                )
            )
            .build()
    }

    companion object {
        const val SRC_ATTR: String = "src"
        const val DEPS_ATTR: String = "deps"
        const val SYMBOL_NAMES_ATTR: String = "symbol_names"
        const val RENDER_MAIN_REPO_NAME: String = "render_main_repo_name"
        const val ALLOW_UNUSED_DOC_COMMENTS: String = "allow_unused_doc_comments"
        val BINARYPROTO_OUT: SafeImplicitOutputsFunction? = fromTemplates("%{name}.binaryproto")
        val TEXTPROTO_OUT: SafeImplicitOutputsFunction? = fromTemplates("%{name}.textproto")

        /**
         * Loads the Starlark module from the source file given by the rule's `src` attribute.
         * 
         * @throws RuleErrorException and reports an error in the rule if the `src` attribute refers
         * to multiple or zero files, a generated file, or a source file which cannot be loaded or
         * parsed
         * @return the module object, or null on Skyframe restart
         */
        @Throws(RuleErrorException::class, InterruptedException::class)
        private fun loadModule(ruleContext: RuleContext, repositoryMapping: RepositoryMapping?): Module? {
            Profiler.instance().profile("BzlDocDump.loadModule").use { c ->
                // Note attr schema validates that src is a .bzl or .scl file.
                val label: Label? = getSourceFileLabel(ruleContext, SRC_ATTR, repositoryMapping)

                // Note getSkyframeEnv() cannot be null while creating a configured target.
                val env: SkyFunction.Environment = ruleContext.getAnalysisEnvironment().getSkyframeEnv()

                val bzlLoadValue: BzlLoadValue?
                try {
                    // TODO(b/276733504): support loading modules in @_builtins
                    bzlLoadValue =
                        env.getValueOrThrow<E?>(
                            BzlLoadValue.keyForBuild(label),
                            BzlLoadFailedException::class.java
                        ) as BzlLoadValue?
                } catch (e: BzlLoadFailedException) {
                    ruleContext.attributeError(SRC_ATTR, e.getMessage())
                    throw RuleErrorException(e)
                }
                if (bzlLoadValue == null) {
                    // Skyframe restart
                    return null
                }
                return bzlLoadValue.getModule()
            }
        }

        /**
         * Retrieves the label of the singular source artifact from a given attribute. Note that we can't
         * simply use `ruleContext.attributes().get(attrName, LABEL)` because that does not resolve
         * aliases and filegroups.
         * 
         * @throws RuleErrorException if the source is not a singular source artifact, meaning its label
         * cannot be used as a label for a Starlark load()
         */
        @Throws(RuleErrorException::class)
        private fun getSourceFileLabel(
            ruleContext: RuleContext, attrName: String?, repositoryMapping: RepositoryMapping?
        ): Label? {
            val artifact: Artifact = ruleContext.getPrerequisiteArtifact(attrName)
            ruleContext.assertNoErrors()
            // If ruleContext.getPrerequisiteArtifact() set no errors, we know artifact != null
            if (!artifact.isSourceArtifact()) {
                val error: RuleErrorException =
                    RuleErrorException(
                        String.format(
                            "%s is not a source file and cannot be loaded in Starlark",
                            formatDerivedArtifact(artifact, repositoryMapping)
                        )
                    )
                ruleContext.attributeError(attrName, error.getMessage())
                throw error
            }
            return Verify.verifyNotNull<T?>(artifact.getOwner())
        }

        private fun formatDerivedArtifact(
            artifact: Artifact, repositoryMapping: RepositoryMapping?
        ): kotlin.String? {
            Preconditions.checkArgument(!artifact.isSourceArtifact())
            return String.format(
                "%s (generated by rule %s)",
                artifact.getRepositoryRelativePath(),
                artifact.getOwner().getDisplayForm(repositoryMapping)
            )
        }

        /**
         * Verifies that the module's transitive loads are a subset of the source artifacts in
         * files-to-build of the rule's deps.
         * 
         * @throws RuleErrorException if that is not the case.
         */
        // TODO(https://github.com/bazelbuild/bazel/issues/18599): to avoid flattening deps, we could use
        // either (a) a new, native bzl_library-like rule that verifies strict deps, or (b) a new native
        // aspect that verifies strict deps for the existing bzl_library rule. Ideally, however, we ought
        // to get rid of the deps attribute (and the need to verify it) altogether; that requires new
        // dependency machinery for `bazel query` to use the Starlark load graph for collecting the
        // dependencies of starlark_doc_extract's src.
        @Throws(RuleErrorException::class)
        private fun verifyModuleDeps(
            ruleContext: RuleContext, module: Module?, repositoryMapping: RepositoryMapping?
        ) {
            // Note attr schema validates that deps are .bzl or .scl files.
            val flattenedDepsPartitionedByIsSource: MutableMap<Boolean?, ImmutableSet<Artifact?>> =
                ruleContext.getPrerequisites(DEPS_ATTR)
                    .stream() // TODO(https://github.com/bazelbuild/bazel/issues/18599): we are using FileProvider
                    // instead of StarlarkLibraryInfo only because StarlarkLibraryInfo is defined in
                    // bazel_skylib, not natively in Bazel.
                    .flatMap({ dep -> dep.getProvider(FileProvider::class.java).getFilesToBuild().toList().stream() })
                    .collect(Collectors.partitioningBy(Artifact::isSourceArtifact, ImmutableSet.toImmutableSet<E?>()))
            // bzl_library targets may contain both source artifacts and derived artifacts (e.g. generated
            // .bzl files for tests); only the source artifacts can be load()-ed by Bazel.
            val flattenedDepsSourceArtifacts: ImmutableSet<Artifact?> =
                flattenedDepsPartitionedByIsSource.getOrDefault(true, ImmutableSet.of<Artifact?>())
            val flattenedDepsDerivedArtifacts: ImmutableSet<Artifact?> =
                flattenedDepsPartitionedByIsSource.getOrDefault(false, ImmutableSet.of<Artifact?>())

            val topmostUnknownLoads: ImmutableList<kotlin.String?> =
                getTopmostUnknownLoads(
                    module,
                    flattenedDepsSourceArtifacts.stream()
                        .map<Any?>(Function { artifact: Artifact? -> Verify.verifyNotNull<T?>(artifact.getOwner()) })
                        .collect(ImmutableSet.toImmutableSet<Any?>()),
                    repositoryMapping
                )

            if (!topmostUnknownLoads.isEmpty()) {
                val errorMessageBuilder =
                    StringBuilder("missing bzl_library targets for Starlark module(s) ")
                        .append(Joiner.on(", ").join(topmostUnknownLoads))
                if (!flattenedDepsDerivedArtifacts.isEmpty()) {
                    // TODO(arostovtsev): we ought to print only the derived artifacts having the same
                    // root-relative path as topmostUnknownLoads.
                    errorMessageBuilder
                        .append("\nNote the following are generated file(s) and cannot be loaded in Starlark: ")
                        .append(
                            Joiner.on(", ")
                                .join(
                                    flattenedDepsDerivedArtifacts.stream()
                                        .map<kotlin.String?>(Function { artifact: Artifact? ->
                                            formatDerivedArtifact(
                                                artifact,
                                                repositoryMapping
                                            )
                                        })
                                        .iterator()
                                )
                        )
                }
                val error: RuleErrorException = RuleErrorException(errorMessageBuilder.toString())
                ruleContext.attributeError(DEPS_ATTR, error.getMessage())
                throw error
            }
        }

        /**
         * Finds the topmost modules that are transitively loaded by the given module but not mentioned in
         * the given set of known modules, and returns these modules' display forms.
         * 
         * 
         * Unknown modules that are only referenced by other unknown modules are not included.
         */
        private fun getTopmostUnknownLoads(
            module: Module?, knownModules: ImmutableSet<Label?>, repositoryMapping: RepositoryMapping?
        ): ImmutableList<kotlin.String?> {
            val unknown = ImmutableList.builder<kotlin.String?>()
            val visited: MutableSet<Label?> = LinkedHashSet<Label?>()
            BazelModuleContext.visitLoadGraphRecursively(
                BazelModuleContext.of(module).loads(),
                { label ->
                    if (!visited.add(label)) {
                        return@visitLoadGraphRecursively false
                    }
                    if (!knownModules.contains(label)) {
                        unknown.add(label.getDisplayForm(repositoryMapping))
                        return@visitLoadGraphRecursively false
                    }
                    true
                })
            return unknown.build()
        }

        /** Returns the main repository's repo mapping value.  */
        @Throws(RuleErrorException::class, InterruptedException::class)
        private fun getMainRepositoryMappingValue(ruleContext: RuleContext): RepositoryMappingValue {
            val repositoryMappingValue: RepositoryMappingValue
            try {
                repositoryMappingValue =
                    ruleContext
                        .getAnalysisEnvironment()
                        .getSkyframeEnv()
                        .getValueOrThrow(
                            RepositoryMappingValue.key(RepositoryName.MAIN),
                            RepositoryMappingResolutionException::class.java
                        ) as RepositoryMappingValue
            } catch (e: RepositoryMappingResolutionException) {
                ruleContext.ruleError(e.getMessage())
                throw RuleErrorException(e)
            }
            Verify.verifyNotNull<RepositoryMappingValue?>(repositoryMappingValue)
            return repositoryMappingValue
        }

        @Throws(RuleErrorException::class)
        private fun getModuleInfo(
            ruleContext: RuleContext, module: Module?, labelRenderer: LabelRenderer?
        ): ModuleInfo {
            val moduleInfoExtractor: ModuleInfoExtractor =
                ModuleInfoExtractor(getWantedSymbolPredicate(ruleContext), labelRenderer)
            if (ruleContext.attributes().get(ALLOW_UNUSED_DOC_COMMENTS, BOOLEAN)) {
                moduleInfoExtractor.allowUnusedDocComments()
            }
            val moduleInfo: ModuleInfo
            try {
                moduleInfo = moduleInfoExtractor.extractFrom(module)
            } catch (e: ExtractionException) {
                ruleContext.ruleError(e.getMessage())
                throw RuleErrorException(e)
            }
            return moduleInfo
        }

        private fun getWantedSymbolPredicate(ruleContext: RuleContext): Predicate<kotlin.String?> {
            val symbolNames: ImmutableList<kotlin.String?> =
                ImmutableList.copyOf(ruleContext.attributes().get(SYMBOL_NAMES_ATTR, STRING_LIST))
            if (symbolNames.isEmpty()) {
                return Predicate { name: kotlin.String? -> true }
            } else {
                return Predicate { `object`: kotlin.String? -> symbolNames.contains(`object`) }
            }
        }

        @Throws(InterruptedException::class)
        private fun createBinaryProtoOutput(ruleContext: RuleContext, moduleInfo: ModuleInfo): Artifact? {
            val binaryProtoOutput: Artifact? = ruleContext.getImplicitOutputArtifact(BINARYPROTO_OUT)
            ruleContext.registerAction(
                BinaryFileWriteAction(
                    ruleContext.getActionOwner(),
                    binaryProtoOutput,
                    ByteSource.wrap(moduleInfo.toByteArray()),  /* makeExecutable= */
                    false
                )
            )
            return binaryProtoOutput
        }

        @CanIgnoreReturnValue
        @Throws(InterruptedException::class, RuleErrorException::class)
        private fun createTextProtoOutput(ruleContext: RuleContext, moduleInfo: ModuleInfo?): Artifact? {
            val textProtoOutput: Artifact? = ruleContext.getImplicitOutputArtifact(TEXTPROTO_OUT)

            val textprotoBuilder = StringBuilder()
            try {
                TextFormat.printer().print(moduleInfo, textprotoBuilder)
            } catch (e: IOException) {
                ruleContext.ruleError(e.getMessage())
                throw RuleErrorException(e)
            }
            ruleContext.registerAction(
                FileWriteAction.create(
                    ruleContext,
                    textProtoOutput,
                    textprotoBuilder.toString(),  /* makeExecutable= */
                    false
                )
            )
            return textProtoOutput
        }
    }
}
