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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleBase
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.bazel.bzlmod.SingleVersionOverride
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import java.util.function.UnaryOperator


/**
 * Represents a node in the external dependency graph during module resolution (discovery &
 * selection).
 * 
 * 
 * In particular, it represents a specific version of a module; there can be multiple [ ]s in a dependency graph with the same name but with different versions (such as
 * after discovery but before selection, or when there's a multiple_version_override in play).
 * 
 * 
 * Compared to [Module], which is used after module resolution, this class holds some more
 * information that's useful only during resolution, such as the `registry` the module comes
 * from, etc.
 */
@AutoValue
abstract class InterimModule : ModuleBase() {
    /** List of bazel compatible versions that would run/fail this module  */
    abstract fun getBazelCompatibility(): com.google.common.collect.ImmutableList<String?>?

    /**
     * The resolved direct dependencies of this module, which can be either the original ones,
     * overridden by a `single_version_override`, by a `multiple_version_override`, or by
     * a [NonRegistryOverride] (the version will be ""). The key type is the repo name of the
     * dep.
     */
    abstract fun getDeps(): com.google.common.collect.ImmutableMap<String?, ModuleKey?>?

    /**
     * The original direct dependencies of this module as they are declared in their MODULE file. The
     * key type is the repo name of the dep.
     */
    abstract fun getOriginalDeps(): com.google.common.collect.ImmutableMap<String?, ModuleKey?>?

    /**
     * The "nodep" dependencies of this module: these don't actually add a dependency on the specified
     * module, but if specified module is somehow in the dependency graph, it'll be at least at this
     * version.
     */
    abstract fun getNodepDeps(): com.google.common.collect.ImmutableList<ModuleKey?>?

    /**
     * The registry where this module came from. Must be null iff the module has a [ ].
     */
    abstract fun getRegistry(): com.google.devtools.build.lib.bazel.bzlmod.Registry?

    /** Returns a [Builder] that starts out with the same fields as this object.  */
    abstract fun toBuilder(): Builder?

    /**
     * Returns a new [InterimModule] with all values in [.getDeps] and [ ][.getNodepDeps] transformed using the given function.
     */
    fun withDepsTransformed(transform: UnaryOperator<ModuleKey?>): InterimModule? {
        return toBuilder()!!
            .setDeps(
                com.google.common.collect.ImmutableMap.copyOf<kotlin.String?, ModuleKey?>(
                    com.google.common.collect.Maps.transformValues<kotlin.String?, ModuleKey?, ModuleKey?>(
                        getDeps(),
                        com.google.common.base.Function { t: ModuleKey? -> transform.apply(t) })
                )
            )!!
            .setNodepDeps(
                com.google.common.collect.ImmutableList.copyOf<ModuleKey?>(
                    com.google.common.collect.Lists.transform<ModuleKey?, ModuleKey?>(
                        getNodepDeps(),
                        com.google.common.base.Function { t: ModuleKey? -> transform.apply(t) })
                )
            )!!
            .build()
    }

    /** Builder type for [InterimModule].  */
    @AutoValue.Builder
    abstract class Builder {
        /** Optional; defaults to the empty string.  */
        abstract fun setName(value: String?): Builder?

        /** Optional; defaults to [Version.EMPTY].  */
        abstract fun setVersion(value: com.google.devtools.build.lib.bazel.bzlmod.Version?): Builder?

        /** Optional; defaults to [ModuleKey.ROOT].  */
        abstract fun setKey(value: ModuleKey?): Builder?


        /** Optional; defaults to [.setName].  */
        abstract fun setRepoName(value: String?): Builder?

        abstract fun bazelCompatibilityBuilder(): com.google.common.collect.ImmutableList.Builder<String?>?

