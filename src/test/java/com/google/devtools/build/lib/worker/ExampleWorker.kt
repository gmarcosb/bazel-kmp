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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat

/** An example implementation of a worker process that is used for integration tests.  */
object ExampleWorker {
    val FLAG_FILE_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile("(?:@|--?flagfile=)(.+)")

    // A UUID that uniquely identifies this running worker process.
    val WORKER_UUID: UUID = UUID.randomUUID()

    // A counter that increases with each work unit processed.
    var workUnitCounter: Int = 1

    // If true, returns corrupt responses instead of correct protobufs.
    var poisoned: Boolean = false

    val inputs: LinkedHashMap<String?, String?> = LinkedHashMap<String?, String?>()

    // Contains the request currently being worked on.
    private var currentRequest: WorkRequest? = null

    // The options passed to this worker on a per-worker-lifetime basis.
    var workerOptions: ExampleWorkerOptions? = null
    private var messageProcessor: WorkerMessageProcessor? = null

    @Throws(java.lang.Exception::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        if (com.google.common.collect.ImmutableSet.copyOf<String?>(args).contains("--persistent_worker")) {
            java.lang.System.err.printf("Worker args: %s\n", java.lang.String.join(" ", *args))
            val parser: OptionsParser =
                OptionsParser.builder()
                    .optionsClasses(ExampleWorkerOptions::class.java)
                    .allowResidue(false)
                    .build()
            parser.parse(args)
            workerOptions = parser.getOptions(ExampleWorkerOptions::class.java)
            val protocolFormat: WorkerProtocolFormat = workerOptions.getWorkerProtocol()
            messageProcessor = null
            when (protocolFormat) {
                JSON -> messageProcessor =
                    JsonWorkerMessageProcessor(
                        JsonReader(
                            BufferedReader(
                                java.io.InputStreamReader(
                                    java.lang.System.`in`,
                                    java.nio.charset.StandardCharsets.UTF_8
                                )
                            )
                        ),
                        BufferedWriter(
                            OutputStreamWriter(
                                java.lang.System.out,
                                java.nio.charset.StandardCharsets.UTF_8
                            )
                        )
                    )

                PROTO -> messageProcessor = ProtoWorkerMessageProcessor(java.lang.System.`in`, java.lang.System.out)
            }
            com.google.common.base.Preconditions.checkNotNull<Any?>(messageProcessor)
            val workRequestHandler: WorkRequestHandler =
                InterruptableWorkRequestHandler(java.util.function.BiFunction { obj: MutableList<String?>?, args: PrintWriter? ->
                    ExampleWorker.doWork(
                        args
                    )
                }, java.lang.System.err, messageProcessor)
            workRequestHandler.processRequests()
        } else {
            // This is a single invocation of the example that exits after it processed the request.
            parseOptionsAndLog(com.google.common.collect.ImmutableList.copyOf<String?>(args))
        }
    }

    private fun doWork(args: MutableList<String>, err: PrintWriter?): Int {
        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        val originalStdOut: PrintStream? = java.lang.System.out
        val originalStdErr: PrintStream = java.lang.System.err

        if (workerOptions.getWaitForCancel()) {
            try {
                val workRequest: WorkRequest = messageProcessor.readWorkRequest()
                if (workRequest.getRequestId() !== currentRequest.getRequestId()) {
                    java.lang.System.err.format(
                        "Got cancel request for %d while expecting cancel request for %d%n",
                        workRequest.getRequestId(), currentRequest.getRequestId()
                    )
                    return 1
                }
                if (!workRequest.getCancel()) {
                    java.lang.System.err.format(
                        "Got non-cancel request for %d while expecting cancel request%n",
                        workRequest.getRequestId()
                    )
                    return 1
                }
            } catch (e: IOException) {
                throw java.lang.RuntimeException("Exception while waiting for cancel request", e)
            }
        }
        try {
            PrintStream(baos).use { ps ->
                java.lang.System.setOut(ps)
                java.lang.System.setErr(ps)
                if (poisoned) {
                    println("I'm a poisoned worker and this is not a protobuf.")
                    println("Here's a fake stack trace for you:")
                    println("    at com.example.Something(Something.java:83)")
                    println("    at java.lang.Thread.run(Thread.java:745)")
                    print("And now, 8k of random bytes: ")
                    val b = ByteArray(8192)
                    Random().nextBytes(b)
                    try {
                        java.lang.System.out.write(b)
                    } catch (e: IOException) {
                        e.printStackTrace()
                        return 1
                    }
                } else {
                    try {
                        if (currentRequest.getVerbosity() > 0) {
                            originalStdErr.println("VERBOSE: Pretending to do work.")
                            originalStdErr.println("VERBOSE: Running in " + java.io.File(".").getAbsolutePath())
                        }
                        parseOptionsAndLog(args)
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                        return 1
                    }
                }
            }
        } finally {
            java.lang.System.setOut(originalStdOut)
            java.lang.System.setErr(originalStdErr)
            currentRequest = null
        }

        if (workerOptions.getExitDuring() > 0 && workUnitCounter > workerOptions.getExitDuring()) {
            java.lang.System.exit(0)
        }

        if (poisoned) {
            try {
                baos.writeTo(java.lang.System.out)
                java.lang.System.out.flush()
                java.lang.System.exit(1)
            } catch (e: IOException) {
                e.printStackTrace()
                java.lang.System.exit(1)
            }
        }
        if (workerOptions.getPoisonAfter() > 0 && workUnitCounter > workerOptions.getPoisonAfter()) {
            poisoned = true
        }
        return 0
    }

