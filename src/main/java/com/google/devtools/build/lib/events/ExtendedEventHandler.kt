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
 * Interface for reporting events during the build. It extends the [EventHandler] by also
 * allowing posting more structured information.
 */
interface ExtendedEventHandler : com.google.devtools.build.lib.events.EventHandler {
    /** An event that can be posted via the extended event handler.  */
    interface Postable : com.google.devtools.build.lib.events.Reportable {
        override fun reportTo(handler: ExtendedEventHandler) {
            handler.post(this)
        }

        override fun withTag(tag: String?): Postable {
            return this // No tag-based filtering.
        }

        companion object {
            /** Replays a sequence of posts on `handler`.  */
            fun replayPostsOn(handler: ExtendedEventHandler, posts: Iterable<Postable?>) {
                for (post in posts) {
                    handler.post(post)
                }
            }
        }
    }

    /** Posts a [Postable] object about an important build event.  */
    fun post(obj: Postable?)

    /**
     * Cleans up any resources used by the event handler. This is called when the event handler is no
     * longer needed.
     */
    fun cleanup() {}

    /** A progress event that reports about fetching from a remote site.  */
    interface FetchProgress : Postable {
        /**
         * The resource that was originally requested and uniquely determines the fetch source. The
         * actual fetching may use mirrors, proxies, or similar. The resource need not be an URL, but it
         * has to uniquely identify the particular fetch among all fetch events.
         */
        @kotlin.jvm.JvmField
        val resourceIdentifier: String?

        /** Human readable description of the progress  */
        @kotlin.jvm.JvmField
        val progress: String?

        /** Wether the fetch progress reported about is finished already  */
        @kotlin.jvm.JvmField
        val isFinished: Boolean
    }

    companion object {
        @kotlin.jvm.JvmField
        val NOOP: ExtendedEventHandler = object : ExtendedEventHandler {
            override fun handle(event: com.google.devtools.build.lib.events.Event?) {}

            override fun post(obj: Postable?) {}
        }
    }
}
