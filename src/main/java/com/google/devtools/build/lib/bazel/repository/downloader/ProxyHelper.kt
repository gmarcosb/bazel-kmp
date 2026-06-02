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
// limitations under the License.
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.devtools.build.lib.util.StringEncoding
import java.io.IOException
import java.lang.String
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Stream
import kotlin.Any
import kotlin.Array
import kotlin.Int
import kotlin.NumberFormatException

/** Helper class for setting up a proxy server for network communication  */
class ProxyHelper
/**
 * Creates new instance.
 * 
 * @param env client environment to check for proxy settings
 */(private val env: MutableMap<String?, String?>) {
    /**
     * This method takes a String for the resource being requested and sets up a proxy to make the
     * request if HTTP_PROXY and/or HTTPS_PROXY environment variables are set, or if the standard
     * `https_proxy` and `http_proxy` system properties are set.
     * 
     * @param requestedUrl remote resource that may need to be retrieved through a proxy
     * @return ProxyInfo containing the proxy and optional credentials
     */
    @Throws(IOException::class)
    fun createProxyIfNeeded(requestedUrl: URI): ProxyInfo {
        var proxyAddress: String? = null
        var proxyUserProperty: String? = null
        var proxyPasswordProperty: String? = null

        // Check no_proxy/NO_PROXY environment variables
        var noProxyUrl = env.get("no_proxy")
        if (Strings.isNullOrEmpty(noProxyUrl)) {
            noProxyUrl = env.get("NO_PROXY")
        }
        if (!Strings.isNullOrEmpty(noProxyUrl)) {
            val noProxyUrlArray: Array<String?> = noProxyUrl.split("\\s*,\\s*")
            val requestedHost = requestedUrl.getHost()
            for (i in noProxyUrlArray.indices) {
                if (noProxyUrlArray[i].startsWith(".")) {
                    // This entry applies to sub-domains only.
                    if (requestedHost.endsWith(noProxyUrlArray[i])) {
                        return ProxyInfo.Companion.NO_PROXY
                    }
                } else {
                    // This entry applies to the literal hostname and sub-domains.
                    if (requestedHost == noProxyUrlArray[i]
                        || requestedHost.endsWith("." + noProxyUrlArray[i])
                    ) {
                        return ProxyInfo.Companion.NO_PROXY
                    }
                }
            }
        }

        // Check http.nonProxyHosts system property (Java standard, uses | separator and * wildcards)
        var nonProxyHosts = System.getProperty("http.nonProxyHosts")
        if (!Strings.isNullOrEmpty(nonProxyHosts)) {
            nonProxyHosts = StringEncoding.platformToInternal(nonProxyHosts)
            val requestedHost = requestedUrl.getHost()
            for (pattern in Splitter.on('|').split(nonProxyHosts)) {
                var pattern = pattern
                pattern = pattern.trim()
                if (pattern.isEmpty()) {
                    continue
                }
                if (pattern.startsWith("*")) {
                    // Wildcard at start: *.example.com matches foo.example.com
                    if (requestedHost.endsWith(pattern.substring(1))) {
                        return ProxyInfo.Companion.NO_PROXY
                    }
                } else if (pattern.endsWith("*")) {
                    // Wildcard at end: example.* matches example.com
                    if (requestedHost.startsWith(pattern.substring(0, pattern.length() - 1))) {
                        return ProxyInfo.Companion.NO_PROXY
                    }
                } else {
                    // Exact match
                    if (requestedHost == pattern) {
                        return ProxyInfo.Companion.NO_PROXY
                    }
                }
            }
        }

        if (HttpUtils.isProtocol(requestedUrl, "https")) {
            proxyAddress =
                Stream.of<Supplier<String?>?>(
                    Supplier { env.get("https_proxy") },
                    Supplier { env.get("HTTPS_PROXY") },
                    Supplier {
                        var host = System.getProperty("https.proxyHost")
                        if (host == null) {
                            return@of null
                        }
                        host = StringEncoding.platformToInternal(host)
                        var port = System.getProperty("https.proxyPort")
                        if (port != null) {
                            port = StringEncoding.platformToInternal(port)
                        }
                        String.format("%s%s", host, if (port == null) "" else ":" + port)
                    })
                    .map<String?>(Function { obj: Supplier<kotlin.String?>? -> obj!!.get() })
                    .filter(Predicate { obj: kotlin.String? -> Objects.nonNull(obj) })
                    .findFirst()
                    .orElse(null)
            // Check for credentials in system properties
            proxyUserProperty = System.getProperty("https.proxyUser")
            if (proxyUserProperty != null) {
                proxyUserProperty = StringEncoding.platformToInternal(proxyUserProperty)
            }
            proxyPasswordProperty = System.getProperty("https.proxyPassword")
            if (proxyPasswordProperty != null) {
                proxyPasswordProperty = StringEncoding.platformToInternal(proxyPasswordProperty)
            }
        } else if (HttpUtils.isProtocol(requestedUrl, "http")) {
            proxyAddress =
                Stream.of<Supplier<kotlin.String?>?>(
                    Supplier { env.get("http_proxy") },
                    Supplier { env.get("HTTP_PROXY") },
                    Supplier {
                        var host = System.getProperty("http.proxyHost")
                        if (host == null) {
                            return@of null
                        }
                        host = StringEncoding.platformToInternal(host)
                        var port = System.getProperty("http.proxyPort")
                        if (port != null) {
                            port = StringEncoding.platformToInternal(port)
                        }
                        String.format("%s%s", host, if (port == null) "" else ":" + port)
                    })
                    .map<kotlin.String?>(Function { obj: Supplier<kotlin.String?>? -> obj!!.get() })
                    .filter(Predicate { obj: kotlin.String? -> Objects.nonNull(obj) })
                    .findFirst()
                    .orElse(null)
            // Check for credentials in system properties
            proxyUserProperty = System.getProperty("http.proxyUser")
            if (proxyUserProperty != null) {
                proxyUserProperty = StringEncoding.platformToInternal(proxyUserProperty)
            }
            proxyPasswordProperty = System.getProperty("http.proxyPassword")
            if (proxyPasswordProperty != null) {
                proxyPasswordProperty = StringEncoding.platformToInternal(proxyPasswordProperty)
            }
        }
        return createProxyInfo(proxyAddress, proxyUserProperty, proxyPasswordProperty)
    }

    companion object {
        // Lock for thread-safe authenticator setup. The Authenticator is a JVM-wide singleton,
        // so we use double-checked locking to ensure it's only set once.
        private val AUTHENTICATOR_LOCK = Any()

        @kotlin.concurrent.Volatile
        private var authenticatorSet = false

        /** Resets the static authenticator state. This is intended for testing only.  */
        @kotlin.jvm.JvmStatic
        fun resetAuthenticatorForTesting() {
            synchronized(AUTHENTICATOR_LOCK) {
                Authenticator.setDefault(null)
                authenticatorSet = false
            }
        }

        /**
         * This method takes a proxyAddress as a String (ex. `http://userId:password@proxyhost.domain.com:8000`) and returns a ProxyInfo containing the proxy
         * configuration and optional authentication credentials.
         * 
         * @param proxyAddress The fully qualified address of the proxy server
         * @return ProxyInfo containing the proxy and optional credentials
         * @throws IOException if the proxy address is invalid
         */
        @kotlin.jvm.JvmStatic
        @Throws(IOException::class)
        fun createProxy(proxyAddress: kotlin.String?): ProxyInfo {
            return createProxyInfo(proxyAddress, null, null)
        }

        /**
         * This method creates a ProxyInfo from either a proxy address URL (which may contain embedded
         * credentials) or from separate credential parameters (typically from system properties).
         * 
         * 
         * Credentials in the proxy address URL take precedence over separately provided credentials.
         * 
         * @param proxyAddress The proxy address, optionally containing embedded credentials
         * @param systemPropertyUser Username from system property (http.proxyUser/https.proxyUser)
         * @param systemPropertyPassword Password from system property
         * (http.proxyPassword/https.proxyPassword)
         * @return ProxyInfo containing the proxy and optional credentials
         * @throws IOException if the proxy address is invalid
         */
        @kotlin.jvm.JvmStatic
        @Throws(IOException::class)
        fun createProxyInfo(
            proxyAddress: kotlin.String?,
            systemPropertyUser: kotlin.String?,
            systemPropertyPassword: kotlin.String?
        ): ProxyInfo {
            if (Strings.isNullOrEmpty(proxyAddress)) {
                return ProxyInfo.Companion.NO_PROXY
            }

            // Here there be dragons.
            // Supports http://, https://, socks://, socks4://, socks5:// or no protocol (defaults to HTTP)
            val matcher: Matcher = URL_PATTERN.matcher(proxyAddress)
            if (!matcher.matches()) {
                throw IOException("Proxy address " + proxyAddress + " is not a valid URL")
            }

            val protocol = matcher.group(1)
            val idAndPassword = matcher.group(2)
            val urlUsername = matcher.group(3)
            val urlPassword = matcher.group(4)
            val hostname = matcher.group(5)
            val portRaw = matcher.group(6)

            var cleanProxyAddress = proxyAddress
            if (idAndPassword != null) {
                cleanProxyAddress =
                    proxyAddress.replace(idAndPassword, "") // Used to remove id+pwd from logging
            }

            val proxyType: Proxy.Type?
            val defaultPort: Int

            if (protocol != null) {
                when (protocol) {
                    "https://" -> {
                        proxyType = Proxy.Type.HTTP
                        defaultPort = 443
                    }

                    "http://" -> {
                        proxyType = Proxy.Type.HTTP
                        defaultPort = 80
                    }

                    "socks://", "socks4://", "socks5://" -> {
                        proxyType = Proxy.Type.SOCKS
                        defaultPort = 1080
                    }

                    else -> throw IOException("Invalid proxy protocol for " + cleanProxyAddress)
                }
            } else {
                proxyType = Proxy.Type.HTTP
                defaultPort = 80
            }

            var port = defaultPort

            if (portRaw != null) {
                try {
                    port = Integer.parseInt(portRaw)
                } catch (e: NumberFormatException) {
                    throw IOException("Error parsing proxy port: " + cleanProxyAddress, e)
                }
            }

            val proxy = Proxy(proxyType, InetSocketAddress(hostname, port))

            // Determine credentials: URL credentials take precedence over system properties
            var username: kotlin.String? = urlUsername
            var password: kotlin.String? = urlPassword

            if (username != null) {
                if (password == null) {
                    throw IOException("No password given for proxy " + cleanProxyAddress)
                }
                // We need to make sure the proxy credentials are not url encoded; some special characters in
                // proxy passwords require url encoding for shells and other tools to properly consume.
                username = StringEncoding.unicodeToInternal(
                    URLDecoder.decode(
                        StringEncoding.internalToUnicode(username),
                        StandardCharsets.UTF_8
                    )
                )
                password = StringEncoding.unicodeToInternal(
                    URLDecoder.decode(
                        StringEncoding.internalToUnicode(password),
                        StandardCharsets.UTF_8
                    )
                )
            } else if (systemPropertyUser != null && systemPropertyPassword != null) {
                // Fall back to system property credentials
                username = systemPropertyUser
                password = systemPropertyPassword
            }

            // If credentials are provided, also set up Java's Authenticator for HTTPS proxy support.
            // For HTTPS connections through HTTP proxies (CONNECT tunneling), Java's HttpURLConnection
            // handles the CONNECT request internally and won't use Proxy-Authorization header we set.
            // Instead, it uses the Authenticator mechanism. We also enable Basic auth tunneling by
            // clearing the disabled schemes (by default, Basic auth is disabled for HTTPS tunneling).
            if (username != null && password != null) {
                // Use double-checked locking to ensure thread-safe, one-time setup of the global
                // Authenticator. The first caller with credentials wins. This is safe because Bazel
                // typically uses a single proxy configuration for all downloads.
                if (!authenticatorSet) {
                    synchronized(AUTHENTICATOR_LOCK) {
                        if (!authenticatorSet) {
                            val finalUsername: kotlin.String? = username
                            val finalPassword: kotlin.String? = password
                            // Capture the previous authenticator to delegate non-proxy auth requests to it.
                            // This preserves existing behavior for server authentication (e.g., .netrc).
                            val previousAuthenticator = Authenticator.getDefault()
                            Authenticator.setDefault(
                                object : Authenticator() {
                                    public override fun getPasswordAuthentication(): PasswordAuthentication? {
                                        // Only provide credentials for proxy authentication.
                                        if (getRequestorType() == RequestorType.PROXY) {
                                            return PasswordAuthentication(
                                                StringEncoding.internalToUnicode(finalUsername),
                                                StringEncoding.internalToUnicode(finalPassword).toCharArray()
                                            )
                                        }
                                        // Delegate non-proxy auth to previous authenticator (if any).
                                        // This preserves existing behavior for server authentication.
                                        if (previousAuthenticator != null) {
                                            return previousAuthenticator.requestPasswordAuthenticationInstance(
                                                getRequestingHost(),
                                                getRequestingSite(),
                                                getRequestingPort(),
                                                getRequestingProtocol(),
                                                getRequestingPrompt(),
                                                getRequestingScheme(),
                                                getRequestingURL(),
                                                getRequestorType()
                                            )
                                        }
                                        return null
                                    }
                                })
                            // Enable Basic authentication for HTTPS tunneling through HTTP proxies.
                            // By default, Java disables Basic auth for tunneling
                            // (jdk.http.auth.tunneling.disabledSchemes defaults to "Basic").
                            enableBasicAuthTunneling()
                            authenticatorSet = true
                        }
                    }
                }
            }

            return ProxyInfo(proxy, username, password)
        }

        private val URL_PATTERN: Pattern = Pattern.compile(
            "^(https?://|socks[45]?://)?(([^:@]+?)(?::([^@]+?))?@)?([^:]+)(?::(\\d+))?/?$"
        )

        /**
         * Enables Basic authentication for HTTPS tunneling through HTTP proxies.
         * 
         * 
         * By default, Java disables Basic authentication for HTTPS proxy tunneling for security
         * reasons (the `jdk.http.auth.tunneling.disabledSchemes` system property defaults to
         * "Basic"). This method clears that restriction to allow authenticated proxies to work with HTTPS
         * URLs.
         * 
         * 
         * This is necessary because most enterprise proxies use Basic authentication, and without this
         * setting, HTTPS downloads through authenticated proxies will fail with 407 errors.
         * 
         * 
         * Note: This modifies a JVM-wide setting. If the user has explicitly set this property, their
         * setting will be preserved.
         */
        private fun enableBasicAuthTunneling() {
            // Use putIfAbsent for thread-safe modification. Only modify if not already set by the user.
            System.getProperties().putIfAbsent("jdk.http.auth.tunneling.disabledSchemes", "")
        }
    }
}
