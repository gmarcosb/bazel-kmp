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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec

/**
 * An IOException subclass that is thrown when a file system access is denied. The message is
 * generally "Permission denied".
 */
class FileAccessException
/**
 * Constructs an `FileAccessException` with the specified detail
 * message. The error message string `s` can later be
 * retrieved by the `[java.lang.Throwable.getMessage]`
 * method of class `java.lang.Throwable`.
 * 
 * @param s the detail message.
 */
    (s: String?) : IOException(s) {
    /**
     * Codec for [FileAccessException].
     * 
     * 
     * [com.google.devtools.build.lib.skyframe.serialization.AutoRegistry] excludes the
     * entire com.google.devtools.build.lib.vfs java package from having DynamicCodec support.
     * Therefore, we need to provide our own codec for @link FileAccessException}.
     */
    @Suppress("unused") // found by CLASSPATH-scanning magic
    private class Codec : LeafObjectCodec<FileAccessException?>() {
        val encodedClass: java.lang.Class<out FileAccessException?>
            get() = FileAccessException::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: LeafSerializationContext, fae: FileAccessException, codedOut: CodedOutputStream
        ) {
            val message: String? = fae.getMessage()
            if (message == null) {
                codedOut.writeBoolNoTag(false)
                return
            }
            codedOut.writeBoolNoTag(true)
            context.serializeLeaf(message, stringCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(
            context: LeafDeserializationContext, codedIn: CodedInputStream
        ): FileAccessException {
            val hasMessage: Boolean = codedIn.readBool()
            if (!hasMessage) {
                return FileAccessException(null)
            }
            val message: String? = context.deserializeLeaf(codedIn, stringCodec())
            return FileAccessException(message)
        }
    }
}
