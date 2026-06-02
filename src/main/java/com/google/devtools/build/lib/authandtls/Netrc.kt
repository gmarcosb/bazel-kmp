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

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.authandtls.NetrcParser
import java.io.IOException

/** Container for the content of a .netrc file.  */
class Netrc(
    defaultCredential: Credential?,
    credentials: com.google.common.collect.ImmutableMap<String?, Credential?>?
) {
    /**
     * Return a [Credential] for a given machine. If machine is not found and there isn't
     * default credential, return `null`.
     */
    fun getCredential(machine: String?): Credential? {
        return this.credentials.getOrDefault(machine, this.defaultCredential)
    }

    /** Container for login, password and account of a machine in .netrc  */
    @AutoValue
    abstract class Credential {
        abstract fun machine(): String?

        abstract fun login(): String?

        abstract fun password(): String?

        abstract fun account(): String?

        /**
         * The generated toString method will leak the password. Override and replace the value of
         * password with constant string `<password>`.
         */
        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("machine", machine())
                .add("login", login())
                .add("password", "<password>")
                .add("account", account())
                .toString()
        }

        /** [Credential]Builder  */
        @AutoValue.Builder
        abstract class Builder {
            abstract fun machine(): String?

            abstract fun setMachine(machine: String?): Builder?

            abstract fun login(): String?

            abstract fun setLogin(value: String?): Builder?

            abstract fun password(): String?

            abstract fun setPassword(value: String?): Builder?

            abstract fun account(): String?

            abstract fun setAccount(value: String?): Builder?

            abstract fun build(): Credential?
        }

        companion object {
            /** Create a [Builder] object for a given machine.  */
            @kotlin.jvm.JvmStatic
            fun builder(machine: String?): Builder {
                return Builder()
                    .setMachine(machine)
                    .setLogin("")
                    .setPassword("")
                    .setAccount("")!!
            }
        }
    }

    val defaultCredential: Credential?
    val credentials: com.google.common.collect.ImmutableMap<String?, Credential?>?

    init {
        this.credentials = credentials
        this.defaultCredential = defaultCredential
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, Credential?>?>(
            credentials,
            "credentials"
        )
    }

    companion object {
        @Throws(IOException::class)
        fun fromStream(inputStream: java.io.InputStream?): Netrc {
            return NetrcParser.parseAndClose(inputStream)
        }

        /**
         * Construct a new [Netrc] instance.
         * 
         * @param defaultCredential default [Credential] for other machines
         * @param credentials map between a machine and it's corresponding [Credential]
         */
        fun create(
            defaultCredential: Credential?, credentials: com.google.common.collect.ImmutableMap<String?, Credential?>?
        ): Netrc {
            return Netrc(defaultCredential, credentials)
        }
    }
}
