// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment

/**
 * Bazel application data for the Starlark thread that evaluates the top-level code in a .bzl (or
 * .scl) module (i.e. when evaluating that module's global symbols).
 */
class BzlInitThreadContext(
    bzlFile: Label?,
    transitiveDigest: ByteArray?,
    toolsRepository: RepositoryName?,
    networkAllowlistForTests: java.util.Optional<Label?>?,
    fragmentNameToClass: com.google.common.collect.ImmutableMap<String?, java.lang.Class<*>?>?,
    mainRepoMapping: RepositoryMapping?
) : StarlarkThreadContext({ mainRepoMapping }), RuleDefinitionEnvironment {
    private val bzlFile: Label?

    /* Digest of the .bzl file being initialized along with all its transitive loads. */
    private val transitiveDigest: ByteArray?

    // For storing the result of calling `visibility()`.
    private var bzlVisibility: BzlVisibility? = null

    private val toolsRepository: RepositoryName?

    // TODO(b/192694287): Remove once we migrate all tests from the allowlist
    private val networkAllowlistForTests: java.util.Optional<Label?>?

    // Used for `configuration_field`.
    private val fragmentNameToClass: com.google.common.collect.ImmutableMap<String?, java.lang.Class<*>?>?

    /**
     * Constructs a new context for initializing a .bzl file.
     * 
     * @param bzlFile the name of the .bzl being initialized
     * @param transitiveDigest the hash of that file and its transitive load()s
     * @param toolsRepository the name of the tools repository, such as "@bazel_tools"
     * @param networkAllowlistForTests an allowlist for rule classes created by this thread
     * @param fragmentNameToClass a map from configuration fragment name to configuration fragment
     * class, such as "apple" to AppleConfiguration.class
     * @param mainRepoMapping the repository mapping of the main repository
     */
    init {
        this.bzlFile = bzlFile
        this.transitiveDigest = transitiveDigest
        this.toolsRepository = toolsRepository
        this.networkAllowlistForTests = networkAllowlistForTests
        this.fragmentNameToClass = fragmentNameToClass
    }

    /**
     * Returns the label of the .bzl module being initialized.
     * 
     * 
     * Note that this is not necessarily the same as the module of the innermost stack frame (i.e.,
     * `BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread)).label()`),
     * since the module may call helper functions loaded from elsewhere.
     */
    fun getBzlFile(): Label? {
        return bzlFile
    }

    /** Returns the transitive digest of the .bzl module being initialized.  */
    fun getTransitiveDigest(): ByteArray? {
        return transitiveDigest
    }

    /**
     * Returns the saved BzlVisibility that was declared for the currently initializing .bzl module.
     */
    fun getBzlVisibility(): BzlVisibility? {
        return bzlVisibility
    }

    /** Sets the BzlVisibility for the currently initializing .bzl module.  */
    fun setBzlVisibility(bzlVisibility: BzlVisibility?) {
        this.bzlVisibility = bzlVisibility
    }

    /** Returns the name of the tools repository, such as "@bazel_tools".  */
    public override fun getToolsRepository(): RepositoryName? {
        return toolsRepository
    }

    /** Returns a label for network allowlist for tests if one should be added.  */ // TODO(b/192694287): Remove once we migrate all tests from the allowlist.
    public override fun getNetworkAllowlistForTests(): java.util.Optional<Label?>? {
        return networkAllowlistForTests
    }

    /** Returns a map from configuration fragment name to configuration fragment class.  */
    fun getFragmentNameToClass(): com.google.common.collect.ImmutableMap<String?, java.lang.Class<*>?>? {
        return fragmentNameToClass
    }

    companion object {
        /**
         * Retrieves this context from a Starlark thread. If not present, throws `EvalException`
         * with an error message indicating that `what` can't be used in this Starlark environment.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFail(thread: net.starlark.java.eval.StarlarkThread, what: String?): BzlInitThreadContext {
            val ctx: StarlarkThreadContext? =
                thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
            if (ctx !is BzlInitThreadContext) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "%s can only be used during .bzl initialization (top-level evaluation)", what
                )
            }
            return ctx
        }

        /**
         * Retrieves this context from a Starlark thread. If not present, returns `null` instead.
         */
        fun fromOrNull(thread: net.starlark.java.eval.StarlarkThread): BzlInitThreadContext? {
            return if (thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java) is BzlInitThreadContext)
                c
            else
                null
        }
    }
}
