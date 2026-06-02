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

import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelper
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperProvider
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse
import java.io.IOException
import java.io.UncheckedIOException

/**
 * Implementation of [Credentials] which fetches credentials by invoking a `credential helper` as subprocess, falling back to another [Credentials] if no suitable helper exists.
 */
class CredentialHelperCredentials(
    credentialHelperProvider: CredentialHelperProvider?,
    credentialHelperEnvironment: CredentialHelperEnvironment?,
    credentialCache: com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?>?,
    fallbackCredentials: java.util.Optional<com.google.auth.Credentials?>?
) : com.google.auth.Credentials() {
    private val credentialHelperProvider: CredentialHelperProvider
    private val credentialHelperEnvironment: CredentialHelperEnvironment
    private val credentialCache: com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?>
    private val fallbackCredentials: java.util.Optional<com.google.auth.Credentials?>

    init {
        this.credentialHelperProvider =
            com.google.common.base.Preconditions.checkNotNull<CredentialHelperProvider>(credentialHelperProvider)
        this.credentialHelperEnvironment =
            com.google.common.base.Preconditions.checkNotNull<CredentialHelperEnvironment>(credentialHelperEnvironment)
        this.credentialCache =
            com.google.common.base.Preconditions.checkNotNull<com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?>>(
                credentialCache
            )
        this.fallbackCredentials =
            com.google.common.base.Preconditions.checkNotNull<java.util.Optional<com.google.auth.Credentials?>>(
                fallbackCredentials
            )
    }

    val authenticationType: String
        get() {
            if (fallbackCredentials.isPresent()) {
                return "credential-helper-with-fallback-" + fallbackCredentials.get().getAuthenticationType()
            }

            return "credential-helper"
        }

    @Throws(IOException::class)  // Map<String, ImmutableList<String>> to Map<String<List<String>>
    override fun getRequestMetadata(uri: java.net.URI?): MutableMap<String?, MutableList<String?>?>? {
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)

        val response: GetCredentialsResponse?
        try {
            response = credentialCache.get(
                uri,
                java.util.function.Function { uri: java.net.URI? -> this.getCredentialsFromHelper(uri) })
        } catch (e: UncheckedIOException) {
            throw e.getCause()
        }
        if (response != null) {
            return response.headers as MutableMap<*, *>?
        }

        if (fallbackCredentials.isPresent()) {
            return fallbackCredentials.get().getRequestMetadata(uri)
        }

        return com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>()
    }

    private fun getCredentialsFromHelper(uri: java.net.URI?): GetCredentialsResponse? {
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)

        val maybeCredentialHelper: java.util.Optional<CredentialHelper> =
            credentialHelperProvider.findCredentialHelper(uri)
        if (maybeCredentialHelper.isEmpty()) {
            return null
        }
        val credentialHelper: CredentialHelper = maybeCredentialHelper.get()

        val response: GetCredentialsResponse?
        try {
            response = credentialHelper.getCredentials(credentialHelperEnvironment, uri)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
        if (response == null) {
            return null
        }

        return response
    }

    override fun hasRequestMetadata(): Boolean {
        return true
    }

    override fun hasRequestMetadataOnly(): Boolean {
        return false
    }

    @Throws(IOException::class)
    override fun refresh() {
        if (fallbackCredentials.isPresent()) {
            fallbackCredentials.get().refresh()
        }

        credentialCache.invalidateAll()
    }
}
