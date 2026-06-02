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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.actions.FileValue

/**
 * The function to evaluate the VENDOR.bazel file under the vendor directory specified by the flag:
 * --vendor_dir.
 */
class VendorFileFunction(starlarkEnv: BazelStarlarkEnvironment) : SkyFunction {
    private val starlarkEnv: BazelStarlarkEnvironment

    init {
        this.starlarkEnv = starlarkEnv
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        if (RepositoryDirectoryValue.VENDOR_DIRECTORY.get(env).isEmpty()) {
            throw VendorFileFunctionException(
                java.lang.IllegalStateException(
                    "VENDOR.bazel file is not accessible with vendor mode off (without --vendor_dir"
                            + " flag)"
                ),
                Transience.PERSISTENT
            )
        }

        val vendorPath: com.google.devtools.build.lib.vfs.Path =
            RepositoryDirectoryValue.VENDOR_DIRECTORY.get(env).get()
        val vendorFilePath: RootedPath =
            RootedPath.toRootedPath(Root.fromPath(vendorPath), LabelConstants.VENDOR_FILE_NAME)

        val vendorFileValue: FileValue? = env.getValue(FileValue.key(vendorFilePath)) as FileValue?
        if (vendorFileValue == null) {
            return null
        }
        if (!vendorFileValue.exists()) {
            createVendorFile(vendorPath, vendorFilePath.asPath())
            return VendorFileValue.Companion.create(
                com.google.common.collect.ImmutableList.of<RepositoryName?>(),
                com.google.common.collect.ImmutableList.of<RepositoryName?>()
            )
        }

        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = PrecomputedValue.STARLARK_SEMANTICS.get(env)
        if (starlarkSemantics == null) {
            return null
        }
        val context: VendorThreadContext =
            getVendorFileContext(env, skyKey, vendorFilePath.asPath(), starlarkSemantics)
        return VendorFileValue.Companion.create(context.getIgnoredRepos(), context.getPinnedRepos())
    }

    @Throws(VendorFileFunctionException::class, java.lang.InterruptedException::class)
    private fun getVendorFileContext(
        env: SkyFunction.Environment,
        skyKey: SkyKey?,
        vendorFilePath: com.google.devtools.build.lib.vfs.Path,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
    ): VendorThreadContext {
        try {
            net.starlark.java.eval.Mutability.create("vendor file").use { mu ->
                val vendorFile: net.starlark.java.syntax.StarlarkFile =
                    readAndParseVendorFile(vendorFilePath, env, starlarkSemantics)
                DotBazelFileSyntaxChecker("VENDOR.bazel files",  /* canLoadBzl= */false)
                    .check(vendorFile)
                val predeclaredEnv: net.starlark.java.eval.Module =
                    net.starlark.java.eval.Module.withPredeclared(
                        starlarkSemantics, starlarkEnv.getStarlarkGlobals().getVendorToplevels()
                    )
                val program: net.starlark.java.syntax.Program =
                    net.starlark.java.syntax.Program.compileFile(vendorFile, predeclaredEnv)
                val thread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.create(
                        mu,
                        starlarkSemantics,
                        "VENDOR.bazel",
                        net.starlark.java.eval.SymbolGenerator.create<SkyKey?>(skyKey)
                    )
                val context: VendorThreadContext = VendorThreadContext()
                context.storeInThread(thread)
                net.starlark.java.eval.Starlark.execFileProgram(program, predeclaredEnv, thread)
                return context
            }
        } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
            throw VendorFileFunctionException(
                BadVendorFileException("error parsing VENDOR.bazel file: " + e.getMessage()),
                Transience.PERSISTENT
            )
        } catch (e: net.starlark.java.eval.EvalException) {
            throw VendorFileFunctionException(
                BadVendorFileException("error parsing VENDOR.bazel file: " + e.getMessage()),
                Transience.PERSISTENT
            )
        }
    }

    @Throws(VendorFileFunctionException::class)
    private fun createVendorFile(
        vendorPath: com.google.devtools.build.lib.vfs.Path,
        vendorFilePath: com.google.devtools.build.lib.vfs.Path?
    ) {
        try {
            vendorPath.createDirectoryAndParents()
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
                vendorFilePath,
                java.nio.charset.StandardCharsets.ISO_8859_1,
                VENDOR_FILE_HEADER
            )
        } catch (e: IOException) {
            throw VendorFileFunctionException(
                IOException("error creating VENDOR.bazel file", e), Transience.TRANSIENT
            )
        }
    }

    /** Thrown when something is wrong with the contents of the VENDOR.bazel file.  */
    class BadVendorFileException(message: String?) : java.lang.Exception(message)

    internal class VendorFileFunctionException private constructor(e: java.lang.Exception?, transience: Transience?) :
        SkyFunctionException(e, transience)

    companion object {
        private val VENDOR_FILE_HEADER: String? = StringEncoding.unicodeToInternal(
            """
###############################################################################
# This file is used to configure how external repositories are handled in vendor mode.
# ONLY the two following functions can be used:
#
# ignore('@@<canonical repo name>', ...) is used to completely ignore this repo from vendoring.
# Bazel will use the normal external cache and fetch process for this repo.
#
# pin('@@<canonical repo name>', ...) is used to pin the contents of this repo under the vendor
# directory as if there is a --override_repository flag for this repo.
# Note that Bazel will NOT update the vendored source for this repo while running vendor command
# unless it's unpinned. The user can modify and maintain the vendored source for this repo manually.
###############################################################################

""".trimIndent()
        )

        @Throws(VendorFileFunctionException::class)
        private fun readAndParseVendorFile(
            path: com.google.devtools.build.lib.vfs.Path,
            env: SkyFunction.Environment,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
        ): net.starlark.java.syntax.StarlarkFile {
            val contents: ByteArray?
            try {
                contents =
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readWithKnownFileSize(path, path.getFileSize())
            } catch (e: IOException) {
                throw VendorFileFunctionException(
                    IOException("error reading VENDOR.bazel file", e), Transience.TRANSIENT
                )
            }
            val parserInput: net.starlark.java.syntax.ParserInput?
            try {
                parserInput =
                    com.google.devtools.build.lib.skyframe.StarlarkUtil.createParserInput(
                        contents,
                        path.getPathString(),
                        starlarkSemantics.get<Utf8EnforcementMode?>(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
                        env.getListener()
                    )
            } catch (e: InvalidUtf8Exception) {
                throw VendorFileFunctionException(
                    BadVendorFileException("error reading VENDOR.bazel file"), Transience.PERSISTENT
                )
            }
            val starlarkFile: net.starlark.java.syntax.StarlarkFile =
                net.starlark.java.syntax.StarlarkFile.parse(parserInput)
            if (!starlarkFile.ok()) {
                com.google.devtools.build.lib.events.Event.replayEventsOn(env.getListener(), starlarkFile.errors())
                throw VendorFileFunctionException(
                    BadVendorFileException("error parsing VENDOR.bazel file"), Transience.PERSISTENT
                )
            }
            return starlarkFile
        }
    }
}
