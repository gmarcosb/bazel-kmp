// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos

@RunWith(JUnit4::class)
class TestProgressTest {
    @org.junit.Test
    fun testTestProgress_convertsToEventId() {
        val progress: TestProgress =
            TestProgress(
                "alabel", ConfigurationId.newBuilder().setId("configid").build(), 1, 2, 3, 4, "auri"
            )

        val id: BuildEventId? = progress.getEventId()

        assertThat(id)
            .isEqualTo(
                BuildEventId.newBuilder()
                    .setTestProgress(
                        TestProgressId.newBuilder()
                            .setLabel("alabel")
                            .setConfiguration(ConfigurationId.newBuilder().setId("configid"))
                            .setRun(1)
                            .setShard(2)
                            .setAttempt(3)
                            .setOpaqueCount(4)
                    )
                    .build()
            )
    }

    @org.junit.Test
    fun testTestProgress_convertsToEvent() {
        val progress: TestProgress =
            TestProgress(
                "alabel", ConfigurationId.newBuilder().setId("configid").build(), 1, 2, 3, 4, "auri"
            )

        val event: BuildEvent? = progress.asStreamProto(null)

        assertThat(event)
            .isEqualTo(
                BuildEvent.newBuilder()
                    .setId(
                        BuildEventId.newBuilder()
                            .setTestProgress(
                                TestProgressId.newBuilder()
                                    .setLabel("alabel")
                                    .setConfiguration(ConfigurationId.newBuilder().setId("configid"))
                                    .setRun(1)
                                    .setShard(2)
                                    .setAttempt(3)
                                    .setOpaqueCount(4)
                            )
                    )
                    .setTestProgress(BuildEventStreamProtos.TestProgress.newBuilder().setUri("auri"))
                    .build()
            )
    }
}
