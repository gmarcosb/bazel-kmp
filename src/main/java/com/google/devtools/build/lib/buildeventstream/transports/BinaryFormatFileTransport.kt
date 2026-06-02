// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream.transports

import java.io.ByteArrayOutputStream

/**
 * A simple [BuildEventTransport] that writes a varint delimited binary representation of
 * [BuildEvent] protocol buffers to a file.
 */
class BinaryFormatFileTransport(
    outputStream: BufferedOutputStream?,
    options: BuildEventProtocolOptions?,
    uploader: BuildEventArtifactUploader?,
    namer: ArtifactGroupNamer?,
    besUploadMode: BesUploadMode?
) : FileTransport(outputStream, options, uploader, namer, besUploadMode) {
    override fun name(): String {
        return this.getClass().getSimpleName()
    }

    override fun serializeEvent(buildEvent: BuildEvent): ByteArray? {
        val size: Int = buildEvent.getSerializedSize()
        val bos =
            ByteArrayOutputStream(CodedOutputStream.computeUInt32SizeNoTag(size) + size)
        try {
            buildEvent.writeDelimitedTo(bos)
        } catch (e: IOException) {
            throw RuntimeException(
                "Unexpected error serializing protobuf to in memory outputstream.", e
            )
        }
        return bos.toByteArray()
    }
}
