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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionEnvironment

/** Unit tests for [SymlinkTreeStrategy].  */
@RunWith(JUnit4::class)
class SymlinkTreeStrategyTest : BuildViewTestCase() {
    @org.junit.Test
    fun testArtifactToPathConversion() {
        val artifact: Artifact = getBinArtifactWithNoOwner("dir/foo")
        assertThat(SymlinkTreeStrategy.TO_PATH.apply(artifact))
            .isEqualTo(artifact.getPath().asFragment())
        assertThat(SymlinkTreeStrategy.TO_PATH.apply(null)).isEqualTo(null)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withOutputService() {
        val context: ActionExecutionContext
        ActionExecutionContext > Mockito.mock<ActionExecutionContext?>(ActionExecutionContext::class.java)
        val outputService: OutputService
        OutputService > Mockito.mock<OutputService?>(OutputService::class.java)
        val eventHandler: StoredEventHandler = StoredEventHandler()

        T > Mockito.`when`<T?>(context.getContext(SymlinkTreeActionContext::class.java))
            .thenReturn(SymlinkTreeStrategy(outputService, TestConstants.WORKSPACE_NAME))
        T > Mockito.`when`<Boolean?>(context.getInputPath(TODO("Cannot convert element"))<T> ArgumentMatchers . any < kotlin . Any ? > ())
        thenAnswer({ i -> (i.< Object > getArgument < kotlin . Any ? > (0) as Artifact).getPath() })
        T > Mockito.`when`<T?>(context.getPathResolver()).thenReturn(ArtifactPathResolver.IDENTITY)
        T > Mockito.`when`<T?>(context.getEventHandler()).thenReturn(eventHandler)
        T > Mockito.`when`<T?>(outputService.canCreateSymlinkTree()).thenReturn(true)

        val inputManifest: Artifact = getBinArtifactWithNoOwner("dir/manifest.in")
        val outputManifest: Artifact = getBinArtifactWithNoOwner("dir.runfiles/MANIFEST")
        val runfile: Artifact = getBinArtifactWithNoOwner("dir/runfile")
        Mockito.doAnswer(
            Answer { i: InvocationOnMock? ->
                outputManifest.getPath().getParentDirectory().createDirectoryAndParents()
                null
            })
            .`when`<Any?>(outputService)
            .createSymlinkTree(TODO("Cannot convert element"))<T> ArgumentMatchers . any < kotlin . Any ? > ()
        T > ArgumentMatchers.any<Any?>()


        val runfiles: Runfiles? =
            Builder("TESTING")
                .setEmptyFilesSupplier(
                    object : EmptyFilesSupplier() {
                        public override fun getExtraPaths(
                            manifestPaths: MutableSet<PathFragment?>?
                        ): com.google.common.collect.ImmutableList<PathFragment?> {
                            return com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("dir/empty"))
                        }

                        public override fun fingerprint(fingerprint: Fingerprint?) {}
                    })
                .addArtifact(runfile)
                .build()
        val action: SymlinkTreeAction =
            SymlinkTreeAction(
                ActionsTestUtil.NULL_ACTION_OWNER,
                inputManifest,
                runfiles,
                outputManifest,  /* repoMappingManifest= */
                null,
                ActionEnvironment.EMPTY,
                RunfileSymlinksMode.CREATE,
                "workspace"
            )

        action.execute(context)

        val capture: ArgumentCaptor<MutableMap<PathFragment?, PathFragment?>?> =
            ArgumentCaptor.forClass<MutableMap<PathFragment?, PathFragment?>?, MutableMap<*, *>?>(MutableMap::class.java)
        Object > Mockito.verify<Any?>(outputService, Mockito.times(1)).createSymlinkTree(
            capture.capture(),
            TODO("Cannot convert element")
        )<T> ArgumentMatchers . any < kotlin . Any ? > ()

        Truth.assertThat(capture.getValue())
            .containsExactly(
                PathFragment.create("TESTING/dir/runfile"),
                runfile.getPath().asFragment(),
                PathFragment.create("TESTING/dir/empty"),
                null
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withoutOutputService() {
        val context: ActionExecutionContext = Mockito.mock<ActionExecutionContext>(ActionExecutionContext::class.java)
        val outputService: OutputService = Mockito.mock<OutputService>(OutputService::class.java)
        val eventHandler: StoredEventHandler = StoredEventHandler()

        Mockito.`when`<T?>(context.getContext(SymlinkTreeActionContext::class.java))
            .thenReturn(SymlinkTreeStrategy(outputService, TestConstants.WORKSPACE_NAME))
        Mockito.`when`<T?>(context.getInputPath(ArgumentMatchers.any<T?>()))
            .thenAnswer(Answer { i: InvocationOnMock? -> (i.getArgument<Any?>(0) as Artifact).getPath() })
        Mockito.`when`<T?>(context.getEventHandler()).thenReturn(eventHandler)
        Mockito.`when`<T?>(outputService.canCreateSymlinkTree()).thenReturn(false)

        val inputManifest: Artifact = getBinArtifactWithNoOwner("dir/manifest.in")
        val outputManifest: Artifact = getBinArtifactWithNoOwner("dir.runfiles/MANIFEST")
        val runfile: Artifact = getBinArtifactWithNoOwner("dir/runfile")

        val runfiles: Runfiles? =
            Builder("TESTING")
                .setEmptyFilesSupplier(
                    object : EmptyFilesSupplier() {
                        public override fun getExtraPaths(
                            manifestPaths: MutableSet<PathFragment?>?
                        ): com.google.common.collect.ImmutableList<PathFragment?> {
                            return com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("dir/empty"))
                        }

                        public override fun fingerprint(fingerprint: Fingerprint?) {}
                    })
                .addArtifact(runfile)
                .build()
        val action: SymlinkTreeAction =
            SymlinkTreeAction(
                ActionsTestUtil.NULL_ACTION_OWNER,
                inputManifest,
                runfiles,
                outputManifest,  /* repoMappingManifest= */
                null,
                ActionEnvironment.EMPTY,
                RunfileSymlinksMode.CREATE,
                "workspace"
            )

        action.execute(context)
        // Check that the OutputService is not used.
        Mockito.verify<Any?>(outputService, Mockito.never())
            .createSymlinkTree(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        val p: Path = outputManifest.getPath().getParentDirectory().getRelative("TESTING/dir/runfile")
        Truth.assertWithMessage("Path %s expected to exist", p).that(p.exists(Symlinks.NOFOLLOW)).isTrue()
        Truth.assertWithMessage("Path %s expected to be a symlink", p).that(p.isSymbolicLink()).isTrue()
        assertThat(p.readSymbolicLink()).isEqualTo(runfile.getPath().asFragment())
        val q: Path = outputManifest.getPath().getParentDirectory().getRelative("TESTING/dir/empty")
        Truth.assertWithMessage("Path %s expected to be a file", q).that(q.isFile()).isTrue()
        assertThat(FileSystemUtils.readContent(q)).isEmpty()
    }
}
