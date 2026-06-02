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

import com.google.common.base.Joiner
import com.google.common.io.ByteStreams
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.annotation.WillNotClose

internal object DownloaderTestUtils {
    @Throws(IOException::class)
    fun sendLines(@WillNotClose socket: Socket, vararg data: String?) {
        ByteStreams.copy(
            ByteArrayInputStream(Joiner.on("\r\n").join(data).toByteArray(StandardCharsets.ISO_8859_1)),
            socket.getOutputStream()
        )
    }
}
