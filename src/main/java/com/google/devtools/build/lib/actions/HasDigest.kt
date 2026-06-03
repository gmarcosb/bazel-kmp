// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.protobuf.ByteString

/** A marker interface for objects which can return a byte[] digest.  */
@java.lang.FunctionalInterface
interface HasDigest : java.io.Serializable {
    fun getDigest(): ByteArray?

    /** An immutable wrapper around a `byte[]` digest.  */
    class ByteStringDigest(bytes: ByteArray) : HasDigest {
        private val bytes: ByteString

        init {
            this.bytes = ByteString.copyFrom(bytes)
        }

        override fun getDigest(): ByteArray? {
            return bytes.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (other is ByteStringDigest) {
                return bytes == other.bytes
            }
            return false
        }

        override fun hashCode(): Int {
            return bytes.hashCode()
        }
    }

    companion object {
        val EMPTY: HasDigest = ByteStringDigest(byteArrayOf())
    }
}
