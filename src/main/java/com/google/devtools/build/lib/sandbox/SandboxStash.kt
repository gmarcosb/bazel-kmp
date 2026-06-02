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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.cmdline.Label

/**
 * Singleton class for the `--reuse_sandbox_directories` flag: Controls a "stash" of old sandbox
 * directories. When a sandboxed runner needs its directory tree, it first tries to grab a stash by
 * just moving it. They are separated by mnemonic because that makes them much more likely to be
 * able to reuse things common for that mnemonic, e.g. standard libraries.
 */
class SandboxStash(
    private val workspaceName: String?,
    sandboxBase: com.google.devtools.build.lib.vfs.Path,
    inMemoryStashes: Boolean
) {
    /** If true, we have already warned about an error causing us to turn off reuse.  */
    private val warnedAboutTurningOffReuse: AtomicBoolean = AtomicBoolean()

    private val sandboxBase: com.google.devtools.build.lib.vfs.Path

    private val stashPathToRunfilesDir: MutableMap<com.google.devtools.build.lib.vfs.Path?, String?> =
        ConcurrentHashMap<com.google.devtools.build.lib.vfs.Path?, String?>()

    private val stashFileListingPool: ExecutorService = Executors.newFixedThreadPool(
        POOL_SIZE,
        com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("stash-file-listing-thread-%d").build()
    )

    val pathToContents: MutableMap<com.google.devtools.build.lib.vfs.Path?, SandboxContents?> =
        ConcurrentHashMap<com.google.devtools.build.lib.vfs.Path?, SandboxContents?>()
    private val sandboxToTarget: MutableMap<com.google.devtools.build.lib.vfs.Path?, Label?> =
        ConcurrentHashMap<com.google.devtools.build.lib.vfs.Path?, Label?>()
    private val pathToLastModified: MutableMap<com.google.devtools.build.lib.vfs.Path?, Long?> =
        ConcurrentHashMap<com.google.devtools.build.lib.vfs.Path?, Long?>()
    private var inMemoryStashes: Boolean

    init {
        this.sandboxBase = sandboxBase
        this.inMemoryStashes = inMemoryStashes
    }

    private fun takeStashedSandboxInternal(
        sandboxPath: com.google.devtools.build.lib.vfs.Path,
        mnemonic: String,
        environment: MutableMap<String?, String?>,
        outputs: SandboxOutputs,
        target: Label?
    ): java.util.Optional<SandboxContents?>? {
        try {
            val sandboxes: com.google.devtools.build.lib.vfs.Path? =
                getSandboxStashDir(mnemonic, sandboxPath.getFileSystem())
            if (sandboxes == null || isTestXmlGenerationOrCoverageSpawn(mnemonic, outputs)) {
                return null
            }

            val diskStashes: MutableCollection<com.google.devtools.build.lib.vfs.Path> = sandboxes.getDirectoryEntries()
            if (diskStashes.isEmpty()) {
                return null
            }

            val stashes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.vfs.Path> =
                sortStashesByMatchingTargetSegments(target, diskStashes)
            // We have to remove the sandbox execroot dir to move a stash there, but it is currently empty
            // and we reinstate it later if we don't get a sandbox. We can't just move the stash dir
            // fully, as we would then lose siblings of the execroot dir, such as hermetic-tmp dirs.
            val sandboxExecroot: com.google.devtools.build.lib.vfs.Path = sandboxPath.getChild("execroot")
            sandboxExecroot.deleteTree()
            for (stash in stashes) {
                try {
                    val stashExecroot: com.google.devtools.build.lib.vfs.Path = stash.getChild("execroot")
                    stashExecroot.renameTo(sandboxExecroot)
                    stash.deleteTree()
                    if (isTestAction(mnemonic)) {
                        val relativeStashedRunfilesDir = stashPathToRunfilesDir.get(stashExecroot)
                        val stashedRunfilesDir: com.google.devtools.build.lib.vfs.Path =
                            sandboxExecroot.getRelative(relativeStashedRunfilesDir)
                        val relativeCurrentRunfilesDir = getCurrentRunfilesDir(environment)
                        val currentRunfiles: com.google.devtools.build.lib.vfs.Path =
                            sandboxExecroot.getRelative(relativeCurrentRunfilesDir)
                        currentRunfiles.getParentDirectory().createDirectoryAndParents()
                        stashedRunfilesDir.renameTo(currentRunfiles)
                        stashPathToRunfilesDir.remove(stashExecroot)
                        if (useInMemoryStashes() && pathToContents.containsKey(stash)) {
                            updateStashContentsAfterRunfilesMove(
                                relativeStashedRunfilesDir,
                                relativeCurrentRunfilesDir,
                                pathToContents.get(stash)
                            )
                        }
                    }
                    sandboxToTarget.remove(stash)
                    // If we switched the flag experimental_inmemory_sandbox_stashes from false to true
                    // without restarting the Bazel server, we may have a stash but not its contents in
                    // memory.
                    return if (useInMemoryStashes() && pathToContents.containsKey(stash))
                        java.util.Optional.of<SandboxContents?>(pathToContents.remove(stash))
                    else
                        java.util.Optional.empty<SandboxContents?>()
                } catch (e: FileNotFoundException) {
                    // Try the next one, somebody else took this one.
                } catch (e: IOException) {
                    turnOffReuse("Error renaming sandbox stash %s to %s: %s\n", stash, sandboxPath, e)
                    return null
                }
            }
            return null
        } catch (e: IOException) {
            turnOffReuse("Failed to prepare for reusing stashed sandbox for %s: %s", sandboxPath, e)
            return null
        }
    }

    private fun stashSandboxInternalWithInMemoryStashes(
        stashName: String?,
        sandboxes: com.google.devtools.build.lib.vfs.Path,
        path: com.google.devtools.build.lib.vfs.Path,
        mnemonic: String,
        environment: MutableMap<String?, String?>,
        treeDeleter: TreeDeleter,
        target: Label?
    ) {
        val temporaryStashes: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getChild(TEMPORARY_SANDBOX_STASH_BASE)
        val temporaryStash: com.google.devtools.build.lib.vfs.Path = temporaryStashes.getChild(stashName)
        try {
            temporaryStashes.createDirectory()
            path.getChild("execroot").renameTo(temporaryStash)
        } catch (e: IOException) {
            turnOffReuse("Error stashing sandbox at %s: %s", temporaryStash, e)
        }
        stashFileListingPool.submit(
            java.lang.Runnable {
                val stashPath: com.google.devtools.build.lib.vfs.Path = sandboxes.getChild(stashName)
                try {
                    val stashContents: SandboxContents? = pathToContents.remove(path)
                    val lastModified: Long =
                        com.google.common.base.Preconditions.checkNotNull<Long?>(pathToLastModified.remove(path))
                    SandboxHelpers.updateContentMap(temporaryStash, lastModified, stashContents)
                    stashPath.createDirectory()
                    val stashPathExecroot: com.google.devtools.build.lib.vfs.Path = stashPath.getChild("execroot")
                    if (isTestAction(mnemonic)) {
                        if (environment.get("TEST_TMPDIR").startsWith("_tmp")) {
                            treeDeleter.deleteTree(
                                temporaryStash.getRelative(environment.get("TEST_WORKSPACE") + "/_tmp")
                            )
                        }
                        // We do this before the rename operation to avoid a race condition.
                        stashPathToRunfilesDir.put(stashPathExecroot, getCurrentRunfilesDir(environment))
                    }
                    setPathContents(stashPath, stashContents)
                    temporaryStash.renameTo(stashPathExecroot)
                    if (target != null) {
                        sandboxToTarget.put(stashPath, target)
                    }
                } catch (e: java.lang.InterruptedException) {
                    // Finish the job without stashing the sandbox
                } catch (e: IOException) {
                    // TODO(bazel-team): Are we sure we don't want to surface this error?
                    turnOffReuse("Error stashing sandbox at %s: %s", stashPath, e)
                }
            })
    }

    private fun stashSandboxInternal(
        stashName: String?,
        sandboxes: com.google.devtools.build.lib.vfs.Path,
        path: com.google.devtools.build.lib.vfs.Path,
        mnemonic: String,
        environment: MutableMap<String?, String?>,
        treeDeleter: TreeDeleter,
        target: Label?
    ) {
        val stashPath: com.google.devtools.build.lib.vfs.Path = sandboxes.getChild(stashName)
        try {
            stashPath.createDirectory()
            val stashPathExecroot: com.google.devtools.build.lib.vfs.Path = stashPath.getChild("execroot")
            if (isTestAction(mnemonic)) {
                if (environment.get("TEST_TMPDIR").startsWith("_tmp")) {
                    treeDeleter.deleteTree(
                        path.getRelative("execroot/" + environment.get("TEST_WORKSPACE") + "/_tmp")
                    )
                }
            }
            if (isTestAction(mnemonic)) {
                // We do this before the rename operation to avoid a race condition.
                stashPathToRunfilesDir.put(stashPathExecroot, getCurrentRunfilesDir(environment))
            }
            path.getChild("execroot").renameTo(stashPathExecroot)
            if (target != null) {
                sandboxToTarget.put(stashPath, target)
            }
        } catch (e: IOException) {
            // Since stash names are unique, this IOException indicates some other problem with stashing,
            // so we turn it off.
            turnOffReuse("Error stashing sandbox at %s: %s", stashPath, e)
        }
    }

    /**
     * Returns the sandbox stashing directory appropriate for this mnemonic. In order to maximize
     * reuse, we keep stashed sandboxes separated by mnemonic. May return null if there are errors, in
     * which case sandbox reuse also gets turned off.
     * 
     * 
     * TODO(bazel-team): Fix integration tests to instantiate FileSystem only once, so that passing
     * it in here (to avoid the cross-filesystem precondition check in renameTo) is no longer
     * necessary.
     */
    private fun getSandboxStashDir(
        mnemonic: String?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    ): com.google.devtools.build.lib.vfs.Path? {
        val stashDir: com.google.devtools.build.lib.vfs.Path =
            getStashBase(fileSystem.getPath(this.sandboxBase.getPathString()))
        try {
            stashDir.createDirectory()
            if (!maybeClearExistingStash(stashDir)) {
                return null
            }
        } catch (e: IOException) {
            turnOffReuse(
                "Error creating sandbox stash dir %s, disabling sandbox reuse: %s\n",
                stashDir, e.message
            )
            return null
        }
        val mnemonicStashDir: com.google.devtools.build.lib.vfs.Path = stashDir.getChild(mnemonic)
        try {
            mnemonicStashDir.createDirectory()
            return mnemonicStashDir
        } catch (e: IOException) {
            turnOffReuse("Error creating mnemonic stash dir %s: %s\n", mnemonicStashDir, e.message)
            return null
        }
    }

    /**
     * Clears away existing stash if this is the first access to the stash in this Blaze server
     * instance.
     * 
     * @param stashPath Path of the stashes.
     * @return True unless there was an error deleting sandbox stashes.
     */
    private fun maybeClearExistingStash(stashPath: com.google.devtools.build.lib.vfs.Path): Boolean {
        synchronized(stash) {
            if (stash.getAndIncrement() == 0) {
                try {
                    for (directoryEntry in stashPath.getDirectoryEntries()) {
                        directoryEntry.deleteTree()
                    }
                } catch (e: IOException) {
                    turnOffReuse("Unable to clear old sandbox stash %s: %s\n", stashPath, e.message)
                    return false
                }
            }
        }
        return true
    }

    private fun turnOffReuse(fmt: String?, vararg args: Any?) {
        reuseSandboxDirectories = false
        if (warnedAboutTurningOffReuse.compareAndSet(false, true)) {
            logger.atWarning().logVarargs("Turning off sandbox reuse: " + fmt, args)
        }
    }

    private fun sortStashesByMatchingTargetSegments(
        target: Label?, stashes: MutableCollection<com.google.devtools.build.lib.vfs.Path>
    ): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.vfs.Path> {
        val sortedStashes: MutableList<com.google.devtools.build.lib.vfs.Path?> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>(stashes)
        val countMap: MutableMap<com.google.devtools.build.lib.vfs.Path?, Int?> =
            HashMap<com.google.devtools.build.lib.vfs.Path?, Int?>()
        var targetStr: Array<String?>? = null
        if (target != null) {
            targetStr = target.getPackageName().split("/")
        }
        for (stash in stashes) {
            val stashTarget: Label? = sandboxToTarget.getOrDefault(stash,  /* defaultValue= */null)
            if (target == null) {
                countMap.put(stash, if (stashTarget == null) 1 else 0)
            } else {
                countMap.put(
                    stash,
                    if (stashTarget == null)
                        0
                    else
                        java.util.Arrays.mismatch(targetStr, stashTarget.getPackageName().split("/"))
                )
            }
        }
        return com.google.common.collect.ImmutableList.sortedCopyOf<com.google.devtools.build.lib.vfs.Path?>(
            java.util.Comparator.comparingInt<Any?>(ToIntFunction { key: Any? -> countMap.get(key) }).reversed(),
            sortedStashes
        )
    }

    private fun updateStashContentsAfterRunfilesMove(
        stashedRunfiles: String?, currentRunfiles: String?, stashContents: SandboxContents
    ) {
        val stashedRunfilesSegments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.copyOf<String?>(PathFragment.create(stashedRunfiles).segments())
        var runfilesStashContents: SandboxContents = stashContents
        for (i in 0..<stashedRunfilesSegments.size - 1) {
            runfilesStashContents =
                com.google.common.base.Preconditions.checkNotNull<SandboxContents>(
                    runfilesStashContents.dirMap.get(stashedRunfilesSegments.get(i))
                )
        }
        runfilesStashContents =
            runfilesStashContents.dirMap.remove(stashedRunfilesSegments.getLast())

        val currentRunfilesSegments: com.google.common.collect.ImmutableList<String> =
            com.google.common.collect.ImmutableList.copyOf<String?>(PathFragment.create(currentRunfiles).segments())
        var currentStashContents: SandboxContents = stashContents
        for (i in 0..<currentRunfilesSegments.size - 1) {
            val segment: String = currentRunfilesSegments.get(i)
            currentStashContents.dirMap.putIfAbsent(segment, SandboxContents())
            currentStashContents = currentStashContents.dirMap.get(segment)
        }
        currentStashContents.dirMap.put(currentRunfilesSegments.getLast(), runfilesStashContents)
    }

    companion object {
        const val SANDBOX_STASH_BASE: String = "sandbox_stash"

        // Used while we gather all the contents asynchronously.
        const val TEMPORARY_SANDBOX_STASH_BASE: String = "tmp_sandbox_stash"
        private const val TEST_RUNNER_MNEMONIC = "TestRunner"
        private const val TEST_SRCDIR = "TEST_SRCDIR"
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** An incrementing count of stashes to avoid filename clashes.  */
        val stash: AtomicInteger = AtomicInteger(0)

        /**
         * Whether to attempt to reuse previously-created sandboxes. Not final because we may turn it off
         * in case of errors.
         */
        var reuseSandboxDirectories: Boolean = false

        private var instance: SandboxStash? = null
        private val POOL_SIZE: Int = java.lang.Runtime.getRuntime().availableProcessors()
        fun takeStashedSandbox(
            sandboxPath: com.google.devtools.build.lib.vfs.Path,
            mnemonic: String,
            environment: MutableMap<String?, String?>,
            outputs: SandboxOutputs,
            target: Label?
        ): java.util.Optional<SandboxContents?>? {
            if (instance == null) {
                return null
            }
            return instance!!.takeStashedSandboxInternal(sandboxPath, mnemonic, environment, outputs, target)
        }

        /** Atomically moves the sandboxPath directory aside for later reuse.  */
        fun stashSandbox(
            path: com.google.devtools.build.lib.vfs.Path,
            mnemonic: String,
            environment: MutableMap<String?, String?>,
            outputs: SandboxOutputs,
            treeDeleter: TreeDeleter,
            target: Label?
        ) {
            if (instance == null) {
                return
            }

            val sandboxes: com.google.devtools.build.lib.vfs.Path? =
                instance!!.getSandboxStashDir(mnemonic, path.getFileSystem())
            if (sandboxes == null || isTestXmlGenerationOrCoverageSpawn(mnemonic, outputs)
                || !path.exists()
            ) {
                return
            }
            val stashName = stash.incrementAndGet().toString()

            if (useInMemoryStashes()) {
                instance!!.stashSandboxInternalWithInMemoryStashes(
                    stashName, sandboxes, path, mnemonic, environment, treeDeleter, target
                )
            } else {
                instance!!.stashSandboxInternal(
                    stashName, sandboxes, path, mnemonic, environment, treeDeleter, target
                )
            }
        }

        private fun getStashBase(sandboxBase: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
            return sandboxBase.getChild(SANDBOX_STASH_BASE)
        }

        fun initialize(
            workspaceName: String?,
            sandboxBase: com.google.devtools.build.lib.vfs.Path,
            options: SandboxOptions,
            treeDeleter: TreeDeleter
        ) {
            if (options.getReuseSandboxDirectories()) {
                if (instance == null) {
                    instance =
                        SandboxStash(
                            workspaceName, sandboxBase, options.getExperimentalInMemorySandboxStashes()
                        )
                } else {
                    if (workspaceName != instance!!.workspaceName) {
                        val stashBase: com.google.devtools.build.lib.vfs.Path = getStashBase(instance!!.sandboxBase)
                        try {
                            Profiler.instance().profile("treeDeleter.deleteTree").use { c ->
                                for (directoryEntry in stashBase.getDirectoryEntries()) {
                                    treeDeleter.deleteTree(directoryEntry)
                                }
                            }
                        } catch (e: IOException) {
                            instance!!.turnOffReuse(
                                "Unable to clear old sandbox stash %s: %s\n", stashBase, e.message
                            )
                        }
                        instance =
                            SandboxStash(
                                workspaceName, sandboxBase, options.getExperimentalInMemorySandboxStashes()
                            )
                    }
                    instance!!.inMemoryStashes = options.getExperimentalInMemorySandboxStashes()
                }
            } else {
                instance = null
            }
        }

        fun useInMemoryStashes(): Boolean {
            com.google.common.base.Preconditions.checkNotNull<SandboxStash?>(instance)
            return instance!!.inMemoryStashes
        }

        fun setPathContents(path: com.google.devtools.build.lib.vfs.Path?, contents: SandboxContents?) {
            com.google.common.base.Preconditions.checkNotNull<SandboxStash?>(instance)
            instance!!.pathToContents.put(path, contents)
        }

        fun setLastModified(path: com.google.devtools.build.lib.vfs.Path?, lastModified: Long?) {
            if (instance != null) {
                instance!!.pathToLastModified.put(path, lastModified)
            }
        }

        fun gotInstance(): Boolean {
            return instance != null
        }

        fun shutdown() {
            if (instance != null) {
                instance!!.stashFileListingPool.shutdown()
            }
        }

        /** Cleans up the entire current stash, if any. Cleaning may be asynchronous.  */
        fun clean(treeDeleter: TreeDeleter?, sandboxBase: com.google.devtools.build.lib.vfs.Path) {
            var treeDeleter: TreeDeleter? = treeDeleter
            val stashDir: com.google.devtools.build.lib.vfs.Path = getStashBase(sandboxBase)
            if (!stashDir.isDirectory()) {
                return
            }
            var stashTrashDir: com.google.devtools.build.lib.vfs.Path = stashDir.getChild("__trash")
            try {
                stashDir.renameTo(stashTrashDir)
            } catch (e: IOException) {
                // If we couldn't move the stashdir away for deletion, we need to delete it synchronously
                // in place, so we can't use the treeDeleter.
                treeDeleter = null
                stashTrashDir = stashDir
            }
            try {
                if (treeDeleter != null) {
                    treeDeleter.deleteTree(stashTrashDir)
                } else {
                    stashTrashDir.deleteTree()
                }
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Failed to clean sandbox stash %s", stashDir)
            }

            if (instance != null) {
                instance!!.stashPathToRunfilesDir.clear()
                instance!!.pathToContents.clear()
                instance!!.sandboxToTarget.clear()
                instance!!.pathToLastModified.clear()
            }
        }

        /**
         * Test actions are guaranteed to have a runfiles directory with the test name as part of the
         * name. The path to the directory is unique between tests. If two tests (foo and bar) have the
         * directory <source-root>/pkg/my_runfiles as part of their runfiles and this directory contains
         * 1000 files, we would be symlinking the 1000 files for each test since the paths do not
         * coincide. To make sure we can reuse the runfiles directory we must rename the old runfiles
         * directory for the action that was stashed to the path that is expected by the current test.
        </source-root> */
        private fun isTestAction(mnemonic: String): Boolean {
            return mnemonic == TEST_RUNNER_MNEMONIC
        }

        /**
         * Test actions are split in two spawns. The first one runs the test and the second generates the
         * XML output from the test log. We do not want the second spawn to reuse the stash because it
         * doesn't contain the inputs needed to run the test; if it reused it, it would be expensive in
         * two ways: it would have to clean up all the inputs, and it would destroy a valid stash that a
         * different test could potentially use. If we are running coverage, there might be a third spawn
         * for coverage where we apply the same reasoning.
         * 
         * 
         * We identify the second and third spawn because they have a single output.
         */
        private fun isTestXmlGenerationOrCoverageSpawn(
            mnemonic: String, outputs: SandboxOutputs
        ): Boolean {
            return isTestAction(mnemonic) && outputs.files.size == 1
        }

        private fun getCurrentRunfilesDir(environment: MutableMap<String?, String?>): String {
            return environment.get("TEST_WORKSPACE") + "/" + environment.get(TEST_SRCDIR)
        }
    }
}

