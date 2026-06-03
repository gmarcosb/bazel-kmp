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

import com.google.devtools.build.docgen.starlark.AnnotStarlarkMethodDoc
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark

/**
 * Documentation for an ordinary, non-constructor [StarlarkMethod]-annotated Java method
 * callable from Starlark.
 */
class AnnotStarlarkOrdinaryMethodDoc(
    private val moduleName: String,
    javaMethod: java.lang.reflect.Method?,
    annotation: StarlarkMethod,
    expander: StarlarkDocExpander?
) : AnnotStarlarkMethodDoc(javaMethod, annotation, expander) {
    val shortName: String?

    private var isOverloaded = false

    init {
        this.shortName = annotation.name
    }

    override fun getName(): String? {
        // Normally we refer to methods by their name, e.g. "foo" for method foo(arg1, arg2).
        // However, if a method is overloaded, the name is no longer unique, which forces us to append
        // the names of the method parameters in order to get a unique value.
        // In this case, the return value for the previous example would be "foo(arg1, arg2)".

        // We decided against ALWAYS returning the full name since we didn't want to pollute the
        // TOC of documentation pages too much. This comes at the cost of inconsistency and more
        // complex code.

        return if (isOverloaded) this.fullName else this.shortName
    }

    private val fullName: String?
        /**
         * Returns the full name of the method, consisting of <method name>(<name of first param>, <name of second param>, ...).
        </name></name></method> */
        get() {
            val paramNames: MutableList<String?> = java.util.ArrayList<String?>()
            for (param in annotation.parameters) {
                paramNames.add(param.name)
            }
            return String.format(
                "%s(%s)",
                this.shortName,
                com.google.common.base.Joiner.on(", ").join(paramNames)
            )
        }

    val rawDocumentation: String
        get() {
            var prefixWarning = ""
            if (!annotation.enableOnlyWithFlag.isEmpty()) {
                prefixWarning =
                    ("<b>Experimental</b>. This API is experimental and may change at any time. "
                            + "Please do not depend on it. It may be enabled on an experimental basis by setting "
                            + "<code>--"
                            + annotation.enableOnlyWithFlag
                            + "</code> <br>")
            } else if (!annotation.disableWithFlag.isEmpty()) {
                prefixWarning =
                    ("<b>Deprecated</b>. This API is deprecated and will be removed soon. "
                            + "Please do not depend on it. It is <i>disabled</i> with "
                            + "<code>--"
                            + annotation.disableWithFlag
                            + "</code>. Use this flag "
                            + "to verify your code is compatible with its imminent removal. <br>")
            }
            return prefixWarning + annotation.doc
        }

    val signature: String?
        get() {
            val objectDotExpressionPrefix = if (moduleName.isEmpty()) "" else moduleName + "."

            return getSignature(objectDotExpressionPrefix + this.shortName)
        }

    val returnType: String?
        get() = Starlark.classTypeFromJava(javaMethod.getReturnType())

    fun setOverloaded(isOverloaded: Boolean) {
        this.isOverloaded = isOverloaded
    }
}
