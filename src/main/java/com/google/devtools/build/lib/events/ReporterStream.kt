// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.events

/**
 * An OutputStream that delegates all writes to an EventHandler.
 */
class ReporterStream(
    handler: com.google.devtools.build.lib.events.EventHandler?,
    eventKind: com.google.devtools.build.lib.events.EventKind?
) : java.io.OutputStream() {
    private val handler: com.google.devtools.build.lib.events.EventHandler
    private val eventKind: com.google.devtools.build.lib.events.EventKind

    init {
        this.handler =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.events.EventHandler>(handler)
        this.eventKind =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.events.EventKind>(eventKind)
    }

    override fun close() {
        // NOP.
    }

    override fun flush() {
        // NOP.
    }

    override fun write(b: Int) {
        handler.handle(
            com.google.devtools.build.lib.events.Event.Companion.of(
                eventKind,
                null,
                byteArrayOf(b.toByte())
            )
        )
    }

    override fun write(bytes: ByteArray) {
        write(bytes, 0, bytes.size)
    }

    override fun write(bytes: ByteArray, offset: Int, len: Int) {
        handler.handle(
            com.google.devtools.build.lib.events.Event.Companion.of(
                eventKind,
                null,
                java.util.Arrays.copyOfRange(bytes, offset, offset + len)
            )
        )
    }
}
