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

import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.quality.Strictness
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Tests [JsonFormatFileTransport].  */
@RunWith(JUnit4::class)
class JsonFormatFileTransportTest {
    private val defaultOpts: BuildEventProtocolOptions = Options.getDefaults(BuildEventProtocolOptions::class.java)

    @Rule
    var tmp: TemporaryFolder = TemporaryFolder()

    @Rule
    var mocks: MockitoRule? = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

    @Mock
    var buildEvent: BuildEvent? = null

    @Mock
    var pathConverter: PathConverter? = null

    @Mock
    var artifactGroupNamer: ArtifactGroupNamer? = null

    private var output: File? = null
    private var outputStream: BufferedOutputStream? = null
    private var underTest: JsonFormatFileTransport? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        output = tmp.newFile()
        outputStream =
            BufferedOutputStream(Files.newOutputStream(Paths.get(output!!.getAbsolutePath())))
        underTest =
            JsonFormatFileTransport(
                outputStream,
                defaultOpts,
                LocalFilesArtifactUploader(),
                artifactGroupNamer,
                SPAWN_EXEC_TYPE_REGISTRY,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )
    }

    @Test
    @Throws(Exception::class)
    fun testCreatesFileAndWritesProtoJsonFormat() {
        // Arrange: Prepare a simple BEP event that can be round-tripped.
        val started: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setStarted(BuildStarted.newBuilder().setCommand("build"))
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<T?>())).thenReturn(started)

        // Act: Send the simple BuildStarted event.
        underTest!!.sendBuildEvent(buildEvent)
        underTest!!.close().get()

        openOutputReader().use { reader ->
            val parser: JsonFormat.Parser = JsonFormat.parser()
            val builder: BuildEventStreamProtos.BuildEvent.Builder =
                BuildEventStreamProtos.BuildEvent.newBuilder()
            parser.merge(reader, builder)
            assertThat(builder.build()).isEqualTo(started)
        }
    }

    @Test
    @Throws(Exception::class)
    fun expandsKnownAnyType() {
        // Arrange: Prepare an Any event that is recognized by the JSON formatter.
        val spawnExecAny: Any? = Any.pack(SpawnExec.newBuilder().setExitCode(1).setMnemonic("Javac").build())
        val action: BuildEventStreamProtos.BuildEvent = makeActionEventWithAnyDetails(spawnExecAny)
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<T?>())).thenReturn(action)

        // Act: Write the event and close the transport to force flushing.
        underTest!!.sendBuildEvent(buildEvent)
        underTest!!.close().get()

        openOutputReader().use { reader ->
            val parser: JsonFormat.Parser = JsonFormat.parser().usingTypeRegistry(SPAWN_EXEC_TYPE_REGISTRY)
            val builder: BuildEventStreamProtos.BuildEvent.Builder =
                BuildEventStreamProtos.BuildEvent.newBuilder()
            parser.merge(reader, builder)
            assertThat(builder.build()).isEqualTo(action)
        }
    }

    @Test
    @Throws(Exception::class)
    fun rejectsUnknownAnyType() {
        // Arrange: Prepare a BuildEvent that cannot be serialized due to an unrecognized Any type.
        val bogusAnyProto: Any? =
            Any.pack(
                BuildEventId.newBuilder()
                    .setActionCompleted(ActionCompletedId.newBuilder().setLabel("//:foo"))
                    .build()
            )
        val action: BuildEventStreamProtos.BuildEvent = makeActionEventWithAnyDetails(bogusAnyProto)
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<T?>())).thenReturn(action)

        // Act: Send the event with a bogus Any value.
        underTest!!.sendBuildEvent(buildEvent)
        underTest!!.close().get()

        BufferedReader(openOutputReader()).use { reader ->
            val jsonLine: String? = reader.readLine()
            val error: UnknownAnyProtoError? =
                GsonBuilder().create().fromJson<UnknownAnyProtoError?>(jsonLine, UnknownAnyProtoError::class.java)
            Truth.assertThat(error).isNotNull()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testFlushesStreamAfterSmallWrites() {
        // Arrange: JsonFormatFileTransport writes to a wrapped output stream to verify flushing.
        val wrappedOutputStream = WrappedOutputStream(outputStream)
        underTest =
            JsonFormatFileTransport(
                wrappedOutputStream,
                defaultOpts,
                LocalFilesArtifactUploader(),
                artifactGroupNamer,
                SPAWN_EXEC_TYPE_REGISTRY,
                BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE
            )

        val started: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setStarted(BuildStarted.newBuilder().setCommand("build"))
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<T?>())).thenReturn(started)

        // Act: Write an event, then wait for three flush intervals.
        underTest!!.sendBuildEvent(buildEvent)
        Thread.sleep(underTest!!.flushInterval.toMillis() * 3)

        // Assert: Confirm BEP events were written even though the file transport is not closed.

        // Some users, e.g. Tulsi, use JSON build event output for interactive use and expect the stream
        // to be flushed at regular short intervals.
        Truth.assertThat(wrappedOutputStream.flushCount).isGreaterThan(0)

        // We know that large writes get flushed; test is valuable only if we check small writes,
        // meaning smaller than 8192, the default buffer size used by BufferedOutputStream.
        Truth.assertThat(wrappedOutputStream.byteCount).isLessThan(8192L)
        Truth.assertThat(wrappedOutputStream.byteCount).isGreaterThan(0L)

        underTest!!.close().get()
    }

    @Throws(FileNotFoundException::class)
    private fun openOutputReader(): InputStreamReader {
        return InputStreamReader(FileInputStream(output), StandardCharsets.UTF_8)
    }

    /**
     * A thin wrapper around an OutputStream that counts number of bytes written and verifies flushes.
     * 
     * 
     * The methods below need to be synchronized because they override methods from [ ] *not* because there is concurrent access to the stream.
     */
    private class WrappedOutputStream(out: OutputStream?) : BufferedOutputStream(out) {
        private var byteCount: Long = 0
        private var flushCount = 0

        init {
            this.out = out
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun write(b: Int) {
            out.write(b)
            byteCount++
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun write(b: ByteArray) {
            out.write(b)
            byteCount += b.size.toLong()
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            out.write(b, off, len)
            byteCount += len.toLong()
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun flush() {
            out.flush()
            flushCount++
        }
    }

    companion object {
        private val SPAWN_EXEC_TYPE_REGISTRY: TypeRegistry =
            TypeRegistry.newBuilder().add(SpawnExec.getDescriptor()).build()

        private fun makeActionEventWithAnyDetails(
            strategyDetails: Any?
        ): BuildEventStreamProtos.BuildEvent {
            return BuildEventStreamProtos.BuildEvent.newBuilder()
                .setAction(
                    ActionExecuted.newBuilder().setExitCode(1).addStrategyDetails(strategyDetails).build()
                )
                .build()
        }
    }
}
