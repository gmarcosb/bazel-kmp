// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/** A request to set or unset a particular environment variable.  */
interface EnvVar {
    /** The name of the environment variable.  */
    fun name(): String?

    /** Set the environment variable to the given value.  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class Set(val name: String?, val value: String?) : EnvVar

    /** Inherit the value of the environment variable from the client environment.  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class Inherit(val name: String?) : EnvVar

    /**
     * Unset the environment variable, i.e., remove any previous assignment or even explicitly unset
     * it if implicitly inheriting the client environment.
     */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class Unset(val name: String?) : EnvVar

    /**
     * A converter for variable assignments from the parameter list of a blaze command invocation.
     * Assignments are expected to have the form "name[=value]", where names and values are defined to
     * be as permissive as possible and value part can be optional (in which case it is considered to
     * be inherited). The special syntax "=name" is also supported and interpreted as a request to
     * unset the variable with the given name.
     */
    class Converter

        : com.google.devtools.common.options.Converter.Contextless<EnvVar?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): EnvVar {
            val pos: Int = input.indexOf('='.code)
            if (input.isEmpty() || input == "=") {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Variable definitions must be in the form of a 'name=value', 'name', or '=name'"
                            + " assignment"
                )
            } else if (pos == 0) {
                return Unset(input.substring(1))
            } else if (pos < 0) {
                return Inherit(input)
            }
            val name: String = input.substring(0, pos)
            val value: String = input.substring(pos + 1)
            return com.google.devtools.build.lib.util.EnvVar.Set(name, value)
        }

        override fun starlarkConvertible(): Boolean {
            return true
        }

        override fun reverseForStarlark(converted: Any?): String? {
            if (converted is Set) {
                return converted.name + "=" + converted.value
            } else if (converted is Inherit) {
                return converted.name
            } else if (converted is Unset) {
                return "=" + converted.name
            } else {
                throw java.lang.IllegalArgumentException(
                    "EnvVar.Converter can only reverse EnvVar types, got: " + converted
                )
            }
        }

        val typeDescription: String
            get() = ("a 'name[=value]' assignment with an optional value part or the special syntax '=name'"
                    + " to unset a variable")
    }
}
