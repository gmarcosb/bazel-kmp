// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.ActionResult

/** Tests for [com.google.devtools.build.lib.remote.RemoteRepositoryRemoteExecutor].  */
@RunWith(JUnit4::class)
class RemoteRepositoryRemoteExecutorTest {
    @org.mockito.Mock
    var remoteCache: RemoteExecutionCache? = null

    @org.mockito.Mock
    var remoteExecutor: RemoteExecutionClient? = null

    private var repoExecutor: RemoteRepositoryRemoteExecutor? = null

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        repoExecutor =
            RemoteRepositoryRemoteExecutor(
                remoteCache,
                remoteExecutor,
                DIGEST_UTIL,
                "none",
                "none",
                TestConstants.WORKSPACE_NAME,  /* remoteInstanceName= */
                "foo",  /* acceptCached= */
                true
            )
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testZeroExitCodeFromCache() {
        // Test that an ActionResult with exit code zero is accepted as cached.

        val cachedResult: ActionResult? = ActionResult.newBuilder().setExitCode(0).build()
        Mockito.`when`<T?>(
            remoteCache.downloadActionResult(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(true),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(cachedResult))

        val executionResult: ExecutionResult =
            repoExecutor.execute(
                com.google.common.collect.ImmutableList.of<E?>("/bin/bash", "-c", "exit 0"),  /* inputFiles= */
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),  /* executionProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* workingDirectory= */
                null,  /* timeout= */
                java.time.Duration.ZERO
            )

        Mockito.verify<Any?>(remoteCache)
            .downloadActionResult(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean(),  /* inlineOutputFiles= */< T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))
        // Don't fallback to execution
        Mockito.verify<Any?>(remoteExecutor, Mockito.never())
            .executeRemotely(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        assertThat(executionResult.exitCode()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testNoneZeroExitCodeFromCache() {
        // Test that an ActionResult with a none-zero exit code is not accepted as cached.

        val cachedResult: ActionResult? = ActionResult.newBuilder().setExitCode(1).build()
        Mockito.`when`<T?>(
            remoteCache.downloadActionResult(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(true),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(cachedResult))

        val response: ExecuteResponse? = ExecuteResponse.newBuilder().setResult(cachedResult).build()
        Mockito.`when`<T?>(
            remoteExecutor.executeRemotely(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(response)

        val executionResult: ExecutionResult =
            repoExecutor.execute(
                com.google.common.collect.ImmutableList.of<E?>("/bin/bash", "-c", "exit 1"),  /* inputFiles= */
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),  /* executionProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* workingDirectory= */
                null,  /* timeout= */
                java.time.Duration.ZERO
            )

        Mockito.verify<Any?>(remoteCache)
            .downloadActionResult(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean(),  /* inlineOutputFiles= */< T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))
        // Fallback to execution
        Mockito.verify<Any?>(remoteExecutor)
            .executeRemotely(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())

        assertThat(executionResult.exitCode()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testInlineStdoutStderr() {
        // Test that inline stdout/stderr responses are returned in execution results.

        val stdout: ByteArray = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val stderr: ByteArray = "world".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val cachedResult: ActionResult? =
            ActionResult.newBuilder()
                .setExitCode(0)
                .setStdoutRaw(ByteString.copyFrom(stdout))
                .setStderrRaw(ByteString.copyFrom(stderr))
                .build()
        Mockito.`when`<T?>(
            remoteCache.downloadActionResult(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(true),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<Any?>())
        ))
        .thenReturn(CachedActionResult.remote(cachedResult))

        val response: ExecuteResponse? = ExecuteResponse.newBuilder().setResult(cachedResult).build()
        Mockito.`when`<T?>(
            remoteExecutor.executeRemotely(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(response)

        val executionResult: ExecutionResult =
            repoExecutor.execute(
                com.google.common.collect.ImmutableList.of<E?>("/bin/bash", "-c", "echo hello"),  /* inputFiles= */
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),  /* executionProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* environment= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* workingDirectory= */
                null,  /* timeout= */
                java.time.Duration.ZERO
            )

        Mockito.verify<Any?>(remoteCache)
            .downloadActionResult(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),  /* inlineOutErr= */
                ArgumentMatchers.eq(true),  /* inlineOutputFiles= */
                < T > eq < T ? > (com.google.common.collect.ImmutableSet.of<E?>()))

        assertThat(executionResult.exitCode()).isEqualTo(0)
        assertThat(executionResult.stdout()).isEqualTo(stdout)
        assertThat(executionResult.stderr()).isEqualTo(stderr)
    }

    companion object {
        val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
    }
}
