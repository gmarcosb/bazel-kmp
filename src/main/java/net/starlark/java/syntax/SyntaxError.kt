// Copyright 2019 The Bazel Authors. All rights reserved.
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
package net.starlark.java.syntax

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import java.lang.String

/**
 * A SyntaxError represents a static error associated with the syntax, such as a scanner or parse
 * error, a structural problem, or a failure of identifier resolution. It records a description of
 * the error and its location in the syntax.
 */
class SyntaxError(location: Location?, message: String?) {
    private val location: Location
    private val message: String

    init {
        this.location = Preconditions.checkNotNull<Location>(location)
        this.message = Preconditions.checkNotNull<String>(message)
    }

    /** Returns the location of the error.  */
    fun location(): Location {
        return location
    }

    /** Returns a description of the error.  */
    fun message(): String {
        return message
    }

    /** Returns a string of the form `"foo.star:1:2: oops"`.  */
    override fun toString(): String {
        return location.toString() + ": " + message
    }

    /**
     * A SyntaxError.Exception is an exception holding one or more syntax errors.
     * 
     * 
     * SyntaxError.Exception is thrown by operations such as [Expression.parse], which are
     * "all or nothing". By contrast, [StarlarkFile.parse] does not throw an exception; instead,
     * it records the accumulated scanner, parser, and optionally validation errors within the syntax
     * tree, so that clients may obtain partial information from a damaged file.
     * 
     * 
     * Clients that fail abruptly when encountering parse errors are encouraged to throw
     * SyntaxError.Exception, as in this example:
     * 
     * <pre>
     * StarlarkFile file = StarlarkFile.parse(input);
     * if (!file.ok()) {
     * throw new SyntaxError.Exception(file.errors());
     * }
    </pre> * 
     */
    class Exception(errors: MutableList<SyntaxError?>) : java.lang.Exception() {
        private val errors: ImmutableList<SyntaxError?>

        /** Construct a SyntaxError from a non-empty list of errors.  */
        init {
            require(!errors.isEmpty()) { "no errors" }
            this.errors = ImmutableList.copyOf<SyntaxError?>(errors)
        }

        /** Returns an immutable non-empty list of errors.  */
        fun errors(): ImmutableList<SyntaxError?> {
            return errors
        }

        override fun getMessage(): String? {
            val first = errors.get(0)!!.message()
            if (errors.size() > 1) {
                // TODO(adonovan): say ("+ n more errors") to avoid ambiguity.
                return String.format("%s (+ %d more)", first, errors.size() - 1)
            } else {
                return first
            }
        }
    }
}
