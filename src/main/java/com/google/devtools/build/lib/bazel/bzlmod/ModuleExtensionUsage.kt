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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId.IsolationKey
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.vfs.PathFragment
import com.ryanharter.auto.value.gson.GenerateTypeAdapter

/**
 * Represents the usage of a module extension in one module. This class records all the information
 * pertinent to all the proxy objects returned from any `use_extension` calls in this module
 * that refer to the same extension (or isolate, when applicable).
 * 
 * 
 * When adding new fields, make sure to update [.trimForEvaluation] as well.
 */
@AutoValue
@GenerateTypeAdapter
abstract class ModuleExtensionUsage {
    /** An unresolved label pointing to the Starlark file where the module extension is defined.  */
    abstract val extensionBzlFile: String?

    /** The name of the extension.  */
    abstract val extensionName: String?

    /**
     * The isolation key of this module extension usage. This is present if and only if the usage is
     * created with `isolate = True`.
     */
    abstract val isolationKey: java.util.Optional<IsolationKey?>?

    /** Represents one "proxy object" returned from one `use_extension` call.  */
    @AutoValue
    @GenerateTypeAdapter
    abstract class Proxy {
        /** The location of the `use_extension` call.  */
        abstract val location: net.starlark.java.syntax.Location?

        /**
         * The name of the proxy object; as in, the name that the return value of `use_extension`
         * is bound to. Is the empty string if the return value is not bound to any name (e.g. `use_repo(use_extension(...))`).
         */
        abstract val proxyName: String?

        /**
         * The path to the MODULE.bazel file (or one of its includes) that contains this proxy object.
         * This path should be relative to the workspace root.
         */
        abstract val containingModuleFilePath: PathFragment?

        /** Whether `dev_dependency` is set to true.  */
        abstract val isDevDependency: Boolean

        /**
         * All the repos imported, through this proxy, from this module extension into the scope of the
         * current module. The key is the local repo name (in the scope of the current module), and the
         * value is the name exported by the module extension.
         */
        abstract val imports: com.google.common.collect.ImmutableBiMap<String?, String?>?

        /** Builder for [ModuleExtensionUsage.Proxy].  */
        @AutoValue.Builder
        abstract class Builder {
            abstract fun setLocation(value: net.starlark.java.syntax.Location?): Builder?

            abstract val proxyName: String?

            abstract fun setProxyName(value: String?): Builder?

            abstract fun setContainingModuleFilePath(value: PathFragment?): Builder?

            abstract val isDevDependency: Boolean

            abstract fun setDevDependency(value: Boolean): Builder?

            abstract fun importsBuilder(): com.google.common.collect.ImmutableBiMap.Builder<String?, String?>?

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addImport(key: String, value: String): Builder {
                importsBuilder().put(key, value)
                return this
            }

            abstract fun setImports(value: com.google.common.collect.ImmutableBiMap<String?, String?>?): Builder?

            abstract fun build(): Proxy?
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun builder(): Builder {
                return Builder().setProxyName("")!!
            }
        }
    }

    /** The list of proxy objects that constitute  */
    abstract val proxies: com.google.common.collect.ImmutableList<Proxy?>?

    /** All the tags specified by this module for this extension.  */
    abstract val tags: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Tag?>?

    val hasDevUseExtension: Boolean
        /**
         * Whether any `use_extension` calls for this usage had `dev_dependency = True` set.
         */
        get() = this.proxies.stream()
            .anyMatch(java.util.function.Predicate { p: Proxy? -> p!!.isDevDependency })

    val hasNonDevUseExtension: Boolean
        /**
         * Whether any `use_extension` calls for this usage had `dev_dependency = False` set.
         */
        get() = this.proxies.stream()
            .anyMatch(java.util.function.Predicate { p: Proxy? -> !p!!.isDevDependency })

    /**
     * Represents a repo that overrides another repo within the scope of the extension.
     * 
     * @param overridingRepoName The apparent name of the overriding repo in the root module.
     * @param mustExist Whether this override should apply to an existing repo.
     * @param location The location of the `override_repo` or `inject_repo` call.
     */
    @AutoCodec
    @GenerateTypeAdapter
    class RepoOverride(
        val overridingRepoName: String?,
        val mustExist: Boolean,
        location: net.starlark.java.syntax.Location?
    ) {
        val location: net.starlark.java.syntax.Location?

        init {
            this.location = location
        }
    }

    /**
     * Contains information about overrides that apply to repos generated by this extension. Keyed by
     * the extension-local repo name.
     * 
     * 
     * This is only non-empty for root module usages.
     */
    @kotlin.jvm.JvmField
    abstract val repoOverrides: com.google.common.collect.ImmutableMap<String?, RepoOverride?>?

    abstract fun toBuilder(): Builder?

    /**
     * Returns a new usage with all information removed that does not influence the evaluation of the
     * extension.
     */
    fun trimForEvaluation(): ModuleExtensionUsage? {
        // We start with the full usage and selectively remove information that does not influence the
        // evaluation of the extension. Compared to explicitly copying over the parts that do, this
        // preserves correctness in case new fields are added without updating this code.
        return toBuilder()!!
            .setTags(
                this.tags.stream()
                    .map<com.google.devtools.build.lib.bazel.bzlmod.Tag?>(java.util.function.Function { obj: com.google.devtools.build.lib.bazel.bzlmod.Tag? -> obj.trimForEvaluation() })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Tag?>())
            )!! // Clear out all proxies as information contained therein isn't useful for evaluation.
            // Locations are only used for error reporting and thus don't influence whether the
            // evaluation of the extension is successful and what its result is in case of success.
            // Extension implementation functions do not see the imports, they are only validated
            // against the set of generated repos in a validation step that comes afterward.
            .setProxies(com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy?>())!! // Tracked in SingleExtensionUsagesValue instead, using canonical instead of apparent names.
            // Whether this override must apply to an existing repo as well as its source location also
            // don't influence the evaluation of the extension as they are checked in
            // SingleExtensionFunction.
            .setRepoOverrides(com.google.common.collect.ImmutableMap.of<kotlin.String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.RepoOverride?>())!!
            .build()
    }

    /** Builder for [ModuleExtensionUsage].  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun setExtensionBzlFile(value: String?): Builder?

        abstract fun setExtensionName(value: String?): Builder?

        abstract fun setIsolationKey(value: java.util.Optional<IsolationKey?>?): Builder?

        abstract fun setProxies(value: com.google.common.collect.ImmutableList<Proxy?>?): Builder?

        abstract fun proxiesBuilder(): com.google.common.collect.ImmutableList.Builder<Proxy?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addProxy(value: Proxy): Builder {
            proxiesBuilder().add(value)
            return this
        }

        abstract fun setTags(value: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.Tag?>?): Builder?

        abstract fun tagsBuilder(): com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.bazel.bzlmod.Tag?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTag(value: com.google.devtools.build.lib.bazel.bzlmod.Tag): Builder {
            tagsBuilder().add(value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        abstract fun setRepoOverrides(repoOverrides: com.google.common.collect.ImmutableMap<String?, RepoOverride?>?): Builder?

        abstract fun build(): ModuleExtensionUsage?
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return Builder()
        }
    }
}
