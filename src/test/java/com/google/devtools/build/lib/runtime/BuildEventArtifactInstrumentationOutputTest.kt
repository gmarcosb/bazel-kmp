// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType

@RunWith(JUnit4::class)
class BuildEventArtifactInstrumentationOutputTest {
    private var bepInstrumentationOutputBuilder: BuildEventArtifactInstrumentationOutput.Builder? = null

    @Before
    fun setup() {
        bepInstrumentationOutputBuilder = Builder()
    }

    @org.junit.Test
    fun testBepInstrumentationBuilder_failToBuildWhenMissingName() {
        val throwable: Throwable? =
            org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                java.lang.NullPointerException::class.java,
                bepInstrumentationOutputBuilder.setUploader(< T > mock < T ? > (BuildEventArtifactUploader::class.java)
            )
        ::build)
        Truth.assertThat(throwable)
            .hasMessageThat()
            .isEqualTo("Cannot create BuildEventArtifactInstrumentationOutput without name")
    }

    @org.junit.Test
    fun testBepInstrumentationBuilder_failToBuildWhenMissingBepUploader() {
        val throwable: Throwable? =
            org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                java.lang.NullPointerException::class.java, bepInstrumentationOutputBuilder.setName("bep")::build
            )
        Truth.assertThat(throwable)
            .hasMessageThat()
            .isEqualTo("Cannot create BuildEventArtifactInstrumentationOutput without bepUploader")
    }

    @org.junit.Test
    fun testBepInstrumentation_cannotPublishIfUploadNeverStarts() {
        val fakeBuildEventArtifactUploader: BuildEventArtifactUploader? =
            Mockito.mock<BuildEventArtifactUploader?>(BuildEventArtifactUploader::class.java)
        val bepInstrumentationOutput: InstrumentationOutput =
            bepInstrumentationOutputBuilder
                .setName("bep")
                .setUploader(fakeBuildEventArtifactUploader)
                .build()

        val buildToolLogCollection: BuildToolLogCollection = BuildToolLogCollection()
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { bepInstrumentationOutput.publish(buildToolLogCollection) })
    }

    @org.junit.Test
    @Throws(ExecutionException::class, java.lang.InterruptedException::class, IOException::class)
    fun testBepInstrumentation_publishNameAndUriFuture() {
        val fakeUploadLoadContext: UploadContext =
            object : UploadContext() {
                val outputStream: java.io.OutputStream
                    get() = java.io.ByteArrayOutputStream()

                public override fun uriFuture(): com.google.common.util.concurrent.ListenableFuture<String?> {
                    return com.google.common.util.concurrent.Futures.immediateFuture<String?>("uri/abc12345")
                }
            }
        val fakeBuildEventArtifactUploader: BuildEventArtifactUploader =
            Mockito.mock<BuildEventArtifactUploader>(BuildEventArtifactUploader::class.java)
        Mockito.`when`<T?>(fakeBuildEventArtifactUploader.startUpload(LocalFileType.LOG, null))
            .thenReturn(fakeUploadLoadContext)

        val bepInstrumentationOutput: InstrumentationOutput =
            bepInstrumentationOutputBuilder
                .setName("bep")
                .setUploader(fakeBuildEventArtifactUploader)
                .build()
        // Create the OutputStream will enforce fakeBuildEventArtifactUploader to create the
        // uploadContext.
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            bepInstrumentationOutput.createOutputStream()
        assertThat(bepInstrumentationOutput)
            .isInstanceOf(BuildEventArtifactInstrumentationOutput::class.java)

        val buildToolLogCollection: BuildToolLogCollection = BuildToolLogCollection()
        bepInstrumentationOutput.publish(buildToolLogCollection)
        buildToolLogCollection.freeze()

        assertThat(buildToolLogCollection.toEvent().remoteUploads()).hasSize(1)
        val soleRemoteUploadUri: com.google.common.util.concurrent.ListenableFuture<String?> =
            buildToolLogCollection.toEvent().remoteUploads().get(0)
        Truth.assertThat(soleRemoteUploadUri.get()).isEqualTo("uri/abc12345")
    }
}
