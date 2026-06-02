// Copyright 2016 The Bazel Authors. All rights reserved.
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
// limitations under the License.package com.google.devtools.build.lib.flags;
package com.google.devtools.common.options

/** Cache mapping a command to the names of all commands it inherits from, including itself.  */
fun interface CommandNameCache {
    /** Class that exists only to expose a static instance variable that can be set and retrieved.  */
    class CommandNameCacheInstance private constructor() : CommandNameCache {
        private var delegate: CommandNameCache? = null

        /** Only for use by `BlazeRuntime`.  */
        fun setCommandNameCache(cache: CommandNameCache) {
            // Can be set multiple times in tests.
            this.delegate = cache
        }

        override fun get(commandName: String?): com.google.common.collect.ImmutableSet<String?>? {
            return delegate!!.get(commandName)
        }

        companion object {
            @kotlin.jvm.JvmField
            val INSTANCE: CommandNameCacheInstance =
                com.google.devtools.common.options.CommandNameCache.CommandNameCacheInstance()
        }
    }

    /** Returns the names of all commands `commandName` inherits from, including itself.  */
    fun get(commandName: String?): com.google.common.collect.ImmutableSet<String?>?
}
