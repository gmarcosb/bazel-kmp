// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventstream

/**
 * Event used to log when a "large" build event was serialized by the Build Event Service transport.
 * The threshold for large event is defined by
 * [LargeBuildEventSerializedEvent.SIZE_OF_LARGE_BUILD_EVENTS_IN_BYTES].
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class LargeBuildEventSerializedEvent(val buildEventId: String?, val serializedSizeInBytes: Int) {
    companion object {
        /**
         * Size of events considered 'large'.
         * TODO(lpino): This size should be 1MB as recommended by
         * protobuf (https://developers.google.com/protocol-buffers/docs/techniques).
         */
        const val SIZE_OF_LARGE_BUILD_EVENTS_IN_BYTES: Int = 10000000
    }
}
