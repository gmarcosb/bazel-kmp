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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Looks up the definition of a repo with the given name.  */
class RepoDefinitionFunction(directories: BlazeDirectories) : SkyFunction {
    private val directories: BlazeDirectories

    init {
        this.directories = directories
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val root: RootModuleFileValue? =
            env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE) as RootModuleFileValue?
        if (env.valuesMissing()) {
            return null
        }

        // Sometimes, the attributes in the repo specs contain label strings instead of label objects.
        // This really only happens for attributes like `patches` that come from an `archive_override`
        // or `git_override` in MODULE.bazel. (This is because those overrides just store all attributes
        // unparsed and unvalidated in the repo spec.) In other words, this can only happen for Step 1
        // below -- but for consistency's sake, we pass this mapping for all 3 cases.
        //
        // In such cases, we need to provide a "basic repo mapping", so that we can properly turn those
        // label strings into label objects. Since we only accept patches from the main repo anyway, we
        // only need the two simple entries pointing into the main repo itself.
        val basicMainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping =
            com.google.devtools.build.lib.cmdline.RepositoryMapping.Companion.create(
                com.google.common.collect.ImmutableMap.builder<String?, RepositoryName?>()
                    .put("", RepositoryName.Companion.MAIN)
                    .put(root.module().getRepoName(), RepositoryName.Companion.MAIN)
                    .buildKeepingLast(),
                RepositoryName.Companion.MAIN
            )

        val repositoryName: RepositoryName =
            (skyKey as com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue.Key).argument()

        // Step 0: Look for repository overrides via canonical names. Requesting the main repo mapping
        // at this point would result in a cycle, so any lookup by apparent name requires special
        // handling.
        val overrides: MutableMap<String?, PathFragment?>? = REPOSITORY_OVERRIDES.get(env)
        if (com.google.common.base.Preconditions.checkNotNull<MutableMap<String?, PathFragment?>?>(overrides)
                .containsKey(repositoryName.getName())
        ) {
            return RepoOverride(overrides!!.get(repositoryName.getName()))
        }
        if (repositoryName == RepositoryName.Companion.BAZEL_TOOLS) {
            return RepoOverride(
                directories.getEmbeddedBinariesRoot().getRelative("embedded_tools").asFragment()
            )
        }

        // Step 1: Look for repositories defined by non-registry overrides.
        val nonRegistryOverride: java.util.Optional<RepoDefinitionValue?> =
            checkRepoFromNonRegistryOverrides(
                root, repositoryName, overrides, basicMainRepoMapping, env
            )
        if (env.valuesMissing()) {
            return null
        }
        if (nonRegistryOverride.isPresent()) {
            return nonRegistryOverride.get()
        }

        // BazelDepGraphValue is affected by repos found in Step 1, therefore it should NOT
        // be requested in Step 1 to avoid cycle dependency.
        val bazelDepGraphValue: BazelDepGraphValue? =
            env.getValue(BazelDepGraphValue.KEY) as BazelDepGraphValue?
        if (env.valuesMissing()) {
            return null
        }

        // Step 2: Look for repository overrides via apparent names.
        val apparentNameOverrides: com.google.common.collect.ImmutableMap<RepositoryName?, PathFragment?>? =
            getApparentNameOverrides(env)
        if (env.valuesMissing()) {
            return null
        }
        if (apparentNameOverrides.containsKey(repositoryName)) {
            return RepoOverride(apparentNameOverrides.get(repositoryName))
        }

        // Step 3: Look for repositories derived from Bazel Modules.
        val repoSpec: java.util.Optional<RepoSpec?> = checkRepoFromBazelModules(bazelDepGraphValue, repositoryName)
        if (repoSpec.isPresent()) {
            return createRepoDefinitionFromSpec(
                repoSpec.get(), repositoryName,  /* originalName= */null, basicMainRepoMapping, env
            )
        }

        // Step 4: look for the repo from module extension evaluation results.
        val extensionId: java.util.Optional<ModuleExtensionId?> =
            bazelDepGraphValue.getExtensionUniqueNames().entrySet().stream()
                .filter({ e -> repositoryName.getName().startsWith(e.getValue() + "+") })
                .map({ obj: MutableMap.MutableEntry<*, *>? -> obj.getKey() })
                .findFirst()

        if (extensionId.isEmpty()) {
            return RepoDefinitionValue.Companion.NOT_FOUND
        }

        val extensionValue: SingleExtensionValue? =
            env.getValue(SingleExtensionValue.key(extensionId.get())) as SingleExtensionValue?
        if (extensionValue == null) {
            return null
        }

