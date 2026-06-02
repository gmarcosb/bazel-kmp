// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.devtools.build.lib.authandtls.BasicHttpAuthenticationEncoder
import java.net.Proxy

/**
 * Container for proxy configuration including the proxy address and optional authentication
 * credentials.
 * 
 * 
 * This class holds both the [Proxy] object for connection routing and the credentials
 * needed for proxy authentication. The credentials are encoded as a Basic authentication header
 * value suitable for use in the Proxy-Authorization HTTP header.
 */
class ProxyInfo internal constructor(
    private val proxy: Proxy?,
    private val username: String?,
    private val password: String?
) {
    /** Returns the proxy to use for connections, or [Proxy.NO_PROXY] for direct connections.  */
    fun proxy(): Proxy? {
        return proxy
    }

    /** Returns true if this proxy requires authentication.  */
    fun hasCredentials(): Boolean {
        return username != null && password != null
    }

    val proxyAuthorizationHeader: String?
        /**
         * Returns the value for the Proxy-Authorization header, or null if no authentication is needed.
         * 
         * 
         * The returned value is Base64-encoded in the format required for HTTP Basic authentication
         * (RFC 7617). Uses UTF-8 encoding to support international characters in credentials.
         */
        get() {
            if (!hasCredentials()) {
                return null
            }
            return BasicHttpAuthenticationEncoder.encode(username, password)
        }

    companion object {
        /** A ProxyInfo representing no proxy (direct connection).  */
        @kotlin.jvm.JvmField
        val NO_PROXY: ProxyInfo = ProxyInfo(Proxy.NO_PROXY, null, null)
    }
}
