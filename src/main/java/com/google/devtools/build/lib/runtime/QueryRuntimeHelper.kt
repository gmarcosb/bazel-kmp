// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.query2.common.CommonQueryOptions

/**
 * Helper for managing the [OutputStream] to which query/cquery/aquery results should be
 * written.
 */
interface QueryRuntimeHelper : java.lang.AutoCloseable {
    /**
     * Returns the [OutputStream] to which to write query results. This [ ] instance, not the caller, is responsible for closing the [ ].
     */
    @kotlin.jvm.JvmField
    val outputStreamForQueryOutput: java.io.OutputStream?

    /**
     * Should be called after the query is successfully evaluated and the entire query output is
     * written to the [OutputStream] returned by [.getOutputStreamForQueryOutput].
     * 
     * 
     * In particular, this method shouldn't be called if query evaluation fails.
     */
    @Throws(QueryRuntimeHelperException::class, java.lang.InterruptedException::class)
    fun afterQueryOutputIsWritten()

    /** Must be called at some point near the end of the life of the query command.  */
    @Throws(QueryRuntimeHelperException::class)
    override fun close()

    /** Factory for [QueryRuntimeHelper] instances.  */
    interface Factory {
        @Throws(QueryRuntimeHelperException::class)
        fun create(env: CommandEnvironment?, options: CommonQueryOptions?): QueryRuntimeHelper?
    }

    /**
     * A [Factory] for [StdoutQueryRuntimeHelper] instances that simply wrap the given
     * [CommandEnvironment] instance's stdout.
     * 
     * 
     * This is intended to be the default [Factory].
     * 
     * 
     * If `--output_file` is set, the stdout is redirected to the defined path value instead.
     */
    class StdoutQueryRuntimeHelperFactory private constructor() : Factory {
        @Throws(QueryRuntimeHelperException::class)
        override fun create(env: CommandEnvironment, options: CommonQueryOptions): QueryRuntimeHelper {
            if (com.google.common.base.Strings.isNullOrEmpty(options.getOutputFile())) {
                return createInternal(env.getReporter().getOutErr().getOutputStream())
            } else {
                return FileQueryRuntimeHelper.Companion.create(
                    env.getWorkingDirectory().getRelative(options.getOutputFile())
                )
            }
        }

        fun createInternal(stdoutOutputStream: java.io.OutputStream?): QueryRuntimeHelper {
            return StdoutQueryRuntimeHelper(stdoutOutputStream)
        }

        /** A QueryRuntimeHelper that simply wraps a [OutputStream] for stdout.  */
        @com.google.common.annotations.VisibleForTesting
        class StdoutQueryRuntimeHelper private constructor(stdoutOutputStream: java.io.OutputStream?) :
            QueryRuntimeHelper {
            private val stdoutOutputStream: java.io.OutputStream?

            init {
                this.stdoutOutputStream = stdoutOutputStream
            }

            override fun getOutputStreamForQueryOutput(): java.io.OutputStream? {
                return stdoutOutputStream
            }

            override fun afterQueryOutputIsWritten() {}

            override fun close() {}
        }

        /**
         * A [QueryRuntimeHelper] that wraps a [java.io.FileOutputStream] instead of writing
         * to standard out, for improved performance.
         */
        class FileQueryRuntimeHelper private constructor(path: com.google.devtools.build.lib.vfs.Path) :
            QueryRuntimeHelper {
            private val path: com.google.devtools.build.lib.vfs.Path?
            private val out: java.io.OutputStream

            init {
                this.path = path
                this.out = path.getOutputStream()
            }

            override fun getOutputStreamForQueryOutput(): java.io.OutputStream {
                return out
            }

            override fun afterQueryOutputIsWritten() {}

            @Throws(QueryRuntimeHelperException::class)
            override fun close() {
                try {
                    out.close()
                } catch (e: IOException) {
                    throw QueryRuntimeHelperException(
                        "Could not close query output file " + path, Code.QUERY_OUTPUT_WRITE_FAILURE, e
                    )
                }
            }

            companion object {
                @Throws(QueryRuntimeHelperException::class)
                fun create(path: com.google.devtools.build.lib.vfs.Path): FileQueryRuntimeHelper {
                    try {
                        return FileQueryRuntimeHelper(path)
                    } catch (e: IOException) {
                        throw QueryRuntimeHelperException(
                            "Could not open query output file " + path.getPathString(),
                            Code.QUERY_OUTPUT_WRITE_FAILURE,
                            e
                        )
                    }
                }
            }
        }

        companion object {
            val INSTANCE: StdoutQueryRuntimeHelperFactory = StdoutQueryRuntimeHelperFactory()
        }
    }

    /** Describes what went wrong in [QueryRuntimeHelper].  */
    class QueryRuntimeHelperException : java.lang.Exception {
        private val detailedCode: Code?

        constructor(
            message: String?,
            detailedCode: FailureDetails.Query.Code?
        ) : super(com.google.common.base.Preconditions.checkNotNull<String?>(message)) {
            this.detailedCode = detailedCode
        }

        constructor(
            message: String?,
            detailedCode: FailureDetails.Query.Code?,
            cause: Throwable?
        ) : super(com.google.common.base.Preconditions.checkNotNull<String?>(message), cause) {
            this.detailedCode = detailedCode
        }

        val failureDetail: FailureDetail
            get() = FailureDetail.newBuilder()
                .setMessage(getMessage())
                .setQuery(Query.newBuilder().setCode(detailedCode))
                .build()
    }
}
