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

/** An implementation of a Bazel worker using Proto to communicate with the worker process.  */
internal class ProtoWorkerProtocol(workersStdin: java.io.OutputStream, workersStdout: java.io.InputStream?) :
    WorkerProtocolImpl {
    /** The worker process's stdin, which we send requests to.  */
    private val workersStdin: java.io.OutputStream

    /** The worker process's stdout, which we read responses from.  */
    private val workersStdout: java.io.InputStream?

    init {
        this.workersStdin = workersStdin
        this.workersStdout = workersStdout
    }

    @Throws(IOException::class)
    override fun putRequest(request: WorkRequest) {
        request.writeDelimitedTo(workersStdin)
        workersStdin.flush()
    }

    @get:Throws(IOException::class)
    val response: WorkResponse
        get() {
            val interrupted: Boolean = java.lang.Thread.interrupted()
            try {
                return WorkResponse.parseDelimitedFrom(workersStdout)
            } finally {
                if (interrupted) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }

    override fun close() {}
}
