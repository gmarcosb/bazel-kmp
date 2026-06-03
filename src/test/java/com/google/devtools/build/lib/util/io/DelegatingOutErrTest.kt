// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.util.StringUtilities.joinLines
import org.junit.After
import org.junit.Test

/**
 * A test for [DelegatingOutErr].
 */
@RunWith(JUnit4::class)
class DelegatingOutErrTest {
    private var delegate: DelegatingOutErr? = null

    @Before
    fun createDelegate() {
        delegate = DelegatingOutErr()
    }

    @After
    @Throws(Exception::class)
    fun closeDelegate() {
        delegate.close()
    }

    @Test
    fun testNewDelegateIsLikeDevNull() {
        delegate.printOut("Hello, world.\n")
        delegate.printErr("Feel free to ignore me.\n")
    }

    @Test
    fun testSubscribeAndUnsubscribeSink() {
        delegate.printOut("Nobody will listen to this.\n")
        val sink: RecordingOutErr = RecordingOutErr()
        delegate.addSink(sink)
        delegate.printOutLn("Hello, sink.")
        delegate.removeSink(sink)
        delegate.printOutLn("... and alone again ...")
        delegate.addSink(sink)
        delegate.printOutLn("How are things?")
        assertThat(sink.outAsLatin1()).isEqualTo("Hello, sink.\nHow are things?\n")
    }

    @Test
    fun testSubscribeMultipleSinks() {
        val left: RecordingOutErr = RecordingOutErr()
        val right: RecordingOutErr = RecordingOutErr()
        delegate.addSink(left)
        delegate.printOutLn("left only")
        delegate.addSink(right)
        delegate.printOutLn("both")
        delegate.removeSink(left)
        delegate.printOutLn("right only")
        delegate.removeSink(right)
        delegate.printOutLn("silence")
        delegate.addSink(left)
        delegate.addSink(right)
        delegate.printOutLn("left and right")
        assertThat(left.outAsLatin1()).isEqualTo(joinLines("left only", "both", "left and right", ""))
        assertThat(right.outAsLatin1())
            .isEqualTo(joinLines("both", "right only", "left and right", ""))
    }
}
