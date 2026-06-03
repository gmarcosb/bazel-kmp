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
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Tests [TextFormatFileTransport].  */
@RunWith(JUnit4::class)
class TextFormatFileTransportTest {
    private val defaultOpts: BuildEventProtocolOptions = Options.getDefaults(BuildEventProtocolOptions::class.java)

    @Rule
    var tmp: TemporaryFolder = TemporaryFolder()

    @Mock
    var buildEvent: BuildEvent? = null

    @Mock
    var pathConverter: PathConverter? = null

    @Mock
    var artifactGroupNamer: ArtifactGroupNamer? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @After
    fun tearDown() {
        Mockito.validateMockitoUsage()
    }

    @Test
    @Throws(Exception::class)
    fun testCreatesFileAndWritesProtoTextFormat() {
        val output = tmp.newFile()
        val outputStream: BufferedOutputStream =
            BufferedOutputStream(
                Files.newOutputStream(Paths.get(output.getAbsolutePath()))
            )

        val started: BuildEventStreamProtos.BuildEvent? =
            BuildEventStreamProtos.BuildEvent.newBuilder()
                .setStarted(BuildStarted.newBuilder().setCommand("build"))
                .build()
        Mockito.`when`<T?>(buildEvent.asStreamProto(ArgumentMatchers.any<BuildEventContext?>())).thenReturn(started)
        val transport =
            TextFormatFileTransport(
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
        val contents: String? =
            trimLines(Joiner.on("\n").join(com.google.common.io.Files.readLines(output, StandardCharsets.UTF_8)))

        Truth.assertThat(contents).contains(trimLines(TextFormat.printer().printToString(started)))
        Truth.assertThat(contents).contains(trimLines(TextFormat.printer().printToString(progress)))
        Truth.assertThat(contents).contains(trimLines(TextFormat.printer().printToString(completed)))
    }

    companion object {
        private fun trimLines(text: String): String? {
            // Replace CRLF with LF and trim leading and trailing spaces.
            return text.replace("\\r".toRegex(), "").replace(" *\\n *".toRegex(), "\n")
        }
    }
}
