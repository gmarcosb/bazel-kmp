// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest

/** Implementation of the Worker Protocol using Proto to communicate with Bazel.  */
class ProtoWorkerMessageProcessor
    (stdin: java.io.InputStream?, stdout: java.io.OutputStream) : WorkerMessageProcessor {
    /** This worker's stdin.  */
    private val stdin: java.io.InputStream?

    /** This worker's stdout. Only [WorkRequest]s should be written here.  */
    private val stdout: java.io.OutputStream

    /** Constructs a [WorkRequestHandler] that reads and writes Protocol Buffers.  */
    init {
        this.stdin = stdin
        this.stdout = stdout
    }

    @Throws(IOException::class)
    override fun readWorkRequest(): WorkRequest {
        return WorkRequest.parseDelimitedFrom(stdin)
    }

    @Throws(IOException::class)
    override fun writeWorkResponse(workResponse: WorkResponse) {
        try {
            workResponse.writeDelimitedTo(stdout)
        } finally {
            stdout.flush()
        }
    }

    override fun close() {}
}
