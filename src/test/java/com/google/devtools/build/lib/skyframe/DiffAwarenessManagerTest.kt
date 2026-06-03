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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * Unit tests for [DiffAwarenessManager], especially of the fact that it works in a sequential
 * manner and of its correctness in the presence of unprocesed diffs.
 */
@RunWith(JUnit4::class)
class DiffAwarenessManagerTest {
    private var fs: FileSystem? = null
    protected var events: EventCollectionApparatus? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createFileSystem() {
        fs = InMemoryFileSystem(DigestHashFunction.SHA256)
    }

    @Before
    fun initializeEventCollectionApparatus() {
        events = EventCollectionApparatus()
        events.setFailFast(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEverythingModifiedIfNoDiffAwareness() {
        val pathEntry: Root? = Root.fromPath(fs.getPath("/pathEntry"))
        val factory = DiffAwarenessFactoryStub()
        val manager: DiffAwarenessManager =
            DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
        Truth.assertWithMessage("Expected EVERYTHING_MODIFIED since there are no factories")
            .that(
                manager
                    .getDiff(
                        events.reporter(),
                        pathEntry,
                        IgnoredSubdirectories.EMPTY,
                        OptionsProvider.EMPTY
                    ).modifiedFileSet
            )
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResetAndSetPathEntriesCallClose() {
        val pathEntry: Root? = Root.fromPath(fs.getPath("/pathEntry"))
        val diff: ModifiedFileSet = ModifiedFileSet.NOTHING_MODIFIED
        val diffAwareness1 = DiffAwarenessStub(com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(diff))
        val diffAwareness2 = DiffAwarenessStub(com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(diff))
        val factory = DiffAwarenessFactoryStub()
        factory.inject(pathEntry, diffAwareness1)
        val manager: DiffAwarenessManager =
            DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
        var unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        Truth.assertWithMessage("diffAwareness1 shouldn't have been closed yet")
            .that(diffAwareness1.closed())
            .isFalse()
        manager.reset()
        Truth.assertWithMessage("diffAwareness1 should have been closed by reset")
            .that(diffAwareness1.closed())
            .isTrue()
        factory.inject(pathEntry, diffAwareness2)
        unused =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        Truth.assertWithMessage("diffAwareness2 shouldn't have been closed yet")
            .that(diffAwareness2.closed())
            .isFalse()
        events.assertNoWarningsOrErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHandlesUnprocessedDiffs() {
        val pathEntry: Root? = Root.fromPath(fs.getPath("/pathEntry"))
        val diff1: ModifiedFileSet = modifiedFileSet("file1")
        val diff2: ModifiedFileSet = modifiedFileSet("file2")
        val diff3: ModifiedFileSet = modifiedFileSet("file3")
        val diffAwareness =
            DiffAwarenessStub(
                com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(
                    diff1,
                    diff2,
                    diff3,
                    DiffAwarenessStub.Companion.BROKEN_DIFF
                )
            )
        val factory = DiffAwarenessFactoryStub()
        factory.inject(pathEntry, diffAwareness)
        val manager: DiffAwarenessManager =
            DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
        val firstProcessableDiff: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        Truth.assertWithMessage("Expected EVERYTHING_MODIFIED on first call to getDiff")
            .that(firstProcessableDiff.modifiedFileSet)
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        firstProcessableDiff.markProcessed()
        val processableDiff1: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        assertThat(processableDiff1.modifiedFileSet).isEqualTo(diff1)
        val processableDiff2: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        assertThat(processableDiff2.modifiedFileSet).isEqualTo(modifiedFileSet("file1", "file2"))
        processableDiff2.markProcessed()
        val processableDiff3: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        assertThat(processableDiff3.modifiedFileSet).isEqualTo(diff3)
        events.assertNoWarningsOrErrors()
        val processableDiff4: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        assertThat(processableDiff4.modifiedFileSet)
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        events.assertContainsWarning("error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHandlesBrokenDiffs() {
        val pathEntry: Root? = Root.fromPath(fs.getPath("/pathEntry"))
        val factory1 = DiffAwarenessFactoryStub()
        val diffAwareness1 = DiffAwarenessStub(com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(), 1)
        factory1.inject(pathEntry, diffAwareness1)
        val factory2 = DiffAwarenessFactoryStub()
        val diff2: ModifiedFileSet? = ModifiedFileSet.builder().modify(PathFragment.create("file2")).build()
        val diffAwareness2 =
            DiffAwarenessStub(
                com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(
                    diff2,
                    DiffAwarenessStub.Companion.BROKEN_DIFF
                )
            )
        factory2.inject(pathEntry, diffAwareness2)
        val factory3 = DiffAwarenessFactoryStub()
        val diff3: ModifiedFileSet = ModifiedFileSet.builder().modify(PathFragment.create("file3")).build()
        val diffAwareness3 = DiffAwarenessStub(com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(diff3))
        factory3.inject(pathEntry, diffAwareness3)
        val manager: DiffAwarenessManager =
            DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory1, factory2, factory3))

        var processableDiff: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        events.assertNoWarningsOrErrors()
        Truth.assertWithMessage("Expected EVERYTHING_MODIFIED on first call to getDiff for diffAwareness1")
            .that(processableDiff.modifiedFileSet)
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        processableDiff.markProcessed()

        processableDiff =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        events.assertContainsEventWithFrequency("error in getCurrentView", 1)
        Truth.assertWithMessage("Expected EVERYTHING_MODIFIED because of broken getCurrentView")
            .that(processableDiff.modifiedFileSet)
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        processableDiff.markProcessed()
        factory1.remove(pathEntry)

        processableDiff =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        Truth.assertWithMessage("Expected EVERYTHING_MODIFIED on first call to getDiff for diffAwareness2")
            .that(processableDiff.modifiedFileSet)
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        processableDiff.markProcessed()

        processableDiff =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        assertThat(processableDiff.modifiedFileSet).isEqualTo(diff2)
        processableDiff.markProcessed()

        processableDiff =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        events.assertContainsEventWithFrequency("error in getDiff", 1)
        Truth.assertWithMessage("Expected EVERYTHING_MODIFIED because of broken getDiff")
            .that(processableDiff.modifiedFileSet)
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        processableDiff.markProcessed()
        factory2.remove(pathEntry)

        processableDiff =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        Truth.assertWithMessage("Expected EVERYTHING_MODIFIED on first call to getDiff for diffAwareness3")
            .that(processableDiff.modifiedFileSet)
            .isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        processableDiff.markProcessed()

        processableDiff =
            manager.getDiff(
                events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
            )
        assertThat(processableDiff.modifiedFileSet).isEqualTo(diff3)
        processableDiff.markProcessed()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndependentAwarenessPerIgnoredPaths() {
        val pathEntry: Root? = Root.fromPath(fs.getPath("/path"))

        val factory: DiffAwareness.Factory = Mockito.mock<DiffAwareness.Factory>(DiffAwareness.Factory::class.java)

        val diff1: ModifiedFileSet = modifiedFileSet("path/ignored-path-2/foo")
        val diffAwareness1: DiffAwareness =
            DiffAwarenessStub(com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(diff1))
        Mockito.`when`<T?>(
            factory.maybeCreate(
                pathEntry,
                IgnoredSubdirectories.of(com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("path/ignored-path-1"))),
                OptionsProvider.EMPTY
            )
        )
            .thenReturn(diffAwareness1)

        val diff2: ModifiedFileSet = modifiedFileSet("path/ignored-path-1/foo")
        val diffAwareness2: DiffAwareness =
            DiffAwarenessStub(com.google.common.collect.ImmutableList.of<ModifiedFileSet?>(diff2))
        Mockito.`when`<T?>(
            factory.maybeCreate(
                pathEntry,
                IgnoredSubdirectories.of(com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("path/ignored-path-2"))),
                OptionsProvider.EMPTY
            )
        )
            .thenReturn(diffAwareness2)

        val manager: DiffAwarenessManager =
            DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))

        var processedDiff1: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(),
                pathEntry,
                IgnoredSubdirectories.of(com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("path/ignored-path-1"))),
                OptionsProvider.EMPTY
            )
        processedDiff1.markProcessed()
        assertThat(processedDiff1.modifiedFileSet).isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        processedDiff1 =
            manager.getDiff(
                events.reporter(),
                pathEntry,
                IgnoredSubdirectories.of(com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("path/ignored-path-1"))),
                OptionsProvider.EMPTY
            )
        processedDiff1.markProcessed()
        assertThat(processedDiff1.modifiedFileSet).isEqualTo(diff1)

        var processedDiff2: ProcessableModifiedFileSet =
            manager.getDiff(
                events.reporter(),
                pathEntry,
                IgnoredSubdirectories.of(com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("path/ignored-path-2"))),
                OptionsProvider.EMPTY
            )
        processedDiff2.markProcessed()
        assertThat(processedDiff2.modifiedFileSet).isEqualTo(ModifiedFileSet.EVERYTHING_MODIFIED)
        processedDiff2 =
            manager.getDiff(
                events.reporter(),
                pathEntry,
                IgnoredSubdirectories.of(com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("path/ignored-path-2"))),
                OptionsProvider.EMPTY
            )
        processedDiff2.markProcessed()
        assertThat(processedDiff2.modifiedFileSet).isEqualTo(diff2)
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diff_cleanBuild_propagatesWorkspaceInfo: Unit
        get() {
            val pathEntry: Root? = Root.fromPath(fs.getPath("/path"))
            val workspaceInfo: WorkspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {}
            val diffAwareness: DiffAwareness = Mockito.mock<DiffAwareness>(DiffAwareness::class.java)
            Mockito.`when`<T?>(diffAwareness.getCurrentView(ArgumentMatchers.any<T?>()))
                .thenReturn(createView(workspaceInfo))
            val factory: DiffAwareness.Factory = Mockito.mock<DiffAwareness.Factory>(DiffAwareness.Factory::class.java)
            Mockito.`when`<T?>(factory.maybeCreate(pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY))
                .thenReturn(diffAwareness)
            val manager: DiffAwarenessManager =
                DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))

            val diff: ProcessableModifiedFileSet =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            assertThat(diff.workspaceInfo).isSameInstanceAs(workspaceInfo)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diff_incrementalBuild_propagatesLatestWorkspaceInfo: Unit
        get() {
            val pathEntry: Root? = Root.fromPath(fs.getPath("/path"))
            val workspaceInfo1: WorkspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {}
            val workspaceInfo2: WorkspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {}
            val diffAwareness: DiffAwareness = Mockito.mock<DiffAwareness>(DiffAwareness::class.java)
            val view1: View = createView(workspaceInfo1)
            val view2: View = createView(workspaceInfo2)
            Mockito.`when`<T?>(diffAwareness.getCurrentView(ArgumentMatchers.any<T?>())).thenReturn(view1, view2)
            Mockito.`when`<T?>(diffAwareness.getDiff(view1, view2))
                .thenReturn(ModifiedFileSet.builder().modify(PathFragment.create("file")).build())
            val factory: DiffAwareness.Factory = Mockito.mock<DiffAwareness.Factory>(DiffAwareness.Factory::class.java)
            Mockito.`when`<T?>(factory.maybeCreate(pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY))
                .thenReturn(diffAwareness)
            val manager: DiffAwarenessManager =
                DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            val diff: ProcessableModifiedFileSet =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            assertThat(diff.workspaceInfo).isSameInstanceAs(workspaceInfo2)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diff_incrementalBuildNoChange_propagatesNewWorkspaceInfo: Unit
        get() {
            val pathEntry: Root? = Root.fromPath(fs.getPath("/path"))
            val workspaceInfo1: WorkspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {}
            val workspaceInfo2: WorkspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {}
            val diffAwareness: DiffAwareness = Mockito.mock<DiffAwareness>(DiffAwareness::class.java)
            val view1: View = createView(workspaceInfo1)
            val view2: View = createView(workspaceInfo2)
            Mockito.`when`<T?>(diffAwareness.getCurrentView(ArgumentMatchers.any<T?>())).thenReturn(view1, view2)
            Mockito.`when`<T?>(diffAwareness.getDiff(view1, view2)).thenReturn(ModifiedFileSet.NOTHING_MODIFIED)
            val factory: DiffAwareness.Factory = Mockito.mock<DiffAwareness.Factory>(DiffAwareness.Factory::class.java)
            Mockito.`when`<T?>(factory.maybeCreate(pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY))
                .thenReturn(diffAwareness)
            val manager: DiffAwarenessManager =
                DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            val diff: ProcessableModifiedFileSet =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            assertThat(diff.workspaceInfo).isSameInstanceAs(workspaceInfo2)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diff_incrementalBuildWithNoWorkspaceInfo_returnsDiffWithNullWorkspaceInfo: Unit
        get() {
            val pathEntry: Root? = Root.fromPath(fs.getPath("/path"))
            val diffAwareness: DiffAwareness = Mockito.mock<DiffAwareness>(DiffAwareness::class.java)
            val view1: View = createView(object : WorkspaceInfoFromDiff() {})
            val view2: View = createView( /*workspaceInfo=*/null)
            Mockito.`when`<T?>(diffAwareness.getCurrentView(ArgumentMatchers.any<T?>())).thenReturn(view1, view2)
            Mockito.`when`<T?>(diffAwareness.getDiff(view1, view2))
                .thenReturn(ModifiedFileSet.builder().modify(PathFragment.create("file")).build())
            val factory: DiffAwareness.Factory = Mockito.mock<DiffAwareness.Factory>(DiffAwareness.Factory::class.java)
            Mockito.`when`<T?>(factory.maybeCreate(pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY))
                .thenReturn(diffAwareness)
            val manager: DiffAwarenessManager =
                DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            val diff: ProcessableModifiedFileSet =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            assertThat(diff.workspaceInfo).isNull()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diff_brokenDiffAwareness_returnsDiffWithNullWorkspaceInfo: Unit
        get() {
            val pathEntry: Root? = Root.fromPath(fs.getPath("/path"))
            val workspaceInfo1: WorkspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {}
            val workspaceInfo2: WorkspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {}
            val diffAwareness: DiffAwareness = Mockito.mock<DiffAwareness>(DiffAwareness::class.java)
            val view1: View = createView(workspaceInfo1)
            val view2: View = createView(workspaceInfo2)
            Mockito.`when`<T?>(diffAwareness.getCurrentView(ArgumentMatchers.any<T?>())).thenReturn(view1, view2)
            Mockito.`when`<T?>(diffAwareness.getDiff(view1, view2)).thenThrow(BrokenDiffAwarenessException::class.java)
            val factory: DiffAwareness.Factory = Mockito.mock<DiffAwareness.Factory>(DiffAwareness.Factory::class.java)
            Mockito.`when`<T?>(factory.maybeCreate(pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY))
                .thenReturn(diffAwareness)
            val manager: DiffAwarenessManager =
                DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
            val diff1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )
            diff1.markProcessed()

            val diff: ProcessableModifiedFileSet =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )

            assertThat(diff.workspaceInfo).isNull()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diff_incompatibleDiff_fails: Unit
        get() {
            val pathEntry: Root? = Root.fromPath(fs.getPath("/path"))
            val diffAwareness: DiffAwareness = Mockito.mock<DiffAwareness>(DiffAwareness::class.java)
            val view1: View = createView( /*workspaceInfo=*/null)
            val view2: View = createView( /*workspaceInfo=*/null)
            Mockito.`when`<T?>(diffAwareness.getCurrentView(ArgumentMatchers.any<T?>())).thenReturn(view1, view2)
            Mockito.`when`<T?>(diffAwareness.getDiff(view1, view2)).thenThrow(IncompatibleViewException::class.java)
            val factory: DiffAwareness.Factory = Mockito.mock<DiffAwareness.Factory>(DiffAwareness.Factory::class.java)
            Mockito.`when`<T?>(factory.maybeCreate(pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY))
                .thenReturn(diffAwareness)
            val manager: DiffAwarenessManager =
                DiffAwarenessManager(com.google.common.collect.ImmutableList.of<E?>(factory))
            val diff1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                manager.getDiff(
                    events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                )
            diff1.markProcessed()

            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    manager.getDiff(
                        events.reporter(), pathEntry, IgnoredSubdirectories.EMPTY, OptionsProvider.EMPTY
                    )
                })
        }

    private class DiffAwarenessFactoryStub : DiffAwareness.Factory {
        private val diffAwarenesses: MutableMap<Root?, DiffAwareness?> =
            com.google.common.collect.Maps.newHashMap<Root?, DiffAwareness?>()

        fun inject(pathEntry: Root?, diffAwareness: DiffAwareness?) {
            diffAwarenesses.put(pathEntry, diffAwareness)
        }

        fun remove(pathEntry: Root?) {
            diffAwarenesses.remove(pathEntry)
        }

        public override fun maybeCreate(
            pathEntry: Root?, ignoredPaths: IgnoredSubdirectories?, optionsProvider: OptionsProvider?
        ): DiffAwareness? {
            return diffAwarenesses.get(pathEntry)
        }
    }

    private class DiffAwarenessStub(sequentialDiffs: MutableList<ModifiedFileSet>, brokenViewNum: Int) : DiffAwareness {
        private var closed = false
        private var curSequenceNum = 0
        private val sequentialDiffs: MutableList<ModifiedFileSet>
        private val brokenViewNum: Int

        constructor(sequentialDiffs: MutableList<ModifiedFileSet>) : this(sequentialDiffs, -1)

        init {
            com.google.common.base.Preconditions.checkArgument(
                sequentialDiffs.stream().noneMatch(ModifiedFileSet::treatEverythingAsModified),
                "Merging of diffs treating everything as modified is not implemented: %s",
                sequentialDiffs
            )
            this.sequentialDiffs = sequentialDiffs
            this.brokenViewNum = brokenViewNum
        }

        private class ViewStub(private val sequenceNum: Int) : DiffAwareness.View

        @Throws(BrokenDiffAwarenessException::class)
        public override fun getCurrentView(options: OptionsProvider?): View {
            if (curSequenceNum == brokenViewNum) {
                throw BrokenDiffAwarenessException("error in getCurrentView")
            }
            return ViewStub(curSequenceNum++)
        }

        @Throws(BrokenDiffAwarenessException::class)
        public override fun getDiff(oldView: View?, newView: View?): ModifiedFileSet {
            if (oldView == null) {
                return ModifiedFileSet.EVERYTHING_MODIFIED
            }

            assertThat(oldView).isInstanceOf(ViewStub::class.java)
            assertThat(newView).isInstanceOf(ViewStub::class.java)
            val oldViewStub = oldView as ViewStub
            val newViewStub = newView as ViewStub
            com.google.common.base.Preconditions.checkState(newViewStub.sequenceNum >= oldViewStub.sequenceNum)
            val diff: ModifiedFileSet.Builder = ModifiedFileSet.builder()
            for (num in oldViewStub.sequenceNum..<newViewStub.sequenceNum) {
                val incrementalDiff: ModifiedFileSet = sequentialDiffs.get(num)
                if (incrementalDiff === BROKEN_DIFF) {
                    throw BrokenDiffAwarenessException("error in getDiff")
                }
                diff.modifyAll(incrementalDiff.modifiedSourceFiles())
            }
            return diff.build()
        }

        public override fun name(): String {
            return "testingstub"
        }

        public override fun close() {
            closed = true
        }

        fun closed(): Boolean {
            return closed
        }

        companion object {
            val BROKEN_DIFF: ModifiedFileSet? =
                ModifiedFileSet.builder().modify(PathFragment.create("special broken marker")).build()
        }
    }

    companion object {
        private fun createView(workspaceInfo: WorkspaceInfoFromDiff?): View {
            return object : View() {
                val workspaceInfo: WorkspaceInfoFromDiff?
                    get() = workspaceInfo
            }
        }

        private fun modifiedFileSet(vararg paths: String?): ModifiedFileSet {
            val modifiedFileSet: ModifiedFileSet.Builder = ModifiedFileSet.builder()
            for (path in paths) {
                modifiedFileSet.modify(PathFragment.create(path))
            }
            return modifiedFileSet.build()
        }
    }
}
