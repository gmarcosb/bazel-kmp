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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.auth.Credentials
import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.vfs.Path
import java.io.IOException
import java.net.URI
import java.util.*

/**
 * A [Downloader] that delegates to another Downloader. Primarily useful for mutable
 * dependency injection.
 */
class DelegatingDownloader(private val defaultDelegate: Downloader?) : Downloader {
    private var delegate: Downloader? = null

    /**
     * Sets the [Downloader] to delegate to. If setDelegate(null) is called, the default
     * delegate passed to the constructor will be used.
     */
    fun setDelegate(delegate: Downloader?) {
        this.delegate = delegate
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun download(
        urls: MutableList<URI?>?,
        headers: MutableMap<String?, MutableList<String?>?>?,
        credentials: Credentials?,
        checksum: Optional<Checksum?>?,
        canonicalId: String?,
        destination: Path?,
        eventHandler: ExtendedEventHandler?,
        clientEnv: MutableMap<String?, String?>?,
        type: Optional<String?>?,
        context: String?
    ) {
        var downloader = defaultDelegate
        if (delegate != null) {
            downloader = delegate
        }
        downloader!!.download(
            urls,
            headers,
            credentials,
            checksum,
            canonicalId,
            destination,
            eventHandler,
            clientEnv,
            type,
            context
        )
    }
}
