// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream.transports

import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode
import io.netty.util.AbstractReferenceCounted
import io.netty.util.ReferenceCounted
import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.mockito.Mock
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Future

/** Tests [BinaryFormatFileTransport].  */
@RunWith(JUnit4::class)
class BinaryFormatFileTransportTest {
    private val defaultOpts: BuildEventProtocolOptions = Options.getDefaults(BuildEventProtocolOptions::class.java)

    @Rule
    var tmp: TemporaryFolder = TemporaryFolder()

    @Mock
    var buildEvent: BuildEvent? = null

    @Mock
    var artifactGroupNamer: ArtifactGroupNamer? = null

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @After
    fun tearDown() {
        Mockito.validateMockitoUsage()
    }

    @Test
    @Throws(Exception::class)
    fun testCreatesFileAndWritesProtoBinaryFormat() {
        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output.getAbsolutePath())))

        val started: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setStarted(BuildStarted.newBuilder().setCommand("build"))
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<BuildEventContext?>())).thenReturn(started)
        val transport =
            BinaryFormatFileTransport(
                outputStream,
                defaultOpts,
                LocalFilesArtifactUploader(),
                artifactGroupNamer,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )
        transport.sendBuildEvent(buildEvent)

        val progress: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setProgress(Progress.getDefaultInstance())
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<BuildEventContext?>())).thenReturn(progress)
        transport.sendBuildEvent(buildEvent)

        val completed: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setCompleted(TargetComplete.newBuilder().setSuccess(true))
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<BuildEventContext?>())).thenReturn(completed)
        transport.sendBuildEvent(buildEvent)

        transport.close().get()
        FileInputStream(output).use { `in` ->
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`)).isEqualTo(started)
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`)).isEqualTo(progress)
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`)).isEqualTo(completed)
            Truth.assertThat(`in`.available()).isEqualTo(0)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testCancelledUpload() {
        val file1: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(file1.getBaseName()).thenReturn("foo")
        val event1: BuildEvent = WithLocalFilesEvent(ImmutableList.of<Path?>(file1))

        val uploader: BuildEventArtifactUploader =
            Mockito.spy(
                object : BuildEventArtifactUploaderWithRefCounting() {
                    public override fun upload(files: MutableMap<Path?, LocalFile?>?): ListenableFuture<PathConverter?> {
                        return Futures.immediateCancelledFuture<PathConverter?>()
                    }

                    public override fun mayBeSlow(): Boolean {
                        return false
                    }
                })

        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output.getAbsolutePath())))
        val transport =
            BinaryFormatFileTransport(
                outputStream,
                defaultOpts,
                uploader,
                artifactGroupNamer,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )
        transport.sendBuildEvent(event1)

        val expected: ExecutionException? =
            Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                ThrowingRunnable { transport.close().get() })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Unable to write all BEP events to file due to 'Task was cancelled.'")

        FileInputStream(output).use { `in` ->
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`)).isNull()
            Truth.assertThat(`in`.available()).isEqualTo(0)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testWriteWhenFileClosed() {
        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output.getAbsolutePath())))

        val started: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setStarted(BuildStarted.newBuilder().setCommand("build"))
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<BuildEventContext?>())).thenReturn(started)

        val transport =
            BinaryFormatFileTransport(
                outputStream,
                defaultOpts,
                LocalFilesArtifactUploader(),
                artifactGroupNamer,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )

        transport.close().get()

        // This should not throw an exception.
        transport.sendBuildEvent(buildEvent)
        transport.close().get()

        FileInputStream(output).use { `in` ->
            Truth.assertThat(`in`.available()).isEqualTo(0)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testWriteWhenTransportClosed() {
        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output.getAbsolutePath())))

        val started: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setStarted(BuildStarted.newBuilder().setCommand("build"))
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<BuildEventContext?>())).thenReturn(started)

        val transport =
            BinaryFormatFileTransport(
                outputStream,
                defaultOpts,
                LocalFilesArtifactUploader(),
                artifactGroupNamer,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )

        transport.sendBuildEvent(buildEvent)
        val closeFuture: Future<Void?> = transport.close()
        closeFuture.get()
        // This should not throw an exception, but also not perform any write.
        transport.sendBuildEvent(buildEvent)

        FileInputStream(output).use { `in` ->
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`)).isEqualTo(started)
            Truth.assertThat(`in`.available()).isEqualTo(0)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testWritesWithUploadDelays() {
        // Test that events are written in order if the first event
        // has to wait a bit for local file uploads to finish.

        val file1: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(file1.getBaseName()).thenReturn("file1")
        val file2: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(file2.getBaseName()).thenReturn("file2")
        val event1: BuildEvent = WithLocalFilesEvent(ImmutableList.of<Path?>(file1))
        val event2: BuildEvent = WithLocalFilesEvent(ImmutableList.of<Path?>(file2))

        val uploader: BuildEventArtifactUploader =
            Mockito.spy(
                object : BuildEventArtifactUploaderWithRefCounting() {
                    public override fun upload(files: MutableMap<Path?, LocalFile?>): ListenableFuture<PathConverter?> {
                        if (files.containsKey(file1)) {
                            LockSupport.parkNanos(Duration.ofMillis(200).toNanos())
                        }
                        return Futures.immediateFuture<PathConverter?>(FileUriPathConverter())
                    }

                    public override fun mayBeSlow(): Boolean {
                        return true
                    }
                })
        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output.getAbsolutePath())))
        val transport =
            BinaryFormatFileTransport(
                outputStream,
                defaultOpts,
                uploader,
                artifactGroupNamer,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )
        transport.sendBuildEvent(event1)
        transport.sendBuildEvent(event2)
        transport.close().get()

        FileInputStream(output).use { `in` ->
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`))
                .isEqualTo(event1.asStreamProto(null))
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`))
                .isEqualTo(event2.asStreamProto(null))
            Truth.assertThat(`in`.available()).isEqualTo(0)
        }
        Mockito.verify<Any?>(uploader).release()
    }

    /** Regression test for b/207287675  */
    @Test
    @Throws(Exception::class)
    fun testHandlesDuplicateFiles() {
        val file1: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(file1.getBaseName()).thenReturn("foo")
        val event1: BuildEvent = WithLocalFilesEvent(ImmutableList.of<Path?>(file1, file1))

        val uploader: BuildEventArtifactUploader =
            Mockito.spy(
                object : BuildEventArtifactUploaderWithRefCounting() {
                    public override fun upload(files: MutableMap<Path?, LocalFile?>?): ListenableFuture<PathConverter?> {
                        return Futures.immediateFuture<PathConverter?>(FileUriPathConverter())
                    }

                    public override fun mayBeSlow(): Boolean {
                        return false
                    }
                })
        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output.getAbsolutePath())))
        val transport =
            BinaryFormatFileTransport(
                outputStream,
                defaultOpts,
                uploader,
                artifactGroupNamer,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )
        transport.sendBuildEvent(event1)
        transport.close().get()

        FileInputStream(output).use { `in` ->
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`))
                .isEqualTo(event1.asStreamProto(null))
            Truth.assertThat(`in`.available()).isEqualTo(0)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testCloseWaitsForWritesToFinish() {
        // Test that .close() waits for all writes to finish.

        val file1: Path = Mockito.mock<Path>(Path::class.java)
        Mockito.`when`<T?>(file1.getBaseName()).thenReturn("file1")
        val event: BuildEvent = WithLocalFilesEvent(ImmutableList.of<Path?>(file1))

        val upload: SettableFuture<PathConverter?> = SettableFuture.create<PathConverter?>()
        val uploader: BuildEventArtifactUploader =
            Mockito.spy(
                object : BuildEventArtifactUploaderWithRefCounting() {
                    public override fun upload(files: MutableMap<Path?, LocalFile?>?): ListenableFuture<PathConverter?> {
                        return upload
                    }

                    public override fun mayBeSlow(): Boolean {
                        return false
                    }
                })

        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output.getAbsolutePath())))
        val transport =
            BinaryFormatFileTransport(
                outputStream,
                defaultOpts,
                uploader,
                artifactGroupNamer,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )
        transport.sendBuildEvent(event)
        val closeFuture = transport.close()

        upload.set(PathConverter.NO_CONVERSION)

        closeFuture.get()

        FileInputStream(output).use { `in` ->
            assertThat(BuildEventStreamProtos.BuildEvent.parseDelimitedFrom(`in`))
                .isEqualTo(event.asStreamProto(null))
            Truth.assertThat(`in`.available()).isEqualTo(0)
        }
        Mockito.verify<Any?>(uploader).release()
    }

    private class WithLocalFilesEvent(files: ImmutableList<Path?>) : BuildEvent {
        var id: Int = 0
        var files: ImmutableList<Path?>

        init {
            this.files = files
        }

        public override fun referencedLocalFiles(): MutableCollection<LocalFile?> {
            return files.stream()
                .map<Any?> { f: Path? -> LocalFile(f, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null) }
                .collect(ImmutableList.toImmutableList<Any?>())
        }

        public override fun asStreamProto(context: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
            return BuildEventStreamProtos.BuildEvent.newBuilder()
                .setId(BuildEventIdUtil.progressId(id))
                .setProgress(
                    BuildEventStreamProtos.Progress.newBuilder()
                        .setStdout(
                            "uploading: "
                                    + Joiner.on(", ")
                                .join(
                                    files.stream().map<Any?>(Path::getBaseName)
                                        .collect(ImmutableList.toImmutableList<Any?>())
                                )
                        )
                        .build()
                )
                .build()
        }

        val eventId: BuildEventId
            get() = BuildEventIdUtil.progressId(id)

        val childrenEvents: MutableCollection<BuildEventId>
            get() = ImmutableList.of<E?>(BuildEventIdUtil.progressId(id + 1))
    }

    private abstract class BuildEventArtifactUploaderWithRefCounting

        : AbstractReferenceCounted(), BuildEventArtifactUploader {
        override fun deallocate() {}

        override fun touch(o: Any?): ReferenceCounted {
            return this
        }
    }
}
