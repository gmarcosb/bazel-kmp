// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

/**
 * A converter is a little helper object that can take a String and turn it into an instance of type
 * T (the type parameter to the converter). A context object is optionally provided.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface Converter<T> {
    /**
     * Convert a string into type T, using the given conversion context. Please note that we assume
     * that converting the same string (if successful) will produce objects which are equal ([ ][Object.equals]).
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun convert(input: String?, conversionContext: Any?): T?

    /**
     * The type description appears in usage messages. E.g.: "a string",
     * "a path", etc.
     */
    fun getTypeDescription(): String?

    /**
     * Can this converter reverse-convert to a Starlark-readable value?
     * 
     * 
     * If so, [.reverseForStarlark] implements the reverse conversion. If not, [ ][.reverseForStarlark] throws an [UnsupportedOperationException].
     */
    fun starlarkConvertible(): Boolean {
        return false
    }

    /**
     * If [.starlarkConvertible] is true, this reverses a converted value back to a
     * Starlark-readable form.
     * 
     * 
     * If [.starlarkConvertible] is true, throws an [UnsupportedOperationException].
     * 
     * @param converted If the option this value represents isn't [Option.allowMultiple], an
     * object of the option's Java type. Else an entry in the option's [java.util.List].
     * Always of type T. Referenced as an [Object] because calling code can call any
     * converter.
     * @return A [String] version of the input. Calling [.convert] on this value should
     * faithfully reproduce `converted`.
     */
    fun reverseForStarlark(converted: Any?): String? {
        throw java.lang.UnsupportedOperationException("This converter doesn't support Starlark reversal.")
    }

    /** A converter that never reads its context parameter.  */
    class Contextless<T> : Converter<T?> {
        /**
         * Actual implementation of [.convert] that just ignores the context
         * parameter.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        abstract fun convert(input: String?): T?

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?, conversionContext: Any?): T? {
            return convert(input)
        }
    }
}
