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
// limitations under the License
package com.google.devtools.build.lib.bazel.debug

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos
import net.starlark.java.syntax.Location
import org.junit.Test
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** Tests handling of WorkspaceRuleEvent  */
@RunWith(JUnit4::class)
class WorkspaceRuleEventTest {
    @Before
    fun setUp() {
    }

    @Test
    fun newExecuteEvent_expectedResult() {
        // Set up arguments, as a combination of String and StarlarkPath
        val arguments = ArrayList<String?>()
        arguments.add("argument 1")
        arguments.add("dummy string")

        val commonEnv: MutableMap<String?, String?> = ImmutableMap.of<String?, String?>("key1", "val1", "key3", "val3")
        val customEnv: MutableMap<String?, String?> =
            ImmutableMap.of<String?, String?>("key2", "val2!", "key3", "val3!")

        val event: WorkspaceLogProtos.WorkspaceEvent =
            WorkspaceRuleEvent.newExecuteEvent(
                arguments,
                2042,
                commonEnv,
                customEnv,
                "outputDir",
                true,
                "my_rule",
                Location.fromFileLineColumn("foo", 10, 20)
            )
                .getLogEvent()

        val expectedArgs: MutableList<String?> = mutableListOf<String?>("argument 1", "dummy string")

        val expectedEnv: MutableMap<String?, String?> =
            ImmutableMap.of<String?, String?>(
                "key1", "val1",
                "key2", "val2!",
                "key3", "val3!"
            )

        assertThat(event.getContext()).isEqualTo("my_rule")
        assertThat(event.getLocation()).isEqualTo("foo:10:20")

        val executeEvent: WorkspaceLogProtos.ExecuteEvent = event.getExecuteEvent()
        assertThat(executeEvent.getTimeoutSeconds()).isEqualTo(2042)
        assertThat(executeEvent.getQuiet()).isEqualTo(true)
        assertThat(executeEvent.getOutputDirectory()).isEqualTo("outputDir")
        assertThat(executeEvent.getArgumentsList()).isEqualTo(expectedArgs)
        assertThat(executeEvent.getEnvironmentMap()).isEqualTo(expectedEnv)
    }
}
