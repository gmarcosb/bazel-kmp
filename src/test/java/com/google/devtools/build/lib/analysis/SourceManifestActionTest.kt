// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * Tests for [SourceManifestAction].
 */
@RunWith(JUnit4::class)
class SourceManifestActionTest : BuildViewTestCase() {
    private var fakeManifest: MutableMap<PathFragment?, Artifact?>? = null
    private var buildFile: Artifact? = null
    private var relativeSymlink: Artifact? = null
    private var absoluteSymlink: Artifact? = null
    private var manifestOutputFile: Artifact? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        analysisMock.pySupport().setup(mockToolsConfig)
        // Test with a raw manifest Action.
        fakeManifest = LinkedHashMap<PathFragment?, Artifact?>()
        val trivialRoot: ArtifactRoot? =
            ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory.getRelative("trivial")))
        val buildFilePath: Path =
            scratch.file("trivial/BUILD", "py_binary(name='trivial', srcs =['trivial.py'])")
        buildFile = ActionsTestUtil.createArtifact(trivialRoot, buildFilePath)

        val pythonSourcePath: Path =
            scratch.file("trivial/trivial.py", "#!/usr/bin/python \n print 'Hello World'")
        val pythonSourceFile: Artifact? = ActionsTestUtil.createArtifact(trivialRoot, pythonSourcePath)
        fakeManifest!!.put(buildFilePath.relativeTo(rootDirectory), buildFile)
        fakeManifest!!.put(pythonSourcePath.relativeTo(rootDirectory), pythonSourceFile)
        val outputDir: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(rootDirectory, RootType.OUTPUT, "blaze-output")
        outputDir.getRoot().asPath().createDirectoryAndParents()
        manifestOutputFile =
            ActionsTestUtil.createArtifact(
                outputDir, rootDirectory.getRelative("blaze-output/trivial.runfiles_manifest")
            )

        val relativeSymlinkPath: Path = outputDir.getRoot().asPath().getChild("relative_symlink")
        relativeSymlinkPath.createSymbolicLink(PathFragment.create("../some/relative/path"))
        relativeSymlink =
            SpecialArtifact.create(
                outputDir,
                outputDir.getExecPath().getChild("relative_symlink"),
                ActionsTestUtil.NULL_ARTIFACT_OWNER,
                SpecialArtifactType.UNRESOLVED_SYMLINK
            )
        val absoluteSymlinkPath: Path = outputDir.getRoot().asPath().getChild("absolute_symlink")
        absoluteSymlinkPath.createSymbolicLink(PathFragment.create("/absolute/path"))
        absoluteSymlink =
            SpecialArtifact.create(
                outputDir,
                outputDir.getExecPath().getChild("absolute_symlink"),
                ActionsTestUtil.NULL_ARTIFACT_OWNER,
                SpecialArtifactType.UNRESOLVED_SYMLINK
            )
    }

    private fun createSymlinkAction(): SourceManifestAction {
        return createAction(ManifestType.SOURCE_SYMLINKS, true)
    }

    private fun createSourceOnlyAction(): SourceManifestAction {
        return createAction(ManifestType.SOURCES_ONLY, true)
    }

    private fun createAction(type: ManifestType?, addInitPy: Boolean): SourceManifestAction {
        val builder: Runfiles.Builder = Builder("TESTING")
        builder.addSymlinks(fakeManifest)
        if (addInitPy) {
            builder.setEmptyFilesSupplier(analysisMock.pySupport().getEmptyRunfilesSupplier())
        }
        return SourceManifestAction(
            type,
            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
            manifestOutputFile,
            builder.build()
        )
    }

    /** Manifest writer that validates an expected call sequence.  */
    private inner class MockManifestWriter : SourceManifestAction.ManifestWriter {
        private val expectedSequence: MutableList<MutableMap.MutableEntry<PathFragment?, Artifact?>> =
            java.util.ArrayList<MutableMap.MutableEntry<PathFragment?, Artifact?>>()

        init {
            expectedSequence.addAll(fakeManifest!!.entries)
        }

        public override fun writeEntry(
            manifestWriter: java.io.Writer?,
            rootRelativePath: PathFragment?,
            symlinkTarget: PathFragment?
        ) {
            Truth.assertWithMessage("Expected manifest input to be exhausted")
                .that(expectedSequence)
                .isNotEmpty()
            val expectedEntry: MutableMap.MutableEntry<PathFragment?, Artifact?> = expectedSequence.removeAt(0)
            assertThat(rootRelativePath)
                .isEqualTo(PathFragment.create("TESTING").getRelative(expectedEntry.key))
            assertThat(symlinkTarget).isEqualTo(expectedEntry.value.getPath().asFragment())
        }

        fun unconsumedInputs(): Int {
            return expectedSequence.size
        }

        val mnemonic: String?
            get() = null

        val rawProgressMessage: String?
            get() = null

        val isRemotable: Boolean
            get() = false

        public override fun emitsAbsolutePaths(): Boolean {
            return false
        }
    }

    /**
     * Tests that SourceManifestAction calls its manifest writer with the expected call sequence.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testManifestWriterIntegration() {
        val mockWriter = MockManifestWriter()
        val manifestContents: String? =
            SourceManifestAction(
                mockWriter,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                manifestOutputFile,
                Builder("TESTING").addSymlinks(fakeManifest).build()
            )
                .getFileContents(reporter)
        Truth.assertThat(mockWriter.unconsumedInputs()).isEqualTo(0)
        Truth.assertThat(manifestContents).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleFileWriting() {
        val manifestContents: String? = createSymlinkAction().getFileContents(reporter)
        Truth.assertThat(manifestContents)
            .isEqualTo(
                """
            TESTING/trivial/BUILD /workspace/trivial/BUILD
            TESTING/trivial/__init__.py 
            TESTING/trivial/trivial.py /workspace/trivial/trivial.py
            
            """.trimIndent()
            )
    }

    /**
     * Tests that the source-only formatting strategy includes relative paths only
     * (i.e. not symlinks).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSourceOnlyFormatting() {
        val manifestContents: String? = createSourceOnlyAction().getFileContents(reporter)
        Truth.assertThat(manifestContents)
            .isEqualTo(
                """
            TESTING/trivial/BUILD
            TESTING/trivial/__init__.py
            TESTING/trivial/trivial.py
            
            """.trimIndent()
            )
    }

    /**
     * Test that a directory which has only a .so file in the manifest triggers
     * the inclusion of a __init__.py file for that directory.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSwigLibrariesTriggerInitDotPyInclusion() {
        val swiggedLibPath: ArtifactRoot? =
            ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory.getRelative("swig")))
        val swiggedFile: Path = scratch.file("swig/fakeLib.so")
        val swigDotSO: Artifact? = ActionsTestUtil.createArtifact(swiggedLibPath, swiggedFile)
        fakeManifest!!.put(swiggedFile.relativeTo(rootDirectory), swigDotSO)
        val manifestContents: String? = createSymlinkAction().getFileContents(reporter)
        Truth.assertThat(manifestContents).containsMatch(".*TESTING/swig/__init__.py .*")
        Truth.assertThat(manifestContents).containsMatch("fakeLib.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPythonOrSwigLibrariesDoNotTriggerInitDotPyInclusion() {
        val nonPythonPath: ArtifactRoot? =
            ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory.getRelative("not_python")))
        val nonPythonFile: Path = scratch.file("not_python/blob_of_data")
        val nonPython: Artifact? = ActionsTestUtil.createArtifact(nonPythonPath, nonPythonFile)
        fakeManifest!!.put(nonPythonFile.relativeTo(rootDirectory), nonPython)
        val manifestContents: String? = createSymlinkAction().getFileContents(reporter)
        Truth.assertThat(manifestContents).doesNotContain("not_python/__init__.py \n")
        Truth.assertThat(manifestContents).containsMatch("blob_of_data")
    }

    @org.junit.Test
    fun testGetMnemonic() {
        assertThat(createSymlinkAction().getMnemonic()).isEqualTo("SourceSymlinkManifest")
        assertThat(createAction(ManifestType.SOURCE_SYMLINKS, false).getMnemonic())
            .isEqualTo("SourceSymlinkManifest")
        assertThat(createSourceOnlyAction().getMnemonic()).isEqualTo("PackagingSourcesManifest")
    }

    @org.junit.Test
    fun testSymlinkProgressMessage() {
        val progress: String = createSymlinkAction().getProgressMessage()
        Truth.assertWithMessage("null action not found in %s", progress)
            .that(progress.contains("//null/action:owner"))
            .isTrue()
    }

    @org.junit.Test
    fun testSymlinkProgressMessageNoPyInitFiles() {
        val progress: String = createAction(ManifestType.SOURCE_SYMLINKS, false).getProgressMessage()
        Truth.assertWithMessage("null action not found in %s", progress)
            .that(progress.contains("//null/action:owner"))
            .isTrue()
    }

    @org.junit.Test
    fun testSourceOnlyProgressMessage() {
        val action: SourceManifestAction =
            SourceManifestAction(
                ManifestType.SOURCES_ONLY,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                getBinArtifactWithNoOwner("trivial.runfiles_manifest"),
                Runfiles.EMPTY
            )
        val progress: String = action.getProgressMessage()
        Truth.assertWithMessage("null action not found in %s", progress)
            .that(progress.contains("//null/action:owner"))
            .isTrue()
    }

    @org.junit.Test
    fun testRootSymlinksAffectKey() {
        val manifest1: Artifact? = getBinArtifactWithNoOwner("manifest1")
        val manifest2: Artifact? = getBinArtifactWithNoOwner("manifest2")

        val action1: SourceManifestAction =
            SourceManifestAction(
                ManifestType.SOURCE_SYMLINKS,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                manifest1,
                Builder("TESTING")
                    .addRootSymlinks(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            PathFragment.create("a"),
                            buildFile
                        )
                    )
                    .build()
            )

        val action2: SourceManifestAction =
            SourceManifestAction(
                ManifestType.SOURCE_SYMLINKS,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                manifest2,
                Builder("TESTING")
                    .addRootSymlinks(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            PathFragment.create("b"),
                            buildFile
                        )
                    )
                    .build()
            )

        Truth.assertThat(computeKey(action2)).isNotEqualTo(computeKey(action1))
    }

    // Regression test for b/116254698.
    @org.junit.Test
    fun testEmptyFilesAffectKey() {
        val manifest1: Artifact? = getBinArtifactWithNoOwner("manifest1")
        val manifest2: Artifact? = getBinArtifactWithNoOwner("manifest2")

        val action1: SourceManifestAction =
            SourceManifestAction(
                ManifestType.SOURCE_SYMLINKS,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                manifest1,
                Builder("TESTING")
                    .addSymlink(PathFragment.create("a"), buildFile)
                    .setEmptyFilesSupplier(
                        object : EmptyFilesSupplier() {
                            public override fun getExtraPaths(
                                manifestPaths: MutableSet<PathFragment?>
                            ): com.google.common.collect.ImmutableSet<PathFragment?> {
                                return manifestPaths.stream()
                                    .map<Any?> { p: PathFragment? -> p.replaceName(p.getBaseName() + "~") }
                                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
                            }

                            public override fun fingerprint(fingerprint: Fingerprint) {
                                fingerprint.addInt(1)
                            }
                        })
                    .build()
            )

        val action2: SourceManifestAction =
            SourceManifestAction(
                ManifestType.SOURCE_SYMLINKS,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                manifest2,
                Builder("TESTING")
                    .addSymlink(PathFragment.create("a"), buildFile)
                    .setEmptyFilesSupplier(
                        object : EmptyFilesSupplier() {
                            public override fun getExtraPaths(
                                manifestPaths: MutableSet<PathFragment?>
                            ): com.google.common.collect.ImmutableSet<PathFragment?> {
                                return manifestPaths.stream()
                                    .map<Any?> { p: PathFragment? -> p.replaceName(p.getBaseName() + "~~") }
                                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
                            }

                            public override fun fingerprint(fingerprint: Fingerprint) {
                                fingerprint.addInt(2)
                            }
                        })
                    .build()
            )

        Truth.assertThat(computeKey(action2)).isNotEqualTo(computeKey(action1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnresolvedSymlink() {
        val manifest: Artifact? = getBinArtifactWithNoOwner("manifest1")

        val action: SourceManifestAction =
            SourceManifestAction(
                ManifestType.SOURCE_SYMLINKS,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                manifest,
                Builder("TESTING")
                    .addArtifact(absoluteSymlink)
                    .addArtifact(buildFile)
                    .addArtifact(relativeSymlink)
                    .build()
            )

        val inputs: NestedSet<Artifact?> = action.getInputs()
        assertThat(inputs.toList()).containsExactly(absoluteSymlink, relativeSymlink)

        // Verify that the return value of getInputs is cached.
        assertThat(inputs).isEqualTo(action.getInputs())
        assertThat(inputs.toList()).isEqualTo(action.getInputs().toList())

        assertThat(action.getFileContents(reporter))
            .isEqualTo(
                """
            TESTING/BUILD /workspace/trivial/BUILD
            TESTING/absolute_symlink /absolute/path
            TESTING/relative_symlink ../some/relative/path
            
            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEscaping() {
        val manifest: Artifact? = getBinArtifactWithNoOwner("manifest1")

        val trivialRoot: ArtifactRoot? =
            ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory.getRelative("trivial")))
        val fileWithSpaceAndBackslashPath: Path = scratch.file("trivial/file with sp\\ace", "foo")
        val fileWithSpaceAndBackslash: Artifact? =
            ActionsTestUtil.createArtifact(trivialRoot, fileWithSpaceAndBackslashPath)
        val fileWithNewlineAndBackslashPath: Path = scratch.file("trivial/file\nwith\\newline", "foo")
        val fileWithNewlineAndBackslash: Artifact? =
            ActionsTestUtil.createArtifact(trivialRoot, fileWithNewlineAndBackslashPath)

        val action: SourceManifestAction =
            SourceManifestAction(
                ManifestType.SOURCE_SYMLINKS,
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                manifest,
                Builder("TESTING")
                    .addSymlink(PathFragment.create("no/sp\\ace"), buildFile)
                    .addSymlink(PathFragment.create("also/no/sp\\ace"), fileWithSpaceAndBackslash)
                    .addSymlink(PathFragment.create("still/no/sp\\ace"), fileWithNewlineAndBackslash)
                    .addSymlink(PathFragment.create("with sp\\ace"), buildFile)
                    .addSymlink(PathFragment.create("also/with sp\\ace"), fileWithSpaceAndBackslash)
                    .addSymlink(PathFragment.create("more/with sp\\ace"), fileWithNewlineAndBackslash)
                    .addSymlink(PathFragment.create("with\nnew\\line"), buildFile)
                    .addSymlink(PathFragment.create("also/with\nnewline"), fileWithSpaceAndBackslash)
                    .addSymlink(PathFragment.create("more/with\nnewline"), fileWithNewlineAndBackslash)
                    .addSymlink(PathFragment.create("with\nnew\\line and space"), buildFile)
                    .addSymlink(
                        PathFragment.create("also/with\nnewline and space"), fileWithSpaceAndBackslash
                    )
                    .addSymlink(
                        PathFragment.create("more/with\nnewline and space"),
                        fileWithNewlineAndBackslash
                    )
                    .build()
            )
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            assertThat(action.getFileContents(reporter))
                .isEqualTo(
                    """
              TESTING/also/no/sp/ace /workspace/trivial/file with sp/ace
               TESTING/also/with\
               newline /workspace/trivial/file with sp/ace
               TESTING/also/with\
               newline\sand\sspace /workspace/trivial/file with sp/ace
               TESTING/also/with\ssp/ace /workspace/trivial/file with sp/ace
               TESTING/more/with\
               newline /workspace/trivial/file\
               with/newline
               TESTING/more/with\
               newline\sand\sspace /workspace/trivial/file\
               with/newline
               TESTING/more/with\ssp/ace /workspace/trivial/file\
               with/newline
              TESTING/no/sp/ace /workspace/trivial/BUILD
               TESTING/still/no/sp/ace /workspace/trivial/file\
               with/newline
               TESTING/with\
               new/line /workspace/trivial/BUILD
               TESTING/with\
               new/line\sand\sspace /workspace/trivial/BUILD
               TESTING/with\ssp/ace /workspace/trivial/BUILD
              
              """.trimIndent()
                )
        } else {
            assertThat(action.getFileContents(reporter))
                .isEqualTo(
                    """
              TESTING/also/no/sp\ace /workspace/trivial/file with sp\ace
               TESTING/also/with\
               newline /workspace/trivial/file with sp\bace
               TESTING/also/with\
               newline\sand\sspace /workspace/trivial/file with sp\bace
               TESTING/also/with\ssp\bace /workspace/trivial/file with sp\bace
               TESTING/more/with\
               newline /workspace/trivial/file\
               with\bnewline
               TESTING/more/with\
               newline\sand\sspace /workspace/trivial/file\
               with\bnewline
               TESTING/more/with\ssp\bace /workspace/trivial/file\
               with\bnewline
              TESTING/no/sp\ace /workspace/trivial/BUILD
               TESTING/still/no/sp\bace /workspace/trivial/file\
               with\bnewline
               TESTING/with\
               new\bline /workspace/trivial/BUILD
               TESTING/with\
               new\bline\sand\sspace /workspace/trivial/BUILD
               TESTING/with\ssp\bace /workspace/trivial/BUILD
              
              """.trimIndent()
                )
        }
    }

    private fun computeKey(action: SourceManifestAction): String {
        val fp: Fingerprint = Fingerprint()
        action.computeKey(actionKeyContext,  /* inputMetadataProvider= */null, fp)
        return fp.hexDigestAndReset()
    }
}
