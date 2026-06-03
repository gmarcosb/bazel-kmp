// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.FileContentsProxy

/** Tests for [FileStateValue].  */
@RunWith(JUnit4::class)
class FileStateValueTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            RegularFileStateValueWithDigest( /* size= */
                1,  /* digest= */byteArrayOf(1, 2, 3)
            ),
            RegularFileStateValueWithDigest( /* size= */
                1,  /* digest= */ByteArray(0)
            ),
            RegularFileStateValueWithContentsProxy( /* size= */
                1, makeFileContentsProxy( /* ctime= */2,  /* nodeId= */42)
            ),
            SpecialFileStateValue(
                makeFileContentsProxy( /* ctime= */4,  /* nodeId= */84)
            ),
            FileStateValue.DIRECTORY_FILE_STATE_NODE,
            SymlinkFileStateValue(PathFragment.create("somewhere/elses")),
            FileStateValue.NONEXISTENT_FILE_STATE_NODE
        )
            .runTests()
    }

    companion object {
        @Throws(IOException::class)
        private fun makeFileContentsProxy(ctime: Long, nodeId: Long): FileContentsProxy {
            val status: FileStatus = Mockito.mock<FileStatus>(FileStatus::class.java)
            Mockito.`when`<Any?>(status.lastChangeTime).thenReturn(ctime)
            Mockito.`when`<Any?>(status.nodeId).thenReturn(nodeId)
            return FileContentsProxy.create(status)
        }
    }
}
