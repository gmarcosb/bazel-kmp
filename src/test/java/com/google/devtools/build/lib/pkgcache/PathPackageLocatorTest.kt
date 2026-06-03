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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Test package-path logic.
 */
@RunWith(JUnit4::class)
class PathPackageLocatorTest : FoundationTestCase() {
    private var buildBazelFile1A: Path? = null
    private var buildFile1B: Path? = null
    private var buildFile2C: Path? = null
    private var buildFile2CD: Path? = null
    private var buildFile2F: Path? = null
    private var buildFile2FGH: Path? = null
    private var buildBazelFile3A: Path? = null
    private var buildFile3B: Path? = null
    private var buildFile3CI: Path? = null
    private var rootDir1: Path? = null
    private var rootDir1WorkspaceFile: Path? = null
    private var rootDir2: Path? = null
    private var rootDir3ParentParent: Path? = null
    private var rootDir3: Path? = null
    private var rootDir4Parent: Path? = null
    private var rootDir4: Path? = null
    private var rootDir5: Path? = null
    private var locator: PathPackageLocator? = null
    private var locatorWithSymlinks: PathPackageLocator? = null

    protected fun getLocator(): PathPackageLocator {
        return locator
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        // Root 1:
        //   WORKSPACE
        //   /A/BUILD.bazel // This is the actual buildfile for this package.
        //   /A/BUILD       // This is a dummy buildfile and isn't used.
        //   /B/BUILD
        //   /C/I/BUILD
        //   /C/D
        //   /C/E
        //   /F/G         // This is a file, not a directory.
        //
        // Root 2:
        //   WORKSPACE
        //   /B/BUILD
        //   /C/BUILD
        //   /C/D/BUILD
        //   /F/BUILD
        //   /F/G
        //   /F/G/H/BUILD
        //   /I/BUILD         // This is a directory, not a file.
        //
        // Root 3:
        //   /usr/local/google/jrluser-foo/READONLY -> root4
        //
        // Root 4 (not used as a package root, but root 3 points to this)
        //   /A -> root1/A
        //   /B/BUILD -> root1/B/BUILD
        //   /C/I/BUILD -> root1/C/I/BUILD
        //   /C/D -> root1/C/D
        //   /C/E -> root1/C/E
        //   /F/G -> root1/F/G
        //   /H/I -> root5/H/I
        //
        // Root 5 (pointed to by Root 4)
        //   Note: the following BUILD file will be found if explicitly specified, but it
        //     would not be found if using wildcards.  That is because isDirectory
        //     will return false since the symlink target is not in the workspace.
        //   /H/I/BUILD
        rootDir1 = scratch.resolve("/home/user/src-foo/workspace")
        rootDir2 = scratch.resolve("/somewhere/1234567/build/workspace")
        rootDir3ParentParent = scratch.resolve("/usr/local/google/jrluser-foo")
        rootDir3 = rootDir3ParentParent.getRelative("READONLY/workspace")
        rootDir4Parent = scratch.resolve("/usr/local/symlinks/client_symlink_jrluser-foo")
        rootDir4 = rootDir4Parent.getRelative("workspace")
        rootDir5 = scratch.resolve("/foo/bar")

        rootDir1WorkspaceFile = scratch.file(rootDir1.toString() + "/WORKSPACE")
        buildBazelFile1A = createBuildFile(rootDir1, "A", true)
        buildFile1B = createBuildFile(rootDir1, "B")
        createBuildFile(rootDir1, "C/I")
        scratch.file(rootDir1.getPathString() + "/F/G")

        rootDir1.getRelative("C").createDirectory()
        rootDir1.getRelative("C/D").createDirectory()
        rootDir1.getRelative("C/E").createDirectory()

        // Workspace file in rootDir2.
        scratch.file(rootDir2.toString() + "/WORKSPACE")
        createBuildFile(rootDir2, "B")
        buildFile2C = createBuildFile(rootDir2, "C")
        buildFile2CD = createBuildFile(rootDir2, "C/D")
        buildFile2F = createBuildFile(rootDir2, "F")
        buildFile2FGH = createBuildFile(rootDir2, "F/G/H")
        scratch.file(rootDir2.getPathString() + "/C/I")

        // Root3 just needs a symlink to 4
        FileSystemUtils.ensureSymbolicLink(
            rootDir3ParentParent.getRelative("READONLY"), rootDir4Parent
        )
        buildBazelFile3A = rootDir3.getRelative("A/BUILD.bazel")
        buildFile3B = rootDir3.getRelative("B/BUILD")
        buildFile3CI = rootDir3.getRelative("C/I/BUILD")

        // Root4
        FileSystemUtils.ensureSymbolicLink(
            rootDir4.getRelative("A"), rootDir1.getRelative("A")
        )
        FileSystemUtils.ensureSymbolicLink(
            rootDir4.getRelative("B/BUILD"), rootDir1.getRelative("B/BUILD")
        )
        FileSystemUtils.ensureSymbolicLink(
            rootDir4.getRelative("C/I/BUILD"), rootDir1.getRelative("C/I/BUILD")
        )
        FileSystemUtils.ensureSymbolicLink(
            rootDir4.getRelative("C/D/BUILD"), rootDir1.getRelative("C/D/BUILD")
        )
        FileSystemUtils.ensureSymbolicLink(
            rootDir4.getRelative("C/E/BUILD"), rootDir1.getRelative("C/E/BUILD")
        )
        FileSystemUtils.ensureSymbolicLink(
            rootDir4.getRelative("F/G/BUILD"), rootDir1.getRelative("F/G/BUILD")
        )
        FileSystemUtils.ensureSymbolicLink(
            rootDir4.getRelative("H/I"), rootDir5.getRelative("H/I")
        )

        // Root5
        createBuildFile(rootDir5, "H/I")

        locator =
            PathPackageLocator(
                outputBase,
                com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDir1), Root.fromPath(rootDir2)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        locatorWithSymlinks =
            PathPackageLocator(
                outputBase,
                com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDir3)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
    }

