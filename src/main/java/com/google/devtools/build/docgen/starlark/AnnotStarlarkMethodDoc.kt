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
import com.google.devtools.build.docgen.starlark.AnnotParamDoc
import com.google.devtools.build.docgen.starlark.MemberDoc
import com.google.devtools.build.docgen.starlark.ParamDoc
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod

/**
 * An abstract class containing documentation for a [StarlarkMethod]-annotated Java method
 * callable from Starlark.
 */
abstract class AnnotStarlarkMethodDoc(
    method: java.lang.reflect.Method,
    annotation: StarlarkMethod,
    expander: StarlarkDocExpander?
) : MemberDoc(expander) {
    protected val javaMethod: java.lang.reflect.Method
    protected val annotation: StarlarkMethod
    protected val params: com.google.common.collect.ImmutableList<AnnotParamDoc?>

    init {
        this.javaMethod = method
        this.annotation = annotation
        this.params = determineParams()
    }

    override fun documented(): Boolean {
        return annotation.documented
    }

    val returnTypeExtraMessage: String
        get() {
            if (annotation.allowReturnNones) {
                return " May return <code>None</code>.\n"
            }
            return ""
        }

    val method: java.lang.reflect.Method
        /** Returns the annotated Java method.  */
        get() = javaMethod

    val isCallable: Boolean
        get() = !annotation.structField

    /** Returns a list containing the documentation for each of the method's parameters.  */
    override fun getParams(): com.google.common.collect.ImmutableList<AnnotParamDoc?> {
        return params
    }

    private fun determineParams(): com.google.common.collect.ImmutableList<AnnotParamDoc?> {
        val paramsBuilder: com.google.common.collect.ImmutableList.Builder<AnnotParamDoc?> =
            com.google.common.collect.ImmutableList.builder<AnnotParamDoc?>()
        for (i in this.startIndexForParams..<annotation.parameters.size) {
            val param: net.starlark.java.annot.Param = annotation.parameters[i]
            if (param.documented) {
                var kind: com.google.devtools.build.docgen.starlark.ParamDoc.Kind =
                    com.google.devtools.build.docgen.starlark.ParamDoc.Kind.ORDINARY
                if (!param.named) {
                    kind = com.google.devtools.build.docgen.starlark.ParamDoc.Kind.POSITIONAL_ONLY
                } else if (!param.positional) {
                    kind = com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KEYWORD_ONLY
                }
                paramsBuilder.add(AnnotParamDoc(this, param, expander, kind, i))
            }
        }
        if (!annotation.extraPositionals.name.isEmpty()) {
            paramsBuilder.add(
                AnnotParamDoc(
                    this,
                    annotation.extraPositionals,
                    expander,
                    com.google.devtools.build.docgen.starlark.ParamDoc.Kind.VARARGS,  /* paramIndex= */
                    -1
                )
            )
        }
        if (!annotation.extraKeywords.name.isEmpty()) {
            paramsBuilder.add(
                AnnotParamDoc(
                    this,
                    annotation.extraKeywords,
                    expander,
                    com.google.devtools.build.docgen.starlark.ParamDoc.Kind.KWARGS,  /* paramIndex= */
                    -1
                )
            )
        }
        return paramsBuilder.build()
    }

    protected fun getSignature(fullyQualifiedMethodName: String?): String? {
        val args = if (this.isCallable) "(" + getParameterString() + ")" else ""

        return String.format(
            "%s %s%s", getTypeAnchor(javaMethod.getReturnType()), fullyQualifiedMethodName, args
        )
    }

    private val startIndexForParams: Int
        /**
         * Returns the index to start at when iterating through the parameters of the method annotation.
         * This is not always 0 because of the "self" param for the "string" module.
         */
        get() {
            val params: Array<net.starlark.java.annot.Param> = annotation.parameters
            if (params.size > 0) {
                val module: StarlarkBuiltin? =
                    javaMethod.getDeclaringClass().getAnnotation<StarlarkBuiltin?>(StarlarkBuiltin::class.java)
                if (module != null && module.name == "string") {
                    // Skip the self parameter, which is the first mandatory
                    // positional parameter in each method of the "string" module.
                    return 1
                }
            }
            return 0
        }
}
