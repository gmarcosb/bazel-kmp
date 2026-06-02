// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.BazelModuleContext

/** Static utility methods pertaining to restricting Starlark method invocations  */ // TODO(bazel-team): Maybe we can merge this utility class with some other existing allowlist
// helper? But it seems like a lot of existing allowlist machinery is geared toward allowlists on
// rule attributes rather than what .bzl you're in.
object BuiltinRestriction {
    private val MAIN_REPO_NAME: String? = RepositoryName.MAIN.name

    /**
     * The "default" allowlist for restricted APIs added to aid the Java to Starlark migration.
     * 
     * 
     * Entries are roughly ordered by expected call frequency (most frequent first), since [ ] checks them in order and stops at the first match.
     */
    val INTERNAL_STARLARK_API_ALLOWLIST: Allowlist = Allowlist.Companion.of( // Cc rules
        mainRepoAllowlistEntry("third_party/bazel_rules/rules_cc"),
        mainRepoAllowlistEntry("tools/build_defs/cc"),
        externalRepoAllowlistEntry("rules_cc", ""),  // Java rules

        mainRepoAllowlistEntry("third_party/bazel_rules/rules_java/java"),
        externalRepoAllowlistEntry("rules_java", "java"),  // Proto rules

        mainRepoAllowlistEntry("third_party/protobuf"),
        externalRepoAllowlistEntry("protobuf", ""),
        externalRepoAllowlistEntry("com_google_protobuf", ""),  // Rust rules

        mainRepoAllowlistEntry("devtools/rust/toolchain/testing"),
        mainRepoAllowlistEntry("third_party/bazel_rules/rules_rust/rust"),
        mainRepoAllowlistEntry("third_party/crubit"),
        externalRepoAllowlistEntry("rules_rust", "rust/private"),  // Go rules

        mainRepoAllowlistEntry("tools/build_defs/go"),  // BuildInfo

        mainRepoAllowlistEntry("tools/build_defs/build_info"),
        externalRepoAllowlistEntry("bazel_tools", "tools/build_defs/build_info"),  // Android rules

        mainRepoAllowlistEntry("bazel_internal/test_rules/cc"),
        mainRepoAllowlistEntry("tools/build_defs/android"),
        mainRepoAllowlistEntry("third_party/bazel_rules/rules_android"),
        externalRepoAllowlistEntry("rules_android", ""),
        externalRepoAllowlistEntry("build_bazel_rules_android", ""),  // Apple rules

        mainRepoAllowlistEntry("third_party/apple_crosstool"),
        mainRepoAllowlistEntry("third_party/cpptoolchains/portable_llvm/build_defs"),
        mainRepoAllowlistEntry("third_party/bazel_rules/rules_apple"),
        externalRepoAllowlistEntry("rules_apple", ""),
        externalRepoAllowlistEntry("build_bazel_rules_apple", ""),  // CUDA rules

        mainRepoAllowlistEntry("third_party/gpus/cuda"),  // Packaging rules

        mainRepoAllowlistEntry("tools/build_defs/packaging"),  // Shell rules

        externalRepoAllowlistEntry("rules_shell", ""),  // Testing

        mainRepoAllowlistEntry("test"),
        mainRepoAllowlistEntry("bazel_internal/test_rules")
    )

    /** Creates an [AllowlistEntry] in the main repository.  */
    @kotlin.jvm.JvmStatic
    fun mainRepoAllowlistEntry(packagePrefix: String?): AllowlistEntry {
        return AllowlistEntry(MAIN_REPO_NAME, PathFragment.create(packagePrefix))
    }

    /**
     * Creates an [AllowlistEntry] for an external repository. This is essentially an unresolved
     * package identifier; that is, a package identifier that has an apparent repo name in place of a
     * canonical repo name.
     */
    @kotlin.jvm.JvmStatic
    fun externalRepoAllowlistEntry(
        apparentRepoName: String, packagePrefix: String?
    ): AllowlistEntry {
        com.google.common.base.Preconditions.checkArgument(apparentRepoName != MAIN_REPO_NAME)
        return AllowlistEntry(apparentRepoName, PathFragment.create(packagePrefix))
    }

