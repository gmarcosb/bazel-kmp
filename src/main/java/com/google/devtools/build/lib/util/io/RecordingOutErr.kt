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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.util.io.OutErr
import java.io.UnsupportedEncodingException

/**
 * An implementation of [OutErr] that captures all out / err output and
 * makes it available as ISO-8859-1 strings. Useful for implementing test
 * cases that assert particular output.
 */
class RecordingOutErr : OutErr {
    constructor() : super(java.io.ByteArrayOutputStream(), java.io.ByteArrayOutputStream())

    constructor(out: java.io.ByteArrayOutputStream?, err: java.io.ByteArrayOutputStream?) : super(out, err)

    /**
     * Reset the captured content; that is, reset the out / err buffers.
     */
    fun reset() {
        this.outputStream.reset()
        this.errorStream.reset()
    }

    /**
     * Interprets the captured out content as an `ISO-8859-1` encoded
     * string.
     */
    fun outAsLatin1(): String {
        try {
            return this.outputStream.toString("ISO-8859-1")
        } catch (e: UnsupportedEncodingException) {
            throw java.lang.AssertionError(e)
        }
    }

    /**
     * Interprets the captured err content as an `ISO-8859-1` encoded
     * string.
     */
    fun errAsLatin1(): String {
        try {
            return this.errorStream.toString("ISO-8859-1")
        } catch (e: UnsupportedEncodingException) {
            throw java.lang.AssertionError(e)
        }
    }

    /**
     * Returns true if any output was recorded.
     */
    fun hasRecordedOutput(): Boolean {
        return this.outputStream.size() > 0 || this.errorStream.size() > 0
    }

    override fun toString(): String {
        val out = outAsLatin1()
        val err = errAsLatin1()
        return ("" + (if (out.length > 0) ("stdout: " + out + "\n") else "")
                + (if (err.length > 0) ("stderr: " + err) else ""))
    }

    val outputStream: java.io.ByteArrayOutputStream?
        get() = super.getOutputStream() as java.io.ByteArrayOutputStream?

    val errorStream: java.io.ByteArrayOutputStream?
        get() = super.getErrorStream() as java.io.ByteArrayOutputStream?
}
