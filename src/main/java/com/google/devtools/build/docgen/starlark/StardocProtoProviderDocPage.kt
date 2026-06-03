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

import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleInfo

/**
 * A documentation page for a Starlark provider described by a Stardoc proto obtained via `starlark_doc_extract` from a .bzl file.
 */
class StardocProtoProviderDocPage(expander: StarlarkDocExpander?, moduleInfo: ModuleInfo, providerInfo: ProviderInfo) :
    StarlarkDocPage(expander) {
    private val sourceFileLabel: String?
    private val providerInfo: ProviderInfo

    init {
        this.sourceFileLabel = moduleInfo.getFile()
        this.providerInfo = providerInfo

        if (providerInfo.hasInit()) {
            setConstructor(
                StardocProtoFunctionDoc(
                    expander,
                    moduleInfo,
                    providerInfo.getProviderName(),
                    providerInfo.getInit(),
                    providerInfo.getProviderName()
                )
            )
        }
        for (fieldInfo in providerInfo.getFieldInfoList()) {
            addMember(ProviderFieldDoc(expander, sourceFileLabel, providerInfo, fieldInfo))
        }
    }

    val name: String
        get() = providerInfo.getProviderName()

    val rawDocumentation: String
        get() = providerInfo.getDocString()

    val deprecatedStanza: String?
        get() {
            if (providerInfo.hasInit()
                && !providerInfo.getInit().getDeprecated().getDocString().isEmpty()
            ) {
                return expander.expand(providerInfo.getInit().getDeprecated().getDocString())
            }
            return ""
        }

    val title: String
        get() = providerInfo.getProviderName()

    val sourceFile: String?
        get() = StarlarkDoc.Companion.getSourceFileFromLabel(sourceFileLabel)

    val loadStatement: String?
        get() {
            val loadableSymbol: String =
                com.google.common.base.Splitter.on('.').splitToList(providerInfo.getProviderName()).getFirst()
            return String.format("load(\"%s\", \"%s\")", sourceFileLabel, loadableSymbol)
        }

    private class ProviderFieldDoc(
        expander: StarlarkDocExpander?,
        // for error reporting
        private val sourceFileLabel: String?,
        providerInfo: ProviderInfo,
        fieldInfo: ProviderFieldInfo
    ) : MemberDoc(expander) {
        private val providerInfo: ProviderInfo // for error reporting
        private val fieldInfo: ProviderFieldInfo
        private val typedDocstring: TypedDocstring

        init {
            this.providerInfo = providerInfo
            this.fieldInfo = fieldInfo
            this.typedDocstring = TypedDocstring.Companion.of(fieldInfo.getDocString())
        }

        val name: String
            get() = fieldInfo.getName()

        override fun documented(): Boolean {
            return true
        }

        val isCallable: Boolean
            get() = false

        val params: com.google.common.collect.ImmutableList<out ParamDoc?>
            get() = com.google.common.collect.ImmutableList.of<ParamDoc?>()

        val returnType: String?
            get() {
                try {
                    // TODO(arostovtsev): the "unknown" fallback text should be provided by the template.
                    return expander.getTypeParser().getHtml(typedDocstring.typeExpression, "unknown")
                } catch (e: net.starlark.java.eval.EvalException) {
                    throw java.lang.IllegalStateException(
                        java.lang.String.format(
                            "Failed to parse type for field %s of %s in %s",
                            this.name, providerInfo.getProviderName(), sourceFileLabel
                        ),
                        e
                    )
                }
            }

        val rawDocumentation: String
            get() = fieldInfo.getDocString()

        val documentation: String?
            get() = expander.expand(typedDocstring.remainder)

        val signature: String?
            get() = String.format("%s %s", this.returnType, this.name)
    }
}
