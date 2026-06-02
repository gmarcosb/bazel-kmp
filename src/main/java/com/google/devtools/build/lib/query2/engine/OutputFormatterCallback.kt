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
package com.google.devtools.build.lib.query2.engine

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible

/** A callback that can receive a finish event when there are no more partial results  */
@ThreadCompatible
abstract class OutputFormatterCallback<T> : Callback<T?> {
    private var ioException: IOException? = null

    /**
     * This method will be called before any partial result are available.
     * 
     * 
     * It should be used for opening resources or sending a header to the output.
     */
    @Throws(IOException::class)
    open fun start() {
    }

    /**
     * Flushes remaining output and cleans up resources if necessary.
     * 
     * 
     * This method is called whether or not there was an error.
     * 
     * @param failFast Indicates whether or not this method is being called after an error. When true
     * implementations should prefer cleaning up resources and avoiding throwing unnecessary
     * exceptions over completing the output.
     */
    @Throws(InterruptedException::class, IOException::class)
    open fun close(failFast: Boolean) {
    }

    /**
     * Note that [Callback] interface does not throw IOExceptions. What this implementation does
     * instead is throw `IoExceptionInterruptedException` and store the `IOException` in
     * the `ioException` field. Users of this class should check on InterruptedException the
     * field to disambiguate between real interruptions or IO Exceptions.
     */
    @Throws(QueryException::class, InterruptedException::class)
    override fun process(partialResult: Iterable<T?>?) {
        try {
            processOutput(partialResult)
        } catch (e: IOException) {
            ioException = e
            throw IoExceptionInterruptedException(e)
        }
    }

    /**
     * Specialization of InterruptedException that indicates that the interruption was triggered by an
     * IOException in the OutputFormatter.
     */
    class IoExceptionInterruptedException(cause: IOException) :
        InterruptedException("Interrupting due to a IOException in the OutputFormatter: " + cause.message) {
        init {
            initCause(cause)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    abstract fun processOutput(partialResult: Iterable<T?>?)

    open fun getIoException(): IOException? {
        return ioException
    }

    companion object {
        /**
         * Use an `OutputFormatterCallback` with an already computed set of targets. Note that this
         * does not work in stream mode, as the `targets` would already be computed.
         * 
         * 
         * The intended usage of this method is to use `StreamedFormatter` formatters in non
         * streaming contexts.
         */
        @Throws(IOException::class, InterruptedException::class)
        fun <T> processAllTargets(callback: OutputFormatterCallback<T?>, targets: Iterable<T?>?) {
            var failFast = true
            try {
                callback.start()
                callback.process(targets)
                failFast = false
            } catch (e: InterruptedException) {
                val ioException: IOException? = callback.getIoException()
                if (ioException != null) {
                    throw ioException
                }
                throw e
            } catch (e: QueryException) {
                throw IllegalStateException(
                    ("This should not happen, as we are not running any query,"
                            + " only printing the results:"
                            + e.message),
                    e
                )
            } finally {
                callback.close(failFast)
            }
        }
    }
}