    @Throws(IOException::class)
    private fun createBuildFile(workspace: Path?, packageName: String?): Path {
        return createBuildFile(workspace, packageName, false)
    }

    @Throws(IOException::class)
    private fun createBuildFile(workspace: Path?, packageName: String?, dotBazel: Boolean): Path {
        val buildFileName = if (dotBazel) "BUILD.bazel" else "BUILD"
        return scratch.file(workspace.toString() + "/" + packageName + "/" + buildFileName)
    }

    private fun checkNoPackage(packageName: String?) {
        checkNoPackage(getLocator(), packageName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetPackageBuildFile() {
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("A"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildBazelFile1A)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("A"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildBazelFile1A)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("B"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile1B)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("B"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile1B)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("C"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile2C)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("C"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile2C)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("C/D"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile2CD)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("C/D"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile2CD)
        checkNoPackage("C/E")
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("C/E"), SyscallCache.NO_CACHE
            )
        )
            .isNull()
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("F"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile2F)
        checkNoPackage("F/G")
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("F/G"), SyscallCache.NO_CACHE
            )
        )
            .isNull()
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("F/G/H"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile2FGH)
        assertThat(
            locator.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("F/G/H"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile2FGH)
        checkNoPackage("I")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetPackageBuildFileWithSymlinks() {
        assertThat(
            locatorWithSymlinks.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("A"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildBazelFile3A)
        assertThat(
            locatorWithSymlinks.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("B"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile3B)
        assertThat(
            locatorWithSymlinks.getPackageBuildFileNullable(
                PackageIdentifier.createInMainRepo("C/I"), SyscallCache.NO_CACHE
            )
        )
            .isEqualTo(buildFile3CI)
        checkNoPackage(locatorWithSymlinks, "C/D")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetWorkspaceFile() {
        assertThat(locator.getWorkspaceFile(SyscallCache.NO_CACHE)).isEqualTo(rootDir1WorkspaceFile)
    }

    private fun setLocator(root: String?): Path {
        val nonExistentRoot: Path = scratch.resolve(root)
        this.locator =
            PathPackageLocator.create( /*outputBase=*/
                null,
                java.util.Arrays.< T > asList < T ? > (root),
                reporter,  /*workspace=*/
                FileSystemUtils.getWorkingDirectory(),  /* clientWorkingDirectory= */
                FileSystemUtils.getWorkingDirectory(
                    scratch.getFileSystem()
                ),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        return nonExistentRoot
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonexistentRoot() {
        val nonExistentRoot1: Path = setLocator("/non/existent/1/workspace")
        createBuildFile(nonExistentRoot1, "X")
        // Now let's create the root:
        // The package isn't found
        checkNoPackage("X")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathResolution() {
        val workspace: Path = scratch.dir("/some/path/to/workspace")
        val clientPath: Path = workspace.getRelative("somewhere/below/workspace")
        scratch.dir(clientPath.getPathString())
        val belowClient: Path = clientPath.getRelative("below/client")
        scratch.dir(belowClient.getPathString())

        val pathElements: MutableList<String?> = com.google.common.collect.ImmutableList.of<E?>(
            "./below/client",  // Client-relative
            ".",  // Client-relative
            "%workspace%/somewhere",  // Workspace-relative
            // Absolute
            clientPath.getRelative("below").getPathString()
        )
        assertThat(
            PathPackageLocator.create( /*outputBase=*/
                null,
                pathElements,
                reporter,
                workspace.asFragment(),
                clientPath,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
                .getPathEntries()
        )
            .containsExactly(
                Root.fromPath(belowClient),
                Root.fromPath(clientPath),
                Root.fromPath(workspace.getRelative("somewhere")),
                Root.fromPath(clientPath.getRelative("below"))
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativePathWarning() {
        val workspace: Path = scratch.dir("/some/path/to/workspace")

        // No warning if workspace == cwd.
        PathPackageLocator.create( /*outputBase=*/
            null,
            com.google.common.collect.ImmutableList.of<E?>("./foo"),
            reporter,
            workspace.asFragment(),
            workspace,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
        )
        Truth.assertThat(eventCollector.count()).isSameInstanceAs(0)

        PathPackageLocator.create( /*outputBase=*/
            null,
            com.google.common.collect.ImmutableList.of<E?>("./foo"),
            reporter,
            workspace.asFragment(),
            workspace.getRelative("foo"),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
        )
        Truth.assertThat(eventCollector.count()).isSameInstanceAs(1)
        assertContainsEvent("The package path element 'foo' will be taken relative")
    }

    /** Regression test for bug: "IllegalArgumentException in PathPackageLocator.create()"  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDollarSigns() {
        val workspace: Path = scratch.dir("/some/path/to/workspace$1")

        PathPackageLocator.create( /*outputBase=*/
            null,
            com.google.common.collect.ImmutableList.of<E?>("%workspace%/blabla"),
            reporter,
            workspace.asFragment(),
            workspace.getRelative("foo"),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
        )
    }

    companion object {
        private fun checkNoPackage(locator: PathPackageLocator, packageName: String?) {
            assertThat(
                locator.getPackageBuildFileNullable(
                    PackageIdentifier.createInMainRepo(packageName), SyscallCache.NO_CACHE
                )
            )
                .isNull()
        }
    }
}
