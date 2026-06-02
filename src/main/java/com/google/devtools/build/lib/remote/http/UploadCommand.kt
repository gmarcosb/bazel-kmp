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

import com.google.common.base.Preconditions
import java.io.InputStream
import java.net.URI

/** Object sent through the channel pipeline to start an upload.  */
internal class UploadCommand(
    uri: URI?,
    private val casUpload: Boolean,
    hash: String?,
    data: InputStream?,
    private val contentLength: Long
) {
    private val uri: URI
    private val hash: String
    private val data: InputStream

    init {
        this.uri = Preconditions.checkNotNull<URI>(uri)
        this.hash = Preconditions.checkNotNull<String>(hash)
        this.data = Preconditions.checkNotNull<InputStream>(data)
    }

    fun uri(): URI {
        return uri
    }

    fun casUpload(): Boolean {
        return casUpload
    }

    fun hash(): String {
        return hash
    }

    fun data(): InputStream {
        return data
    }

    fun contentLength(): Long {
        return contentLength
    }
}
