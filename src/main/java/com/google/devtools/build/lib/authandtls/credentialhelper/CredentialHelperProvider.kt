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
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperProvider
import java.io.IOException
import java.util.HashMap

/**
 * A provider for [CredentialHelper]s.
 * 
 * 
 * This class is used to find the right [CredentialHelper] for a [URI], using the
 * most specific match.
 */
@com.google.errorprone.annotations.Immutable
class CredentialHelperProvider private constructor(
    defaultHelper: java.util.Optional<com.google.devtools.build.lib.vfs.Path?>?,
    hostToHelper: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.vfs.Path?>?,
    suffixToHelper: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.vfs.Path?>?
) {
    // `Path` is immutable, but not annotated.
    private val defaultHelper: java.util.Optional<com.google.devtools.build.lib.vfs.Path?>

    private val hostToHelper: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.vfs.Path?>? =
        null

    private val suffixToHelper: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.vfs.Path?>? =
        null

    init {
        .also {
            this.defaultHelper = it
        } < Optional < Path shr com.google.common.base.Preconditions.checkNotNull<java.util.Optional<com.google.devtools.build.lib.vfs.Path?>?>(
            defaultHelper
        )
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.hostToHelper = <ImmutableMap<String, Path>>checkNotNull(hostToHelper);
            """.trimMargin()
        )
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.suffixToHelper = <ImmutableMap<String, Path>>checkNotNull(suffixToHelper);
            """.trimMargin()
        )
    }

    /**
     * Returns [CredentialHelper] to use for getting credentials for connection to the provided
     * [URI].
     * 
     * @param uri The [URI] to get a credential helper for.
     * @return The [CredentialHelper], or nothing if no [CredentialHelper] is configured
     * for the provided [URI].
     */
    fun findCredentialHelper(uri: java.net.URI?): java.util.Optional<CredentialHelper?> {
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)

        val host: String? = uri.getHost()
        if (com.google.common.base.Strings.isNullOrEmpty(host)) {
            // Some URIs (e.g. unix://) legitimately have no host component.
            // Use the default helper if one is provided.
            return defaultHelper.map<CredentialHelper?>(java.util.function.Function { path: com.google.devtools.build.lib.vfs.Path? ->
                CredentialHelper(
                    path
                )
            })
        }

        val credentialHelper: java.util.Optional<com.google.devtools.build.lib.vfs.Path?> =
            findHostCredentialHelper(host)
                .or(java.util.function.Supplier { findWildcardCredentialHelper(host) })
                .or(java.util.function.Supplier { defaultHelper })
        return credentialHelper.map<CredentialHelper?>(java.util.function.Function { path: com.google.devtools.build.lib.vfs.Path? ->
            CredentialHelper(
                path
            )
        })
    }

    private fun findHostCredentialHelper(host: String?): java.util.Optional<com.google.devtools.build.lib.vfs.Path?> {
        com.google.common.base.Preconditions.checkNotNull<String?>(host)

        return java.util.Optional.ofNullable<com.google.devtools.build.lib.vfs.Path?>(hostToHelper.get(host))
    }

    private fun findWildcardCredentialHelper(host: String?): java.util.Optional<com.google.devtools.build.lib.vfs.Path?> {
        com.google.common.base.Preconditions.checkNotNull<String?>(host)

        return java.util.Optional.ofNullable<com.google.devtools.build.lib.vfs.Path?>(suffixToHelper.get(host))
            .or(
                java.util.function.Supplier {
                    val subdomain: java.util.Optional<String?> = Companion.parentDomain(host!!)
                    if (subdomain.isEmpty()) {
                        return@or java.util.Optional.empty<com.google.devtools.build.lib.vfs.Path?>()
                    }
                    findWildcardCredentialHelper(subdomain.get())
                })
    }

    /** Builder for [CredentialHelperProvider].  */
    class Builder {
        private var defaultHelper: java.util.Optional<com.google.devtools.build.lib.vfs.Path?> =
            java.util.Optional.empty<com.google.devtools.build.lib.vfs.Path?>()
        private val hostToHelper: MutableMap<String?, com.google.devtools.build.lib.vfs.Path?> =
            HashMap<String?, com.google.devtools.build.lib.vfs.Path?>()
        private val suffixToHelper: MutableMap<String?, com.google.devtools.build.lib.vfs.Path?> =
            HashMap<String?, com.google.devtools.build.lib.vfs.Path?>()

        /**
         * Adds a default credential helper to use for all [URI]s that don't specify a more
         * specific credential helper.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(IOException::class)
        fun add(helper: com.google.devtools.build.lib.vfs.Path?): Builder {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(helper)
            defaultHelper = java.util.Optional.of<com.google.devtools.build.lib.vfs.Path?>(helper)
            return this
        }

        /**
         * Adds a credential helper to use for all [URI]s matching the provided pattern.
         * 
         * 
         * If `pattern` starts with a `*.` wildcard, it matches every subdomain in
         * addition to the domain itself. For example `*.example.com` would match `example.com`, `foo.example.com`, `bar.example.com`, `baz.bar.example.com`
         * and so on, but not anything that isn't a subdomain of `example.com`.
         * 
         * 
         * More complex wildcard patterns are not supported.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(IOException::class)
        fun add(pattern: String?, helper: com.google.devtools.build.lib.vfs.Path?): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(pattern)
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(helper)

            // The pattern has already been normalized during options parsing.
            if (pattern.startsWith("*.")) {
                suffixToHelper.put(pattern.substring(2), helper)
            } else {
                hostToHelper.put(pattern, helper)
            }

            return this
        }

        fun build(): CredentialHelperProvider {
            return CredentialHelperProvider(
                defaultHelper,
                com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.build.lib.vfs.Path?>(
                    hostToHelper
                ),
                com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.build.lib.vfs.Path?>(
                    suffixToHelper
                )
            )
        }
    }

    companion object {
        /**
         * Returns the parent domain of the provided domain (e.g., `foo.example.com` for `bar.foo.example.com`).
         */
        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun parentDomain(domain: String): java.util.Optional<String?> {
            val dot: Int = domain.indexOf('.'.code)
            if (dot < 0) {
                // We reached the last segment, end.
                return java.util.Optional.empty<String?>()
            }

            return java.util.Optional.of<String?>(domain.substring(dot + 1))
        }

        /** Returns a new builder for a [CredentialHelperProvider].  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperProvider.Builder()
        }
    }
}
