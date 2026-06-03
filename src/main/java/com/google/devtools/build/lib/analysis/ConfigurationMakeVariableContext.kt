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

import com.google.devtools.build.lib.analysis.MakeVariableSupplier.MapBackedMakeVariableSupplier

/**
 * Implements make variable expansion for make variables that depend on the configuration and the
 * target (not on behavior of the [ConfiguredTarget] implementation). Retrieved Make variable
 * value can be modified using [MakeVariableSupplier]
 */
open class ConfigurationMakeVariableContext(
    pkgDeclarations: Package.Declarations,
    configuration: BuildConfigurationValue,
    ruleTemplateVariableProviders: com.google.common.collect.ImmutableList<TemplateVariableInfo?>?,
    extraMakeVariableSuppliers: Iterable<out MakeVariableSupplier?>?
) : com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext {
    private val allMakeVariableSuppliers: com.google.common.collect.ImmutableList<out MakeVariableSupplier>

    constructor(
        pkgDeclarations: Package.Declarations,
        configuration: BuildConfigurationValue,
        ruleTemplateVariableProviders: com.google.common.collect.ImmutableList<TemplateVariableInfo?>?
    ) : this(
        pkgDeclarations,
        configuration,
        ruleTemplateVariableProviders,
        com.google.common.collect.ImmutableList.of<MakeVariableSupplier?>()
    )

    init {
        this.allMakeVariableSuppliers =
            com.google.common.collect.ImmutableList.builder<MakeVariableSupplier>() // These should be in priority order:
                // 1) extra suppliers passed in (assume the caller knows what they are doing)
                // 2) variables from the command-line
                // 3) package-level overrides (ie, vardef)
                // 4) variables from the rule (including from resolved toolchains)
                // 5) variables from the global configuration
                .addAll(com.google.common.base.Preconditions.checkNotNull(extraMakeVariableSuppliers))
                .add(MapBackedMakeVariableSupplier(configuration.getCommandLineBuildVariables()))
                .add(MapBackedMakeVariableSupplier(pkgDeclarations.getMakeEnvironment()))
                .add(TemplateVariableInfoBackedMakeVariableSupplier(ruleTemplateVariableProviders))
                .add(MapBackedMakeVariableSupplier(configuration.getGlobalMakeEnvironment()))
                .build()
    }

    @Throws(ExpansionException::class)
    override fun lookupVariable(name: String?): String? {
        for (supplier in allMakeVariableSuppliers) {
            val variableValue: String? = supplier.getMakeVariable(name)
            if (variableValue != null) {
                return variableValue
            }
        }
        throw ExpansionException(java.lang.String.format("$(%s) not defined", name))
    }

    @Throws(ExpansionException::class)
    fun collectMakeVariables(): net.starlark.java.eval.Dict.Builder<String?, String?> {
        val map: net.starlark.java.eval.Dict.Builder<String?, String?> = Dict.builder<String?, String?>()
        // Collect variables in the reverse order as in lookupMakeVariable
        // because each update is overwriting.
        for (supplier in allMakeVariableSuppliers.reverse()) {
            map.putAll(supplier.getAllMakeVariables())
        }
        return map
    }

    @Throws(ExpansionException::class)
    override fun lookupFunction(name: String?, param: String?): String? {
        throw ExpansionException(java.lang.String.format("$(%s) not defined", name))
    }
}
