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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.query2.engine.ThreadSafeOutputFormatterCallback
import java.util.stream.Collectors

/** A [ThreadSafeOutputFormatterCallback] that has a name to select on.  */
abstract class NamedThreadSafeOutputFormatterCallback<T>
    : ThreadSafeOutputFormatterCallback<T?>() {
    abstract val name: String?

    companion object {
        fun <T> callbackNames(
            callbacks: Iterable<NamedThreadSafeOutputFormatterCallback<T?>?>
        ): String? {
            return com.google.common.collect.Streams.stream<NamedThreadSafeOutputFormatterCallback<T?>?>(callbacks)
                .map<String?> { obj: NamedThreadSafeOutputFormatterCallback<T?>? -> obj!!.name }
                .collect(Collectors.joining(", "))
        }

        fun <T> selectCallback(
            type: String?, callbacks: Iterable<NamedThreadSafeOutputFormatterCallback<T?>>
        ): NamedThreadSafeOutputFormatterCallback<T?>? {
            for (callback in callbacks) {
                if (callback.name == type) {
                    return callback
                }
            }
            return null
        }
    }
}
