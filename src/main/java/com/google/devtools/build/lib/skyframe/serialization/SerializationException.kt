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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason

/** Exception signaling a failure to Serialize or Deserialize an Object.  */
open class SerializationException : java.lang.Exception {
    private val trail: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
    private val reason: MissReason?

    constructor(msg: String?) : super(msg) {
        this.reason = MissReason.MISS_REASON_UNSPECIFIED
    }

    constructor(cause: Throwable?) : super(cause) {
        this.reason =
            com.google.devtools.build.lib.skyframe.serialization.SerializationException.Companion.maybePropagateReason(
                cause
            )
    }

    constructor(msg: String?, reason: MissReason?) : super(msg) {
        this.reason = reason
    }

    constructor(msg: String?, cause: Throwable?) : super(msg, cause) {
        this.reason =
            com.google.devtools.build.lib.skyframe.serialization.SerializationException.Companion.maybePropagateReason(
                cause
            )
    }

    constructor(msg: String?, cause: Throwable?, reason: MissReason?) : super(msg, cause) {
        this.reason = reason
    }

    fun getReason(): MissReason? {
        return reason
    }

    // No SerializationException(Throwable) overload because serialization errors should always
    // provide as much context as possible.
    /**
     * [SerializationException] indicating that Blaze has no serialization schema for an object
     * or type of object.
     */
    class NoCodecException : SerializationException {
        internal constructor(message: String?) : super(message)

        internal constructor(message: String?, type: java.lang.Class<*>) : super(message) {
            addTrail(type)
        }

        // Needed for wrapping.
        internal constructor(message: String?, e: NoCodecException?) : super(message, e)
    }

    override fun getMessage(): String {
        return super.getMessage() + (if (trail.isEmpty()) "" else " " + trail)
    }

    /**
     * Adds extra tracing info for debugging.
     * 
     * 
     * Primarily useful for [DynamicCodec].
     */
    fun addTrail(type: java.lang.Class<*>) {
        trail.add(type.getName())
    }

    fun getTrailForTesting(): com.google.common.collect.ImmutableList<String?> {
        return com.google.common.collect.ImmutableList.copyOf<String?>(trail)
    }

    companion object {
        private fun maybePropagateReason(cause: Throwable?): MissReason? {
            return if (cause is SerializationException)
                cause.getReason()
            else
                MissReason.MISS_REASON_UNSPECIFIED
        }

        /**
         * Throws a [SerializationException] with the given message and that wraps the given cause.
         * 
         * 
         * If the cause is a [NoCodecException], the returned exception will also be a `NoCodecException`.
         * 
         * 
         * The return type is [SerializationException] rather than `void` so that you can
         * call this function from within a `throw` statement. Doing so keeps the calling code more
         * readable. It also avoids spurious compiler errors, e.g. for using uninitialized variables after
         * the `throw`.
         */
        fun propagate(msg: String?, cause: Throwable?): SerializationException {
            if (cause is NoCodecException) {
                return NoCodecException(msg, cause as NoCodecException)
            } else {
                return com.google.devtools.build.lib.skyframe.serialization.SerializationException(msg, cause)
            }
        }
    }
}
