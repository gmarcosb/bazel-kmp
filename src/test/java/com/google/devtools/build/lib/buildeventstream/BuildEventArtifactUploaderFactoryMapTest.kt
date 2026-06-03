// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile

/** Tests for [BuildEventArtifactUploaderFactoryMap].  */
@RunWith(JUnit4::class)
class BuildEventArtifactUploaderFactoryMapTest {
    private var uploaderFactories: BuildEventArtifactUploaderFactoryMap? = null
    private var noConversionUploaderFactory: BuildEventArtifactUploaderFactory? = null

    @Before
    fun setUp() {
        noConversionUploaderFactory =
            BuildEventArtifactUploaderFactory { env: CommandEnvironment? ->
                object : BuildEventArtifactUploader() {
                    public override fun upload(files: MutableMap<Path?, LocalFile?>?): com.google.common.util.concurrent.ListenableFuture<PathConverter?> {
                        return@BuildEventArtifactUploaderFactory com.google.common.util.concurrent.Futures.immediateFuture<PathConverter?>(
                            PathConverter.NO_CONVERSION
                        )
                    }

                    public override fun mayBeSlow(): Boolean {
                        return@BuildEventArtifactUploaderFactory false
                    }

                    public override fun refCnt(): Int {
                        return@BuildEventArtifactUploaderFactory 0
                    }

                    public override fun retain(): io.netty.util.ReferenceCounted {
                        return@BuildEventArtifactUploaderFactory this
                    }

                    public override fun retain(i: Int): io.netty.util.ReferenceCounted {
                        return@BuildEventArtifactUploaderFactory this
                    }

                    public override fun touch(): io.netty.util.ReferenceCounted {
                        return@BuildEventArtifactUploaderFactory this
                    }

                    public override fun touch(o: Any?): io.netty.util.ReferenceCounted {
                        return@BuildEventArtifactUploaderFactory this
                    }

                    public override fun release(): Boolean {
                        return@BuildEventArtifactUploaderFactory false
                    }

                    public override fun release(i: Int): Boolean {
                        return@BuildEventArtifactUploaderFactory false
                    }
                }
            }
        uploaderFactories =
            Builder()
                .add("a", BuildEventArtifactUploaderFactory.LOCAL_FILES_UPLOADER_FACTORY)
                .add("b", noConversionUploaderFactory)
                .build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyUploaders() {
        val emptyUploader: BuildEventArtifactUploaderFactoryMap =
            Builder().build()
        assertThat(emptyUploader.select(null).create(null).getClass())
            .isEqualTo(LocalFilesArtifactUploader::class.java)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testAlphabeticalOrder() {
        assertThat(uploaderFactories.select(null).create(null).getClass())
            .isEqualTo(LocalFilesArtifactUploader::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectByName() {
        assertThat(uploaderFactories.select("b"))
            .isEqualTo(noConversionUploaderFactory)
    }
}