        val internalRepo: String? = extensionValue.canonicalRepoNameToInternalNames().get(repositoryName)
        if (internalRepo == null) {
            return RepoDefinitionValue.Companion.NOT_FOUND
        }
        val extRepoSpec: RepoSpec = extensionValue.generatedRepoSpecs().get(internalRepo)
        return createRepoDefinitionFromSpec(
            extRepoSpec, repositoryName, internalRepo, basicMainRepoMapping, env
        )
    }

    // Callers must check env.valuesMissing() and ignore the result if true.
    @Throws(RepoDefinitionFunctionException::class, java.lang.InterruptedException::class)
    private fun checkRepoFromNonRegistryOverrides(
        root: RootModuleFileValue,
        repositoryName: RepositoryName,
        overrides: MutableMap<String?, PathFragment?>,
        basicMainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        env: SkyFunction.Environment
    ): java.util.Optional<RepoDefinitionValue?> {
        val moduleName: String? = root.nonRegistryOverrideCanonicalRepoToModuleName().get(repositoryName)
        if (moduleName == null) {
            return java.util.Optional.empty<RepoDefinitionValue?>()
        }
        // If this module is a direct dep of the root module, its apparent name may be named by the
        // --override_repository flag, which takes precedence over the non-registry override.
        val moduleRepoName: String? = root.nonRegistryOverrideModuleToRepoName().get(moduleName)
        if (moduleRepoName != null) {
            val commandLineOverride: PathFragment? = overrides.get(moduleRepoName)
            if (commandLineOverride != null) {
                return java.util.Optional.of<RepoDefinitionValue?>(RepoOverride(commandLineOverride))
            }
        }
        val override: NonRegistryOverride = root.overrides().get(moduleName) as NonRegistryOverride
        return java.util.Optional.ofNullable<RepoDefinitionValue?>(
            createRepoDefinitionFromSpec(
                override.repoSpec(),
                repositoryName,  /* originalName= */
                null,
                basicMainRepoMapping,
                env
            )
        )
    }

    @Throws(java.lang.InterruptedException::class, RepoDefinitionFunctionException::class)
    private fun getApparentNameOverrides(env: SkyFunction.Environment): com.google.common.collect.ImmutableMap<RepositoryName?, PathFragment?>? {
        val mainRepoMapping: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(RepositoryName.Companion.MAIN)) as RepositoryMappingValue?
        if (mainRepoMapping == null) {
            return null
        }
        val overrides: MutableMap<String?, PathFragment?> =
            com.google.common.base.Preconditions.checkNotNull<MutableMap<String?, PathFragment?>>(
                REPOSITORY_OVERRIDES.get(env)
            )
        val apparentNameOverrides: com.google.common.collect.ImmutableMap.Builder<RepositoryName?, PathFragment?> =
            com.google.common.collect.ImmutableMap.builder<RepositoryName?, PathFragment?>()
        for (entry in overrides.entrySet()) {
            if (!RepositoryName.Companion.isApparent(entry.getKey())) {
                continue
            }
            val apparentName: String? = entry.getKey()
            val canonicalName: RepositoryName = mainRepoMapping.repositoryMapping.get(apparentName)
            if (!canonicalName.isVisible()) {
                throw RepoDefinitionFunctionException(
                    ExternalDepsException.withMessage(
                        Code.BAD_MODULE,
                        "no repository visible as '@%s'%s from the main repository, but overridden with"
                                + " --override_repository. Use --inject_repository to add new repositories.",
                        apparentName,
                        net.starlark.java.spelling.SpellChecker.didYouMean(
                            apparentName,
                            com.google.common.collect.Iterables.transform<String?, String?>(
                                mainRepoMapping.repositoryMapping.entries().keySet(),
                                com.google.common.base.Function { name: String? -> "@" + name })
                        )
                    ),
                    Transience.PERSISTENT
                )
            }
            apparentNameOverrides.put(canonicalName, entry.getValue())
        }
        return apparentNameOverrides.buildOrThrow()
    }

    private fun checkRepoFromBazelModules(
        bazelDepGraphValue: BazelDepGraphValue, repositoryName: RepositoryName?
    ): java.util.Optional<RepoSpec?> {
        val moduleKey: ModuleKey? = bazelDepGraphValue.canonicalRepoNameLookup.get(repositoryName)
        if (moduleKey == null) {
            return java.util.Optional.empty<RepoSpec?>()
        }
        return java.util.Optional.ofNullable<T?>(bazelDepGraphValue.depGraph.get(moduleKey).getRepoSpec())
    }

    @Throws(RepoDefinitionFunctionException::class, java.lang.InterruptedException::class)
    private fun createRepoDefinitionFromSpec(
        repoSpec: RepoSpec,
        repositoryName: RepositoryName,
        originalName: String?,
        basicMainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        env: SkyFunction.Environment
    ): RepoDefinitionValue? {
        val repoRule: RepoRule? = loadRepoRule(repoSpec.repoRuleId(), env)
        if (repoRule == null) {
            return null
        }

        try {
            val typeCheckedRepoSpec: RepoSpec =
                repoRule.instantiate(
                    repoSpec.attributes()
                        .attributes(),  // Use a completely fake call stack. This should never be user-visible anyway, since
                    // the repo spec here should have already been typechecked (or generated directly by
                    // a Registry#getRepoSpec call -- in which case it better be correct already...).
                    com.google.common.collect.ImmutableList.of<net.starlark.java.eval.StarlarkThread.CallStackEntry?>(
                        net.starlark.java.eval.StarlarkThread.callStackEntry(
                            "<toplevel>",
                            net.starlark.java.syntax.Location.BUILTIN
                        )
                    ),
                    LabelConverter(PackageIdentifier.Companion.EMPTY_PACKAGE_ID, basicMainRepoMapping),
                    env.getListener(),
                    "to the root module"
                )
            val repoDefinition: RepoDefinition =
                RepoDefinition(
                    repoRule, typeCheckedRepoSpec.attributes(), repositoryName.getName(), originalName
                )
            return Found(repoDefinition)
        } catch (e: ExternalDepsException) {
            throw RepoDefinitionFunctionException(e, Transience.PERSISTENT)
        }
    }

    @Throws(java.lang.InterruptedException::class, RepoDefinitionFunctionException::class)
    private fun loadRepoRule(repoRuleId: RepoRuleId, env: SkyFunction.Environment): RepoRule? {
        val key: SkyKey?
        if (NonRegistryOverride.BOOTSTRAP_REPO_RULES.contains(repoRuleId)) {
            key = BzlLoadValue.keyForBzlmodBootstrap(repoRuleId.bzlFileLabel())
        } else {
            key = BzlLoadValue.keyForBzlmod(repoRuleId.bzlFileLabel())
        }

        // Load the .bzl file pointed to by the label.
        val bzlLoadValue: BzlLoadValue?
        try {
            bzlLoadValue =
                env.getValueOrThrow<BzlLoadFailedException?>(key, BzlLoadFailedException::class.java) as BzlLoadValue?
        } catch (e: BzlLoadFailedException) {
            // No need for a super detailed error message, since errors here can basically only happen
            // when something is horribly wrong. (The labels to load are either hardcoded or already
            // sanity-checked somewhere else.)
            throw RepoDefinitionFunctionException(e, Transience.PERSISTENT)
        }
        if (bzlLoadValue == null) {
            return null
        }

        val `object`: Any? = bzlLoadValue.getModule().getGlobal(repoRuleId.ruleName())
        if (`object` is com.google.devtools.build.lib.bazel.repository.RepoRule.Supplier) {
            return `object`.getRepoRule()
        } else if (`object` == null) {
            throw RepoDefinitionFunctionException(
                ExternalDepsException.withMessage(
                    Code.EXTENSION_EVAL_ERROR,
                    "repository rule %s does not exist (no such symbol in that file)",
                    repoRuleId
                ),
                Transience.PERSISTENT
            )
        } else {
            throw RepoDefinitionFunctionException(
                ExternalDepsException.withMessage(
                    Code.EXTENSION_EVAL_ERROR,
                    "invalid repository rule: %s, expected type repository_rule, got type %s",
                    repoRuleId,
                    net.starlark.java.eval.Starlark.type(`object`)
                ),
                Transience.PERSISTENT
            )
        }
    }

    private class RepoDefinitionFunctionException : SkyFunctionException {
        internal constructor(e: BzlLoadFailedException?, transience: Transience?) : super(e, transience)

        internal constructor(e: ExternalDepsException?, transience: Transience?) : super(e, transience)
    }

    companion object {
        @kotlin.jvm.JvmField
        val REPOSITORY_OVERRIDES: Precomputed<MutableMap<String?, PathFragment?>?> =
            Precomputed<MutableMap<String?, PathFragment?>?>("repository_overrides")
    }
}
