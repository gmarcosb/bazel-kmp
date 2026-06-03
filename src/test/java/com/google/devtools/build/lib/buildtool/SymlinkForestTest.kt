// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.cmdline.LabelConstants

/** Tests [SymlinkForest].  */
@RunWith(JUnit4::class)
class SymlinkForestTest {
    private var fileSystem: FileSystem? = null

    private var topDir: Path? = null
    private var file1: Path? = null
    private var file2: Path? = null
    private var aDir: Path? = null

    // The execution root.
    private var linkRoot: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeFileSystem() {
        val clock: com.google.devtools.build.lib.testutil.ManualClock =
            com.google.devtools.build.lib.testutil.ManualClock()
        fileSystem = InMemoryFileSystem(clock, DigestHashFunction.SHA256)
        linkRoot = fileSystem.getPath("/linkRoot")
        linkRoot.createDirectoryAndParents()
    }

    /*
   * Build a directory tree that looks like:
   *   top-dir/
   *     file-1
   *     file-2
   *     a-dir/
   *       file-3
   *       inner-dir/
   *         link-1 => file-4
   *         dir-link => b-dir
   *   file-4
   */
    @Throws(IOException::class)
    private fun createTestDirectoryTree() {
        topDir = fileSystem.getPath("/top-dir")
        file1 = fileSystem.getPath("/top-dir/file-1")
        file2 = fileSystem.getPath("/top-dir/file-2")
        aDir = fileSystem.getPath("/top-dir/a-dir")
        val bDir: Path = fileSystem.getPath("/top-dir/b-dir")
        val file3: Path? = fileSystem.getPath("/top-dir/a-dir/file-3")
        val innerDir: Path = fileSystem.getPath("/top-dir/a-dir/inner-dir")
        val link1: Path = fileSystem.getPath("/top-dir/a-dir/inner-dir/link-1")
        val dirLink: Path = fileSystem.getPath("/top-dir/a-dir/inner-dir/dir-link")
        val file4: Path? = fileSystem.getPath("/file-4")
        val file5: Path? = fileSystem.getPath("/top-dir/b-dir/file-5")

        topDir.createDirectory()
        FileSystemUtils.createEmptyFile(file1)
        FileSystemUtils.createEmptyFile(file2)
        aDir.createDirectory()
        bDir.createDirectory()
        FileSystemUtils.createEmptyFile(file3)
        innerDir.createDirectory()
        link1.createSymbolicLink(file4) // simple symlink
        dirLink.createSymbolicLink(bDir)
        FileSystemUtils.createEmptyFile(file4)
        FileSystemUtils.createEmptyFile(file5)
    }

