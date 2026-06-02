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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.buildtool.BuildRequestOptions

/** Provides support for reading target patterns from a file or the command-line.  */
object TargetPatternsHelper {
    private val TARGET_PATTERN_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on('#')

    /**
     * Reads a list of target patterns, either from the command-line residue or by reading newline
     * delimited target patterns from the --target_pattern_file flag. If --target_pattern_file is
     * specified and options contain a residue, or if the file cannot be read, throws [ ].
     */
    @Throws(TargetPatternsHelperException::class)
    fun readFrom(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): MutableList<String?> {
        var targets: MutableList<String?> = options.getResidue()
        val buildRequestOptions: BuildRequestOptions? = options.getOptions<O?>(BuildRequestOptions::class.java)
        if (!targets.isEmpty() && !buildRequestOptions.targetPatternFile.isEmpty()) {
            throw TargetPatternsHelperException(
                "Command-line target pattern and --target_pattern_file cannot both be specified",
                TargetPatterns.Code.TARGET_PATTERN_FILE_WITH_COMMAND_LINE_PATTERN
            )
        } else if (!buildRequestOptions.targetPatternFile.isEmpty()) {
            // Works for absolute or relative file.
            val residuePath: com.google.devtools.build.lib.vfs.Path =
                env.getWorkingDirectory().getRelative(buildRequestOptions.targetPatternFile)
            try {
                env.getEventBus()
                    .post(
                        InputFileEvent.Companion.create( /* type= */
                            "target_pattern_file", residuePath.getFileSize()
                        )
                    )
                targets =
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readLines(
                        residuePath,
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    ).stream()
                        .map<String?>(java.util.function.Function { s: String? ->
                            TARGET_PATTERN_SPLITTER.splitToList(s).get(0)
                        })
                        .map<String?>(java.util.function.Function { obj: String? -> obj.trim() })
                        .filter(java.util.function.Predicate.not<String?>(java.util.function.Predicate { obj: String? -> obj.isEmpty() }))
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
            } catch (e: IOException) {
                throw TargetPatternsHelperException(
                    "I/O error reading from " + residuePath.getPathString() + ": " + e.getMessage(),
                    TargetPatterns.Code.TARGET_PATTERN_FILE_READ_FAILURE
                )
            }
        } else {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("ProjectFileSupport.getTargets")
                .use { closeable ->
                    targets = ProjectFileSupport.getTargets(env.getRuntime().getProjectFileProvider(), options)
                }
        }
        return targets
    }

    /** Thrown when target patterns couldn't be read.  */
    class TargetPatternsHelperException private constructor(message: String?, detailedCode: TargetPatterns.Code?) :
        java.lang.Exception(com.google.common.base.Preconditions.checkNotNull<String?>(message)) {
        private val detailedCode: TargetPatterns.Code?

        init {
            this.detailedCode = detailedCode
        }

        val failureDetail: FailureDetail
            get() = FailureDetail.newBuilder()
                .setMessage(getMessage())
                .setTargetPatterns(TargetPatterns.newBuilder().setCode(detailedCode))
                .build()
    }
}
