// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile

/** Tests for [LastBuildEvent].  */
@RunWith(JUnit4::class)
class LastBuildEventTest {
    @org.junit.Test
    fun testForwardsReferencedLocalFilesCall() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val localFile: LocalFile =
            LocalFile(
                fs.getPath("/some/file"),
                LocalFileType.FAILED_TEST_OUTPUT,  /* artifactMetadata= */
                null
            )
        val event: LastBuildEvent =
            LastBuildEvent(
                object : BuildEvent() {
                    val eventId: BuildEventId?
                        get() = null

                    val childrenEvents: com.google.common.collect.ImmutableList<BuildEventId?>
                        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

                    public override fun referencedLocalFiles(): com.google.common.collect.ImmutableList<LocalFile?> {
                        return com.google.common.collect.ImmutableList.of<LocalFile?>(localFile)
                    }

                    public override fun asStreamProto(context: BuildEventContext?): BuildEventStreamProtos.BuildEvent? {
                        return null
                    }
                })
        assertThat(event.referencedLocalFiles()).containsExactly(localFile)
    }
}
