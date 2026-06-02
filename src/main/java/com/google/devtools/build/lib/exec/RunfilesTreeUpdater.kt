// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ExecException

/**
 * Utility used in local execution to create a runfiles tree if `--nobuild_runfile_links` has
 * been specified.
 * 
 * 
 * It is safe to call [.updateRunfiles] concurrently.
 */
@javax.annotation.concurrent.ThreadSafe
class RunfilesTreeUpdater(execRoot: com.google.devtools.build.lib.vfs.Path, xattrProvider: XattrProvider?) {
    private val execRoot: com.google.devtools.build.lib.vfs.Path
    private val xattrProvider: XattrProvider?

    /**
     * Deduplicates multiple attempts to update the same runfiles tree.
     * 
     * 
     * Attempts may occur concurrently, e.g. if multiple local actions have the same input.
     * 
     * 
     * The presence of an entry in the map signifies that an earlier attempt to update the
     * corresponding runfiles tree was started, and will (have) set the future upon completion.
     */
    private val updatedTrees: ConcurrentHashMap<PathFragment?, CompletableFuture<java.lang.Void?>?> =
        ConcurrentHashMap<PathFragment?, CompletableFuture<java.lang.Void?>?>()

    init {
        this.execRoot = execRoot
        this.xattrProvider = xattrProvider
    }

    /** Creates or updates input runfiles trees for a spawn.  */
    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    fun updateRunfiles(runfilesTrees: Iterable<RunfilesTree>) {
        for (tree in runfilesTrees) {
            val runfilesDir: PathFragment? = tree.getExecPath()
            if (tree.isBuildRunfileLinks()) {
                continue
            }

            val freshFuture: CompletableFuture<java.lang.Void?> = CompletableFuture<java.lang.Void?>()
            val priorFuture: CompletableFuture<java.lang.Void?>? = updatedTrees.putIfAbsent(runfilesDir, freshFuture)

            if (priorFuture == null) {
                // We are the first attempt; update the runfiles tree and mark the future complete.
                try {
                    updateRunfilesTree(tree)
                    freshFuture.complete(null)
                } catch (e: java.lang.Exception) {
                    freshFuture.completeExceptionally(e)
                    throw e
                }
            } else {
                // There was a previous attempt; wait for it to complete.
                try {
                    priorFuture.join()
                } catch (e: CompletionException) {
                    val cause: Throwable? = e.getCause()
                    if (cause != null) {
                        com.google.common.base.Throwables.throwIfInstanceOf<X?>(cause, ExecException::class.java)
                        com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(
                            cause,
                            IOException::class.java
                        )
                        com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                            cause,
                            java.lang.InterruptedException::class.java
                        )
                        com.google.common.base.Throwables.throwIfUnchecked(cause)
                    }
                    throw java.lang.AssertionError("Unexpected exception", e)
                }
            }
        }
    }

    @Throws(IOException::class, ExecException::class)
    private fun updateRunfilesTree(tree: RunfilesTree) {
        val runfilesDir: com.google.devtools.build.lib.vfs.Path = execRoot.getRelative(tree.getExecPath())
        val inputManifest: com.google.devtools.build.lib.vfs.Path =
            execRoot.getRelative(RunfilesSupport.inputManifestExecPath(tree.getExecPath()))
        if (!inputManifest.exists()) {
            return
        }
        val outputManifest: com.google.devtools.build.lib.vfs.Path =
            execRoot.getRelative(RunfilesSupport.outputManifestExecPath(tree.getExecPath()))
        try {
            // Avoid rebuilding the runfiles directory if the manifest in it matches the input manifest,
            // implying the symlinks exist and are already up to date. If the output manifest is a
            // symbolic link, it is likely a symbolic link to the input manifest, so we cannot trust it as
            // an up-to-date check.
            // On Windows, where symlinks may be silently replaced by copies, a previous run in SKIP mode
            // could have resulted in an output manifest that is an identical copy of the input manifest,
            // which we must not treat as up to date, but we also don't want to unnecessarily rebuild the
            // runfiles directory all the time. Instead, check for the presence of the first runfile in
            // the manifest. If it is present, we can be certain that the previous mode wasn't SKIP.
            if (tree.getSymlinksMode() === RunfileSymlinksMode.CREATE && !outputManifest.isSymbolicLink() && java.util.Arrays.equals(
                    com.google.devtools.build.lib.vfs.DigestUtils.getDigestWithManualFallback(
                        outputManifest,
                        xattrProvider
                    ),
                    com.google.devtools.build.lib.vfs.DigestUtils.getDigestWithManualFallback(
                        inputManifest,
                        xattrProvider
                    )
                )
                && (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS
                        || isRunfilesDirectoryPopulated(runfilesDir, outputManifest))
            ) {
                return
            }
        } catch (e: IOException) {
            // Ignore it - we will just try to create runfiles directory.
        }

        if (!runfilesDir.exists()) {
            runfilesDir.createDirectoryAndParents()
        }

        val helper: SymlinkTreeHelper =
            SymlinkTreeHelper(inputManifest, outputManifest, runfilesDir, tree.getWorkspaceName())

        when (tree.getSymlinksMode()) {
            CREATE -> {
                helper.createRunfilesSymlinks(tree.getMapping())
                helper.linkManifest()
            }

            SKIP -> helper.createMinimalRunfilesDirectory()
        }
    }

    companion object {
        fun forCommandEnvironment(env: CommandEnvironment): RunfilesTreeUpdater {
            return RunfilesTreeUpdater(env.getExecRoot(), env.getXattrProvider())
        }

        private fun isRunfilesDirectoryPopulated(
            runfilesDir: com.google.devtools.build.lib.vfs.Path,
            outputManifest: com.google.devtools.build.lib.vfs.Path
        ): Boolean {
            val relativeRunfilePath: String?
            try {
                BufferedReader(
                    java.io.InputStreamReader(
                        outputManifest.getInputStream(),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                ).use { reader ->
                    // If it is created at all, the manifest always contains at least one line.
                    relativeRunfilePath = reader.readLine().split(" ", -1)[0]
                }
            } catch (e: IOException) {
                // Instead of failing outright, just assume the runfiles directory is not populated.
                return false
            }
            // The runfile could be a dangling symlink.
            return runfilesDir.getRelative(relativeRunfilePath).exists(Symlinks.NOFOLLOW)
        }
    }
}
