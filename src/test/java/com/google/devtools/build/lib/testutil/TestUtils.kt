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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.util.StringEncoding

/** Some static utility functions for testing.  */
object TestUtils {
    val ZERO_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

    /**
     * Returns the path to a fixed temporary directory, with back-slashes turned into slashes. The
     * directory is guaranteed to exist and be unique for the test *target*. Since test
     * *cases* may run in parallel, prefer using [.createUniqueTmpDir] instead, which
     * also guarantees that the directory is empty.
     */
    fun tmpDir(): String {
        return com.google.devtools.build.lib.testutil.TestUtils.tmpDirFile().getAbsolutePath().replace('\\', '/')
    }

    fun getUserValue(key: String): String? {
        var value: String? = java.lang.System.getProperty(key)
        if (value == null) {
            value = java.lang.System.getenv(key)
        }
        return value
    }

    /**
     * Returns a fixed temporary directory, guaranteed to exist and be unique for the test
     * *target*. Since test *cases* may run in parallel, prefer using [ ][.createUniqueTmpDir] instead, which also guarantees that the directory is empty.
     */
    fun tmpDirFile(): java.io.File {
        val tmpDir: java.io.File = com.google.devtools.build.lib.testutil.TestUtils.tmpDirRoot()

        // Ensure tmpDir exists
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs()
        }
        return tmpDir
    }

    /**
     * Creates a unique and empty temporary directory.
     * 
     * @param fileSystem The file system the directory should be created on. If null, uses the Java
     * file system.
     * @return A newly created directory, extremely likely to be unique.
     */
    @Throws(IOException::class)
    fun createUniqueTmpDir(fileSystem: FileSystem?): Path {
        var fileSystem: FileSystem? = fileSystem
        if (fileSystem == null) {
            fileSystem = JavaIoFileSystem(DigestHashFunction.SHA256)
        }
        val tmpDirRoot: java.io.File = com.google.devtools.build.lib.testutil.TestUtils.tmpDirRoot()
        val path: Path =
            fileSystem
                .getPath(StringEncoding.platformToInternal(tmpDirRoot.getPath()))
                .getRelative(UUID.randomUUID().toString())
        path.createDirectoryAndParents()
        return path
    }

    private fun tmpDirRoot(): java.io.File {
        var tmpDir: java.io.File // Flag value specified in environment?
        val tmpDirStr: String? = com.google.devtools.build.lib.testutil.TestUtils.getUserValue("TEST_TMPDIR")

        if (tmpDirStr != null && tmpDirStr.length > 0) {
            tmpDir = java.io.File(tmpDirStr)
        } else {
            // Fallback default $TEMP/$USER/tmp/$TESTNAME
            val baseTmpDir: String = java.lang.System.getProperty("java.io.tmpdir")
            tmpDir = java.io.File(baseTmpDir).getAbsoluteFile()

            // .. Add username
            var username: String = java.lang.System.getProperty("user.name")
            username = username.replace('/', '_')
            username = username.replace('\\', '_')
            username = username.replace('\u0000', '_')
            tmpDir = java.io.File(tmpDir, username)
            tmpDir = java.io.File(tmpDir, "tmp")
        }
        return tmpDir
    }

    val randomSeed: Int
        get() {
            // Default value if not running under framework
            var randomSeed = 301

            // Value specified in environment by framework?
            val value: String? =
                com.google.devtools.build.lib.testutil.TestUtils.getUserValue("TEST_RANDOM_SEED")
            if ((value != null) && (value.length > 0)) {
                try {
                    randomSeed = value.toInt()
                } catch (e: java.lang.NumberFormatException) {
                    throw java.lang.RuntimeException("TEST_RANDOM_SEED must be an integer", e)
                }
            }

            return randomSeed
        }

    fun makeDisappearingFileCache(path: String?): SyscallCache {
        val badPath: PathFragment? = PathFragment.create(path)
        return object : SyscallCache() {
            @Throws(IOException::class)
            public override fun readdir(path: Path?): MutableCollection<Dirent?> {
                return SyscallCache.NO_CACHE.readdir(path)
            }

            @Throws(IOException::class)
            public override fun statIfFound(path: Path, symlinks: Symlinks?): FileStatus? {
                return if (path.asFragment().endsWith(badPath))
                    null
                else
                    SyscallCache.NO_CACHE.statIfFound(path, symlinks)
            }

            public override fun getType(path: Path, symlinks: Symlinks?): DirentTypeWithSkip {
                return if (path.asFragment().endsWith(badPath))
                    DirentTypeWithSkip.FILE
                else
                    DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED
            }

            public override fun clear() {}
        }
    }

    /** Creates the assertion String to match against when a target isn't found.  */
    fun createMissingTargetAssertionString(
        target: String?, packageStr: String, workspaceRoot: String?, expectedTargets: String?
    ): String? {
        var workspaceRoot = workspaceRoot
        if (workspaceRoot == null) {
            workspaceRoot = ""
        }

        val buildFilePath = workspaceRoot + "/" + packageStr + "/BUILD"

        val fullTarget = "//" + packageStr + ":" + target

        val suggestedTargetsBaseString =
            ("no such target '%s': target '%s' not declared in package '%s' "
                    + "defined by %s"
                    + expectedTargets)

        return String.format(
            suggestedTargetsBaseString, fullTarget, target, packageStr, buildFilePath, expectedTargets
        )
    }

    /**
     * Timeouts for asserting that an arbitrary event occurs eventually.
     * 
     * 
     * In general, it's not appropriate to use a small constant timeout for an arbitrary
     * computation since there is no guarantee that a snippet of code will execute within a given
     * amount of time - you are at the mercy of the jvm, your machine, and your OS. In theory we could
     * try to take all of these factors into account but instead we took the simpler and obviously
     * correct approach of not having timeouts.
     * 
     * 
     * If a test that uses these timeout values is failing due to a "timeout" at the 'blaze test'
     * level, it could be because of a legitimate deadlock that would have been caught if the timeout
     * values below were small. So you can rule out such a deadlock by changing these values to small
     * numbers (also note that the --test_timeout blaze flag may be useful).
     */
    val WAIT_TIMEOUT_MILLISECONDS: Long = Long.Companion.MAX_VALUE

    val WAIT_TIMEOUT_SECONDS: Long = com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS / 1000

    val WAIT_TIMEOUT_DURATION: java.time.Duration? =
        java.time.Duration.ofMillis(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
}
