// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.util.io.OutErr
import java.io.IOException

/**
 * An [OutErr] specialization that supports subscribing / removing
 * sinks, using [.addSink] and [.removeSink].
 * A sink is a destination to which the [DelegatingOutErr] will write.
 * 
 * Also, we can hook up [System.out] / [System.err] as sources.
 */
class DelegatingOutErr
/**
 * Create a new instance to which no sinks have subscribed (basically just
 * like a `/dev/null`.
 */
    : OutErr(DelegatingOutputStream(), DelegatingOutputStream()) {
    private fun outSink(): DelegatingOutputStream {
        return getOutputStream() as DelegatingOutputStream
    }

    private fun errSink(): DelegatingOutputStream {
        return getErrorStream() as DelegatingOutputStream
    }

    /**
     * Add a sink, that is, after calling this method, `outErrSink` will
     * receive all output / errors written to `this` object.
     */
    fun addSink(outErrSink: OutErr) {
        outSink().addSink(outErrSink.getOutputStream())
        errSink().addSink(outErrSink.getErrorStream())
    }

    /**
     * Remove the sink, that is, after calling this method, `outErrSink`
     * will no longer receive output / errors written to `this` object.
     */
    fun removeSink(outErrSink: OutErr) {
        outSink().removeSink(outErrSink.getOutputStream())
        errSink().removeSink(outErrSink.getErrorStream())
    }

    private class DelegatingOutputStream : java.io.OutputStream() {
        private val sinks: MutableList<java.io.OutputStream> = java.util.ArrayList<java.io.OutputStream>()

        fun addSink(sink: java.io.OutputStream?) {
            sinks.add(com.google.common.base.Preconditions.checkNotNull<java.io.OutputStream?>(sink))
        }

        fun removeSink(sink: java.io.OutputStream?) {
            sinks.remove(sink)
        }

        @Throws(IOException::class)
        override fun write(b: Int) {
            for (sink in sinks) {
                sink.write(b)
            }
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            for (sink in sinks) {
                sink.write(b, off, len)
            }
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?) {
            for (sink in sinks) {
                sink.write(b)
            }
        }

        @Throws(IOException::class)
        override fun close() {
            // Ensure that we close all sinks even if one throws.
            var firstException: IOException? = null
            for (sink in sinks) {
                try {
                    sink.close()
                } catch (e: IOException) {
                    if (firstException == null) {
                        firstException = e
                    } else {
                        firstException.addSuppressed(e)
                    }
                }
            }

            if (firstException != null) {
                throw firstException
            }
        }

        @Throws(IOException::class)
        override fun flush() {
            for (sink in sinks) {
                sink.flush()
            }
        }
    }
}
