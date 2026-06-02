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

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest

/** An implementation of a Bazel worker using JSON to communicate with the worker process.  */
internal class JsonWorkerProtocol(workersStdin: java.io.OutputStream, workersStdout: java.io.InputStream) :
    WorkerProtocolImpl {
    /** Reader for reading the WorkResponse.  */
    private val reader: JsonReader

    /** Printer for printing the WorkRequest  */
    private val jsonPrinter: Printer

    /** Writer for writing the WorkRequest to the worker  */
    private val jsonWriter: BufferedWriter

    init {
        jsonPrinter = JsonFormat.printer().omittingInsignificantWhitespace()
        jsonWriter = BufferedWriter(OutputStreamWriter(workersStdin, java.nio.charset.StandardCharsets.UTF_8))
        reader = JsonReader(
            BufferedReader(
                java.io.InputStreamReader(
                    workersStdout,
                    java.nio.charset.StandardCharsets.UTF_8
                )
            )
        )
        reader.setLenient(true)
    }

    @Throws(IOException::class)
    override fun putRequest(request: WorkRequest?) {
        // WorkRequests are serialized according to ndjson spec.
        // https://github.com/ndjson/ndjson-spec
        jsonPrinter.appendTo(request, jsonWriter)
        jsonWriter.append("\n")
        jsonWriter.flush()
    }

    @get:Throws(IOException::class)
    val response: WorkResponse
        get() {
            val interrupted: Boolean = java.lang.Thread.interrupted()
            try {
                return parseResponse()
            } finally {
                if (interrupted) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }

    @Throws(IOException::class)
    private fun parseResponse(): WorkResponse {
        var exitCode: Int? = null
        var output: String? = null
        var requestId: Int? = null
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                val name: String = reader.nextName()
                when (name) {
                    "exitCode" -> {
                        if (exitCode != null) {
                            throw IOException("Work response cannot have more than one exit code")
                        }
                        exitCode = reader.nextInt()
                    }

                    "output" -> {
                        if (output != null) {
                            throw IOException("Work response cannot have more than one output")
                        }
                        output = reader.nextString()
                    }

                    "requestId" -> {
                        if (requestId != null) {
                            throw IOException("Work response cannot have more than one requestId")
                        }
                        requestId = reader.nextInt()
                    }

                    else ->             // As per https://bazel.build/docs/creating-workers#work-responses,
                        // unknown fields are ignored.
                        reader.skipValue()
                }
            }
            reader.endObject()
        } catch (e: MalformedJsonException) {
            throw IOException("Could not parse json work request correctly", e)
        } catch (e: EOFException) {
            throw IOException("Could not parse json work request correctly", e)
        } catch (e: java.lang.IllegalStateException) {
            throw IOException("Could not parse json work request correctly", e)
        }

        val responseBuilder: WorkResponse.Builder = WorkResponse.newBuilder()

        if (exitCode != null) {
            responseBuilder.setExitCode(exitCode)
        }
        if (output != null) {
            responseBuilder.setOutput(output)
        }
        if (requestId != null) {
            responseBuilder.setRequestId(requestId)
        }

        return responseBuilder.build()
    }

    @Throws(IOException::class)
    override fun close() {
        reader.close()
        jsonWriter.close()
    }
}
