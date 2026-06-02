// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.authandtls.credentialhelper

/**
 * WallTicker is a Ticker which reports wall time since the unix epoch.
 * 
 * 
 * We use this instead of com.github.benmanes.caffeine.cache.Ticker.SystemTicker because the
 * latter uses monotonic time (which doesn't increment the time source while the system is asleep)
 * with an unspecified reference point (which is unhelpful when computing the cache duration for
 * credentials whose expiry is a fixed point in time, not a fixed duration).
 */
internal class WallTicker(clock: com.google.devtools.build.lib.clock.Clock) :
    com.github.benmanes.caffeine.cache.Ticker {
    private val clock: com.google.devtools.build.lib.clock.Clock

    init {
        this.clock = clock
    }

    override fun read(): Long {
        // Documented to return a value in nanoseconds.
        return java.time.Duration.ofMillis(clock.currentTimeMillis()).toNanos()
    }
}
