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
package com.google.devtools.build.remote.worker.http

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import io.netty.channel.ChannelHandler
import java.util.concurrent.ConcurrentMap

/** A simple HTTP REST in-memory cache used during testing the LRE.  */
@ChannelHandler.Sharable
class InMemoryHttpCacheServerHandler @VisibleForTesting constructor(cache: ConcurrentMap<String?, ByteArray?>?) :
    AbstractHttpCacheServerHandler() {
    private val cache: ConcurrentMap<String?, ByteArray?>

    init {
        this.cache = Preconditions.checkNotNull<ConcurrentMap<String?, ByteArray?>>(cache)
    }

    override fun readFromCache(uri: String?): ByteArray? {
        return cache.get(uri)
    }

    override fun writeToCache(uri: String?, content: ByteArray?) {
        cache.putIfAbsent(uri, content)
    }
}
