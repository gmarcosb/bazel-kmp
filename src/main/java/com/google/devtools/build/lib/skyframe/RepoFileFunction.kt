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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileValue

/** The function to evaluate the REPO.bazel file at the root of a repo.  */
class RepoFileFunction(starlarkEnv: BazelStarlarkEnvironment, workspaceRoot: Root?) : SkyFunction {
    private val starlarkEnv: BazelStarlarkEnvironment
    private val workspaceRoot: Root?

    init {
        this.starlarkEnv = starlarkEnv
        this.workspaceRoot = workspaceRoot
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val repoName: RepositoryName = skyKey.argument() as RepositoryName
        // First we need to find the REPO.bazel file. How we do this depends on whether this is for the
        // main repo or an external repo.
        val repoRoot: Root?
        if (repoName.isMain()) {
            repoRoot = workspaceRoot
        } else {
            val repoDirValue: RepositoryDirectoryValue? =
                env.getValue(RepositoryDirectoryValue.key(repoName)) as RepositoryDirectoryValue?
            if (repoDirValue == null) {
                return null
            }
            when (repoDirValue) {
                -> repoRoot = s.root()
                -> throw RepoFileFunctionException(IOException(errorMsg), Transience.PERSISTENT)
            }
        }
        val repoFilePath: RootedPath = RootedPath.toRootedPath(repoRoot, LabelConstants.REPO_FILE_NAME)
        val repoFileValue: FileValue? = env.getValue(FileValue.key(repoFilePath)) as FileValue?
        if (repoFileValue == null) {
            return null
        }
        if (!repoFileValue.exists()) {
            // It's okay to not have a REPO.bazel file.
            return RepoFileValue.Companion.of(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            )
        }

        // Now we can actually evaluate the file.
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
            PrecomputedValue.Companion.STARLARK_SEMANTICS.get(env)
        if (env.valuesMissing()) {
            return null
        }

        val repoFile: net.starlark.java.syntax.StarlarkFile =
            readAndParseRepoFile(repoFilePath.asPath(), env, starlarkSemantics)
        return evalRepoFile(repoFile, repoName, starlarkSemantics, env.getListener())
    }

    @Throws(RepoFileFunctionException::class, java.lang.InterruptedException::class)
    private fun evalRepoFile(
        starlarkFile: net.starlark.java.syntax.StarlarkFile?,
        repoName: RepositoryName,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        handler: ExtendedEventHandler
    ): RepoFileValue {
        val repoDisplayName = getDisplayNameForRepo(repoName, null)
        try {
            net.starlark.java.eval.Mutability.create("repo file", repoName).use { mu ->
                DotBazelFileSyntaxChecker("REPO.bazel files",  /* canLoadBzl= */false)
                    .check(starlarkFile)
                val predeclared: net.starlark.java.eval.Module =
                    net.starlark.java.eval.Module.withPredeclared(starlarkSemantics, starlarkEnv.getRepoBazelEnv())
                val program: net.starlark.java.syntax.Program =
                    net.starlark.java.syntax.Program.compileFile(starlarkFile, predeclared)
                val thread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.create(
                        mu,
                        starlarkSemantics,
                        "REPO.bazel file of " + repoDisplayName,
                        net.starlark.java.eval.SymbolGenerator.create<Any?>(repoName)
                    )
                thread.setPrintHandler(Event.makeDebugPrintHandler(handler))
                val context: RepoThreadContext = RepoThreadContext()
                context.storeInThread(thread)
                net.starlark.java.eval.Starlark.execFileProgram(program, predeclared, thread)
                return RepoFileValue.Companion.of(context.getPackageArgsMap(), context.getIgnoredDirectories())
            }
        } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
            Event.replayEventsOn(handler, e.errors())
            throw RepoFileFunctionException(
                BadRepoFileException("error parsing REPO.bazel file for " + repoDisplayName, e)
            )
        } catch (e: net.starlark.java.eval.EvalException) {
            handler.handle(Event.error(e.getMessageWithStack()))
            throw RepoFileFunctionException(
                BadRepoFileException("error evaluating REPO.bazel file for " + repoDisplayName, e)
            )
        }
    }

    /** Thrown when something is wrong with the contents of the REPO.bazel file of a certain repo.  */
    class BadRepoFileException : java.lang.Exception {
        constructor(message: String?) : super(message)

        constructor(message: String?, cause: java.lang.Exception?) : super(message, cause)
    }

    internal class RepoFileFunctionException : SkyFunctionException {
        private constructor(e: IOException?, transience: Transience?) : super(e, transience)

        private constructor(e: BadRepoFileException?) : super(e, Transience.PERSISTENT)
    }

    companion object {
        @Throws(RepoFileFunctionException::class)
        private fun readAndParseRepoFile(
            path: com.google.devtools.build.lib.vfs.Path,
            env: SkyFunction.Environment,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
        ): net.starlark.java.syntax.StarlarkFile {
            val contents: ByteArray?
            try {
                contents =
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readWithKnownFileSize(path, path.getFileSize())
            } catch (e: IOException) {
                throw RepoFileFunctionException(
                    IOException("error reading REPO.bazel file at " + path, e), Transience.TRANSIENT
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
                throw RepoFileFunctionException(
                    BadRepoFileException("error reading REPO.bazel file at " + path)
                )
            }
            val starlarkFile: net.starlark.java.syntax.StarlarkFile =
                net.starlark.java.syntax.StarlarkFile.parse(parserInput)
            if (!starlarkFile.ok()) {
                Event.replayEventsOn(env.getListener(), starlarkFile.errors())
                throw RepoFileFunctionException(
                    BadRepoFileException("error parsing REPO.bazel file at " + path)
                )
            }
            return starlarkFile
        }

        fun getDisplayNameForRepo(
            repoName: RepositoryName, mainRepoMapping: RepositoryMapping?
        ): String {
            val displayName: String = repoName.getDisplayForm(mainRepoMapping)
            if (displayName.isEmpty()) {
                return "the main repo"
            }
            return displayName
        }
    }
}
