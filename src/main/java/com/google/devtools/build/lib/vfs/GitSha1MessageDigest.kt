// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import java.security.MessageDigest

/** A [MessageDigest] for GitSha1.  */
class GitSha1MessageDigest : MessageDigest("GITSHA1") {
    private val sha1: MessageDigest
    private val stream: java.io.ByteArrayOutputStream

    init {
        sha1 = MessageDigest.getInstance("SHA-1")
        stream = java.io.ByteArrayOutputStream()
    }

    public override fun engineUpdate(data: ByteArray?, offset: Int, length: Int) {
        stream.write(data, offset, length)
    }

    public override fun engineUpdate(b: Byte) {
        stream.write(b.toInt())
    }

    override fun engineReset() {
        internalReset()
    }

    override fun engineDigest(): ByteArray? {
        val size: Int = stream.size()
        sha1.update(header)
        sha1.update(java.lang.Integer.toString(size).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        sha1.update(0.toByte())
        sha1.update(stream.toByteArray())
        val digest: ByteArray? = sha1.digest()
        internalReset()
        return digest
    }

    private fun internalReset() {
        sha1.reset()
        stream.reset()
    }

    companion object {
        private val header =
            byteArrayOf('b'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(), ' '.code.toByte())
    }
}
