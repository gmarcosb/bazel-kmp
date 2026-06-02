// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.protobuf.AbstractMessageLite
import com.google.protobuf.ByteString
import java.io.IOException

/** A [DeterministicWriter] wrapping an [AbstractMessageLite] supplier.  */
class ProtoDeterministicWriter : DeterministicWriter {
    private val messageSupplier: MessageSupplier

    /** Constructs a [ProtoDeterministicWriter] with an eagerly constructed message.  */
    constructor(message: AbstractMessageLite<*, *>?) {
        this.messageSupplier = MessageSupplier { message }
    }

    /**
     * Constructs a [ProtoDeterministicWriter] with the given supplier. The supplier may be
     * called multiple times, but must supply the same message every time.
     */
    constructor(supplier: MessageSupplier) {
        this.messageSupplier = supplier
    }

    @Throws(IOException::class)
    public override fun writeTo(out: java.io.OutputStream?) {
        messageSupplier.getMessage().writeTo(out)
    }

    @get:Throws(IOException::class)
    val bytes: ByteString?
        get() = messageSupplier.getMessage().toByteString()

    /** Supplies an [AbstractMessageLite], possibly throwing [IOException].  */
    fun interface MessageSupplier {
        @Throws(IOException::class)
        fun getMessage(): AbstractMessageLite<*, *>?
    }
}
