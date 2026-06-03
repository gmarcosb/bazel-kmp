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
 * A documentation page for a Starlark struct described by a Stardoc proto obtained via `starlark_doc_extract` from a .bzl file.
 */
class StardocProtoStructDocPage(
    expander: StarlarkDocExpander?,
    moduleInfo: ModuleInfo,
    structInfo: StarlarkOtherSymbolInfo
) : StarlarkDocPage(expander) {
    private val sourceFileLabel: String?
    private val structInfo: StarlarkOtherSymbolInfo

    init {
        this.sourceFileLabel = moduleInfo.getFile()
        this.structInfo = structInfo
    }

    val name: String
        get() = structInfo.getName()

    val rawDocumentation: String
        get() = structInfo.getDoc()

    val title: String
        get() = structInfo.getName()

    val sourceFile: String?
        get() = StarlarkDoc.Companion.getSourceFileFromLabel(sourceFileLabel)

    val loadStatement: String?
        get() = java.lang.String.format("load(\"%s\", \"%s\")", sourceFileLabel, structInfo.getName())

    fun addProviderAlias(providerInfo: ProviderInfo) {
        addMember(ProviderAliasDoc(expander, sourceFileLabel, structInfo.getName(), providerInfo))
    }

    private class ProviderAliasDoc(
        expander: StarlarkDocExpander?,
        private val sourceFileLabel: String?,
        structName: String,
        providerInfo: ProviderInfo
    ) : MemberDoc(expander) {
        private val structName: String?
        val name: String?
        private val providerInfo: ProviderInfo

        init {
            this.structName = structName
            this.name =
                if (providerInfo.getProviderName().startsWith(structName + "."))
                    providerInfo.getProviderName().substring(structName.length + 1)
                else
                    providerInfo.getProviderName()
            this.providerInfo = providerInfo
        }

        override fun documented(): Boolean {
            return true
        }

        val isCallable: Boolean
            get() =// For simplicity, we document a provider alias in its role as a symbol.
                false

        val params: com.google.common.collect.ImmutableList<out ParamDoc?>
            get() = com.google.common.collect.ImmutableList.of<ParamDoc?>()

        val returnType: String
            get() = expander.getTypeParser().getHtmlForIdentifier("Provider")

        val rawDocumentation: String?
            get() = String.format("A convenience alias for the %s provider symbol.", this.aliasedName)

        val documentation: String?
            get() = String.format(
                "A convenience alias for the %s provider symbol.",
                expander.getTypeParser().getHtmlForIdentifier(this.aliasedName)
            )

        val aliasedName: String?
            /**
             * Returns the documented name of the provider symbol that this one is aliasing; or this
             * provider's name without the struct namespace as fallback.
             */
            get() {
                if (expander.getTypeParser().isDocumentedIdentifier(providerInfo.getOriginKey().getName())) {
                    return providerInfo.getOriginKey().getName()
                } else {
                    return this.name
                }
            }

        val signature: String?
            get() = String.format("%s %s", this.returnType, this.name)

        val loadStatement: String?
            get() = String.format("load(\"%s\", \"%s\")", sourceFileLabel, structName)
    }
}
