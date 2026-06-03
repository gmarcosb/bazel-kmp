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

/**
 * An example implementation of a multiplex worker process that is used for integration tests. By
 * default, it concatenates writes the options residue and outputs it on stdout. [ ] specifies ways the behaviour can be modofied.
 */
object ExampleWorkerMultiplexer {
    val FLAG_FILE_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile("(?:@|--?flagfile=)(.+)")

    // Creating Executor Service with a thread pool of Size 3.
    const val CONCURRENT_THREAD_NUMBER: Int = 3

    // A UUID that uniquely identifies this running worker process.
    val WORKER_UUID: UUID = UUID.randomUUID()
    const val FILE_INPUT_PREFIX: String = "FILE:"

    // A counter that increases with each work unit processed.
    var workUnitCounter: Int = 1

    var counterOutput: Int = workUnitCounter

    var protectResponse: Semaphore = Semaphore(1)

    // Keep state across multiple builds.
    val inputs: LinkedHashMap<String?, String?> = LinkedHashMap<String?, String?>()

    @Throws(java.lang.Exception::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        if (com.google.common.collect.ImmutableSet.copyOf<String?>(args).contains("--persistent_worker")) {
            java.lang.System.err.printf("Worker args: %s\n", java.lang.String.join(" ", *args))
            val parser: OptionsParser =
                OptionsParser.builder()
                    .optionsClasses(ExampleWorkerMultiplexerOptions::class.java)
                    .allowResidue(false)
                    .build()
            parser.parse(args)
            val workerOptions: ExampleWorkerMultiplexerOptions =
                parser.getOptions(ExampleWorkerMultiplexerOptions::class.java)
            com.google.common.base.Preconditions.checkState(workerOptions.getPersistentWorker())

            runPersistentWorker(workerOptions)
        } else {
            // This is a single invocation of the example that exits after it processed the request.
            processRequest(
                parserHelper(com.google.common.collect.ImmutableList.copyOf<String?>(args)),
                WorkRequest.getDefaultInstance()
            )
        }
    }

    @Throws(IOException::class, ExecutionException::class, java.lang.InterruptedException::class)
    private fun runPersistentWorker(workerOptions: ExampleWorkerMultiplexerOptions) {
        val originalStdOut: PrintStream? = java.lang.System.out
        val originalStdErr: PrintStream = java.lang.System.err

        val executorService: ExecutorService = Executors.newFixedThreadPool(CONCURRENT_THREAD_NUMBER)
        val results: MutableList<java.util.concurrent.Future<*>> = java.util.ArrayList<java.util.concurrent.Future<*>>()

        while (true) {
            try {
                val request: WorkRequest? = WorkRequest.parseDelimitedFrom(java.lang.System.`in`)
                if (request == null) {
                    break
                }
                val requestId: Int = request.getRequestId()

                inputs.clear()
                for (input in request.getInputsList()) {
                    inputs.put(input.getPath(), input.getDigest().toStringUtf8())
                }

                // If true, returns corrupt responses instead of correct protobufs.
                var poisoned = false
                if (workerOptions.getPoisonAfter() > 0
                    && workUnitCounter > workerOptions.getPoisonAfter()
                ) {
                    poisoned = true
                }

                if (poisoned && workerOptions.getHardPoison()) {
                    java.lang.System.err.println("I'm a very poisoned worker and will just crash.")
                    java.lang.System.exit(1)
                } else {
                    var exitCode = 0
                    try {
                        val parser: OptionsParser = parserHelper(request.getArgumentsList())
                        val options: ExampleWorkMultiplexerOptions =
                            parser.getOptions(ExampleWorkMultiplexerOptions::class.java)
                        if (options.getWriteCounter()) {
                            counterOutput = workUnitCounter++
                        }
                        results.add(
                            executorService.submit(
                                createTask(
                                    originalStdOut, originalStdErr, requestId, parser, poisoned, request
                                )
                            )
                        )
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                        exitCode = 1
                        WorkResponse.newBuilder()
                            .setRequestId(requestId)
                            .setOutput(java.io.ByteArrayOutputStream().toString())
                            .setExitCode(exitCode)
                            .build()
                            .writeDelimitedTo(java.lang.System.out)
                    }
                }

                if (workerOptions.getExitAfter() > 0 && workUnitCounter > workerOptions.getExitAfter()) {
                    java.lang.System.`in`.close()
                }
            } finally {
                // Be a good worker process and consume less memory when idle.
                java.lang.System.gc()
            }
        }

        for (result in results) {
            result.get()
        }
    }