        abstract fun flagAliasesBuilder(): com.google.common.collect.ImmutableMap.Builder<String?, String?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(LabelSyntaxException::class)
        fun addFlagAlias(nativeName: String?, starlarkLabel: String?): Builder {
            flagAliasesBuilder().put(nativeName, starlarkLabel)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBazelCompatibilityValues(values: Iterable<String?>): Builder {
            bazelCompatibilityBuilder().addAll(values)
            return this
        }

        abstract fun executionPlatformsToRegisterBuilder(): com.google.common.collect.ImmutableList.Builder<String?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecutionPlatformsToRegister(values: Iterable<String?>): Builder {
            executionPlatformsToRegisterBuilder().addAll(values)
            return this
        }

        abstract fun toolchainsToRegisterBuilder(): com.google.common.collect.ImmutableList.Builder<String?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addToolchainsToRegister(values: Iterable<String?>): Builder {
            toolchainsToRegisterBuilder().addAll(values)
            return this
        }

        abstract fun setOriginalDeps(value: com.google.common.collect.ImmutableMap<String?, ModuleKey?>?): Builder?

        abstract fun setDeps(value: com.google.common.collect.ImmutableMap<String?, ModuleKey?>?): Builder?

        abstract fun nodepDepsBuilder(): com.google.common.collect.ImmutableList.Builder<ModuleKey?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNodepDep(value: ModuleKey): Builder {
            nodepDepsBuilder().add(value)
            return this
        }

        abstract fun setNodepDeps(value: com.google.common.collect.ImmutableList<ModuleKey?>?): Builder?

        abstract fun setRegistry(value: com.google.devtools.build.lib.bazel.bzlmod.Registry?): Builder?

        abstract fun setExtensionUsages(value: com.google.common.collect.ImmutableList<ModuleExtensionUsage?>?): Builder?

        abstract fun extensionUsagesBuilder(): com.google.common.collect.ImmutableList.Builder<ModuleExtensionUsage?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExtensionUsage(value: ModuleExtensionUsage): Builder {
            extensionUsagesBuilder().add(value)
            return this
        }

        abstract fun getKey(): ModuleKey?

        abstract fun getName(): String?

        abstract fun getVersion(): com.google.devtools.build.lib.bazel.bzlmod.Version?

        abstract fun getRepoName(): java.util.Optional<String?>?

        abstract fun autoBuild(): InterimModule?

        fun build(): InterimModule? {
            if (getRepoName().isEmpty()) {
                setRepoName(getName())
            }
            return autoBuild()
        }
    }

    companion object {
        /** Returns a new, empty [Builder].  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return Builder()
                .setName("")
                .setVersion(com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY)
                .setKey(ModuleKey.Companion.ROOT)!!
        }

        /**
         * Builds a [Module] from an [InterimModule], discarding unnecessary fields and adding
         * extra necessary ones (such as the repo spec).
         * 
         * @param remoteRepoSpec the [RepoSpec] for the module obtained from a registry or null if
         * the module has a non-registry override
         */
        fun toModule(
            interim: InterimModule,
            override: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?,
            remoteRepoSpec: RepoSpec?
        ): com.google.devtools.build.lib.bazel.bzlmod.Module? {
            return com.google.devtools.build.lib.bazel.bzlmod.Module.Companion.builder()
                .setName(interim.getName())
                .setVersion(interim.getVersion())
                .setKey(interim.getKey())
                .setRepoName(interim.getRepoName())
                .setExecutionPlatformsToRegister(interim.getExecutionPlatformsToRegister())
                .setToolchainsToRegister(interim.getToolchainsToRegister())
                .setDeps(interim.getDeps())
                .setRepoSpec(maybeAppendAdditionalPatches(remoteRepoSpec, override))
                .setExtensionUsages(interim.getExtensionUsages())
                .setFlagAliases(interim.getFlagAliases())
                .build()
        }

        private fun maybeAppendAdditionalPatches(
            repoSpec: RepoSpec?, override: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?
        ): RepoSpec? {
            if (override !is SingleVersionOverride) {
                return repoSpec
            }
            if (override.patches.isEmpty()
                && override.patchCmds.isEmpty()
                && override.patchStrip == 0
            ) {
                return repoSpec
            }
            val attrBuilder: net.starlark.java.eval.Dict.Builder<String?, Any?> =
                net.starlark.java.eval.Dict.builder<String?, Any?>()
            attrBuilder.putAll(repoSpec.attributes.attributes())
            attrBuilder.put(
                "patches",
                net.starlark.java.eval.StarlarkList.immutableCopyOf<com.google.devtools.build.lib.cmdline.Label?>(
                    override.patches
                )
            )
            attrBuilder.put(
                "patch_cmds",
                net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(override.patchCmds)
            )
            attrBuilder.put(
                "patch_args",
                net.starlark.java.eval.StarlarkList.immutableOf<String?>("-p" + override.patchStrip)
            )
            return RepoSpec(
                repoSpec.repoRuleId,
                com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(attrBuilder.buildImmutable())
            )
        }
    }
}
