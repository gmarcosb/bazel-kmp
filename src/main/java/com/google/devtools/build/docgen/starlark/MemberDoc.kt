// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.starlark.ParamDoc
import com.google.devtools.build.docgen.starlark.StarlarkDoc
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import net.starlark.java.syntax.Identifier.getName

/**
 * Documentation for a method or struct field of a Java class annotated with [ ], or for a field of a Starlark-defined struct.
 */
abstract class MemberDoc protected constructor(expander: StarlarkDocExpander?) : StarlarkDoc(expander) {
    /** Returns whether the value is documented.  */
    abstract fun documented(): Boolean

    /**
     * Returns whether the value can be called as a function.
     * 
     * 
     * For example, `ctx.label` is not callable.
     */
    abstract val isCallable: Boolean

    /**
     * For a callable value, returns the name for the return type; or the name of the value's own type
     * otherwise.
     */
    abstract val returnType: String?

    open val returnTypeExtraMessage: String
        /**
         * For a callable value, returns a short piece of additional documentation about the return value,
         * which will be appended to the main documentation.
         * 
         * 
         * Returns an empty string by default.
         * 
         * 
         * Contrast with [.getReturnsStanza], which requires formatting under a separate
         * sub-header.
         */
        get() = ""

    open val returnsStanza: String?
        /**
         * Long-form HTML documentation about the return value; inserted in the output as a separate
         * stanza with a sub-header.
         */
        get() = ""

    open val isConstructor: Boolean
        /** Returns true if the value is callable and is a constructor of its type.  */
        get() = false

    open val shortName: String?
        /**
         * Returns the value's name within its module.
         * 
         * 
         * In most cases, this is the same as [.getName]. The exception is for overloaded methods
         * in a [net.starlark.java.annot.StarlarkBuiltin]-annotated Java class. In that case, this
         * method would return the name of the method, while [.getName] would return the method
         * signature with parameters, e.g. `method_name(arg1, arg2)`.
         */
        get() = getName()

    /**
     * For a callable value, returns a list containing the documentation for each of the method's
     * parameters; or an empty list otherwise.
     */
    abstract val params: com.google.common.collect.ImmutableList<out ParamDoc>

    protected val parameterString: String
        /**
         * For a callable value, returns the string representation of the parameters, for example `"arg1, arg2=None, **kwargs"`; or an empty string otherwise.
         */
        get() {
            val params: com.google.common.collect.ImmutableList<out ParamDoc> = this.params
            var nparams: Int = params.size
            var kwargs: ParamDoc? = null
            if (nparams > 0 && params.get(nparams - 1)
                    .getKind() == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KWARGS
            ) {
                kwargs = params.get(nparams - 1)
                nparams--
            }
            var varargs: ParamDoc? = null
            if (nparams > 0 && params.get(nparams - 1)
                    .getKind() == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.VARARGS
            ) {
                varargs = params.get(nparams - 1)
                nparams--
            }

            val argList: MutableList<String?> =
                java.util.ArrayList<String?>(params.size)
            var numKeywordOnly = 0
            for (i in 0..<nparams) {
                val param: ParamDoc = params.get(i)
                if (param.getKind() == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KEYWORD_ONLY) {
                    numKeywordOnly = nparams - i
                    break
                }
                argList.add(formatParameter(param))
            }
            if (varargs != null) {
                argList.add("*" + varargs.getName())
            } else if (numKeywordOnly > 0) {
                argList.add("*")
            }
            for (i in nparams - numKeywordOnly..<nparams) {
                argList.add(formatParameter(params.get(i)))
            }
            if (kwargs != null) {
                argList.add("**" + kwargs.getName())
            }
            return com.google.common.base.Joiner.on(", ").join(argList)
        }

    /**
     * For a callable value, returns the string representing the method signature of the Starlark
     * method, which contains HTML links to the documentation of parameter types if available. For a
     * non-callable value, returns the string representation of the value's type (with HTML links to
     * the type's documentation, if available) and name.
     */
    abstract val signature: String?

    private fun formatParameter(param: ParamDoc): String? {
        if (!param.getDefaultValue().isEmpty()) {
            return String.format("%s=%s", param.getName(), param.getDefaultValue())
        } else {
            return param.getName()
        }
    }
}
