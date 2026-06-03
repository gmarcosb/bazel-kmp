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

import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamInfo

/**
 * A documentation entry for a Starlark function described by a Stardoc proto obtained via `starlark_doc_extract` from a .bzl file.
 */
class StardocProtoFunctionDoc(
    expander: StarlarkDocExpander?,
    moduleInfo: ModuleInfo,
    private val structName: String,
    functionInfo: StarlarkFunctionInfo,
    constructorType: String?
) : MemberDoc(expander) {
    private val sourceFileLabel: String?
    val name: String?
    private val functionInfo: StarlarkFunctionInfo
    private val typedReturnDocstring: TypedDocstring
    private val params: com.google.common.collect.ImmutableList<StardocProtoParamDoc?>?
    private val constructorType: String?

    init {
        this.sourceFileLabel = moduleInfo.getFile()
        this.name =
            if (functionInfo.getFunctionName().startsWith(structName + "."))
                functionInfo.getFunctionName().substring(structName.length + 1)
            else
                functionInfo.getFunctionName()
        this.functionInfo = functionInfo
        this.constructorType = constructorType
        if (constructorType == null) {
            this.typedReturnDocstring =
                TypedDocstring.Companion.of(functionInfo.getReturn().getDocString())
        } else {
            // Constructors always return the type they construct
            this.typedReturnDocstring = TypedDocstring(constructorType, "")
        }
        this.params =
            functionInfo.getParameterList().stream()
                .map(
                    { paramInfo -> StardocProtoParamDoc(expander, sourceFileLabel, functionInfo, paramInfo) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    constructor(
        expander: StarlarkDocExpander?,
        moduleInfo: ModuleInfo,
        structName: String,
        functionInfo: StarlarkFunctionInfo
    ) : this(expander, moduleInfo, structName, functionInfo,  /* constructorType= */null)

    override fun documented(): Boolean {
        return true
    }

    val isCallable: Boolean
        get() = true

    val isConstructor: Boolean
        get() = constructorType != null

    val rawDocumentation: String
        get() = functionInfo.getDocString()

    val loadStatement: String?
        get() = java.lang.String.format(
            "load(\"%s\", \"%s\")",
            sourceFileLabel, if (structName.isEmpty()) functionInfo.getFunctionName() else structName
        )

    val returnType: String?
        get() {
            try {
                // TODO(arostovtsev): the "unknown" fallback text should be provided by the template.
                return expander.getTypeParser().getHtml(typedReturnDocstring.typeExpression, "unknown")
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Failed to parse return type for %s in %s",
                        functionInfo.getFunctionName(), sourceFileLabel
                    ),
                    e
                )
            }
        }

    val returnsStanza: String?
        get() = expander.expand(typedReturnDocstring.remainder)

    val deprecatedStanza: String?
        get() =// A provider constructor's deprecation stanza applies to the provider it constructs.
            if (this.isConstructor) "" else expander.expand(functionInfo.getDeprecated().getDocString())

    override fun getParams(): com.google.common.collect.ImmutableList<StardocProtoParamDoc?>? {
        return params
    }

    val signature: String?
        /**
         * Returns a string representing the method signature of the Starlark method, which contains HTML
         * links to the documentation of parameter types if available.
         */
        get() = java.lang.String.format(
            "%s %s(%s)", this.returnType, functionInfo.getFunctionName(), getParameterString()
        )

    /** Documentation for a Starlark function parameter.  */
    class StardocProtoParamDoc(
        expander: StarlarkDocExpander?,
        private val sourceFileLabel: String?,
        functionInfo: StarlarkFunctionInfo,
        paramInfo: FunctionParamInfo
    ) : ParamDoc(
        expander,
        com.google.devtools.build.docgen.starlark.ParamDoc.Kind.Companion.fromProto(paramInfo.getRole())
    ) {
        private val functionInfo: StarlarkFunctionInfo
        private val paramInfo: FunctionParamInfo
        private val typedDocstring: TypedDocstring

        init {
            this.functionInfo = functionInfo
            this.paramInfo = paramInfo
            this.typedDocstring = TypedDocstring.Companion.of(paramInfo.getDocString())
        }

        val name: String
            get() = paramInfo.getName()

        val type: String?
            get() {
                try {
                    // TODO(arostovtsev): the fallback text should be provided by the template.
                    return expander.getTypeParser().getHtml(typedDocstring.typeExpression)
                } catch (e: net.starlark.java.eval.EvalException) {
                    throw java.lang.IllegalStateException(
                        java.lang.String.format(
                            "Failed to parse type for param %s of %s in %s",
                            this.name, functionInfo.getFunctionName(), sourceFileLabel
                        ),
                        e
                    )
                }
            }

        val defaultValue: String
            get() = paramInfo.getDefaultValue()

        val rawDocumentation: String
            get() = paramInfo.getDocString()

        val documentation: String?
            get() = expander.expand(typedDocstring.remainder)
    }
}
