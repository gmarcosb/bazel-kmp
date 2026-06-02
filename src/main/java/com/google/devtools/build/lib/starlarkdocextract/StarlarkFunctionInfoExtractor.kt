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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_KEYWORD_ONLY

/** Contains a number of utility methods for functions and parameters.  */
class StarlarkFunctionInfoExtractor private constructor(labelRenderer: LabelRenderer) {
    private val labelRenderer: LabelRenderer

    init {
        this.labelRenderer = labelRenderer
    }

    @Throws(ExtractionException::class)
    private fun extract(functionName: String?, fn: net.starlark.java.eval.StarlarkFunction): StarlarkFunctionInfo {
        val paramNameToDocMap: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        val functionInfoBuilder: StarlarkFunctionInfo.Builder =
            StarlarkFunctionInfo.newBuilder().setFunctionName(StringEncoding.internalToUnicode(functionName))
        functionInfoBuilder.setOriginKey(getFunctionOriginKey(fn))
        val doc: String? = fn.getDocumentation()
        if (doc != null) {
            val parseErrors: MutableList<DocstringParseError> = java.util.ArrayList<DocstringParseError>()
            val docstringInfo: DocstringInfo = DocstringUtils.parseDocstring(doc, parseErrors)
            if (!parseErrors.isEmpty()) {
                throw makeDocstringExtractionException(functionName, fn.getLocation(), parseErrors)
            }
            val functionDescription: java.lang.StringBuilder = java.lang.StringBuilder(docstringInfo.getSummary())
            if (!docstringInfo.getSummary().isEmpty() && !docstringInfo.getLongDescription().isEmpty()) {
                functionDescription.append("\n\n")
            }
            functionDescription.append(docstringInfo.getLongDescription())
            functionInfoBuilder.setDocString(StringEncoding.internalToUnicode(functionDescription.toString()))
            for (paramDoc in docstringInfo.getParameters()) {
                paramNameToDocMap.put(paramDoc.getParameterName(), paramDoc.getDescription())
            }
            val returns: String = docstringInfo.getReturns()
            if (!returns.isEmpty()) {
                functionInfoBuilder.setReturn(
                    FunctionReturnInfo.newBuilder().setDocString(StringEncoding.internalToUnicode(returns)).build()
                )
            }
            val deprecated: String = docstringInfo.getDeprecated()
            if (!deprecated.isEmpty()) {
                functionInfoBuilder.setDeprecated(
                    FunctionDeprecationInfo.newBuilder()
                        .setDocString(StringEncoding.internalToUnicode(deprecated))
                        .build()
                )
            }
        }
        functionInfoBuilder.addAllParameter(parameterInfos(fn, paramNameToDocMap))
        return functionInfoBuilder.build()
    }

    private fun forParam(
        name: String?,
        docString: java.util.Optional<String?>,
        defaultValue: Any?,
        role: FunctionParamRole?
    ): FunctionParamInfo {
        val paramBuilder: FunctionParamInfo.Builder =
            FunctionParamInfo.newBuilder().setName(StringEncoding.internalToUnicode(name)).setRole(role)
        docString.map<String?>(java.util.function.Function { s: String? -> StringEncoding.internalToUnicode(s) })
            .ifPresent(paramBuilder::setDocString)
        if (defaultValue == null) {
            paramBuilder.setMandatory(true)
        } else {
            paramBuilder
                .setDefaultValue(StringEncoding.internalToUnicode(labelRenderer.repr(defaultValue)))
                .setMandatory(false)
        }
        return paramBuilder.build()
    }

    private fun parameterInfos(
        fn: net.starlark.java.eval.StarlarkFunction, parameterDoc: MutableMap<String?, String?>
    ): com.google.common.collect.ImmutableList<FunctionParamInfo?> {
        val names: com.google.common.collect.ImmutableList<String> = fn.getParameterNames()
        val numOrdinaryParams: Int = fn.getNumOrdinaryParameters()
        val numKeywordOnlyParams: Int = fn.getNumKeywordOnlyParameters()
        val varargsIndex = if (fn.hasVarargs()) numOrdinaryParams + numKeywordOnlyParams else -1
        val kwargsIndex = if (fn.hasKwargs()) names.size() - 1 else -1
        com.google.common.base.Preconditions.checkState(varargsIndex == -1 || varargsIndex < names.size())
        com.google.common.base.Preconditions.checkState(kwargsIndex == -1 || varargsIndex == -1 || kwargsIndex == varargsIndex + 1)

        val infos: com.google.common.collect.ImmutableList.Builder<FunctionParamInfo?> =
            com.google.common.collect.ImmutableList.builder<FunctionParamInfo?>()
        for (i in names.indices) {
            val name: String = names.get(i)
            val info: FunctionParamInfo
            if (i == varargsIndex) {
                // *args
                val doc: java.util.Optional<String?> =
                    java.util.Optional.ofNullable<String?>(parameterDoc.get("*" + name))
                info = forSpecialParam(name, doc, PARAM_ROLE_VARARGS)
            } else if (i == kwargsIndex) {
                // **kwargs
                val doc: java.util.Optional<String?> =
                    java.util.Optional.ofNullable<String?>(parameterDoc.get("**" + name))
                info = forSpecialParam(name, doc, PARAM_ROLE_KWARGS)
            } else {
                // regular parameter
                val doc: java.util.Optional<String?> = java.util.Optional.ofNullable<String?>(parameterDoc.get(name))
                info =
                    forParam(
                        name,
                        doc,
                        fn.getDefaultValue(i),
                        if (i < numOrdinaryParams) PARAM_ROLE_ORDINARY else PARAM_ROLE_KEYWORD_ONLY
                    )
            }
            infos.add(info)
        }
        return infos.build()
    }

