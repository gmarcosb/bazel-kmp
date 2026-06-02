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
package com.google.devtools.build.lib.util

import com.google.protobuf.ByteString
import java.io.IOException

/**
 * A [DeterministicWriter] writes a stream of bytes to an [OutputStream].
 * 
 * 
 * The same stream of bytes is written on every invocation of [.writeTo].
 */
interface DeterministicWriter {
    /**
     * Writes the stream of bytes to the given [OutputStream].
     * 
     * 
     * Every invocation of this method writes the same stream of bytes.
     * 
     * 
     * Implementations
     * 
     * 
     *  * must not close the given [OutputStream]
     *  * may flush the given [OutputStream]
     *  * should not wrap the given [OutputStream] in a buffered stream. The caller is
     * responsible for providing a buffered stream if necessary.
     * 
     * 
     * @param out the [OutputStream] to write to
     * @throws IOException only if out throws an IOException
     */
    @Throws(IOException::class)
    fun writeTo(out: java.io.OutputStream?)

    @get:Throws(IOException::class)
    val bytes: ByteString?
        /**
         * Returns the stream of bytes as a [ByteString].
         * 
         * 
         * May be used to avoid unnecessary copying by callers that only need a [ByteString].
         * 
         * 
         * The default implementation calls [.writeTo] on a fresh [ByteString.Output] and
         * returns the resulting [ByteString]. Other implementations may provide a more efficient
         * alternative.
         */
        get() {
            val out: com.google.protobuf.ByteString.Output = ByteString.newOutput()
            writeTo(out)
            return out.toByteString()
        }
}
