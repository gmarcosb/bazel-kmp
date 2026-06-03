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

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.starlark.AnnotStarlarkMethodDoc
import com.google.devtools.build.docgen.starlark.ParamDoc
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import net.starlark.java.annot.ParamType

/**
 * A class containing the documentation for a parameter of a [ ]-annotated Java method callable from Starlark.
 */
class AnnotParamDoc(
    method: AnnotStarlarkMethodDoc,
    param: net.starlark.java.annot.Param,
    expander: StarlarkDocExpander?,
    kind: com.google.devtools.build.docgen.starlark.ParamDoc.Kind?,
    paramIndex: Int
) : ParamDoc(expander, kind) {
    private val method: AnnotStarlarkMethodDoc
    private val param: net.starlark.java.annot.Param
    private val paramIndex: Int

    init {
        this.method = method
        this.param = param
        this.paramIndex = paramIndex
    }

    val type: String
        get() {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            if (param.allowedTypes.size == 0) {
                // There is no `allowedTypes` field; we need to figure it out from the Java type.
                if (kind == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.ORDINARY || kind == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.POSITIONAL_ONLY || kind == com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KEYWORD_ONLY) {
                    // Only deal with normal args for now; unclear what we could do for varargs.
                    val type: java.lang.Class<*> = method.getMethod().getParameterTypes()[paramIndex]
                    if (type != Any::class.java) {
                        sb.append(getTypeAnchor(type))
                    }
                }
            } else {
                for (i in param.allowedTypes.indices) {
                    val paramType: ParamType = param.allowedTypes[i]
                    // TODO(adonovan): make generic1 an array.
                    if (paramType.generic1 == Any::class.java) {
                        sb.append(getTypeAnchor(paramType.type))
                    } else {
                        sb.append(getTypeAnchor(paramType.type, paramType.generic1))
                    }
                    if (i < param.allowedTypes.size - 1) {
                        sb.append("; or ")
                    }
                }
            }
            return sb.toString()
        }

    fun getMethod(): AnnotStarlarkMethodDoc {
        return method
    }

    val name: String?
        get() = param.name

    val defaultValue: String?
        get() = param.defaultValue

    val rawDocumentation: String
        get() {
            var prefixWarning = ""
            if (!param.enableOnlyWithFlag.isEmpty()) {
                prefixWarning =
                    ("<b>Experimental</b>. This parameter is experimental and may change at any "
                            + "time. Please do not depend on it. It may be enabled on an experimental basis by "
                            + "setting <code>--"
                            + param.enableOnlyWithFlag.substring(1)
                            + "</code> <br>")
            } else if (!param.disableWithFlag.isEmpty()) {
                prefixWarning =
                    ("<b>Deprecated</b>. This parameter is deprecated and will be removed soon. "
                            + "Please do not depend on it. It is <i>disabled</i> with "
                            + "<code>--"
                            + param.disableWithFlag.substring(1)
                            + "</code>. Use this flag "
                            + "to verify your code is compatible with its imminent removal. <br>")
            }
            return prefixWarning + param.doc
        }
}
