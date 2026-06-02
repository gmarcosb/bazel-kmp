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
package com.google.devtools.build.lib.authandtls

/** Implementation of [Credentials] which provides a static set of credentials.  */
class StaticCredentials(credentials: MutableMap<java.net.URI?, MutableMap<String?, MutableList<String?>?>?>?) :
    com.google.auth.Credentials() {
    private val credentials: com.google.common.collect.ImmutableMap<java.net.URI?, MutableMap<String?, MutableList<String?>?>?>

    init {
        com.google.common.base.Preconditions.checkNotNull<MutableMap<java.net.URI?, MutableMap<String?, MutableList<String?>?>?>?>(
            credentials
        )

        this.credentials =
            com.google.common.collect.ImmutableMap.copyOf<java.net.URI?, MutableMap<String?, MutableList<String?>?>?>(
                credentials
            )
    }

    val authenticationType: String
        get() = "static"

    override fun getRequestMetadata(uri: java.net.URI?): MutableMap<String?, MutableList<String?>?>? {
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)

        return credentials.getOrDefault(
            uri,
            com.google.common.collect.ImmutableMap.of<String?, MutableList<String?>?>()
        )
    }

    override fun hasRequestMetadata(): Boolean {
        return true
    }

    override fun hasRequestMetadataOnly(): Boolean {
        return true
    }

    override fun refresh() {
        // Can't refresh static credentials.
    }

    companion object {
        @kotlin.jvm.JvmField
        val EMPTY: StaticCredentials =
            StaticCredentials(com.google.common.collect.ImmutableMap.of<java.net.URI?, MutableMap<String?, MutableList<String?>?>?>())
    }
}
