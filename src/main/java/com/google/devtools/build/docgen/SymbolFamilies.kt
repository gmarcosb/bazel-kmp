// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.docgen

import com.google.devtools.build.lib.packages.StarlarkNativeModule

/**
 * A helper class that collects Starlark Api symbols including top level modules, native rules and
 * builtin types.
 */
class SymbolFamilies(
    expander: StarlarkDocExpander,
    urlMapper: SourceUrlMapper?,
    provider: String?,
    inputJavaDirs: MutableList<String?>?,
    buildEncyclopediaStardocProtos: MutableList<String?>?,
    ruleDenyList: String?,
    apiStardocProtos: MutableList<String?>
) {
    private val nativeRules: com.google.common.collect.ImmutableList<RuleDocumentation?>
    private val allDocPages: com.google.common.collect.ImmutableMap<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>?

    // Mappings between Starlark names and Starlark entities generated from the fakebuildapi.
    private val globals: com.google.common.collect.ImmutableMap<String?, Any?>?
    private val bzlGlobals: com.google.common.collect.ImmutableMap<String?, Any?>

    init {
        val configuredRuleClassProvider: ConfiguredRuleClassProvider = createRuleClassProvider(provider)
        this.nativeRules =
            com.google.common.collect.ImmutableList.copyOf<RuleDocumentation?>(
                collectNativeRules(
                    expander.ruleExpander,
                    urlMapper,
                    configuredRuleClassProvider,
                    inputJavaDirs,
                    buildEncyclopediaStardocProtos,
                    ruleDenyList
                )
            )
        this.globals = Starlark.UNIVERSE
        this.bzlGlobals = collectBzlGlobals(configuredRuleClassProvider)
        this.allDocPages =
            StarlarkDocumentationCollector.getAllDocPages(
                expander, com.google.common.collect.ImmutableList.copyOf<String?>(apiStardocProtos)
            )
    }

    /*
   * Returns a list of native rules.
   */
    fun getNativeRules(): MutableList<RuleDocumentation?> {
        return nativeRules
    }

    /*
   * Returns a mapping between Starlark names and Starkark entities that are available both in BZL
   * and BUILD files.
   */
    fun getGlobals(): MutableMap<String?, Any?>? {
        return globals
    }

    /*
   * Returns a mapping between Starlark names and Starkark entities that are available only in BZL
   * files.
   */
    fun getBzlGlobals(): MutableMap<String?, Any?> {
        return bzlGlobals
    }

    // Returns a mapping between type names and module/type documentation.
    fun getAllDocPages(): com.google.common.collect.ImmutableMap<StarlarkDocumentationProcessor.Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>? {
        return allDocPages
    }

    /** Collects symbols predefined in BZL files.  */
    private fun collectBzlGlobals(provider: ConfiguredRuleClassProvider): com.google.common.collect.ImmutableMap<String?, Any?> {
        // StarlarkNativeModule is treated specially because we want to inherit the documentation
        // carried in its annotations, whereas the real "native" object is just a bare struct.
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        env.put("native", StarlarkNativeModule())
        for (entry in provider.getBazelStarlarkEnvironment().getUninjectedBuildBzlEnv().entrySet()) {
            if (entry.key == "native") {
                continue
            }
            env.put(entry)
        }
        return env.buildOrThrow()
    }

    /*
   * Collects a list of native rules that are available in BUILD files as top level functions
   * and in BZL files as methods of the native package.
   */
    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    private fun collectNativeRules(
        linkExpander: RuleLinkExpander?,
        urlMapper: SourceUrlMapper?,
        provider: ConfiguredRuleClassProvider?,
        inputJavaDirs: MutableList<String?>?,
        buildEncyclopediaStardocProtos: MutableList<String?>?,
        denyList: String?
    ): MutableList<RuleDocumentation?>? {
        val processor: ProtoFileBuildEncyclopediaProcessor =
            ProtoFileBuildEncyclopediaProcessor(linkExpander, urlMapper, provider)
        processor.generateDocumentation(inputJavaDirs, buildEncyclopediaStardocProtos, "", denyList)
        return processor.getNativeRules()
    }

    @Throws(
        java.lang.NoSuchMethodException::class,
        java.lang.reflect.InvocationTargetException::class,
        java.lang.IllegalAccessException::class,
        java.lang.ClassNotFoundException::class
    )
    private fun createRuleClassProvider(classProvider: String?): ConfiguredRuleClassProvider {
        val providerClass: java.lang.Class<*> = java.lang.Class.forName(classProvider)
        val createMethod: java.lang.reflect.Method = providerClass.getMethod("create")
        return createMethod.invoke(null) as ConfiguredRuleClassProvider
    }
}
