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

import com.google.devtools.build.lib.actions.Action

@RunWith(JUnit4::class)
class BinaryFileWriteActionTest : FileWriteActionTestCase() {
    override fun createAction(
        actionOwner: ActionOwner?, outputArtifact: Artifact?, data: String, makeExecutable: Boolean
    ): Action? {
        return BinaryFileWriteAction(
            actionOwner, outputArtifact,
            com.google.common.io.ByteSource.wrap(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)), makeExecutable
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
}
