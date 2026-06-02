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
package com.google.devtools.build.lib.remote.http

import build.bazel.remote.execution.v2.Digest
import com.google.common.base.Preconditions
import java.io.OutputStream
import java.net.URI

/** Object sent through the channel pipeline to start a download.  */
internal class DownloadCommand(
    uri: URI?,
    private val casDownload: Boolean,
    digest: Digest?,
    out: OutputStream?,
    private val offset: Long
) {
    private val uri: URI
    private val digest: Digest
    private val out: OutputStream

    init {
        this.uri = Preconditions.checkNotNull<URI>(uri)
        this.digest = Preconditions.checkNotNull<Digest>(digest)
        this.out = Preconditions.checkNotNull<OutputStream>(out)
    }

    constructor(uri: URI?, casDownload: Boolean, digest: Digest?, out: OutputStream?) : this(
        uri,
        casDownload,
        digest,
        out,
        0
    )

    fun uri(): URI {
        return uri
    }

    fun casDownload(): Boolean {
        return casDownload
    }

    fun digest(): Digest {
        return digest
    }

    fun out(): OutputStream {
        return out
    }

    fun offset(): Long {
        return offset
    }
}
