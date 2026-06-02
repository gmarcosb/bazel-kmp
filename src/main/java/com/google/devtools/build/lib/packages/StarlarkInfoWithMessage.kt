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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.StarlarkInfo
import com.google.devtools.build.lib.packages.StarlarkInfoNoSchema

/**
 * A struct-like object supporting a custom no-such-field error message.
 * 
 * 
 * This is used for certain special structs like `ctx.attr`.
 */
// TODO(bazel-team): It's questionable whether the use cases for this class should be part of the
// class hierarchy of StructImpl at all. They really only need to have fields and custom error
// messages (which are features of the simpler Structure class), not `+` concatenation,
// proto/json encoding, or provider functionality.
class StarlarkInfoWithMessage private constructor(
    provider: com.google.devtools.build.lib.packages.Provider?,
    values: MutableMap<String?, Any?>?,
    unknownFieldError: String
) : StarlarkInfoNoSchema(provider, values) {
    // A format string with one %s placeholder for the missing field name.
    // TODO(adonovan): make the provider determine the error message
    // (but: this has implications for struct+struct, the equivalence
    // relation, and other observable behaviors).
    // Perhaps it should be a property of the StarlarkInfo instance, but
    // defined by a subclass?
    private val unknownFieldError: String

    init {
        this.unknownFieldError = unknownFieldError
    }

    /** Returns the per-instance error message, if specified, or the provider's message otherwise.  */
    override fun getErrorMessageForUnknownField(name: String?): String {
        return java.lang.String.format(unknownFieldError, name) + allAttributesSuffix()
    }

    companion object {
        /**
         * Creates a schemaless provider instance with the given provider type, field values, and
         * unknown-field error message.
         * 
         * 
         * This is used to create structs for special purposes, such as `ctx.attr` and the `native` module. The creation location will be [Location.BUILTIN].
         * 
         * 
         * `unknownFieldError` is a string format, as for [ ][Provider.getErrorMessageFormatForUnknownField].
         * 
         */
        // TODO(bazel-team): Eliminate the need for this class by migrating the special structs that need
        // a custom error message to inherit from Structure rather than from the provider machinery. If
        // there are any use cases where the object is an actual native provider, migrate them to their
        // own subclasses of BuiltinProvider.
        //
        // However, either of these migrations could cause obscure user-visible changes in:
        //   - the type name ("struct" vs something else)
        //   - equality (`==`) semantics
        //   - the availability of the struct concatenation operator (`+`) and the type of its result.
        //     (Today, concatenation of structs with different error messages is allowed, and the result
        //     uses the error message of the left-hand side. But maybe this should be disallowed, or maybe
        //     it should always return a plain struct, or maybe the operator should be abolished
        //     altogether.)
        @Deprecated(
            """Do not use this method. Instead, create a new subclass of {@link BuiltinProvider}
        with the desired error message format, and create a corresponding {@link NativeInfo}
        subclass."""
        )
        fun createWithCustomMessage(
            provider: com.google.devtools.build.lib.packages.Provider?,
            values: MutableMap<String?, Any?>?,
            unknownFieldError: String?
        ): StarlarkInfo {
            com.google.common.base.Preconditions.checkNotNull<String?>(unknownFieldError)
            return StarlarkInfoWithMessage(provider, values, unknownFieldError!!)
        }
    }
}