    @org.junit.Test
    fun testLongestPathPrefix() {
        val a: PathFragment = PathFragment.create("A")
        assertThat(longestPathPrefix("A/b", "A", "B")).isEqualTo(a) // simple parent
        assertThat(longestPathPrefix("A", "A", "B")).isEqualTo(a) // self
        assertThat(longestPathPrefix("A/B/c", "A", "A/B"))
            .isEqualTo(a.getRelative("B")) // want longest
        assertThat(longestPathPrefix("C/b", "A", "B")).isNull() // not found in other parents
        assertThat(longestPathPrefix("A", "A/B", "B")).isNull() // not found in child
        assertThat(longestPathPrefix("A/B/C/d/e/f.h", "A/B/C", "B/C/d"))
            .isEqualTo(a.getRelative("B/C"))
        assertThat(longestPathPrefix("A/f.h", "", "B/C/d")).isEqualTo(PathFragment.EMPTY_FRAGMENT)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testDeleteTreesBelowNotPrefixed() {
        createTestDirectoryTree()
        SymlinkForest.deleteTreesBelowNotPrefixed(topDir, "file-")
        assertThat(file1.exists()).isTrue()
        assertThat(file2.exists()).isTrue()
        assertThat(aDir.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlantLinkForestWithMultiplePackagePath() {
        val rootA: Root? = Root.fromPath(fileSystem.getPath("/A"))
        val rootB: Root? = Root.fromPath(fileSystem.getPath("/B"))

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createPkg(rootA, rootB, "pkgA"), rootA)
                .put(createPkg(rootA, rootB, "dir1/pkgA"), rootA)
                .put(createPkg(rootA, rootB, "dir1/pkgB"), rootB)
                .put(createPkg(rootA, rootB, "dir2/pkg"), rootA)
                .put(createPkg(rootA, rootB, "dir2/pkg/pkg"), rootB)
                .put(createPkg(rootA, rootB, "pkgB"), rootB)
                .put(createPkg(rootA, rootB, "pkgB/dir/pkg"), rootA)
                .put(createPkg(rootA, rootB, "pkgB/pkg"), rootA)
                .put(createPkg(rootA, rootB, "pkgB/pkg/pkg"), rootA)
                .buildOrThrow()
        createPkg(rootA, rootB, "pkgB/dir") // create a file in there

        val linkRoot: Path = fileSystem.getPath("/linkRoot")
        linkRoot.createDirectoryAndParents()
        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, false)
                .plantSymlinkForest()

        assertLinksTo(linkRoot, rootA, "pkgA")
        assertIsDir(linkRoot, "dir1")
        assertLinksTo(linkRoot, rootA, "dir1/pkgA")
        assertLinksTo(linkRoot, rootB, "dir1/pkgB")
        assertIsDir(linkRoot, "dir2")
        assertIsDir(linkRoot, "dir2/pkg")
        assertLinksTo(linkRoot, rootA, "dir2/pkg/file")
        assertLinksTo(linkRoot, rootB, "dir2/pkg/pkg")
        assertIsDir(linkRoot, "pkgB")
        assertIsDir(linkRoot, "pkgB/dir")
        assertLinksTo(linkRoot, rootB, "pkgB/dir/file")
        assertLinksTo(linkRoot, rootA, "pkgB/dir/pkg")
        assertLinksTo(linkRoot, rootA, "pkgB/pkg")
        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getRelative("pkgA"),
                linkRoot.getRelative("dir1/pkgA"),
                linkRoot.getRelative("dir1/pkgB"),
                linkRoot.getRelative("dir2/pkg/file"),
                linkRoot.getRelative("dir2/pkg/pkg"),
                linkRoot.getRelative("pkgB/file"),
                linkRoot.getRelative("pkgB/dir/file"),
                linkRoot.getRelative("pkgB/dir/pkg"),
                linkRoot.getRelative("pkgB/pkg")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackage() {
        val rootX: Root? = Root.fromPath(fileSystem.getPath("/X"))
        val rootY: Root? = Root.fromPath(fileSystem.getPath("/Y"))
        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createPkg(rootX, rootY, ""), rootX)
                .put(createPkg(rootX, rootY, "foo"), rootX)
                .buildOrThrow()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, false)
                .plantSymlinkForest()
        assertLinksTo(linkRoot, rootX, "file")
        Truth.assertThat(plantedSymlinks)
            .containsExactly(linkRoot.getRelative("file"), linkRoot.getRelative("foo"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlantSymlinkForest() {
        val outputBase: Root = Root.fromPath(fileSystem.getPath("/ob"))
        val mainRepo: Root = Root.fromPath(fileSystem.getPath("/my_repo"))
        val linkRoot: Path = outputBase.getRelative("execroot/ws_name")

        mainRepo.asPath().createDirectoryAndParents()
        linkRoot.createDirectoryAndParents()

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createMainPkg(mainRepo, "dir_main"), mainRepo)
                .put(createMainPkg(mainRepo, "dir_lib/pkg"), mainRepo)
                .put(createMainPkg(mainRepo, ""), mainRepo) // Remote repo without top-level package.
                .put(
                    createExternalPkg(outputBase, "X", "dir_x/pkg"),
                    externalSourceRoot(outputBase, "X")
                ) // Remote repo with and without top-level package.
                .put(createExternalPkg(outputBase, "Y", ""), externalSourceRoot(outputBase, "Y"))
                .put(
                    createExternalPkg(outputBase, "Y", "dir_y/pkg"),
                    externalSourceRoot(outputBase, "Y")
                ) // Only top-level pkg.
                .put(createExternalPkg(outputBase, "Z", ""), externalSourceRoot(outputBase, "Z"))
                .build()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, false)
                .plantSymlinkForest()

        assertLinksTo(linkRoot, mainRepo, "dir_main")
        assertLinksTo(linkRoot, mainRepo, "dir_lib")
        assertLinksTo(linkRoot, mainRepo, "file")
        assertLinksToExternalRepo(linkRoot, outputBase, "X")
        assertLinksToExternalRepo(linkRoot, outputBase, "Y")
        assertLinksToExternalRepo(linkRoot, outputBase, "Z")
        assertThat(
            linkRoot
                .getRelative(LabelConstants.EXTERNAL_PATH_PREFIX)
                .getRelative("Y/file")
                .exists()
        )
            .isTrue()
        assertThat(
            linkRoot
                .getRelative(LabelConstants.EXTERNAL_PATH_PREFIX)
                .getRelative("Z/file")
                .exists()
        )
            .isTrue()
        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getRelative("dir_main"),
                linkRoot.getRelative("dir_lib"),
                linkRoot.getRelative("file"),
                linkRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX + "/X"),
                linkRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX + "/Y"),
                linkRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX + "/Z")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_withSiblingRepoLayout_plantSymlinkForest() {
        val outputBase: Root = Root.fromPath(fileSystem.getPath("/ob"))
        val mainRepo: Root = Root.fromPath(fileSystem.getPath("/my_repo"))
        val linkRoot: Path = outputBase.getRelative("execroot/ws_name")

        mainRepo.asPath().createDirectoryAndParents()
        linkRoot.createDirectoryAndParents()

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createMainPkg(mainRepo, "dir_main"), mainRepo)
                .put(createMainPkg(mainRepo, "dir_lib/pkg"), mainRepo)
                .put(createMainPkg(mainRepo, ""), mainRepo) // Remote repo without top-level package.
                .put(
                    createExternalPkg(outputBase, "X", "dir_x/pkg"),
                    externalSourceRoot(outputBase, "X")
                ) // Remote repo with and without top-level package.
                .put(createExternalPkg(outputBase, "Y", ""), externalSourceRoot(outputBase, "Y"))
                .put(
                    createExternalPkg(outputBase, "Y", "dir_y/pkg"),
                    externalSourceRoot(outputBase, "Y")
                ) // Only top-level pkg.
                .put(createExternalPkg(outputBase, "Z", ""), externalSourceRoot(outputBase, "Z"))
                .build()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, true)
                .plantSymlinkForest()

        // Expected sibling repository layout (X, Y and Z are siblings of ws_name):
        //
        // .
        // ├── execroot
        // │   ├── ws_name { ... }
        // │   ├── X -> external/X
        // │   ├── Y -> external/Y
        // │   └── Z -> external/Z
        // └── external
        //     ├── X
        //     ├── Y
        //     └── Z
        assertLinksTo(linkRoot, mainRepo, "dir_main")
        assertLinksTo(linkRoot, mainRepo, "dir_lib")
        assertLinksTo(linkRoot, mainRepo, "file")
        assertLinksTo(
            linkRoot.getParentDirectory().getRelative("X"),
            outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION + "/X")
        )
        assertLinksTo(
            linkRoot.getParentDirectory().getRelative("Y"),
            outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION + "/Y")
        )
        assertLinksTo(
            linkRoot.getParentDirectory().getRelative("Z"),
            outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION + "/Z")
        )
        assertThat(linkRoot.getParentDirectory().getRelative("Y/file").exists()).isTrue()
        assertThat(linkRoot.getParentDirectory().getRelative("Z/file").exists()).isTrue()
        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getRelative("dir_main"),
                linkRoot.getRelative("dir_lib"),
                linkRoot.getRelative("file"),
                linkRoot.getParentDirectory().getRelative("X"),
                linkRoot.getParentDirectory().getRelative("Y"),
                linkRoot.getParentDirectory().getRelative("Z")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlantSymlinkForestForMainRepo() {
        // For the main repo, plantSymlinkForest function should only link all files and dirs under
        // main repo root that're presented in packageRootMap.
        val outputBase: Root = Root.fromPath(fileSystem.getPath("/ob"))
        val mainRepo: Root = Root.fromPath(fileSystem.getPath("/my_repo"))
        val linkRoot: Path = outputBase.getRelative("execroot/ws_name")

        linkRoot.createDirectoryAndParents()
        mainRepo.asPath().createDirectoryAndParents()
        mainRepo.getRelative("dir4").createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(mainRepo.getRelative("file"))

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createMainPkg(mainRepo, "dir1/pkg/foo"), mainRepo)
                .put(createMainPkg(mainRepo, "dir2/pkg"), mainRepo)
                .put(createMainPkg(mainRepo, "dir3"), mainRepo)
                .put(
                    createExternalPkg(outputBase, "X", "dir_x/pkg"),
                    externalSourceRoot(outputBase, "X")
                )
                .buildOrThrow()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, false)
                .plantSymlinkForest()

        assertLinksTo(linkRoot, mainRepo, "dir1")
        assertLinksTo(linkRoot, mainRepo, "dir2")
        assertLinksTo(linkRoot, mainRepo, "dir3")
        // dir4 and the file under main repo root should not be linked
        // because they are not presented in packageRootMap.
        assertThat(linkRoot.getChild("dir4").exists()).isFalse()
        assertThat(linkRoot.getChild("file").exists()).isFalse()
        assertLinksToExternalRepo(linkRoot, outputBase, "X")
        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getRelative("dir1"),
                linkRoot.getRelative("dir2"),
                linkRoot.getRelative("dir3"),
                linkRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX + "/X")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_withSubdirRepoLayout_testExternalDirInMainRepoIsIgnored1() {
        // Test external/ is ignored even when packages like "//external/foo" is specified.
        val outputBase: Root = Root.fromPath(fileSystem.getPath("/ob"))
        val mainRepo: Root = Root.fromPath(fileSystem.getPath("/my_repo"))
        val linkRoot: Path = outputBase.getRelative("execroot/ws_name")

        linkRoot.createDirectoryAndParents()
        mainRepo.asPath().createDirectoryAndParents()

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createMainPkg(mainRepo, "dir1/pkg/foo"), mainRepo)
                .put(createMainPkg(mainRepo, "dir2/pkg"), mainRepo)
                .put(
                    createMainPkg(mainRepo, "dir3"),
                    mainRepo
                ) // external/ should not be linked even we have "//external/foo" package
                .put(createMainPkg(mainRepo, "external/foo"), mainRepo)
                .put(
                    createExternalPkg(outputBase, "X", "dir_x/pkg"),
                    externalSourceRoot(outputBase, "X")
                )
                .build()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, false)
                .plantSymlinkForest()

        assertLinksTo(linkRoot, mainRepo, "dir1")
        assertLinksTo(linkRoot, mainRepo, "dir2")
        assertLinksTo(linkRoot, mainRepo, "dir3")
        assertLinksToExternalRepo(linkRoot, outputBase, "X")
        assertThat(outputBase.getRelative("external/foo").exists()).isFalse()
        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getRelative("dir1"),
                linkRoot.getRelative("dir2"),
                linkRoot.getRelative("dir3"),
                linkRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX + "/X")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_withSubDirRepoLayout_testExternalDirInMainRepoIsIgnored2() {
        // Test external/ is ignored when root package "//:" is specified.
        val outputBase: Root = Root.fromPath(fileSystem.getPath("/ob"))
        val mainRepo: Root = Root.fromPath(fileSystem.getPath("/my_repo"))
        val linkRoot: Path = outputBase.getRelative("execroot/ws_name")

        linkRoot.createDirectoryAndParents()
        mainRepo.asPath().createDirectoryAndParents()
        mainRepo.getRelative("dir3").createDirectoryAndParents()
        mainRepo.getRelative("external/foo").createDirectoryAndParents()

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createMainPkg(mainRepo, "dir1/pkg/foo"), mainRepo)
                .put(
                    createMainPkg(mainRepo, "dir2/pkg"),
                    mainRepo
                ) // Empty package will cause every top-level files to be linked, except external/
                .put(createMainPkg(mainRepo, ""), mainRepo)
                .put(
                    createExternalPkg(outputBase, "X", "dir_x/pkg"),
                    externalSourceRoot(outputBase, "X")
                )
                .build()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, false)
                .plantSymlinkForest()

        assertLinksTo(linkRoot, mainRepo, "dir1")
        assertLinksTo(linkRoot, mainRepo, "dir2")
        assertLinksTo(linkRoot, mainRepo, "dir3")
        assertLinksToExternalRepo(linkRoot, outputBase, "X")
        assertThat(outputBase.getRelative("external/foo").exists()).isFalse()
        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getRelative("dir1"),
                linkRoot.getRelative("dir2"),
                linkRoot.getRelative("dir3"),
                linkRoot.getRelative("file"),
                linkRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX + "/X")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_withSiblingRepoLayout_testExternalDirInMainRepoExists() {
        // Test external/ is ignored even when packages like "//external/foo" is specified.
        val outputBase: Root = Root.fromPath(fileSystem.getPath("/ob"))
        val mainRepo: Root = Root.fromPath(fileSystem.getPath("/my_repo"))
        val linkRoot: Path = outputBase.getRelative("execroot/ws_name")

        linkRoot.createDirectoryAndParents()
        mainRepo.asPath().createDirectoryAndParents()

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createMainPkg(mainRepo, "dir1/pkg/foo"), mainRepo)
                .put(createMainPkg(mainRepo, "dir2/pkg"), mainRepo)
                .put(
                    createMainPkg(mainRepo, "dir3"),
                    mainRepo
                ) // external/ should not be linked even we have "//external/foo" package
                .put(createMainPkg(mainRepo, "external/foo"), mainRepo)
                .put(
                    createExternalPkg(outputBase, "X", "dir_x/pkg"),
                    externalSourceRoot(outputBase, "X")
                )
                .build()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, true)
                .plantSymlinkForest()

        // Expected output base layout with sibling repositories in the execroot where
        // ws_name and X are siblings:
        //
        // /ob
        // ├── execroot
        // │   ├── ws_name
        // │   │   ├── dir1
        // │   │   │   └── pkg
        // │   │   │       └── foo -> /my_repo/dir1/pkg/foo
        // │   │   ├── dir2
        // │   │   │   └── pkg -> /my_repo/dir2/pkg
        // │   │   ├── dir3 -> /my_repo/dir3
        // │   │   └── external -> /my_repo/external
        // │   └── X -> /ob/external/X
        // └── external
        //     └── X
        assertLinksTo(linkRoot, mainRepo, "dir1")
        assertLinksTo(linkRoot, mainRepo, "dir2")
        assertLinksTo(linkRoot, mainRepo, "dir3")

        assertThat(
            outputBase
                .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                .getRelative("X")
                .exists()
        )
            .isTrue()
        assertThat(outputBase.getRelative("execroot/X").exists()).isTrue()
        assertLinksTo(
            linkRoot.getParentDirectory().getRelative("X"),  // Sibling of the main repo.
            outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION).getRelative("X")
        )

        assertThat(linkRoot.getRelative("external/foo").exists()).isTrue()

        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getRelative("dir1"),
                linkRoot.getRelative("dir2"),
                linkRoot.getRelative("dir3"),
                linkRoot.getRelative("external"),  // Symlinked to the main repo's top level external dir
                linkRoot.getParentDirectory().getRelative("X")
            ) // Symlinked to /ob/external/X
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_withSiblingRepoLayoutAndRootPackageInRoots_testExternalDirInMainRepoExists() {
        // Test external/ is ignored when root package "//:" is specified.
        val outputBase: Root = Root.fromPath(fileSystem.getPath("/ob"))
        val mainRepo: Root = Root.fromPath(fileSystem.getPath("/my_repo"))
        val linkRoot: Path = outputBase.getRelative("execroot/ws_name")

        linkRoot.createDirectoryAndParents()
        mainRepo.asPath().createDirectoryAndParents()

        mainRepo.getRelative("external/foo").createDirectoryAndParents()

        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>()
                .put(createMainPkg(mainRepo, ""), mainRepo)
                .put(
                    createExternalPkg(outputBase, "X", "dir_x/pkg"),
                    externalSourceRoot(outputBase, "X")
                )
                .buildOrThrow()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, true)
                .plantSymlinkForest()

        // Expected output base layout with sibling repositories in the execroot where
        // ws_name and X are siblings:
        //
        // /ob
        // ├── execroot
        // │   ├── ws_name
        // │   │   └── external -> /my_repo/external
        // │   └── X -> /ob/external/X
        // └── external
        //     └── X
        assertThat(
            outputBase
                .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                .getRelative("X")
                .exists()
        )
            .isTrue()
        assertThat(outputBase.getRelative("execroot/X").exists()).isTrue()
        assertLinksTo(
            linkRoot.getParentDirectory().getRelative("X"),  // Sibling of the main repo.
            outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION).getRelative("X")
        )

        assertThat(linkRoot.getRelative("external/foo").exists()).isTrue()

        Truth.assertThat(plantedSymlinks)
            .containsExactly(
                linkRoot.getParentDirectory().getRelative("X"),
                linkRoot.getRelative("file"),  // created by createMainPkg test setup
                linkRoot.getRelative("external") // symlink to main repo's top level external directory
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExternalPackage() {
        val linkRoot: Path = fileSystem.getPath("/linkRoot")
        linkRoot.createDirectoryAndParents()

        val root: Root? = Root.fromPath(fileSystem.getPath("/src"))
        val packageRootMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Root?>() // Virtual root, shouldn't actually be linked in.
                .put(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER, root)
                .build()

        val plantedSymlinks: com.google.common.collect.ImmutableList<Path?>? =
            SymlinkForest(packageRootMap, linkRoot, TestConstants.PRODUCT_NAME, false)
                .plantSymlinkForest()
        assertThat(linkRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX).exists()).isFalse()
        Truth.assertThat(plantedSymlinks).isEmpty()
    }

    companion object {
        private fun longestPathPrefix(path: String?, vararg prefixStrs: String?): PathFragment? {
            val prefixes: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
                com.google.common.collect.ImmutableSet.builder<PackageIdentifier?>()
            for (prefix in prefixStrs) {
                prefixes.add(PackageIdentifier.createInMainRepo(prefix))
            }
            val longest: PackageIdentifier? =
                SymlinkForest.longestPathPrefix(PackageIdentifier.createInMainRepo(path), prefixes.build())
            return if (longest != null) longest.getPackageFragment() else null
        }

        private fun externalSourceRoot(outputBase: Root, repoName: String?): Root {
            return Root.fromPath(
                outputBase
                    .asPath()
                    .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                    .getRelative(repoName)
            )
        }

        // Create same package under two different roots
        @Throws(IOException::class)
        private fun createPkg(rootA: Root?, rootB: Root?, pkg: String?): PackageIdentifier {
            if (rootA != null) {
                rootA.getRelative(pkg).createDirectoryAndParents()
                FileSystemUtils.createEmptyFile(rootA.getRelative(pkg).getChild("file"))
            }
            if (rootB != null) {
                rootB.getRelative(pkg).createDirectoryAndParents()
                FileSystemUtils.createEmptyFile(rootB.getRelative(pkg).getChild("file"))
            }
            return PackageIdentifier.createInMainRepo(pkg)
        }

        // Create package for external repo
        @Throws(IOException::class, LabelSyntaxException::class)
        private fun createExternalPkg(root: Root?, repo: String?, pkg: String?): PackageIdentifier {
            if (root != null) {
                val repoRoot: Path =
                    root.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION).getRelative(repo)
                repoRoot.getRelative(pkg).createDirectoryAndParents()
                FileSystemUtils.createEmptyFile(repoRoot.getRelative(pkg).getChild("file"))
            }
            return PackageIdentifier.create(RepositoryName.create(repo), PathFragment.create(pkg))
        }

        // Create package for main repo
        @Throws(IOException::class, LabelSyntaxException::class)
        private fun createMainPkg(repoRoot: Root?, pkg: String?): PackageIdentifier {
            if (repoRoot != null) {
                repoRoot.getRelative(pkg).createDirectoryAndParents()
                FileSystemUtils.createEmptyFile(repoRoot.getRelative(pkg).getChild("file"))
            }
            return PackageIdentifier.createInMainRepo(PathFragment.create(pkg))
        }

        @Throws(IOException::class)
        private fun assertLinksTo(fromRoot: Path, toRoot: Root, relpart: String?) {
            assertLinksTo(fromRoot.getRelative(relpart), toRoot.getRelative(relpart))
        }

        @Throws(IOException::class)
        private fun assertLinksTo(fromRoot: Path, toRoot: Path) {
            assertWithMessage("stat: %s", fromRoot.stat()).that(fromRoot.isSymbolicLink()).isTrue()
            assertThat(fromRoot.readSymbolicLink()).isEqualTo(toRoot.asFragment())
        }

        @Throws(IOException::class)
        private fun assertLinksToExternalRepo(fromRoot: Path, toRoot: Root, repoName: String?) {
            assertLinksTo(
                fromRoot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX.getRelative(repoName)),
                toRoot.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION).getRelative(repoName)
            )
        }

        private fun assertIsDir(root: Path, relpart: String?) {
            assertThat(root.getRelative(relpart).isDirectory(Symlinks.NOFOLLOW)).isTrue()
        }
    }
}
