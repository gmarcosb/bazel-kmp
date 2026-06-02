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

import com.google.common.base.Ascii
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLConnection

/** HTTP utilities.  */
object HttpUtils {
    /** Returns `true` if `uri` is supported by [HttpDownloader].  */
    fun isUrlSupportedByDownloader(uri: URI): Boolean {
        return isHttp(uri) || isProtocol(uri, "file")
    }

    fun isHttp(uri: URI): Boolean {
        return isProtocol(uri, "http") || isProtocol(uri, "https")
    }

    fun isProtocol(uri: URI, protocol: String): Boolean {
        // An implementation should accept uppercase letters as equivalent to lowercase in scheme names
        // (e.g., allow "HTTP" as well as "http") for the sake of robustness. Quoth RFC3986 § 3.1
        return Ascii.equalsIgnoreCase(protocol, uri.getScheme())
    }

    fun checkUrlsArgument(uris: MutableCollection<URI>) {
        Preconditions.checkArgument(!uris.isEmpty(), "urls list empty")
        for (uri in uris) {
            Preconditions.checkArgument(isUrlSupportedByDownloader(uri), "unsupported protocol: %s", uri)
        }
    }

    @kotlin.jvm.JvmStatic
    fun getExtension(path: String): String {
        val index: Int = path.lastIndexOf('.'.code)
        if (index == -1) {
            return ""
        }
        return Ascii.toLowerCase(path.substring(index + 1))
    }

    @kotlin.jvm.JvmStatic
    @Throws(IOException::class)
    fun getLocation(connection: HttpURLConnection): URI {
        val newLocation = connection.getHeaderField("Location")
        if (newLocation == null) {
            throw IOException("Remote redirect missing Location.")
        }
        val result = mergeUrls(URI.create(newLocation), toUri(connection))
        if (!isHttp(result)) {
            throw IOException("Bad Location: " + newLocation)
        }
        return result
    }

    @Throws(IOException::class)
    private fun mergeUrls(preferred: URI, original: URI): URI {
        // Try to short cut to preferred to preserve the original presentation of the
        // quoting (as a call to the structured URI constructor puts quoting into a canonical form).
        // This is necessary as some sites rely on the precise presentation for the authentication
        // scheme of their redirect URLs.
        if (preferred.getHost() != null && preferred.getScheme() != null && (preferred.getFragment() != null || original.getFragment() == null) // Forward user info to the same origin.
            && (preferred.getUserInfo() != null || original.getUserInfo() == null || !(preferred.getHost() == original.getHost()
                    && preferred.getPort() == original.getPort()))
        ) {
            // In this case we obviously do not inherit anything from the original URL, as all inheritable
            // fields are either set explicitly or not present in the original either. Therefore, it is
            // safe to short cut.
            return preferred
        }

        // If the Location value provided in a 3xx (Redirection) response does not have a fragment
        // component, a user agent MUST process the redirection as if the value inherits the fragment
        // component of the URI reference used to generate the request target (i.e., the redirection
        // inherits the original reference's fragment, if any). Quoth RFC7231 § 7.1.2
        val protocol = MoreObjects.firstNonNull<String>(preferred.getScheme(), original.getScheme())
        var userInfo = preferred.getUserInfo()
        var host = preferred.getHost()
        val port: Int
        if (host == null) {
            host = original.getHost()
            port = original.getPort()
            userInfo = original.getUserInfo()
        } else {
            port = preferred.getPort()
            if (userInfo == null && host == original.getHost() && port == original.getPort()) {
                userInfo = original.getUserInfo()
            }
        }
        val path = preferred.getPath()
        val query = preferred.getQuery()
        var fragment = preferred.getFragment()
        if (fragment == null) {
            fragment = original.getFragment()
        }
        val result: URI
        try {
            result = URI(protocol, userInfo, host, port, path, query, fragment)
        } catch (e: URISyntaxException) {
            throw IOException("Could not merge " + preferred + " into " + original, e)
        }
        return result
    }

    /**
     * Converts a [URLConnection]'s URL to a [URI]. Since the URL comes from an active
     * connection, it should always be a valid URI.
     */
    fun toUri(connection: URLConnection): URI {
        try {
            return connection.getURL().toURI()
        } catch (e: URISyntaxException) {
            throw IllegalStateException("Invalid URI from connection URL: " + connection.getURL(), e)
        }
    }
}