    /**
     * Throws `EvalException` if the innermost Starlark function in the given thread's call
     * stack is not defined within the builtins repository.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun failIfCalledOutsideBuiltins(thread: net.starlark.java.eval.StarlarkThread?) {
        failIfCalledOutsideAllowlist(thread, Allowlist.Companion.EMPTY)
    }

    /**
     * Throws `EvalException` if the innermost Starlark function in the given thread's call
     * stack is not defined within either 1) the builtins repository, or 2) a package or subpackage of
     * an entry in the given allowlist.
     * 
     * @throws NullPointerException if there is no currently executing Starlark function, or the
     * innermost Starlark function's module is not a .bzl file
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun failIfCalledOutsideAllowlist(thread: net.starlark.java.eval.StarlarkThread?, allowlist: Allowlist) {
        failIfModuleOutsideAllowlist(BazelModuleContext.ofInnermostBzlOrThrow(thread), allowlist)
    }

    /**
     * Throws `EvalException` if the call is made outside of the default allowlist or outside of
     * builtins.
     * 
     * @throws NullPointerException if there is no currently executing Starlark function, or the
     * innermost Starlark function's module is not a .bzl file
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun failIfCalledOutsideDefaultAllowlist(thread: net.starlark.java.eval.StarlarkThread?) {
        failIfCalledOutsideAllowlist(thread, INTERNAL_STARLARK_API_ALLOWLIST)
    }

    /**
     * Throws `EvalException` if the given [BazelModuleContext] is not within either 1)
     * the builtins repository, or 2) a package or subpackage of an entry in the given allowlist.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun failIfModuleOutsideAllowlist(
        moduleContext: BazelModuleContext, allowlist: Allowlist
    ) {
        failIfLabelOutsideAllowlist(moduleContext.label(), moduleContext.repoMapping(), allowlist)
    }

    /**
     * Throws `EvalException` if the given [Label] is not within either 1) the builtins
     * repository, or 2) a package or subpackage of an entry in the given allowlist.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun failIfLabelOutsideAllowlist(
        label: Label, repoMapping: RepositoryMapping, allowlist: Allowlist
    ) {
        if (isNotAllowed(label, repoMapping, allowlist)) {
            throw net.starlark.java.eval.Starlark.errorf("file '%s' cannot use private API", label.getCanonicalForm())
        }
    }

    /**
     * Returns true if the given [Label] is not within both 1) the builtins repository, or 2) a
     * package or subpackage of an entry in the given allowlist.
     */
    fun isNotAllowed(
        label: Label, repoMapping: RepositoryMapping, allowlist: Allowlist
    ): Boolean {
        if (label.getRepository().equals(RepositoryName.BUILTINS)) {
            return false
        }
        return !allowlist.allows(label, repoMapping)
    }

