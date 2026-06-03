// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.BlazeCommandDispatcher

/** Tests for [DumpCommand].  */
@RunWith(JUnit4::class)
class DumpCommandTest : BuildIntegrationTestCase() {
    private var dispatcher: BlazeCommandDispatcher? = null
    private var recordingOutErr: RecordingOutErr? = null

    @Before
    fun createDispatcher() {
        val runtime: BlazeRuntime = runtime
        runtime.getCommandMap().put("dump", DumpCommand())
        dispatcher = BlazeCommandDispatcher(runtime)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createRecording() {
        recordingOutErr = RecordingOutErr()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun dump(vararg args: String?): BlazeCommandResult {
        val params: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("dump")
        Collections.addAll<String?>(params, *args)
        return dispatcher.exec(params, "test", recordingOutErr)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotContainWarningInStdout() {
        assertThat(dump("--skyframe", "count").isSuccess()).isTrue()
        com.google.common.truth.Subject.contains(DumpCommand.WARNING_MESSAGE)
        assertThat(recordingOutErr.outAsLatin1()).doesNotContain(DumpCommand.WARNING_MESSAGE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiOptionSmoke() {
        write("foo/BUILD", "genrule(name = 'foo', outs = ['out'], cmd = 'touch $@')")
        addOptions("--nobuild")
        buildTarget("//foo:foo")
        assertThat(dump("--rule_classes", "--rules", "--skyframe", "summary").isSuccess()).isTrue()
        com.google.common.truth.Subject.contains("filegroup")
        com.google.common.truth.Subject.contains("RULE")
        com.google.common.truth.Subject.contains("Node count")
    }
}
