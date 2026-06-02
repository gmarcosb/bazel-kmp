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

import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException
import com.google.devtools.build.lib.bazel.bzlmod.Facts
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalFactors
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionMetadata
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.WithValue
import com.google.devtools.build.skyframe.SkyFunction

/**
 * An internal abstraction to support the two "flavors" of module extensions: the "regular", which
 * is declared using `module_extension` in a .bzl file; and the "innate", which is fabricated
 * from usages of `use_repo_rule` in MODULE.bazel files.
 * 
 * 
 * The general idiom is to "load" such a [RunnableExtension] object by getting as much
 * information about it as needed to determine whether it can be reused from the lockfile (hence
 * methods such as [.getEvalFactors] and [.getBzlTransitiveDigest]). Then the [ ][.run] method can be called if it's determined that we can't reuse the cached results in the
 * lockfile and have to re-run this extension.
 */
internal interface RunnableExtension {
    val evalFactors: ModuleExtensionEvalFactors?

    val bzlTransitiveDigest: ByteArray?

    /**
     * The current schema version of the facts produced by this extension. Persisted in the lockfile
     * alongside the facts and compared against the value before the extension runs: if they differ,
     * the persisted facts are discarded and the extension is invoked with empty facts.
     */
    val factsVersion: Int

    /** Runs the extension. Returns null if a Skyframe restart is required.  */
    @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
    fun run(
        env: SkyFunction.Environment?,
        usagesValue: SingleExtensionUsagesValue?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        extensionId: ModuleExtensionId?,
        mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        facts: Facts?
    ): RunModuleExtensionResult?

    /* Holds the result data from running a module extension */
    class RunModuleExtensionResult(
        recordedInputs: com.google.common.collect.ImmutableList<WithValue?>?,
        generatedRepoSpecs: com.google.common.collect.ImmutableMap<String?, RepoSpec?>?,
        moduleExtensionMetadata: ModuleExtensionMetadata?
    ) {
        val recordedInputs: com.google.common.collect.ImmutableList<WithValue?>?
        val generatedRepoSpecs: com.google.common.collect.ImmutableMap<String?, RepoSpec?>?
        val moduleExtensionMetadata: ModuleExtensionMetadata?

        init {
            this.recordedInputs = recordedInputs
            this.generatedRepoSpecs = generatedRepoSpecs
            this.moduleExtensionMetadata = moduleExtensionMetadata
        }
    }
}
