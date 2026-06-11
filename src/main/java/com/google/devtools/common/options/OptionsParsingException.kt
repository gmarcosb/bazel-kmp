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
 * An exception that's thrown when the [OptionsParser] fails.
 * 
 * @see OptionsParser.parse
 */
open class OptionsParsingException : java.lang.Exception {
    private val invalidArgument: String?

    @kotlin.jvm.JvmOverloads
    constructor(message: String?, argument: String? = null as String?) : super(message) {
        this.invalidArgument = argument
    }

    constructor(message: String?, throwable: Throwable?) : this(message, null, throwable)

    constructor(message: String?, argument: String?, throwable: Throwable?) : super(message, throwable) {
        this.invalidArgument = argument
    }

    /**
     * Gets the name of the invalid argument or `null` if the exception
     * can not determine the exact invalid arguments
     */
    fun getInvalidArgument(): String? {
        return invalidArgument
    }
}