    @Throws(java.lang.Exception::class)
    private fun parserHelper(args: MutableList<String>): OptionsParser {
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
            OptionsParser.builder()
                .optionsClasses(ExampleWorkMultiplexerOptions::class.java)
                .allowResidue(true)
                .build()
        parser.parse(expandedArgs.build())

        return parser
    }

    private fun createTask(
        originalStdOut: PrintStream?,
        originalStdErr: PrintStream,
        requestId: Int,
        parser: OptionsParser,
        poisoned: Boolean,
        request: WorkRequest
    ): java.lang.Runnable {
        return java.lang.Runnable {
            val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            var exitCode = 0
            try {
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
                            java.lang.System.out.write(b)
                        } else {
                            try {
                                if (request.getVerbosity() > 0) {
                                    originalStdErr.println("VERBOSE: Pretending to do work.")
                                    originalStdErr.println("VERBOSE: Running in " + java.io.File(".").getAbsolutePath())
                                    originalStdErr.println("VERBOSE: Args " + request.getArgumentsList())
                                }
                                processRequest(parser, request)
                            } catch (e: java.lang.Exception) {
                                e.printStackTrace()
                                exitCode = 1
                            }
                        }
                    }
                } finally {
                    java.lang.System.setOut(originalStdOut)
                    java.lang.System.setErr(originalStdErr)
                }

                if (poisoned) {
                    baos.writeTo(java.lang.System.out)
                } else {
                    protectResponse.acquire()
                    WorkResponse.newBuilder()
                        .setRequestId(requestId)
                        .setOutput(baos.toString())
                        .setExitCode(exitCode)
                        .build()
                        .writeDelimitedTo(java.lang.System.out)
                    protectResponse.release()
                }
                java.lang.System.out.flush()
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException(e)
            }
        }
    }

    @Throws(java.lang.Exception::class)
    private fun processRequest(parser: OptionsParser, request: WorkRequest) {
        val options: ExampleWorkMultiplexerOptions = parser.getOptions(ExampleWorkMultiplexerOptions::class.java)

        val outputs: MutableList<String?> = java.util.ArrayList<String?>()

        if (options.getDelay()) {
            val randomDelay: Int = Random().nextInt(200) + 100
            TimeUnit.MILLISECONDS.sleep(randomDelay.toLong())
            outputs.add("DELAY " + randomDelay + " MILLISECONDS")
        }

        if (options.getWriteUUID()) {
            outputs.add("UUID " + WORKER_UUID.toString())
        }

        if (options.getWriteCounter()) {
            outputs.add("COUNTER " + counterOutput)
        }

        var residue: MutableList<String?> = parser.getResidue()
        val paths: MutableList<String> =
            residue.stream().filter { s: String? -> s.startsWith(FILE_INPUT_PREFIX) }.collect(Collectors.toList())
        residue =
            residue.stream().filter { p: String? -> !paths.contains(p!!) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

        var residueStr: String = com.google.common.base.Joiner.on(' ').join(residue)
        if (options.getUppercase()) {
            residueStr = com.google.common.base.Ascii.toUpperCase(residueStr)
        }
        outputs.add(residueStr)
        var prefix = if (options.getIgnoreSandbox()) "" else request.getSandboxDir()
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length - 1)
        }
        for (p in paths) {
            val path: Path = Paths.get(prefix, p.substring(FILE_INPUT_PREFIX.length))
            val lines: MutableList<String?> = java.nio.file.Files.readAllLines(path)
            var content: String = com.google.common.base.Joiner.on("\n").join(lines)
            if (options.getUppercase()) {
                content = com.google.common.base.Ascii.toUpperCase(content)
            }
            outputs.add(content)
        }

        if (options.getPrintInputs()) {
            for (input in inputs.entries) {
                outputs.add("INPUT " + input.key + " " + input.value)
            }
        }

        if (options.getPrintEnv()) {
            for (entry in java.lang.System.getenv().entries) {
                outputs.add(entry.key + "=" + entry.value)
            }
        }

        val outputStr: String = com.google.common.base.Joiner.on('\n').join(outputs)
        if (options.getOutputFile().isEmpty()) {
            println(outputStr)
        } else {
            val actualFile: String? =
                if (prefix.isEmpty()) options.getOutputFile() else prefix + "/" + options.getOutputFile()
            PrintStream(actualFile).use { outputFile ->
                outputFile.println(outputStr)
            }
        }
    }
}
