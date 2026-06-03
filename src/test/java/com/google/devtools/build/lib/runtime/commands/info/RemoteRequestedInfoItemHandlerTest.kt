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

import com.google.devtools.build.lib.runtime.commands.PathToReplaceUtils.bytes

@RunWith(JUnit4::class)
class RemoteRequestedInfoItemHandlerTest : BuildIntegrationTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteRequestedInfoItemHandlerCreation() {
        val infoItemHandler: InfoItemHandler =
            InfoItemHandlerFactoryImpl()
                .create(
                    runtimeWrapper.newCommand(),
                    InfoItemOutputType.RESPONSE_PROTO,  /* printKeys= */
                    true
                )
        Truth.assertThat(infoItemHandler).isInstanceOf(RemoteRequestedInfoItemHandler::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteRequestedInfoItemHandler_noExtensionIfNothingAdded() {
        val env: CommandEnvironment = runtimeWrapper.newCommand()
        RemoteRequestedInfoItemHandler(env,  /* printKeys= */true).use { unused -> }
        assertThat(env.getResponseExtensions()).hasSize(1)
        assertThat(env.getResponseExtensions().get(0).`is`(InfoResponse::class.java)).isTrue()
        val infoResponse: InfoResponse = env.getResponseExtensions().get(0).unpack(InfoResponse::class.java)

        assertPathsToReplaceContainsExpectedItems(infoResponse, env)
        assertThat(infoResponse.getInfoItemList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteRequestedInfoItemHandler_addOneItemWithoutPrintKey() {
        val env: CommandEnvironment = runtimeWrapper.newCommand()
        RemoteRequestedInfoItemHandler(env,  /* printKeys= */false).use { remoteRequestedInfoItemHandler ->
            remoteRequestedInfoItemHandler.addInfoItem(
                "foo",
                "value-foo\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
        }
        assertThat(env.getResponseExtensions()).hasSize(1)
        assertThat(env.getResponseExtensions().get(0).`is`(InfoResponse::class.java)).isTrue()
        val infoResponse: InfoResponse = env.getResponseExtensions().get(0).unpack(InfoResponse::class.java)

        assertPathsToReplaceContainsExpectedItems(infoResponse, env)
        assertThat(infoResponse.getInfoItemList())
            .containsExactly(
                InfoItem.newBuilder()
                    .setKey("foo")
                    .setValue(ByteString.copyFromUtf8("value-foo\n"))
                    .build()
            )
        assertThat(infoResponse.getPrintKeys()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteRequestedInfoItemHandler_addTwoItems() {
        val env: CommandEnvironment = runtimeWrapper.newCommand()
        RemoteRequestedInfoItemHandler(env,  /* printKeys= */true).use { remoteRequestedInfoItemHandler ->
            remoteRequestedInfoItemHandler.addInfoItem(
                "foo",
                "value-foo\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
            remoteRequestedInfoItemHandler.addInfoItem(
                "bar",
                "value-bar\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
        }
        assertThat(env.getResponseExtensions()).hasSize(1)
        assertThat(env.getResponseExtensions().get(0).`is`(InfoResponse::class.java)).isTrue()
        val infoResponse: InfoResponse = env.getResponseExtensions().get(0).unpack(InfoResponse::class.java)

        assertPathsToReplaceContainsExpectedItems(infoResponse, env)
        assertThat(infoResponse.getInfoItemList())
            .containsExactly(
                InfoItem.newBuilder()
                    .setKey("foo")
                    .setValue(ByteString.copyFromUtf8("value-foo\n"))
                    .build(),
                InfoItem.newBuilder()
                    .setKey("bar")
                    .setValue(ByteString.copyFromUtf8("value-bar\n"))
                    .build()
            )
        assertThat(infoResponse.getPrintKeys()).isTrue()
    }

    companion object {
        private fun assertPathsToReplaceContainsExpectedItems(
            infoResponse: InfoResponse, env: CommandEnvironment
        ) {
            assertThat(infoResponse.getPathToReplaceList())
                .containsAtLeast(
                    PathToReplace.newBuilder()
                        .setType(PathToReplace.Type.OUTPUT_BASE)
                        .setValue(bytes(env.getOutputBase().getPathString()))
                        .build(),
                    PathToReplace.newBuilder()
                        .setType(PathToReplace.Type.BUILD_WORKING_DIRECTORY)
                        .setValue(bytes(env.getWorkspace().getPathString()))
                        .build(),
                    PathToReplace.newBuilder()
                        .setType(PathToReplace.Type.BUILD_WORKSPACE_DIRECTORY)
                        .setValue(bytes(env.getWorkspace().getPathString()))
                        .build()
                )
        }
    }
}
