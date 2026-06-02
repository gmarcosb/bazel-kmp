// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.authandtls

import com.google.devtools.build.lib.authandtls.BasicHttpAuthenticationEncoder
import com.google.devtools.build.lib.authandtls.Netrc
import java.io.IOException

/**
 * Subclass of [Credentials] which uses username and password from [Netrc] to provide
 * request metadata.
 */
class NetrcCredentials(netrc: Netrc) : com.google.auth.Credentials() {
    private val netrc: Netrc

    init {
        this.netrc = netrc
    }

    val authenticationType: String
        get() = "netrc"

    /**
     * Get the request metadata for a given [URI].
     * 
     * 
     * The credentials from .netrc file usually consist of machine name and it's corresponding
     * username/password pair.
     * 
     * 
     * For a given [URI], we compare its host name with credential's machine name to find the
     * username and password. Use [BasicHttpAuthenticationEncoder] to encode matched credential.
     * Return empty request metadata if no match found.
     * 
     * 
     * The returned request metadata has "Authorization" as its key and a single element list of
     * "Basic token" as its value.
     */
    @Throws(IOException::class)
    override fun getRequestMetadata(uri: java.net.URI): MutableMap<String?, MutableList<String?>?> {
        val credential: com.google.devtools.build.lib.authandtls.Netrc.Credential? = netrc.getCredential(uri.getHost())
        if (credential != null) {
            val token: String =
                BasicHttpAuthenticationEncoder.encode(credential.login(), credential.password())
            return com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>(
                "Authorization",
                com.google.common.collect.ImmutableList.of<String?>(token)
            )
        } else {
            return com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>()
        }
    }

    override fun hasRequestMetadata(): Boolean {
        return true
    }

    override fun hasRequestMetadataOnly(): Boolean {
        return true
    }

    @Throws(IOException::class)
    override fun refresh() {
    }
}
