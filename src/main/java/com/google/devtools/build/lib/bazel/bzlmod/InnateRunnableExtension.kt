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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code

/**
 * A fabricated module extension "innate" to each module, used to generate all repos defined using
 * `use_repo_rule` for a single repo rule.
 */
internal class InnateRunnableExtension(
    moduleKey: ModuleKey,
    bzlLabel: com.google.devtools.build.lib.cmdline.Label?,
    ruleName: String?,
    loadedBzl: BzlLoadValue,
    tags: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Tag>
) : RunnableExtension {
    private val moduleKey: ModuleKey
    private val bzlLabel: com.google.devtools.build.lib.cmdline.Label?
    private val ruleName: String?
    private val loadedBzl: BzlLoadValue

    // Never empty.
    private val tags: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Tag>

    init {
        this.moduleKey = moduleKey
        this.bzlLabel = bzlLabel
        this.ruleName = ruleName
        this.loadedBzl = loadedBzl
        com.google.common.base.Preconditions.checkArgument(!tags.isEmpty())
        this.tags = tags
    }

    val evalFactors: ModuleExtensionEvalFactors
        get() = ModuleExtensionEvalFactors.Companion.create("", "")

    val bzlTransitiveDigest: ByteArray?
        get() = loadedBzl.getTransitiveDigest()

    val factsVersion: Int
        get() = 0

    @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
    override fun run(
        env: SkyFunction.Environment,
        usagesValue: SingleExtensionUsagesValue,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        extensionId: ModuleExtensionId,
        mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        facts: Facts?
    ): RunModuleExtensionResult {
        val exported: Any? = loadedBzl.getModule().getGlobal(ruleName)
        if (exported == null) {
            val exportedRepoRules: com.google.common.collect.ImmutableSet<String?> =
                loadedBzl.getModule().getGlobals().entrySet().stream()
                    .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<String?, Any?>? -> e.getValue() is StarlarkRepoRule })
                    .map<String?>(java.util.function.Function { obj: MutableMap.MutableEntry<String?, Any?>? -> obj.getKey() })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
            throw ExternalDepsException.Companion.withMessage(
                Code.BAD_MODULE,
                "%s does not export a repository_rule called %s, yet its use is requested at %s%s",
                bzlLabel,
                ruleName,
                tags.getFirst().getLocation(),
                net.starlark.java.spelling.SpellChecker.didYouMean(ruleName, exportedRepoRules)
            )
        } else if (exported !is StarlarkRepoRule) {
            throw ExternalDepsException.Companion.withMessage(
                Code.BAD_MODULE,
                "%s exports a value called %s of type %s, yet a repository_rule is requested at %s",
                bzlLabel,
                ruleName,
                net.starlark.java.eval.Starlark.type(exported),
                tags.getFirst().getLocation()
            )
        }
        val repoRule: RepoRule = (exported as StarlarkRepoRule).getRepoRule()

        val generatedRepoSpecs: com.google.common.collect.ImmutableMap.Builder<String?, RepoSpec?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, RepoSpec?>(tags.size())
        // Instantiate the repos one by one.
        for (tag in tags) {
            val kwargs: net.starlark.java.eval.Dict<String?, Any?> = tag.getAttributeValues().attributes()
            // This cast should be safe since it should have been verified at tag creation time.
            val name = kwargs.get("name") as String?
            val fakeCallStack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> =
                com.google.common.collect.ImmutableList.of<net.starlark.java.eval.StarlarkThread.CallStackEntry?>(
                    net.starlark.java.eval.StarlarkThread.callStackEntry(
                        "<toplevel>",
                        tag.getLocation()
                    )
                )
            val labelConverter: LabelConverter =
                LabelConverter(
                    extensionId.bzlFileLabel.getPackageIdentifier(),
                    usagesValue.getRepoMappings().get(moduleKey)
                )
            generatedRepoSpecs.put(
                name,
                repoRule.instantiate(
                    kwargs,
                    fakeCallStack,
                    labelConverter,
                    env.getListener(),
                    "to the %s".formatted(moduleKey.toDisplayString())
                )
            )
        }
        return RunModuleExtensionResult(
            com.google.common.collect.ImmutableList.of<WithValue?>(),
            generatedRepoSpecs.buildOrThrow(),
            ModuleExtensionMetadata.Companion.REPRODUCIBLE
        )
    }

    companion object {
        /** Returns null if a Skyframe restart is needed.  */
        @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
        fun load(
            extensionId: ModuleExtensionId,
            usagesValue: SingleExtensionUsagesValue,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
            env: SkyFunction.Environment
        ): InnateRunnableExtension? {
            // An innate extension should have a singular usage.
            if (usagesValue.getExtensionUsages().size() > 1) {
                throw ExternalDepsException.Companion.withMessage(
                    Code.BAD_MODULE,
                    "innate module extension %s is used by multiple modules: %s",
                    extensionId,
                    usagesValue.getExtensionUsages().keySet()
                )
            }
            val moduleKey: ModuleKey? = com.google.common.collect.Iterables.getOnlyElement<ModuleKey?>(
                usagesValue.getExtensionUsages().keySet()
            )
            // ModuleFileFunction doesn't add usages for use_repo_rule without any instantiations, so we can
            // assume that there is at least one tag.
            val tags: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Tag> =
                com.google.common.collect.Iterables.getOnlyElement<ModuleExtensionUsage?>(
                    usagesValue.getExtensionUsages().values()
                ).getTags()
            val repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping? =
                usagesValue.getRepoMappings().get(moduleKey)
            val repoContext: RepoContext =
                com.google.devtools.build.lib.cmdline.Label.RepoContext.of(repoMapping.contextRepo(), repoMapping)

            // The name of the extension is of the form "<bzl_file_label> <rule_name>". Rule names cannot
            // contain spaces, so we can split on the last space.
            val lastSpace: Int = extensionId.extensionName.lastIndexOf(' '.code)
            val rawLabel: String = extensionId.extensionName.substring(0, lastSpace)
            val ruleName: String = extensionId.extensionName.substring(lastSpace + 1)
            val location: net.starlark.java.syntax.Location? = tags.getFirst().getLocation()
            val bzlLabel: com.google.devtools.build.lib.cmdline.Label?
            try {
                bzlLabel = com.google.devtools.build.lib.cmdline.Label.parseWithRepoContext(rawLabel, repoContext)
                BzlLoadFunction.checkValidLoadLabel(bzlLabel, starlarkSemantics)
            } catch (e: LabelSyntaxException) {
                throw withCauseAndMessage(
                    Code.BAD_MODULE, e, "bad repo rule .bzl file label at %s", location
                )
            }
            if (ruleName.startsWith("_")) {
                throw ExternalDepsException.Companion.withMessage(
                    Code.BAD_MODULE,
                    "%s does not export a repository_rule called %s, yet its use is requested at %s",
                    bzlLabel,
                    ruleName,
                    tags.getFirst().getLocation()
                )
            }

            // Load the .bzl file.
            val loadedBzl: BzlLoadValue?
            try {
                loadedBzl =
                    env.getValueOrThrow<BzlLoadFailedException?>(
                        BzlLoadValue.keyForBzlmod(bzlLabel), BzlLoadFailedException::class.java
                    ) as BzlLoadValue?
            } catch (e: BzlLoadFailedException) {
                throw ExternalDepsException.Companion.withCauseAndMessage(
                    Code.BAD_MODULE,
                    e,
                    "error loading '%s' for repo rules, requested by %s",
                    bzlLabel,
                    location
                )
            }
            if (loadedBzl == null) {
                return null
            }

            return InnateRunnableExtension(moduleKey, bzlLabel, ruleName, loadedBzl, tags)
        }
    }
}
