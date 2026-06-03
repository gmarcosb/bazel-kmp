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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole

/** Documentation for a function parameter.  */
abstract class ParamDoc(expander: StarlarkDocExpander?, val kind: Kind?) : StarlarkDoc(expander) {
    /**
     * Represents the param kind, e.g. whether it's an ordinary parameter, keyword-only, *args,
     * **kwargs, etc.
     */
    // Keep in sync with FunctionParamRole in stardoc_output.proto.
    enum class Kind {
        /** An ordinary parameter which may be used as a positional or by keyword.  */
        ORDINARY,

        /**
         * A positional-only parameter; such parameters cannot be defined in pure Starlark code, but
         * exist in some natively-defined functions.
         */
        POSITIONAL_ONLY,

        /**
         * A keyword-only parameter, i.e. a non-vararg/kwarg parameter that follows `*` or `*args` in
         * the function's declaration.
         */
        KEYWORD_ONLY,

        /** Residual varargs, typically `*args` in the function's declaration.  */
        VARARGS,

        /** Residual keyword arguments, typically `**kwargs` in the function's declaration.  */
        KWARGS;

        companion object {
            fun fromProto(role: FunctionParamRole): Kind {
                return when (role) {
                    PARAM_ROLE_ORDINARY -> com.google.devtools.build.docgen.starlark.ParamDoc.Kind.ORDINARY
                    PARAM_ROLE_POSITIONAL_ONLY -> com.google.devtools.build.docgen.starlark.ParamDoc.Kind.POSITIONAL_ONLY
                    PARAM_ROLE_KEYWORD_ONLY -> com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KEYWORD_ONLY
                    PARAM_ROLE_VARARGS -> com.google.devtools.build.docgen.starlark.ParamDoc.Kind.VARARGS
                    PARAM_ROLE_KWARGS -> com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KWARGS
                    else -> throw java.lang.IllegalArgumentException("Unknown param role: " + role)
                }
            }
        }
    }

    /**
     * Returns the string representing the type of this parameter with the link to the documentation
     * for the type if available.
     * 
     * 
     * If the parameter type is unspecified (e.g. [Object] for a Java-defined method), then
     * returns the empty string. If the parameter type is not a generic, then this method returns a
     * string representing the type name with a link to the documentation for the type if available.
     * If the parameter type is a generic, then this method returns a string "CONTAINER of TYPE" (with
     * HTML link markup).
     */
    abstract val type: String?

    abstract val defaultValue: String?
}
