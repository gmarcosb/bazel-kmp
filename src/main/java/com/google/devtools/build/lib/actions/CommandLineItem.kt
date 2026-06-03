// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.CommandLineExpansionException

/** An interface for an object that customizes how it is expanded into a command line.  */
interface CommandLineItem {
    /**
     * A map function that allows caller customization how a type is expanded into the command line.
     */
    interface MapFn<T> {
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun expandToCommandLine(`object`: T?, args: java.util.function.Consumer<String?>?)

        companion object {
            @kotlin.jvm.JvmField
            val DEFAULT: ExceptionlessMapFn<Any?> =
                ExceptionlessMapFn { `object`: Any?, args: java.util.function.Consumer<kotlin.String?>? ->
                    args.accept(CommandLineItem.Companion.expandToCommandLine(`object`!!))
                }
        }
    }

    /** A [CommandLineItem.MapFn] that does not throw.  */
    interface ExceptionlessMapFn<T> : MapFn<T?> {
        override fun expandToCommandLine(`object`: T?, args: java.util.function.Consumer<String?>?)
    }

    /**
     * Use this map function when parametrizing over a limited set of values.
     * 
     * 
     * The user promises that the number of distinct instances constructed is closer to O(rule
     * class count) than O(rule count).
     * 
     * 
     * Without this, [ ] will refuse to cache
     * your [MapFn] computations.
     */
    class ParametrizedMapFn<T> : MapFn<T?> {
        abstract override fun equals(obj: Any?): Boolean

        abstract override fun hashCode(): Int

        /**
         * This method controls the max number of distinct instances allowed. If the system sees any
         * more than this, it will throw.
         * 
         * 
         * Override and set this to something low. You want this to represent the small number of
         * preallocated static instances used in this blaze instance. 3 is an OK number, 100 is a bad
         * number.
         */
        abstract fun maxInstancesAllowed(): Int
    }

    /** Expands the object into the command line as a string.  */
    fun expandToCommandLine(): String?

    companion object {
        /**
         * The default method of expanding types.
         * 
         * 
         * If the object is a [CommandLineItem] we use its [ ][CommandLineItem.expandToCommandLine] method, else we call [Object.toString].
         */
        @kotlin.jvm.JvmStatic
        fun expandToCommandLine(`object`: Any): String? {
            // TODO(b/150322434): The fallback on toString() isn't great. Particularly so for
            // StarlarkCustomCommandLine, since toString() does not necessarily give the same results as
            // Starlark's str() or repr().
            //
            // The ideal refactoring is to make StarlarkValue implement CommandLineItem (or a slimmer
            // version
            // thereof). Then the default behavior can be that StarlarkValue#expandToCommandLine calls
            // StarlarkValue#str. This default behavior is inefficient but rare; Artifacts and the like
            // would continue to override expandToCommandLine to take the fast code path that doesn't
            // involve a Printer.
            //
            // Since StarlarkValue should be moved out of Bazel, this refactoring would be blocked on making
            // a BuildStarlarkValue subinterface for Bazel-specific Starlark types. It would then be
            // BuildStarlarkValue, rather than StarlarkValue, that extends CommandLineItem.
            if (`object` is CommandLineItem) {
                return `object`.expandToCommandLine()
            } else {
                return `object`.toString()
            }
        }
    }
}
