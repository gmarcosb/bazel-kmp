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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.actions.FileStateValue.DIRECTORY_FILE_STATE_NODE

/** Tests for [RBuildFilesVisitor].  */
@RunWith(JUnit4::class)
class RBuildFilesVisitorTest {
    var graph: WalkableGraph? = null

    @Before
    fun setUp() {
        graph = Mockito.mock<WalkableGraph>(WalkableGraph::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_singleAncestor() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar"],
        // /*includeAncestorDirs=*/ false).

        // File "foo/bar" belongs in the same directory as "foo/BUILD"
        //
        // An empty existingDirs is passed to set includeAncestorDirs = false.

        val keys: MutableSet<SkyKey?> = getSkyKeysForFiles(existingPkgs("foo"), existingDirs(), diff("foo/bar"))
        Truth.assertThat(keys)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<SkyKey?>(
                    fileStates("foo/bar"),
                    files("foo/bar")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_singleAncestorTwoFiles() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar", "foo/baz"],
        // /*includeAncestorDirs=*/ false).
        //
        // File "foo/bar" and "foo/baz" both belong in the same directory as "foo/BUILD"
        //
        // An empty existingDirs is passed to set includeAncestorDirs = false.
        val keys: MutableSet<SkyKey?> =
            getSkyKeysForFiles(existingPkgs("foo"), existingDirs(), diff("foo/bar", "foo/baz"))

        Truth.assertThat(keys)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<SkyKey?>(
                    fileStates("foo/bar", "foo/baz"),
                    files("foo/bar", "foo/baz")
                )
            )
        // Because foo/bar and foo/baz belong in the same folder, we expect the package lookup to occur
        // at the same time and only once.
        Mockito.verify<Any?>(graph).getSuccessfulValues(ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_packageNotFoundInDirectory() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar/baz", "foo/bar/bax"],
        // /*includeAncestorDirs=*/ false).
        //
        // File "foo/bar/baz" and "foo/bar/bax" both belong in a subdirectory of package foo.
        //
        // An empty existingDirs is passed to set includeAncestorDirs = false.
        val keys: MutableSet<SkyKey?> =
            getSkyKeysForFiles(existingPkgs("foo"), existingDirs(), diff("foo/bar/baz", "foo/bar/bax"))
        Truth.assertThat(keys)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<SkyKey?>(
                    fileStates("foo/bar/baz", "foo/bar/bax"), files("foo/bar/baz", "foo/bar/bax")
                )
            )
        // We expect to take two steps of searching parent directories to find the package foo.
        Mockito.verify<Any?>(graph, Mockito.times(2)).getSuccessfulValues(ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_onlyAncestorPackageAndDirExists() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar/baz", "foo/bar/bax"],
        // /*includeAncestorDirs=*/ true).
        //
        // File "foo/bar/baz" and "foo/bar/bax" both belong in a subdirectory of package foo.
        //
        // existingDirs = ["foo"] means we are passing in true for 'includeAncestorDirs' and that
        // "foo/bar" is a newly created directory whereas "foo" already existed as a directory.
        val keys: MutableSet<SkyKey?> =
            getSkyKeysForFiles(
                existingPkgs("foo"), existingDirs("foo"), diff("foo/bar/baz", "foo/bar/bax")
            )

        // Because "foo/bar" is newly created, add a file and file state key for that directory as well
        // as adding the keys for the directory listing and directory listing state for "foo/bar" and
        // "foo".
        Truth.assertThat(keys)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<SkyKey?>(
                    fileStates("foo/bar/baz", "foo/bar/bax", "foo/bar"),
                    files("foo/bar/baz", "foo/bar/bax", "foo/bar"),
                    dirs("foo", "foo/bar"),
                    dirStates("foo", "foo/bar")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_onlyOneSubdirectoryExists() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar/f1", "foo/baz/f2"],
        // /*includeAncestorDirs=*/ true).
        //
        // File "foo/bar/f1" and "foo/baz/f2" both belong in a subdirectory of package foo.
        //
        // existingDirs = ["foo", "foo/bar"] means we are passing in true for 'includeAncestorDirs' and
        // that while "foo/bar" was an existing directory, "foo/baz" is newly created.
        val keys: MutableSet<SkyKey?> =
            getSkyKeysForFiles(
                existingPkgs("foo"), existingDirs("foo", "foo/bar"), diff("foo/bar/f1", "foo/baz/f2")
            )

        // We include a file and file state key for the newly added directory "foo/baz" but not for
        // "foo/bar". Because includeAncestorDirs was set to true, we also get directory listing and
        // directory listing state keys for all directories for which this could have changed.
        Truth.assertThat(keys)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<SkyKey?>(
                    fileStates("foo/bar/f1", "foo/baz/f2", "foo/baz"),
                    files("foo/bar/f1", "foo/baz/f2", "foo/baz"),
                    dirs("foo", "foo/bar", "foo/baz"),
                    dirStates("foo", "foo/bar", "foo/baz")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_bothSubdirectoryExists() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar/f1", "foo/baz/f2"],
        // /*includeAncestorDirs=*/ true).
        //
        // File "foo/bar/f1" and "foo/baz/f2" both belong in a subdirectory of package foo.
        //
        // existingDirs = ["foo", "foo/bar", "foo/baz"] means we are passing in true for
        // 'includeAncestorDirs' and that no new directories were created.
        val keys: MutableSet<SkyKey?> =
            getSkyKeysForFiles(
                existingPkgs("foo"),
                existingDirs("foo", "foo/bar", "foo/baz"),
                diff("foo/bar/f1", "foo/baz/f2")
            )

        // Since no new directories were created, we expect no file or file state keys for them. Because
        // includeAncestorKeys was set to true, include the directory listing and directory listing
        // state keys of the two existing directories that had files in the diff.
        Truth.assertThat(keys)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<SkyKey?>(
                    fileStates("foo/bar/f1", "foo/baz/f2"),
                    files("foo/bar/f1", "foo/baz/f2"),
                    dirs("foo/bar", "foo/baz"),
                    dirStates("foo/bar", "foo/baz")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_packageInDifferentAncestor() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar", "foo/bar/bax"],
        // /*includeAncestorDirs=*/ false).
        //
        // File "foo/bar" and "foo/bar/bax" belong in subdirectories with a differing amount of nesting
        // under package foo.
        //
        // An empty existingDirs is passed to set includeAncestorDirs = false.
        val keys: MutableSet<SkyKey?> =
            getSkyKeysForFiles(existingPkgs("foo"), existingDirs(), diff("foo/bar", "foo/bar/bax"))
        Truth.assertThat(keys)
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.concat<SkyKey?>(
                    fileStates("foo/bar", "foo/bar/bax"), files("foo/bar", "foo/bar/bax")
                )
            )
        // Because we expect the search for "foo/bar/bax"'s package to take two hops, we expect two
        // calls to the graph in the package search.
        Mockito.verify<Any?>(graph, Mockito.times(2)).getSuccessfulValues(ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathFragmentToSkyKey_noAncestorKeys() {
        // Tests RBuildFilesVisitor#getSkyKeysForFileFragments(
        // graph,
        // /*files=*/ ["foo/bar"],
        // /*includeAncestorDirs=*/ false).
        //
        // File "foo/bar" has no parent package
        //
        // An empty existingDirs is passed to set includeAncestorDirs = false.
        val keys: MutableSet<SkyKey?> = getSkyKeysForFiles(existingPkgs(), existingDirs(), diff("foo/bar"))

        // Because "foo/bar" has no parent package, we are not able to return any keys.
        Truth.assertThat(keys).isEmpty()
    }

    /**
     * Calls RBuildFilesVisitor#getSkyKeysForFileFragments where the files passed in are specified by
     * the 'diff' variable.
     * 
     * 
     * The aforementioned function makes a skyframe call to retrieve PackageLookupValues and
     * FileStateValues and so the parameters 'existingPackages' and 'existingDirectories' allows us to
     * seed our mock graph with successful package lookups and existent directory file states for the
     * specified paths.
     * 
     * 
     * Note: The skyframe lookups for FileStateValues occurs only if the parameter
     * 'includeAncestorKeys' in RBuildFilesVisitor#getSkyKeysForFileFragments is true and so the paths
     * inside 'existingDirectories' are relevant if and only if 'includeAncestorKeys' is true. Because
     * of this, we pass in 'includeAncestorKeys' as true if and only if 'existingDirectories' is
     * non-empty.
     * 
     * 
     * Note: The skyquery function 'rbuildfiles' uses RBuildFilesVisitor#getSkyKeysForFileFragments
     * with 'includeAncestorKeys' as being false. Passing in an empty set for 'existingDirs' allows
     * this mode of operation to be tested.
     */
    @Throws(java.lang.Exception::class)
    private fun getSkyKeysForFiles(
        existingPackages: MutableSet<PathFragment?>,
        existingDirectories: MutableSet<PathFragment?>,
        pathFragments: MutableSet<PathFragment?>?
    ): MutableSet<SkyKey?> {
        Mockito.`when`<T?>(graph.getSuccessfulValues(ArgumentMatchers.any<T?>()))
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val result: MutableMap<SkyKey?, SkyValue?> = HashMap<SkyKey?, SkyValue?>()
                    val paths = invocationOnMock.getArgument<Any?>(0) as Iterable<*>
                    for (`object` in paths) {
                        Truth.assertThat(`object`).isInstanceOf(SkyKey::class.java)
                        val key: SkyKey = `object` as SkyKey
                        if (key.functionName().equals(PACKAGE_LOOKUP)) {
                            val fragment: PathFragment? = (key.argument() as PackageIdentifier).getPackageFragment()
                            if (existingPackages.contains(fragment)) {
                                result.put(key, PackageLookupValue.success(root, BuildFileName.BUILD))
                            }
                        } else if (key.functionName().equals(FILE_STATE)) {
                            val fragment: PathFragment? = (key.argument() as RootedPath).getRootRelativePath()
                            if (existingDirectories.contains(fragment)) {
                                result.put(key, DIRECTORY_FILE_STATE_NODE)
                            }
                        } else {
                            throw java.lang.IllegalStateException("Unexpected skyframe lookup: " + key)
                        }
                    }
                    result
                })

        return RBuildFilesVisitor.getSkyKeysForFileFragments(
            graph, pathFragments, !existingDirectories.isEmpty()
        )
    }

    companion object {
        private val fs: FileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        private val root: Root? = Root.fromPath(fs.getPath("/root/"))

        private fun fileStates(vararg paths: String?): MutableSet<SkyKey?>? {
            return makeKeys(FileStateValue::key, paths)
        }

        private fun files(vararg paths: String?): MutableSet<SkyKey?>? {
            return makeKeys(FileValue::key, paths)
        }

        private fun dirStates(vararg paths: String?): MutableSet<SkyKey?>? {
            return makeKeys(DirectoryListingStateValue::key, paths)
        }

        private fun dirs(vararg paths: String?): MutableSet<SkyKey?>? {
            return makeKeys(DirectoryListingValue::key, paths)
        }

        private fun makeKeys(
            rootedPathToKey: java.util.function.Function<RootedPath?, SkyKey?>, vararg paths: String?
        ): MutableSet<SkyKey?> {
            return toPaths(*paths).stream()
                .map<Any?> { path: PathFragment? -> rootedPathToKey.apply(RootedPath.toRootedPath(root, path)) }
                .collect(Collectors.toSet())
        }

        private fun diff(vararg files: String?): MutableSet<PathFragment?> {
            return toPaths(*files)
        }

        private fun existingDirs(vararg files: String?): MutableSet<PathFragment?> {
            return toPaths(*files)
        }

        private fun existingPkgs(vararg files: String?): MutableSet<PathFragment?> {
            return toPaths(*files)
        }

        private fun toPaths(vararg files: String?): MutableSet<PathFragment?> {
            val result: MutableSet<PathFragment?> = HashSet<PathFragment?>()
            for (file in files) {
                result.add(PathFragment.create(file))
            }
            return result
        }
    }
}