    @Throws(java.lang.Exception::class)
    private fun parseOptionsAndLog(args: MutableList<String>) {
        val expandedArgs: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (arg in args) {
            val flagFileMatcher: java.util.regex.Matcher = FLAG_FILE_PATTERN.matcher(arg)
            if (flagFileMatcher.matches()) {
                expandedArgs.addAll(
                    java.nio.file.Files.readAllLines(
                        Paths.get(flagFileMatcher.group(1)),
                        java.nio.charset.StandardCharsets.UTF_8
                    )
                )
            } else {
                expandedArgs.add(arg)
            }
        }

        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExampleWorkOptions::class.java).allowResidue(true).build()
        parser.parse(expandedArgs.build())
        val options: ExampleWorkOptions = parser.getOptions(ExampleWorkOptions::class.java)

        val outputs: MutableList<String?> = java.util.ArrayList<String?>()

        if (options.getWriteUUID()) {
            outputs.add("UUID " + WORKER_UUID)
        }

        if (options.getWriteCounter()) {
            outputs.add("COUNTER " + workUnitCounter++)
        }

        var residueStr: String = com.google.common.base.Joiner.on(' ').join(parser.getResidue())
        if (options.getUppercase()) {
            residueStr = com.google.common.base.Ascii.toUpperCase(residueStr)
        }
        outputs.add(residueStr)

        if (options.getPrintInputs()) {
            for (input in inputs.entries) {
                outputs.add("INPUT " + input.key + " " + input.value)
            }
        }

        if (!options.getPrintDirListing().isEmpty()) {
            val rootDir: Path = Path.of(options.getPrintDirListing())
            java.nio.file.Files.walk(rootDir, Int.Companion.MAX_VALUE).use { paths ->
                for (path in paths.collect(com.google.common.collect.ImmutableList.toImmutableList<Path?>())) {
                    outputs.add(String.format("DIRENT %s %s", rootDir.relativize(path), getInode(path)))
                }
            }
        }

        if (options.getPrintRequests()) {
            outputs.add("REQUEST: " + currentRequest)
        }

        if (options.getPrintEnv()) {
            for (entry in java.lang.System.getenv().entries) {
                outputs.add(entry.key + "=" + entry.value)
            }
        }

        if (options.getWorkTime() != null) {
            try {
                java.lang.Thread.sleep(options.getWorkTime().toMillis())
            } catch (e: java.lang.InterruptedException) {
                java.lang.System.err.printf(
                    "Interrupted while pretending to work for %d millis%n",
                    options.getWorkTime().toMillis()
                )
            }
        }

        val outputStr: String = com.google.common.base.Joiner.on('\n').join(outputs)
        if (options.getOutputFile().isEmpty()) {
            println(outputStr)
        } else {
            PrintStream(options.getOutputFile()).use { outputFile ->
                outputFile.println(outputStr)
            }
        }
    }

    @Throws(IOException::class)
    private fun getInode(path: Path): Long {
        return java.nio.file.Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Long
    }

    private class InterruptableWorkRequestHandler(
        callback: java.util.function.BiFunction<MutableList<String?>?, PrintWriter?, Int?>?,
        stderr: PrintStream?,
        messageProcessor: WorkerMessageProcessor?
    ) : WorkRequestHandler(callback, stderr, messageProcessor) {
        @Throws(IOException::class)
        public override fun processRequests() {
            val captured: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            val workerIO: WorkerIO =
                WorkerIO(java.lang.System.`in`, java.lang.System.out, java.lang.System.err, captured, captured)

            while (true) {
                val request: WorkRequest? = messageProcessor.readWorkRequest()
                if (request == null) {
                    break
                }

                currentRequest = request
                inputs.clear()
                for (input in request.getInputsList()) {
                    inputs.put(input.getPath(), input.getDigest().toStringUtf8())
                }
                check(!(poisoned && workerOptions.getHardPoison())) { "I'm a very poisoned worker and will just crash." }
                if (request.getCancel()) {
                    respondToCancelRequest(request)
                } else {
                    startResponseThread(workerIO, request)
                }
                if (workerOptions.getExitAfter() > 0 && workUnitCounter > workerOptions.getExitAfter()) {
                    java.lang.System.exit(0)
                }
            }

            try {
                // Unwrap the system streams placing the original streams back
                workerIO.close()
            } catch (e: java.lang.Exception) {
                workerIO.getOriginalErrorStream().println(e.message)
            }
        }
    }
}
