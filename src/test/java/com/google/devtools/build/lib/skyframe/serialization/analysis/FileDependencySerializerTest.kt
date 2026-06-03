// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.actions.FileValue

@RunWith(JUnit4::class)
class FileDependencySerializerTest {
    @org.junit.Rule
    val mocks: MockitoRule = MockitoJUnit.rule()

    private val executor: java.util.concurrent.Executor = ForkJoinPool(THREAD_COUNT)

    @org.mockito.Mock
    private val versionGetter: LongVersionGetter? = null

    @org.mockito.Mock
    private val graph: InMemoryGraph? = null

    @org.mockito.Mock
    private val writer: KeyValueWriter? = null

    @org.mockito.Mock
    private val nodeEntry: InMemoryNodeEntry? = null

    private var serializer: FileDependencySerializer? = null
    private var root: Root? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        root = Root.fromPath(fs.getPath("/root"))
        root.asPath().createDirectoryAndParents()
        serializer = FileDependencySerializer(versionGetter, graph, writer, executor, null)
    }

    @org.junit.Test
    fun missingNodeEntry_incrementsErrorCounter() {
        val key: FileKey? = FileKey.create(RootedPath.toRootedPath(root, PathFragment.create("missing.txt")))
        Mockito.`when`<T?>(graph.getIfPresent(key)).thenReturn(null)

        val result: FileDataInfoOrFuture = serializer.registerDependency(key)

        assertThat(result).isInstanceOf(FutureFileDataInfo::class.java)
        val e: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { (result as FutureFileDataInfo).get() })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(MissingSkyframeEntryException::class.java)
        assertThat(serializer.counters.nodesWithProcessingErrors.get()).isEqualTo(1)
        assertThat(serializer.counters.nodesWaitingForDeps.get()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rootDirectoryDependency_isConstantAndDecrementsCounter() {
        val key: FileKey? = FileKey.create(RootedPath.toRootedPath(root, PathFragment.EMPTY_FRAGMENT))

        val result: FileDataInfoOrFuture? = serializer.registerDependency(key)

        assertThat(result).isEqualTo(InvalidationDataInfoOrFuture.ConstantFileData.CONSTANT_FILE)
        assertThat(serializer.counters.nodesWaitingForDeps.get()).isEqualTo(0)
        assertThat(serializer.counters.nodesWithProcessingErrors.get()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkResolutionFailure_incrementsErrorCounter() {
        val symlinkPathFragment: PathFragment? = PathFragment.create("symlink.txt")
        val targetPathFragment: PathFragment? = PathFragment.create("target.txt")
        val symlinkRootedPath: RootedPath = RootedPath.toRootedPath(root, symlinkPathFragment)
        val symlinkKey: FileKey? = FileKey.create(symlinkRootedPath)

        val symlinkFsv: FileValue = Mockito.mock<FileValue>(FileValue::class.java)
        Mockito.`when`<T?>(symlinkFsv.isSymlink()).thenReturn(true)
        Mockito.`when`<T?>(symlinkFsv.getUnresolvedLinkTarget()).thenReturn(targetPathFragment)
        Mockito.`when`<T?>(symlinkFsv.realRootedPath(symlinkRootedPath)).thenReturn(symlinkRootedPath)
        Mockito.`when`<T?>(symlinkFsv.exists()).thenReturn(true)
        Mockito.`when`<T?>(symlinkFsv.isDirectory()).thenReturn(false)
        Mockito.`when`<Any?>(nodeEntry.value).thenReturn(symlinkFsv)
        Mockito.`when`<T?>(graph.getIfPresent(symlinkKey)).thenReturn(nodeEntry)

        // Symlink resolution calls getVersion on link path.
        Mockito.`when`<Long?>(versionGetter.getFilePathOrSymlinkVersion(symlinkRootedPath.asPath())).thenReturn(2L)

        // Create the failure mode where the symlink target does not exist in graph.
        val targetRootedPath: RootedPath? = RootedPath.toRootedPath(root, targetPathFragment)
        Mockito.`when`<T?>(graph.getIfPresent(targetRootedPath)).thenReturn(null)

        val result: FileDataInfoOrFuture = serializer.registerDependency(symlinkKey)

        assertThat(result).isInstanceOf(FutureFileDataInfo::class.java)
        val e: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { (result as FutureFileDataInfo).get() })
        Truth.assertThat(e).hasCauseThat().isInstanceOf(MissingSkyframeEntryException::class.java)
        assertThat(serializer.counters.nodesWithProcessingErrors.get()).isEqualTo(1)
        assertThat(serializer.counters.nodesWaitingForDeps.get()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun registerFileDependency_recordsSamples() {
        val profileCollector: ProfileCollector = ProfileCollector()
        serializer =
            FileDependencySerializer(versionGetter, graph, writer, executor, profileCollector)

        val filePathFragment: PathFragment? = PathFragment.create("file.txt")
        val rootedPath: RootedPath = RootedPath.toRootedPath(root, filePathFragment)
        val key: FileKey? = FileKey.create(rootedPath)

        val fsv: FileValue = Mockito.mock<FileValue>(FileValue::class.java)
        Mockito.`when`<T?>(fsv.isSymlink()).thenReturn(false)
        Mockito.`when`<T?>(fsv.realRootedPath(rootedPath)).thenReturn(rootedPath)
        Mockito.`when`<T?>(fsv.exists()).thenReturn(true)
        Mockito.`when`<T?>(fsv.isDirectory()).thenReturn(false)
        Mockito.`when`<Any?>(nodeEntry.value).thenReturn(fsv)
        Mockito.`when`<T?>(graph.getIfPresent(key)).thenReturn(nodeEntry)

        Mockito.`when`<Long?>(versionGetter.getFilePathOrSymlinkVersion(rootedPath.asPath())).thenReturn(2L)

        val writeStatus: SettableWriteStatus = SettableWriteStatus()
        Mockito.`when`<T?>(writer.put(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())).thenReturn(writeStatus)

        val result: FileDataInfoOrFuture = serializer.registerDependency(key)
        (result as FutureFileDataInfo).get()

        // Not novel yet, no samples.
        assertThat(profileCollector.toProto().getSampleCount()).isEqualTo(0)

        writeStatus.markSuccess(true) // was novel

        // Samples should be recorded now.
        val profile: Profile = profileCollector.toProto()
        assertThat(profile.getSampleCount()).isGreaterThan(0)
    }

    companion object {
        private const val THREAD_COUNT = 10
    }
}
