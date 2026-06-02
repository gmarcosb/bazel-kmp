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

import com.google.devtools.build.lib.authandtls.Netrc
import com.google.devtools.build.lib.authandtls.NetrcParser
import java.io.BufferedReader
import java.io.IOException
import java.util.ArrayDeque
import java.util.HashMap

/**
 * A parser used to parse .netrc content.
 * 
 * @see [](https://man.cx/netrc
@see <a
href=)//github.com/bazelbuild/bazel/blob/master/tools/build_defs/repo/utils.bzl.L203-L204">Starlark
 * netrc parser
 */
object NetrcParser {
    private const val MACHINE = "machine"
    private const val MACDEF = "macdef"
    private const val DEFAULT = "default"
    private const val LOGIN = "login"
    private const val PASSWORD = "password"
    private const val ACCOUNT = "account"

    @Throws(IOException::class)
    fun parseAndClose(inputStream: java.io.InputStream): Netrc {
        TokenStream(inputStream).use { tokenStream ->
            return parse(tokenStream)
        }
    }

    @Throws(IOException::class)
    private fun parse(tokenStream: TokenStream): Netrc {
        var defaultCredential: com.google.devtools.build.lib.authandtls.Netrc.Credential? = null
        val credentialMap: MutableMap<String?, com.google.devtools.build.lib.authandtls.Netrc.Credential?> =
            HashMap<String?, com.google.devtools.build.lib.authandtls.Netrc.Credential?>()

        var done = false
        while (!done && tokenStream.hasNext()) {
            val token = tokenStream.next()
            if (token is ItemToken) {
                val item = token.item
                when (item) {
                    MACHINE -> {
                        val machine = nextItem(tokenStream)
                        val credential: com.google.devtools.build.lib.authandtls.Netrc.Credential? =
                            parseCredentialForMachine(tokenStream, machine)
                        credentialMap.put(machine, credential)
                    }

                    MACDEF -> skipMacdef(tokenStream)
                    DEFAULT -> {
                        defaultCredential = parseCredentialForMachine(tokenStream, DEFAULT)
                        // There can be only one default token, and it must be after all machine tokens.
                        done = true
                    }

                    else -> throw IOException(
                        String.format(
                            "Unexpected token: %s (expecting %s, %s or %s)",
                            item, MACHINE, MACDEF, DEFAULT
                        )
                    )
                }
            }
        }

        return Netrc.Companion.create(
            defaultCredential,
            com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.build.lib.authandtls.Netrc.Credential?>(
                credentialMap
            )
        )
    }

    @Throws(IOException::class)
    private fun nextItem(tokenStream: TokenStream): String {
        while (tokenStream.hasNext()) {
            val token = tokenStream.next()
            if (token is ItemToken) {
                return token.item
            }
        }

        throw IOException("Unexpected EOF")
    }

    /** Parse credentials for a given machine from token stream.  */
    @Throws(IOException::class)
    private fun parseCredentialForMachine(
        tokenStream: TokenStream,
        machine: String?
    ): com.google.devtools.build.lib.authandtls.Netrc.Credential? {
        val builder: com.google.devtools.build.lib.authandtls.Netrc.Credential.Builder =
            com.google.devtools.build.lib.authandtls.Netrc.Credential.Companion.builder(machine)

        var done = false
        while (!done && tokenStream.hasNext()) {
            // Peek rather than taking next token since we probably won't process it
            val token = tokenStream.peek()
            if (token is) {
                when (item) {
                    LOGIN -> {
                        tokenStream.next()
                        builder.setLogin(nextItem(tokenStream))
                    }

                    PASSWORD -> {
                        tokenStream.next()
                        builder.setPassword(nextItem(tokenStream))
                    }

                    ACCOUNT -> {
                        tokenStream.next()
                        builder.setAccount(nextItem(tokenStream))
                    }

                    MACHINE, MACDEF, DEFAULT -> done = true
                    else -> throw IOException(
                        java.lang.String.format(
                            "Unexpected item: %s (expecting %s, %s, %s, %s, %s or %s)",
                            item, LOGIN, PASSWORD, ACCOUNT, MACHINE, MACDEF, DEFAULT
                        )
                    )
                }
            } else {
                tokenStream.next()
            }
        }

        return builder.build()
    }

    /** Skip macdef section since we don't need that data currently.  */
    @Throws(IOException::class)
    private fun skipMacdef(tokenStream: TokenStream) {
        var numNewlines = 0
        while (tokenStream.hasNext()) {
            val token = tokenStream.next()
            if (token is NewlineToken) {
                ++numNewlines
            } else {
                numNewlines = 0
            }
            if (numNewlines >= 2) {
                break
            }
        }
    }

    internal interface Token

    @kotlin.jvm.JvmRecord
    internal data class ItemToken(val item: String) : Token {
        init {
            java.util.Objects.requireNonNull<String?>(item, "item")
        }

        companion object {
            fun create(item: String): ItemToken {
                return ItemToken(item)
            }
        }
    }

    internal class NewlineToken : Token {
        companion object {
            fun create(): NewlineToken {
                return NewlineToken()
            }
        }
    }

    internal class CommentToken : Token {
        companion object {
            fun create(): CommentToken {
                return CommentToken()
            }
        }
    }

    private class TokenStream(inputStream: java.io.InputStream) : java.io.Closeable {
        private val bufferedReader: BufferedReader
        private val tokens: java.util.Queue<Token?> = ArrayDeque<Token?>()

        init {
            bufferedReader =
                BufferedReader(java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.ISO_8859_1))
            processLine()
        }

        @Throws(IOException::class)
        override fun close() {
            bufferedReader.close()
        }

        @Throws(IOException::class)
        fun processLine() {
            val line: String? = bufferedReader.readLine()
            if (line == null) {
                return
            }

            // Comments start with #
            if (line.startsWith("#")) {
                tokens.add(com.google.devtools.build.lib.authandtls.NetrcParser.CommentToken.Companion.create())
            } else {
                java.util.Arrays.stream<String?>(line.split("\\s+"))
                    .filter(com.google.common.base.Predicates.not<String?>(com.google.common.base.Predicate { string: String? ->
                        com.google.common.base.Strings.isNullOrEmpty(
                            string
                        )
                    }))
                    .map<ItemToken?>(java.util.function.Function { item: String? -> ItemToken.Companion.create(item!!) })
                    .forEach(java.util.function.Consumer { e: ItemToken? -> tokens.add(e) })
            }

            tokens.add(NewlineToken.Companion.create())
        }

        fun hasNext(): Boolean {
            return !tokens.isEmpty()
        }

        @Throws(IOException::class)
        fun next(): Token? {
            val token: Token? = tokens.poll()
            if (tokens.isEmpty()) {
                processLine()
            }
            return token
        }

        fun peek(): Token? {
            return tokens.peek()
        }
    }
}
