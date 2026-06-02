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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * A module name, version pair that identifies a node in the external dependency graph.
 * 
 * @param name The name of the module. Must be empty for the root module.
 * @param version The version of the module. Must be empty iff the module has a [     ].
 */
@AutoCodec
class ModuleKey(@kotlin.jvm.JvmField val name: String?, version: com.google.devtools.build.lib.bazel.bzlmod.Version?) {
    override fun toString(): String {
        if (this == ROOT) {
            return "<root>"
        }
        return name + "@" + (if (version.isEmpty()) "_" else version.toString())
    }

    /** Returns a string such as "root module" or "module foo@1.2.3" for display purposes.  */
    fun toDisplayString(): String? {
        if (this == ROOT) {
            return "root module"
        }
        return java.lang.String.format("module '%s'", this)
    }

    val canonicalRepoNameWithVersion: RepositoryName?
        /**
         * Returns the canonical name of the repo backing this module, including its version. This name is
         * always guaranteed to be unique.
         * 
         * 
         * This method must not be called if the module has a [NonRegistryOverride].
         */
        get() = getCanonicalRepoName( /* includeVersion= */true)

    val canonicalRepoNameWithoutVersion: RepositoryName?
        /**
         * Returns the canonical name of the repo backing this module, excluding its version. This name is
         * only guaranteed to be unique when there is a single version of the module in the entire dep
         * graph.
         */
        get() = getCanonicalRepoName( /* includeVersion= */false)

    private fun getCanonicalRepoName(includeVersion: Boolean): RepositoryName? {
        if (WELL_KNOWN_MODULES.containsKey(name)) {
            return WELL_KNOWN_MODULES.get(name)
        }
        if (ROOT == this) {
            return RepositoryName.MAIN
        }
        val suffix: String?
        if (includeVersion) {
            // getVersion().isEmpty() is true only for modules with non-registry overrides, which enforce
            // that there is a single version of the module in the dep graph.
            com.google.common.base.Preconditions.checkState(!version.isEmpty())
            suffix = version.toString()
        } else {
            // This results in canonical repository names such as `rules_foo+` for the module `rules_foo`.
            // This particular format is chosen since:
            // * The plus ensures that canonical and apparent repository names can be distinguished even
            //   in contexts where users don't rely on `@` vs. `@@` to distinguish between them. For
            //   example, this means that the repo mapping as applied by runfiles libraries is idempotent.
            // * Appending a plus even in the case of a unique version means that module repository
            //   names always contain the same number of plus-separated components, which improves
            //   compatibility with existing logic based on the `rules_foo+1.2.3` format.
            // * By making it so that the module name and the canonical repository name of a module are
            //   never identical, even when using an override, we introduce "grease" that intentionally
            //   tickles bugs in code that doesn't properly distinguish between the two, e.g., by not
            //   applying repo mappings. Otherwise, these bugs could go unnoticed in BCR test modules and
            //   would only be discovered when used with a `multiple_version_override`, which is very
            //   rarely used.
            suffix = ""
        }
        return RepositoryName.createUnvalidated(java.lang.String.format("%s+%s", name, suffix))
    }

    val version: com.google.devtools.build.lib.bazel.bzlmod.Version?

    init {
        this.version = version
    }

    companion object {
        /**
         * A mapping from module name to repository name for certain special "well-known" modules.
         * 
         * 
         * The repository name of certain modules are required to be exact strings (instead of the
         * normal format seen in [.getCanonicalRepoName]) due to backwards compatibility
         * reasons. For example, bazel_tools must be known as "@bazel_tools" for WORKSPACE repos to work
         * correctly.
         */
        // Keep in sync with src/tools/bzlmod/utils.bzl.
        private val WELL_KNOWN_MODULES: com.google.common.collect.ImmutableMap<String?, RepositoryName?> =
            com.google.common.collect.ImmutableMap.of<String?, RepositoryName?>(
                "bazel_tools",
                RepositoryName.BAZEL_TOOLS,  // Ensures that references to "@platforms" in WORKSPACE files resolve to the repository of
                // the "platforms" module. Without this, constraints on toolchains registered in WORKSPACE
                // would reference the "platforms" repository defined in the WORKSPACE suffix, whereas
                // the host constraints generated by local_config_platform would reference the "platforms"
                // module repository, resulting in a toolchain resolution mismatch.
                "platforms",
                RepositoryName.createUnvalidated("platforms")
            )

        @kotlin.jvm.JvmField
        val ROOT: ModuleKey = ModuleKey("", com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY)

        val LEXICOGRAPHIC_COMPARATOR: java.util.Comparator<ModuleKey?>? =
            java.util.Comparator.comparing<ModuleKey?, String?>(ModuleKey::name)
                .thenComparing<com.google.devtools.build.lib.bazel.bzlmod.Version?>(ModuleKey::version)

        @Throws(com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException::class)
        fun fromString(s: String): ModuleKey? {
            if (s == "<root>") {
                return ROOT
            }
            val parts: MutableList<String?> = com.google.common.base.Splitter.on('@').splitToList(s)
            if (parts.get(1) == "_") {
                return ModuleKey(parts.get(0), com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY)
            }

            val version: com.google.devtools.build.lib.bazel.bzlmod.Version? =
                com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(parts.get(1))
            return ModuleKey(parts.get(0), version)
        }
    }
}
