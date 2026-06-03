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

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile

@RunWith(TestParameterInjector::class)
class LocalInstrumentationOutputTest {
    private var localInstrumentationOutputBuilder: LocalInstrumentationOutput.Builder? = null

    @Before
    fun setup() {
        localInstrumentationOutputBuilder = Builder()
    }

    @org.junit.Test
    fun testLocalInstrumentationOutputBuilder_failToBuildWhenMissingName() {
        val throwable: Throwable? =
            org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                java.lang.NullPointerException::class.java,
                localInstrumentationOutputBuilder.setPath(
                    InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/file")
                )
                ::build
            )
        Truth.assertThat(throwable)
            .hasMessageThat()
            .isEqualTo("Cannot create LocalInstrumentationOutputBuilder without name")
    }

    @org.junit.Test
    fun testLocalInstrumentationOutputBuilder_failToBuildWhenMissingPath() {
        val throwable: Throwable? =
            org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                java.lang.NullPointerException::class.java, localInstrumentationOutputBuilder.setName("local")::build
            )
        Truth.assertThat(throwable)
            .hasMessageThat()
            .isEqualTo("Cannot create LocalInstrumentationOutputBuilder without path")
    }

    @org.junit.Test
    fun testLocalInstrumentation_publishNameAndPath() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val path: Path? = fs.getPath("/file")
        val localInstrumentationOutput: InstrumentationOutput =
            localInstrumentationOutputBuilder.setName("local").setPath(path).build()

        assertThat(localInstrumentationOutput).isInstanceOf(LocalInstrumentationOutput::class.java)
        val buildToolLogCollection: BuildToolLogCollection = BuildToolLogCollection()
        localInstrumentationOutput.publish(buildToolLogCollection)
        buildToolLogCollection.freeze()

        assertThat(buildToolLogCollection.getLocalFiles())
            .containsExactly(
                LogFileEntry(
                    "local", LocalFile(path, LocalFileType.LOG,  /* artifactMetadata= */null)
                )
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testLocalInstrumentation_recursiveCreateParentDirectory(
        @TestParameter enableRecursiveCreateDirectory: Boolean
    ) {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val path: Path = fs.getPath("/subdir1/subdir2/file")
        assertThat(path.exists()).isFalse()

        localInstrumentationOutputBuilder.setName("recursive-dir-output").setPath(path)
        if (enableRecursiveCreateDirectory) {
            val localInstrumentationOutput: InstrumentationOutput =
                localInstrumentationOutputBuilder.setCreateParent( /* createParent= */true).build()
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                localInstrumentationOutput.createOutputStream()
            assertThat(path.exists()).isTrue()
        } else {
            val localInstrumentationOutput: InstrumentationOutput = localInstrumentationOutputBuilder.build()
            org.junit.Assert.assertThrows<IOException?>(
                "No such file or directory",
                IOException::class.java,
                localInstrumentationOutput::createOutputStream
            )
        }
    }
}
