// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.util.StringUtilities
import java.lang.String
import java.net.URI
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.Boolean
import kotlin.Long

/**
 * Postable event reporting on progress made downloading an URL. It can be used to report the URL
 * being downloaded and the number of bytes read so far.
 */
class DownloadProgressEvent(
    val originalUrl: URI,
    val actualUrl: URI?,
    val bytesRead: Long,
    private val totalBytes: OptionalLong,
    private val downloadFinished: Boolean
) : ExtendedEventHandler.FetchProgress {
    constructor(originalUrl: URI, bytesRead: Long, finished: Boolean) : this(
        originalUrl,
        null,
        bytesRead,
        OptionalLong.empty(),
        finished
    )

    @kotlin.jvm.JvmOverloads
    constructor(url: URI, bytesRead: Long = 0) : this(url, bytesRead, false)

    override fun getResourceIdentifier(): String? {
        return originalUrl.toString()
    }

    override fun isFinished(): Boolean {
        return downloadFinished
    }

    override fun getProgress(): String? {
        if (bytesRead > 0) {
            if (totalBytes.isPresent()) {
                val totalBytesDouble = this.totalBytes.getAsLong().toDouble()
                val ratio = if (totalBytesDouble != 0.0) bytesRead / totalBytesDouble else 1.0
                // 10.1 MiB (20.2%)
                return String.format(
                    "%s (%s)", StringUtilities.bytesCountToDisplayString(bytesRead), PERCENTAGE_FORMAT.format(ratio)
                )
            } else {
                // 10.1 MiB (10,590,000B)
                return String.format("%s (%,dB)", StringUtilities.bytesCountToDisplayString(bytesRead), bytesRead)
            }
        } else {
            return ""
        }
    }

    companion object {
        private val PERCENTAGE_FORMAT = DecimalFormat("0.0%", DecimalFormatSymbols(Locale.US))
    }
}
