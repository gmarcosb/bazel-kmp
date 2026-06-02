// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionOwner

@RunWith(JUnit4::class)
class FileWriteActionTest : FileWriteActionTestCase() {
    override fun createAction(
        actionOwner: ActionOwner?, outputArtifact: Artifact?, data: String?, makeExecutable: Boolean
    ): FileWriteAction {
        return FileWriteAction.create(
            actionOwner, outputArtifact, data, makeExecutable, Compression.DISALLOW
        )
    }

    @org.junit.Test
    fun testNoInputs() {
        checkNoInputsByDefault()
    }

    @org.junit.Test
    fun testDestinationArtifactIsOutput() {
        checkDestinationArtifactIsOutput()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanWriteNonExecutableFile() {
        checkCanWriteNonExecutableFile()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanWriteExecutableFile() {
        checkCanWriteExecutableFile()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputesConsistentKeys() {
        checkComputesConsistentKeys()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionWithShortString() {
        val outputArtifact: Artifact? = getBinArtifactWithNoOwner("destination.txt")
        val contents = "Hello world"
        val action: FileWriteAction =
            FileWriteAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                contents,  /*makeExecutable=*/
                false,
                Compression.DISALLOW
            )
        assertThat(action.getFileContents()).isEqualTo(contents)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionWithLazyString() {
        val outputArtifact: Artifact? = getBinArtifactWithNoOwner("destination.txt")
        val backingString = "Hello world"
        val contents: OnDemandString =
            object : OnDemandString() {
                public override fun toString(): String {
                    return backingString
                }
            }
        val action: FileWriteAction =
            FileWriteAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                contents,  /*makeExecutable=*/
                false,
                Compression.DISALLOW
            )
        assertThat(action.getFileContents()).isEqualTo(backingString)
    }

    /**
     * Returns a string filled with (deterministic) random characters to get a string that won't
     * compress to a tiny size.
     */
    private fun generateLongRandomString(): String {
        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        val random: Random = Random(0)
        for (i in 0..<16 * 1024) {
            val c: Char = random.nextInt(128).toChar()
            sb.append(c)
        }
        return sb.toString()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionWithLongStringAndCompression() {
        val outputArtifact: Artifact? = getBinArtifactWithNoOwner("destination.txt")
        val contents = generateLongRandomString()
        val action: FileWriteAction =
            FileWriteAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                contents,  /*makeExecutable=*/
                false,
                Compression.ALLOW
            )
        assertThat(action.getFileContents()).isEqualTo(contents)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionWithCompressionDoesNotForceLazyString() {
        val outputArtifact: Artifact? = getBinArtifactWithNoOwner("destination.txt")
        val backingContents = generateLongRandomString()

        class ForceCountingOnDemandString : OnDemandString() {
            var forced: Int = 0

            override fun toString(): String {
                forced += 1
                return backingContents
            }
        }

        val contents = ForceCountingOnDemandString()
        val action: FileWriteAction =
            FileWriteAction.create(
                ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                outputArtifact,
                contents,  /*makeExecutable=*/
                false,
                Compression.ALLOW
            )

        // The string should only be forced once we actually read it, not when the action is
        // constructed.
        Truth.assertThat(contents.forced).isEqualTo(0)
        assertThat(action.getFileContents()).isEqualTo(backingContents)
        Truth.assertThat(contents.forced).isEqualTo(1)
    }
}
