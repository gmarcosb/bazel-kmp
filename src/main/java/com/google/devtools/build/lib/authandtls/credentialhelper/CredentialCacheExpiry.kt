// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.github.benmanes.caffeine.cache.Expiry
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse
import java.time.Instant

internal class CredentialCacheExpiry : Expiry<java.net.URI?, GetCredentialsResponse?> {
    private var defaultCacheDuration: java.time.Duration = java.time.Duration.ZERO

    /**
     * Sets the default cache duration for [GetCredentialsResponse]s that don't set `expiry`.
     */
    fun setDefaultCacheDuration(duration: java.time.Duration?) {
        this.defaultCacheDuration = com.google.common.base.Preconditions.checkNotNull<java.time.Duration>(duration)
    }

    private fun getCacheDuration(response: GetCredentialsResponse?, now: Instant): java.time.Duration? {
        com.google.common.base.Preconditions.checkNotNull<GetCredentialsResponse?>(response)

        val expires: java.util.Optional<Instant?> = response.expires
        if (expires.isEmpty()) {
            return defaultCacheDuration
        }

        return java.time.Duration.between(now, expires.get())
    }

    override fun expireAfterCreate(uri: java.net.URI?, response: GetCredentialsResponse?, currentTime: Long): Long {
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)
        com.google.common.base.Preconditions.checkNotNull<GetCredentialsResponse?>(response)

        // currentTime is in nanos since epoch (see WallTicker).
        val now: Instant = Instant.ofEpochSecond(0, currentTime)

        return getCacheDuration(response, now).toNanos()
    }

    override fun expireAfterUpdate(
        uri: java.net.URI?, response: GetCredentialsResponse?, currentTime: Long, currentDuration: Long
    ): Long {
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)
        com.google.common.base.Preconditions.checkNotNull<GetCredentialsResponse?>(response)

        // currentTime is in nanos since epoch (see WallTicker).
        val now: Instant = Instant.ofEpochSecond(0, currentTime)

        return getCacheDuration(response, now).toNanos()
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun expireAfterRead(
        uri: java.net.URI?, response: GetCredentialsResponse?, currentTime: Long, currentDuration: Long
    ): Long {
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)
        com.google.common.base.Preconditions.checkNotNull<GetCredentialsResponse?>(response)

        // Don't extend the duration on read access.
        return currentDuration
    }
}
