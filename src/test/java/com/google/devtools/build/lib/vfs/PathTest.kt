// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/** Test for [Path].  */
@RunWith(JUnit4::class)
class PathTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serialization_roundTripsEqualObject() {
        val fileSystem: FileSystem = SerializableMockFileSystem.instance
        val path: Path? = fileSystem.getPath("/file")

        SerializationTester(path).runTests()
    }

    @AutoCodec
    internal object SerializableMockFileSystem : DelegateFileSystem() {
        // Used by (de)serialization.
        @get:Instantiator
        @get:Suppress("unused")
        val instance: SerializableMockFileSystem = SerializableMockFileSystem()

        private fun createMock(): FileSystem {
            val fileSystem: FileSystem = Mockito.mock<FileSystem>(FileSystem::class.java)
            Mockito.`when`<T?>(fileSystem.getDigestFunction()).thenReturn(DigestHashFunction.SHA256)
            return fileSystem
        }
    }
}
