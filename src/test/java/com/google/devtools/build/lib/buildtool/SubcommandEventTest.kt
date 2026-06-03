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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.shell.Command

/**
 * Test that SUBCOMMAND events report command lines in a form than can be "replayed" by copy+paste
 * to the shell.
 */
@RunWith(JUnit4::class)
class SubcommandEventTest : BuildIntegrationTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun stageEmbeddedTools() {
        addOptions("--spawn_strategy=standalone")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubcommandEvent() {
        val eventCollector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.SUBCOMMAND)
        events.addHandler(eventCollector)
        runtimeWrapper.addOptions("--subcommands")

        write(
            "hello/BUILD",
            """
        genrule(
            name = "hello",
            outs = ["hello.out"],
            cmd = 'echo "Hello, World!" > ${'$'}(location hello.out)',
        )
        
        """.trimIndent()
        )

        // (1) Ensure that building the target creates the output:
        buildTarget("//hello")
        val helloOut: Path =
            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts("//hello:hello.out")).getPath()
        assertThat(helloOut.isFile()).isTrue()
        assertThat(helloOut.getFileSize()).isEqualTo(14)

        // (2) Delete the output:
        helloOut.delete()
        assertThat(helloOut.exists()).isFalse()

        // (3) Test that the message in the SUBCOMMAND event replays the action:
        var command: String? = null
        for (event in eventCollector) {
            command = event.getMessage()
            if (command.contains("World")) {
                break
            }
        }
        assertThat(
            Command(com.google.common.collect.ImmutableList.of<E?>("/bin/sh", "-c", command), java.lang.System.getenv())
                .execute(java.io.ByteArrayOutputStream(), java.io.ByteArrayOutputStream())
                .terminationStatus()
                .success()
        )
            .isTrue()
        assertThat(helloOut.isFile()).isTrue()
        assertThat(helloOut.getFileSize()).isEqualTo(14)
    }
}
