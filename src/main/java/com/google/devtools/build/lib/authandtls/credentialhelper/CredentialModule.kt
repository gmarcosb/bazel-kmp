// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialCacheExpiry
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse
import com.google.devtools.build.lib.authandtls.credentialhelper.WallTicker
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.CommandEnvironment

/** A module whose sole purpose is to hold the credential cache which is shared by other modules.  */
class CredentialModule @com.google.common.annotations.VisibleForTesting internal constructor(clock: com.google.devtools.build.lib.clock.Clock?) :
    BlazeModule() {
    private val credentialCacheExpiry: CredentialCacheExpiry
    private val credentialCache: com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?>
    private var lastDefaultCacheDuration: java.time.Duration = java.time.Duration.ZERO

    constructor() : this(com.google.devtools.build.lib.clock.JavaClock())

    init {
        this.credentialCacheExpiry = CredentialCacheExpiry()
        this.credentialCache =
            Caffeine.newBuilder()
                .ticker(WallTicker(clock))
                .expireAfter<java.net.URI?, GetCredentialsResponse?>(credentialCacheExpiry)
                .build<java.net.URI?, GetCredentialsResponse?>()
    }

    /** Returns the credential cache.  */
    fun getCredentialCache(): com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?> {
        return credentialCache
    }

    val commonCommandOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            AuthAndTLSOptions::class.java
        )

    override fun beforeCommand(env: CommandEnvironment) {
        val defaultCacheDuration: java.time.Duration =
            env.getOptions().getOptions<AuthAndTLSOptions?>(AuthAndTLSOptions::class.java)
                .getCredentialHelperCacheTimeout()

        val defaultCacheDurationChanged = defaultCacheDuration != lastDefaultCacheDuration
        lastDefaultCacheDuration = defaultCacheDuration

        // Clear the cache on clean or when the default cache duration changes.
        if (env.getCommandName() == "clean" || defaultCacheDurationChanged) {
            credentialCache.invalidateAll()
            credentialCache.cleanUp()
        }

        // Update the expiration policy for future entries.
        credentialCacheExpiry.setDefaultCacheDuration(defaultCacheDuration)
    }
}