    /** An allowlist of packages that can access restricted APIs.  */
    class Allowlist private constructor(
        mainRepoEntries: com.google.common.collect.ImmutableList<AllowlistEntry?>,
        externalRepoEntries: com.google.common.collect.ImmutableList<AllowlistEntry?>
    ) {
        // Keep separate lists for main and external repo entries. This allows us to optimize checks
        // based on the incoming label's repository.
        private val mainRepoEntries: com.google.common.collect.ImmutableList<AllowlistEntry?>
        private val externalRepoEntries: com.google.common.collect.ImmutableList<AllowlistEntry?>

        init {
            this.mainRepoEntries = mainRepoEntries
            this.externalRepoEntries = externalRepoEntries
        }

        private fun allows(label: Label, repoMapping: RepositoryMapping): Boolean {
            // Check main repo entries first to reduce the chances of needing to look up repo mappings.
            if (label.getRepository().isMain() && anyAllows(mainRepoEntries, label, repoMapping)) {
                return true
            }
            return anyAllows(externalRepoEntries, label, repoMapping)
        }

        companion object {
            private val EMPTY = Allowlist(
                com.google.common.collect.ImmutableList.of<AllowlistEntry?>(),
                com.google.common.collect.ImmutableList.of<AllowlistEntry?>()
            )

            fun create(entries: MutableCollection<AllowlistEntry>): Allowlist {
                if (entries.isEmpty()) {
                    return EMPTY
                }
                val mainBuilder: com.google.common.collect.ImmutableList.Builder<AllowlistEntry?> =
                    com.google.common.collect.ImmutableList.builder<AllowlistEntry?>()
                val externalBuilder: com.google.common.collect.ImmutableList.Builder<AllowlistEntry?> =
                    com.google.common.collect.ImmutableList.builder<AllowlistEntry?>()
                for (entry in entries) {
                    if (entry.apparentRepoName == MAIN_REPO_NAME) {
                        mainBuilder.add(entry)
                    } else {
                        externalBuilder.add(entry)
                    }
                }
                return Allowlist(mainBuilder.build(), externalBuilder.build())
            }

            fun of(vararg entries: AllowlistEntry?): Allowlist {
                return create(java.util.Arrays.asList<AllowlistEntry?>(*entries))
            }

            private fun anyAllows(
                entries: com.google.common.collect.ImmutableList<AllowlistEntry?>,
                label: Label,
                repoMapping: RepositoryMapping
            ): Boolean {
                // Hot code path, avoid iterator garbage.
                for (i in entries.indices) {
                    if (entries.get(i).allows(label, repoMapping)) {
                        return true
                    }
                }
                return false
            }
        }
    }

    /** An entry in an [Allowlist].  */
    class AllowlistEntry(apparentRepoName: String?, packagePrefix: PathFragment?) {
        private fun allows(label: Label, repoMapping: RepositoryMapping): Boolean {
            return Companion.reposMatch(apparentRepoName!!, label.getRepository(), repoMapping)
                    && label.getPackageFragment().startsWith(packagePrefix)
        }

        val apparentRepoName: String?
        val packagePrefix: PathFragment?

        init {
            this.packagePrefix = packagePrefix
            this.apparentRepoName = apparentRepoName
            com.google.common.base.Preconditions.checkNotNull<String?>(apparentRepoName)
            com.google.common.base.Preconditions.checkNotNull<PathFragment?>(packagePrefix)
        }

        companion object {
            private fun reposMatch(
                allowedName: String, givenName: RepositoryName, repoMapping: RepositoryMapping
            ): Boolean {
                if (givenName.isMain()) {
                    // The main repository may be one of the allowlisted rulesets, in which case we need to fall
                    // back to interpreting allowedName as the apparent repo name. This is not a performance
                    // concern since:
                    // * In Bazel, the main repo is not expected to use private API unless it is one of the
                    //   allowlisted rulesets. For these rulesets, it is acceptable to pay the cost of a failed
                    //   RepositoryMapping lookup, which is expensive because it uses SpellChecker to construct
                    //   error messages. The only other case in which this cost is paid is if the main repo
                    //   attempts to use private APIs and subsequently fails.
                    // * In Blaze, we should virtually always hit the first branch of the disjunction below,
                    //   since Allowlist checks main repo entries first.
                    return allowedName == MAIN_REPO_NAME || repoMapping.get(allowedName).isMain()
                }
                if (givenName.equals(RepositoryName.BAZEL_TOOLS)) {
                    return allowedName == RepositoryName.BAZEL_TOOLS.name
                }
                // allowedName is a module name and givenName is a real canonical repo name, so it belongs to
                // any version of that module if and only if it contains <allowedName>+ as a prefix.
                return givenName.name.startsWith(allowedName + "+")
            }
        }
    }
}
