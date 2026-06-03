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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat.JSON

/** Tests for [SingleplexWorker].  */
@RunWith(JUnit4::class)
class WorkerTest {
    val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    private var workerForCleanup: WorkerTestUtils.TestWorker? = null
    private val options: WorkerOptions? =
        com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)

    @org.junit.After
    @Throws(IOException::class)
    fun destroyWorker() {
        if (workerForCleanup != null) {
            workerForCleanup.destroy()
            workerForCleanup = null
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    private fun createTestWorker(
        outputStreamBytes: ByteArray?,
        protocolFormat: WorkerProtocolFormat?
    ): WorkerTestUtils.TestWorker {
        com.google.common.base.Preconditions.checkState(
            workerForCleanup == null, "createTestWorker can only be called once per test"
        )

        val key: WorkerKey = WorkerTestUtils.createWorkerKey(protocolFormat, fs)

        val fakeSubprocess: FakeSubprocess = FakeSubprocess(outputStreamBytes)

        val workerBaseDir: Path = fs.getPath("/outputbase/bazel-workers")
        val workerId = 1
        val logFile: Path? = workerBaseDir.getRelative("test-log-file.log")

        val worker: WorkerTestUtils.TestWorker =
            WorkerTestUtils.TestWorker(key, workerId, key.getExecRoot(), logFile, fakeSubprocess, options)

        val sandboxInputs: SandboxInputs? = null
        val sandboxOutputs: SandboxOutputs? = null
        worker.prepareExecution(
            sandboxInputs,
            sandboxOutputs,
            key.getWorkerFilesWithDigests().keySet(),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )

        workerForCleanup = worker

        return worker
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testPutRequest_success() {
        val request: WorkRequest? = WorkRequest.getDefaultInstance()

        val testWorker: WorkerTestUtils.TestWorker = createTestWorker(ByteArray(0), PROTO)
        testWorker.putRequest(request)

        val stdout: java.io.OutputStream = testWorker.getFakeSubprocess().getOutputStream()
        val requestFromStdout: WorkRequest? =
            WorkRequest.parseDelimitedFrom(
                ByteArrayInputStream(
                    stdout.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                )
            )

        assertThat(requestFromStdout).isEqualTo(request)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_success() {
        val response: WorkResponse = WorkResponse.getDefaultInstance()

        val testWorker: WorkerTestUtils.TestWorker = createTestWorker(serializeResponseToProtoBytes(response), PROTO)
        val readResponse: WorkResponse? = testWorker.getResponse(0)

        assertThat(readResponse).isEqualTo(response)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testPutRequest_json_success() {
        val testWorker: WorkerTestUtils.TestWorker = createTestWorker(ByteArray(0), JSON)
        testWorker.putRequest(WorkRequest.getDefaultInstance())

        val stdout: java.io.OutputStream = testWorker.getFakeSubprocess().getOutputStream()
        Truth.assertThat(stdout.toString()).isEqualTo("{}\n")
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_json_success() {
        val testWorker: WorkerTestUtils.TestWorker =
            createTestWorker("{}\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8), JSON)
        val readResponse: WorkResponse? = testWorker.getResponse(0)
        val response: WorkResponse? = WorkResponse.getDefaultInstance()

        assertThat(readResponse).isEqualTo(response)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testPutRequest_json_populatedFields_success() {
        val request: WorkRequest? =
            WorkRequest.newBuilder()
                .addArguments("testRequest")
                .addInputs(
                    Input.newBuilder()
                        .setPath("testPath")
                        .setDigest(ByteString.copyFromUtf8("testDigest"))
                        .build()
                )
                .setRequestId(1)
                .setVerbosity(11)
                .build()

        val testWorker: WorkerTestUtils.TestWorker = createTestWorker(ByteArray(0), JSON)
        testWorker.putRequest(request)

        val stdout: java.io.OutputStream = testWorker.getFakeSubprocess().getOutputStream()
        val requestJsonString =
            "{\"arguments\":[\"testRequest\"],\"inputs\":[{\"path\":\"testPath\",\"digest\":\"dGVzdERpZ2VzdA==\"}],\"requestId\":1,\"verbosity\":11}\n"
        Truth.assertThat(stdout.toString()).isEqualTo(requestJsonString)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_json_populatedFields_success() {
        val testWorker: WorkerTestUtils.TestWorker =
            createTestWorker(
                "{\"exitCode\":1,\"output\":\"test output\",\"requestId\":1}".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                JSON
            )
        val readResponse: WorkResponse? = testWorker.getResponse(1)
        val response: WorkResponse? =
            WorkResponse.newBuilder().setExitCode(1).setOutput("test output").setRequestId(1).build()

        assertThat(readResponse).isEqualTo(response)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPutRequest_destroyedWorker_throws() {
        val testWorker: WorkerTestUtils.TestWorker = createTestWorker(ByteArray(0), PROTO)
        testWorker.destroy()
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { testWorker.putRequest(WorkRequest.getDefaultInstance()) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetResponse_destroyedWorker_throws() {
        val testWorker: WorkerTestUtils.TestWorker = createTestWorker(ByteArray(0), PROTO)
        testWorker.destroy()
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { testWorker.getResponse(0) })
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    private fun verifyGetResponseFailure(responseString: String?, expectedError: String?) {
        val testWorker: WorkerTestUtils.TestWorker =
            createTestWorker((responseString + "\n").toByteArray(java.nio.charset.StandardCharsets.UTF_8), JSON)
        val ex: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { testWorker.getResponse(0) })
        Truth.assertThat(ex).hasMessageThat().contains(expectedError)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_badJson_throws() {
        verifyGetResponseFailure(
            "{ \"output\": \"I'm missing a bracket\"", "Could not parse json work request correctly"
        )
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_json_multipleExitCode_fails() {
        verifyGetResponseFailure(
            "{\"exitCode\":1,\"exitCode\":1}", "Work response cannot have more than one exit code"
        )
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_json_multipleOutput_fails() {
        verifyGetResponseFailure(
            "{\"output\":\"\",\"output\":\"\"}", "Work response cannot have more than one output"
        )
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_json_multipleRequestId_fails() {
        verifyGetResponseFailure(
            "{\"requestId\":0,\"requestId\":0}", "Work response cannot have more than one requestId"
        )
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun testGetResponse_json_unknownFieldsIgnored() {
        val testWorker: WorkerTestUtils.TestWorker =
            createTestWorker(
                "{\"exitCode\":1,\"output\":\"test output\",\"requestId\":1,\"unknown\":{1:['a']}}"
                    .toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                JSON
            )
        val readResponse: WorkResponse? = testWorker.getResponse(1)
        val response: WorkResponse? =
            WorkResponse.newBuilder().setExitCode(1).setOutput("test output").setRequestId(1).build()

        assertThat(readResponse).isEqualTo(response)
    }

    companion object {
        @Throws(IOException::class)
        private fun serializeResponseToProtoBytes(response: WorkResponse): ByteArray? {
            val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            response.writeDelimitedTo(baos)
            return baos.toByteArray()
        }
    }
}
