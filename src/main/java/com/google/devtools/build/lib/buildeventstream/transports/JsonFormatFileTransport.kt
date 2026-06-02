// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting
import java.nio.charset.StandardCharsets

/**
 * A simple [BuildEventTransport] that writes the JSON representation of the protocol-buffer
 * representation of the events to a file.
 */
class JsonFormatFileTransport(
    outputStream: BufferedOutputStream?,
    options: BuildEventProtocolOptions?,
    uploader: BuildEventArtifactUploader?,
    namer: ArtifactGroupNamer?,
    typeRegistry: TypeRegistry?,
    besUploadMode: BesUploadMode?
) : FileTransport(outputStream, options, uploader, namer, besUploadMode) {
    private val jsonPrinter: Printer

    init {
        jsonPrinter =
            JsonFormat.printer().usingTypeRegistry(typeRegistry).omittingInsignificantWhitespace()
    }

    override fun name(): String {
        return this.getClass().getSimpleName()
    }

    override fun serializeEvent(buildEvent: BuildEvent?): ByteArray? {
        var protoJsonRepresentation: String?
        try {
            protoJsonRepresentation = jsonPrinter.print(buildEvent)
        } catch (e: InvalidProtocolBufferException) {
            // We don't expect any unknown Any fields in our protocol buffer. Nevertheless, handle
            // the exception gracefully and, at least, return valid JSON with an id field.
            logger.atWarning().withCause(e).log(
                "Failed to serialize to JSON due to Any type resolution failure: %s", buildEvent
            )
            protoJsonRepresentation = UNKNOWN_ANY_TYPE_ERROR_EVENT
        }
        return (protoJsonRepresentation + "\n").getBytes(StandardCharsets.UTF_8)
    }

    /** Error produced when serializing an `Any` protobuf whose contained type is unknown.  */
    @VisibleForTesting
    internal class UnknownAnyProtoError {
        @Suppress("unused") // Used by Gson formatting; cannot be static
        private val id = "unknown"

        @Suppress("unused") // Used by Gson formatting; cannot be static
        private val exception = "InvalidProtocolBufferException"
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val UNKNOWN_ANY_TYPE_ERROR_EVENT: String? = GsonBuilder().create().toJson(UnknownAnyProtoError())
    }
}
