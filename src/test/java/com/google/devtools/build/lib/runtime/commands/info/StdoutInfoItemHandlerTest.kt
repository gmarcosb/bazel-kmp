// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands.info

import com.google.devtools.build.lib.runtime.CommandEnvironment

@RunWith(JUnit4::class)
class StdoutInfoItemHandlerTest {
    @org.junit.Test
    fun testStdOutputItemHandlerCreation() {
        val infoItemHandler: InfoItemHandler =
            InfoItemHandlerFactoryImpl()
                .create(
                    Mockito.mock<CommandEnvironment?>(CommandEnvironment::class.java),
                    InfoItemOutputType.STDOUT,  /* printKeys= */
                    true
                )
        Truth.assertThat(infoItemHandler).isInstanceOf(StdoutInfoItemHandler::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStdOutputItemHandler_addOneItemWithoutPrintingKey() {
        val outErr: RecordingOutErr = RecordingOutErr()
        StdoutInfoItemHandler(outErr,  /* printKeys= */false).use { stdoutInfoItemHandler ->
            stdoutInfoItemHandler.addInfoItem(
                "info-1",
                "value-1\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
        }
        assertThat(outErr.outAsLatin1()).isEqualTo("value-1\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStdOutputItemHandler_addTwoItemWithPrintingKey() {
        val outErr: RecordingOutErr = RecordingOutErr()
        StdoutInfoItemHandler(outErr,  /* printKeys= */true).use { stdoutInfoItemHandler ->
            stdoutInfoItemHandler.addInfoItem("foo", "value-foo\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            stdoutInfoItemHandler.addInfoItem("bar", "value-bar\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }
        assertThat(outErr.outAsLatin1()).isEqualTo("foo: value-foo\nbar: value-bar\n")
    }
}
