// Copyright 2017 The Bazel Authors. All rights reserved.
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

import java.io.IOException

/**
 * A [ThreadSafeOutputFormatterCallback] wrapper around a [OutputFormatterCallback]
 * delegate.
 */
class SynchronizedDelegatingOutputFormatterCallback<T>
    (private val delegate: OutputFormatterCallback<T?>) : ThreadSafeOutputFormatterCallback<T?>() {
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun start() {
        delegate.start()
    }

    @kotlin.jvm.Synchronized
    @Throws(InterruptedException::class, IOException::class)
    override fun close(failFast: Boolean) {
        delegate.close(failFast)
    }

    @kotlin.jvm.Synchronized
    @Throws(QueryException::class, InterruptedException::class)
    override fun process(partialResult: Iterable<T?>?) {
        delegate.process(partialResult)
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class, InterruptedException::class)
    override fun processOutput(partialResult: Iterable<T?>?) {
        delegate.processOutput(partialResult)
    }

    override fun getIoException(): IOException? {
        return delegate.getIoException()
    }
}
