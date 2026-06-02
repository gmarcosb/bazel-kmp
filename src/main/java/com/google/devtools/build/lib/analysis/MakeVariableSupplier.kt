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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.TemplateVariableInfo
import java.util.TreeMap

/**
 * Instances of [MakeVariableSupplier] passed to [ConfigurationMakeVariableContext] will
 * be called before getting value from [ConfigurationMakeVariableContext] itself.
 */
interface MakeVariableSupplier {
    /** Returns Make variable value or null if value is not supplied.  */
    @Throws(com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException::class)
    fun getMakeVariable(variableName: String?): String?

    /** Returns all Make variables that it supplies  */
    @Throws(com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException::class)
    fun getAllMakeVariables(): com.google.common.collect.ImmutableMap<String?, String?>?

    /**
     * [MakeVariableSupplier] that reads variables from a list of [TemplateVariableInfo]
     * providers. This implementation respects the ordering of providers, with the first listed being
     * the highest priority.
     */
    class TemplateVariableInfoBackedMakeVariableSupplier(templateVariableProviders: MutableList<TemplateVariableInfo?>) :
        MakeVariableSupplier {
        private val templateVariableProviders: com.google.common.collect.ImmutableList<TemplateVariableInfo>

        init {
            this.templateVariableProviders =
                com.google.common.collect.ImmutableList.copyOf<TemplateVariableInfo?>(templateVariableProviders)
        }

        override fun getMakeVariable(variableName: String?): String? {
            for (templateVariableInfo in templateVariableProviders) {
                if (templateVariableInfo.getVariables().containsKey(variableName)) {
                    return templateVariableInfo.getVariables().get(variableName)
                }
            }
            return null
        }

        override fun getAllMakeVariables(): com.google.common.collect.ImmutableMap<String?, String?> {
            val variables: MutableMap<String?, String?> = TreeMap<String?, String?>()
            for (templateVariableInfo in templateVariableProviders) {
                variables.putAll(templateVariableInfo.getVariables())
            }
            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(variables)
        }
    }

    /** [MakeVariableSupplier] that reads variables it supplies from a map.  */
    class MapBackedMakeVariableSupplier(makeVariables: com.google.common.collect.ImmutableMap<String?, String?>?) :
        MakeVariableSupplier {
        private val makeVariables: com.google.common.collect.ImmutableMap<String?, String?>

        init {
            this.makeVariables =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, String?>>(
                    makeVariables
                )
        }

        override fun getMakeVariable(variableName: String?): String? {
            return makeVariables.get(variableName)
        }

        override fun getAllMakeVariables(): com.google.common.collect.ImmutableMap<String?, String?> {
            return makeVariables
        }
    }
}