    private fun getFunctionOriginKey(fn: net.starlark.java.eval.StarlarkFunction): OriginKey {
        val builder: OriginKey.Builder = OriginKey.newBuilder()
        // We can't just `builder.setName(fn.getName())` - fn could be a nested function or a lambda, so
        // fn.getName() may not be a unique name in fn's module. Instead, we look for fn in the module's
        // globals, and if we fail to find it, we leave OriginKey.name unset.
        // For nested functions and lambdas, we could theoretically derive OriginKey.name from
        // fn.getName() and fn.getLocation(), e.g. "<foo at 123:4>". It's unclear how useful this would
        // be in practice; and the location would be highly likely (as compared to docstring content) to
        // change with any edits to the .bzl file, resulting in lots of churn in golden tests.
        for (entry in fn.getModule().getGlobals().entrySet()) {
            if (fn == entry.getValue()) {
                builder.setName(StringEncoding.internalToUnicode(entry.getKey()))
                break
            }
        }

        // TODO(arostovtsev): also recurse into global structs/dicts/lists
        val moduleContext: BazelModuleContext? = BazelModuleContext.of(fn.getModule())
        if (moduleContext != null) {
            builder.setFile(StringEncoding.internalToUnicode(labelRenderer.render(moduleContext.label())))
        }
        return builder.build()
    }

    companion object {
        /**
         * Create and return a [StarlarkFunctionInfo] object encapsulating information obtained from
         * the given function and from its parsed docstring.
         * 
         * @param functionName the name of the function in the target scope. (Note this is not necessarily
         * the original exported function name; the function may have been renamed in the target
         * Starlark file's scope)
         * @param fn the function object
         * @param labelRenderer a string renderer for [Label] values in argument defaults and for
         * the [OriginKey]'s file
         * @throws ExtractionException if the function's docstring is malformed
         */
        @Throws(ExtractionException::class)
        fun fromNameAndFunction(
            functionName: String?, fn: net.starlark.java.eval.StarlarkFunction, labelRenderer: LabelRenderer
        ): StarlarkFunctionInfo {
            return StarlarkFunctionInfoExtractor(labelRenderer).extract(functionName, fn)
        }

        private fun makeDocstringExtractionException(
            functionName: String?,
            definedLocation: net.starlark.java.syntax.Location?,
            parseErrors: MutableList<DocstringParseError>
        ): ExtractionException {
            val message: java.lang.StringBuilder = java.lang.StringBuilder()
            message.append(
                java.lang.String.format(
                    "Unable to generate documentation for function %s (defined at %s) "
                            + "due to malformed docstring. Parse errors:\n",
                    functionName, definedLocation
                )
            )
            for (parseError in parseErrors) {
                message.append(
                    java.lang.String.format(
                        "  %s line %s: %s\n",
                        definedLocation,
                        parseError.getLineNumber(),
                        parseError.getMessage().replace('\n', ' ')
                    )
                )
            }
            return ExtractionException(message.toString())
        }

        /** Constructor to be used for *args or **kwargs.  */
        private fun forSpecialParam(
            name: String?, docString: java.util.Optional<String?>, role: FunctionParamRole?
        ): FunctionParamInfo {
            val paramBuilder: FunctionParamInfo.Builder =
                FunctionParamInfo.newBuilder()
                    .setName(StringEncoding.internalToUnicode(name))
                    .setRole(role)
                    .setMandatory(false)
            docString.map<String?>(java.util.function.Function { s: String? -> StringEncoding.internalToUnicode(s) })
                .ifPresent(paramBuilder::setDocString)
            return paramBuilder.build()
        }
    }
}
