// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec


import com.google.devtools.build.lib.actions.ActionInput

/**
 * Maintains a mapping between relative path (from the execution root) to [ActionInput], for
 * various auxiliary binaries used during action execution (alarm. etc).
 */
class BinTools private constructor(
    embeddedBinariesRoot: com.google.devtools.build.lib.vfs.Path,
    embeddedToolNames: com.google.common.collect.ImmutableList<String?>
) {
    private val embeddedBinariesRoot: com.google.devtools.build.lib.vfs.Path
    private val actionInputs: com.google.common.collect.ImmutableMap<String?, ActionInput?>

    init {
        this.embeddedBinariesRoot = embeddedBinariesRoot

        val builder: com.google.common.collect.ImmutableMap.Builder<String?, ActionInput?> =
            com.google.common.collect.ImmutableMap.builder<String?, ActionInput?>()
        for (toolName in embeddedToolNames) {
            val path: com.google.devtools.build.lib.vfs.Path = embeddedBinariesRoot.getRelative(toolName)
            val execPath: PathFragment = PathFragment.create("_bin").getRelative(toolName)
            builder.put(toolName, com.google.devtools.build.lib.exec.BinTools.PathActionInput(path, execPath))
        }
        actionInputs = builder.buildOrThrow()
    }


    /**
     * Returns an action input for the given embedded tool.
     */
    fun getActionInput(embeddedPath: String?): ActionInput? {
        return actionInputs.get(embeddedPath)
    }

    fun getEmbeddedPath(embedPath: String?): com.google.devtools.build.lib.vfs.Path? {
        if (!actionInputs.containsKey(embedPath)) {
            return null
        }
        return embeddedBinariesRoot.getRelative(embedPath)
    }

    /** An ActionInput pointing at an absolute path.  */
    @com.google.common.annotations.VisibleForTesting
    class PathActionInput(path: com.google.devtools.build.lib.vfs.Path, execPath: PathFragment) : VirtualActionInput() {
        private val lock: ReentrantLock = ReentrantLock()
        private val path: com.google.devtools.build.lib.vfs.Path
        private val execPath: PathFragment

        @get:Throws(IOException::class)
        @kotlin.concurrent.Volatile
        var metadata: FileArtifactValue? = null
            get() {
                // We intentionally delay hashing until it is necessary.
                if (field == null) {
                    lock.lock()
                    try {
                        if (field == null) {
                            field = com.google.devtools.build.lib.exec.BinTools.PathActionInput.Companion.hash(path)
                        }
                    } finally {
                        lock.unlock()
                    }
                }
                return field
            }
            private set

        /** Contains the digest of the input once it has been written.  */
        @kotlin.concurrent.Volatile
        private var digest: ByteArray?

        init {
            this.path = path
            this.execPath = execPath
        }

        @Throws(IOException::class)
        public override fun writeTo(out: java.io.OutputStream) {
            path.getInputStream().use { `in` ->
                com.google.common.io.ByteStreams.copy(`in`, out)
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(IOException::class)
        protected override fun atomicallyWriteTo(outputPath: com.google.devtools.build.lib.vfs.Path): ByteArray? {
            // The embedded tools do not change, but we need to be sure they're written out without race
            // conditions. We rely on the fact that no two {@link PathActionInput} instances refer to the
            // same file to use in-memory synchronization and avoid writing to a temporary file first.
            if (digest == null || !outputPath.exists()) {
                lock.lock()
                try {
                    if (digest == null || !outputPath.exists()) {
                        outputPath.getParentDirectory().createDirectoryAndParents()
                        digest = writeTo(outputPath)
                        // Some of the embedded tools are executable.
                        outputPath.setExecutable(true)
                    }
                } finally {
                    lock.unlock()
                }
            }
            return digest
        }

        val execPathString: String?
            get() = execPath.getPathString()

        public override fun getExecPath(): PathFragment {
            return execPath
        }

        companion object {
            @Throws(IOException::class)
            private fun hash(path: com.google.devtools.build.lib.vfs.Path): FileArtifactValue {
                val hashFn: DigestHashFunction = path.getFileSystem().getDigestFunction()
                val hasher: com.google.common.hash.Hasher = hashFn.getHashFunction().newHasher()
                var bytesCopied = 0
                path.getInputStream().use { `in` ->
                    val buffer = ByteArray(1024)
                    var len: Int
                    while ((`in`.read(buffer).also { len = it }) > 0) {
                        hasher.putBytes(buffer, 0, len)
                        bytesCopied += len
                    }
                }
                return FileArtifactValue.createForVirtualActionInput(
                    hasher.hash().asBytes(),
                    bytesCopied
                )
            }
        }
    }

    companion object {
        /**
         * Creates an instance with the list of embedded tools obtained from scanning the directory
         * into which said binaries were extracted by the launcher.
         */
        @Throws(IOException::class)
        fun forProduction(directories: BlazeDirectories): BinTools {
            val embeddedBinariesRoot: com.google.devtools.build.lib.vfs.Path = directories.getEmbeddedBinariesRoot()
            // All tools of interest are in the root directory, so don't scan subdirectories.
            val builder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (dirent in embeddedBinariesRoot.readdir(Symlinks.NOFOLLOW)) {
                if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.FILE) {
                    builder.add(dirent.getName())
                }
            }
            return BinTools(embeddedBinariesRoot, builder.build())
        }

        /**
         * Creates an empty instance for testing.
         */
        @com.google.common.annotations.VisibleForTesting
        fun empty(directories: BlazeDirectories): BinTools {
            return BinTools(
                directories.getEmbeddedBinariesRoot(),
                com.google.common.collect.ImmutableList.of<String?>()
            )
        }

        /**
         * Creates an instance for testing with the given embedded binaries root.
         */
        @com.google.common.annotations.VisibleForTesting
        fun forEmbeddedBin(
            embeddedBinariesRoot: com.google.devtools.build.lib.vfs.Path,
            tools: Iterable<String?>
        ): BinTools {
            return BinTools(embeddedBinariesRoot, com.google.common.collect.ImmutableList.copyOf<String?>(tools))
        }

        /**
         * Creates an instance for testing without actually symlinking the tools.
         * 
         * 
         * Used for tests that need a set of embedded tools to be present, but not the actual files.
         */
        @com.google.common.annotations.VisibleForTesting
        fun forUnitTesting(execroot: com.google.devtools.build.lib.vfs.Path, tools: Iterable<String?>): BinTools {
            return BinTools(
                execroot.getRelative("/fake/embedded/tools"),
                com.google.common.collect.ImmutableList.copyOf<String?>(tools)
            )
        }

        /**
         * Returns a BinTools instance. Before calling this method, you have to populate the
         * [BlazeDirectories.getEmbeddedBinariesRoot] directory.
         */
        @com.google.common.annotations.VisibleForTesting
        fun forIntegrationTesting(
            directories: BlazeDirectories, tools: Iterable<String?>
        ): BinTools {
            return BinTools(
                directories.getEmbeddedBinariesRoot(),
                com.google.common.collect.ImmutableList.copyOf<String?>(tools)
            )
        }
    }
}
