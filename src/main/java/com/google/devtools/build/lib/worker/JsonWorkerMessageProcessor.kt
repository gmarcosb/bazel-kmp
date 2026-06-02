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

import com.google.devtools.build.lib.worker.WorkerProtocol.Input

/** Implementation of the Worker Protocol using JSON to communicate with Bazel.  */
class JsonWorkerMessageProcessor(reader: JsonReader, jsonWriter: BufferedWriter) : WorkerMessageProcessor {
    /** Reader for reading the WorkResponse.  */
    private val reader: JsonReader

    /** Printer for printing the WorkRequest.  */
    private val jsonPrinter: Printer

    /** Writer for writing the WorkRequest to the worker.  */
    private val jsonWriter: BufferedWriter

    /** Constructs a `WorkRequestHandler` that reads and writes JSON.  */
    init {
        this.reader = reader
        reader.setLenient(true)
        this.jsonWriter = jsonWriter
        jsonPrinter =
            JsonFormat.printer().omittingInsignificantWhitespace().alwaysPrintFieldsWithNoPresence()
    }

    @Throws(IOException::class)
    override fun readWorkRequest(): WorkRequest {
        var arguments: MutableList<String?>? = null
        var inputs: MutableList<Input?>? = null
        var requestId: Int? = null
        var verbosity: Int? = null
        var sandboxDir: String? = null
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                val name: String = reader.nextName()
                when (name) {
                    "arguments" -> {
                        if (arguments != null) {
                            throw IOException("WorkRequest cannot have more than one 'arguments' field")
                        }
                        arguments = readArguments(reader)
                    }

                    "inputs" -> {
                        if (inputs != null) {
                            throw IOException("WorkRequest cannot have more than one 'inputs' field")
                        }
                        inputs = readInputs(reader)
                    }

                    "requestId" -> {
                        if (requestId != null) {
                            throw IOException("WorkRequest cannot have more than one requestId")
                        }
                        requestId = reader.nextInt()
                    }

                    "verbosity" -> {
                        if (verbosity != null) {
                            throw IOException("Work response cannot have more than one verbosity")
                        }
                        verbosity = reader.nextInt()
                    }

                    "sandboxDir" -> {
                        if (sandboxDir != null) {
                            throw IOException("Work response cannot have more than one sandboxDir")
                        }
                        sandboxDir = reader.nextString()
                    }

                    else ->             // As per https://bazel.build/docs/creating-workers#work-responses,
                        // unknown fields are ignored.
                        reader.skipValue()
                }
            }
            reader.endObject()
        } catch (e: MalformedJsonException) {
            throw IOException(e)
        } catch (e: java.lang.IllegalStateException) {
            throw IOException(e)
        } catch (e: EOFException) {
            throw IOException(e)
        }

        val requestBuilder: WorkRequest.Builder = WorkRequest.newBuilder()
        if (arguments != null) {
            requestBuilder.addAllArguments(arguments)
        }
        if (inputs != null) {
            requestBuilder.addAllInputs(inputs)
        }
        if (requestId != null) {
            requestBuilder.setRequestId(requestId)
        }
        if (verbosity != null) {
            requestBuilder.setVerbosity(verbosity)
        }
        if (sandboxDir != null) {
            requestBuilder.setSandboxDir(sandboxDir)
        }
        return requestBuilder.build()
    }

    @Throws(IOException::class)
    override fun writeWorkResponse(response: WorkResponse?) {
        jsonPrinter.appendTo(response, jsonWriter)
        jsonWriter.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        jsonWriter.close()
    }

    companion object {
        @Throws(IOException::class)
        private fun readArguments(reader: JsonReader): com.google.common.collect.ImmutableList<String?> {
            reader.beginArray()
            val argumentsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            while (reader.hasNext()) {
                argumentsBuilder.add(reader.nextString())
            }
            reader.endArray()
            return argumentsBuilder.build()
        }

        @Throws(IOException::class)
        private fun readInputs(reader: JsonReader): com.google.common.collect.ImmutableList<Input?> {
            reader.beginArray()
            val inputsBuilder: com.google.common.collect.ImmutableList.Builder<Input?> =
                com.google.common.collect.ImmutableList.builder<Input?>()
            while (reader.hasNext()) {
                var digest: String? = null
                var path: String? = null

                reader.beginObject()
                while (reader.hasNext()) {
                    val name: String = reader.nextName()
                    when (name) {
                        "digest" -> {
                            if (digest != null) {
                                throw IOException("Input cannot have more than one digest")
                            }
                            digest = reader.nextString()
                        }

                        "path" -> {
                            if (path != null) {
                                throw IOException("Input cannot have more than one path")
                            }
                            path = reader.nextString()
                        }

                        else ->             // As per https://bazel.build/docs/creating-workers#work-responses,
                            // unknown fields are ignored.
                            reader.skipValue()
                    }
                }
                reader.endObject()
                val inputBuilder: Input.Builder = Input.newBuilder()
                if (digest != null) {
                    inputBuilder.setDigest(ByteString.copyFromUtf8(digest))
                }
                if (path != null) {
                    inputBuilder.setPath(path)
                }
                inputsBuilder.add(inputBuilder.build())
            }
            reader.endArray()
            return inputsBuilder.build()
        }
    }
}
