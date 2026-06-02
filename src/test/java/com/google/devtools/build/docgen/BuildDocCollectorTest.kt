// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.docgen.BuildDocCollector.DocumentationOrigin

/** Tests for BuildDocCollector.  */
@RunWith(JUnit4::class)
class BuildDocCollectorTest {
    // The following are initialized by setUpCollectorState to simplify boilerplate.
    var ruleDocOrigin: MutableMap<String?, DocumentationOrigin?>? = null
    var ruleDocEntries: MutableMap<String?, RuleDocumentation>? = null
    var attributeDocEntries: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?>? = null
    var urlMapper: SourceUrlMapper = SourceUrlMapper( /* sourceUrlRoot= */
        "https://example.com/",  /* inputRoot= */
        "/tmp/io_bazel/",
        com.google.common.collect.ImmutableMap.of<K?, V?>(
            "@//", "https://main-repo.com/",
            "@_builtins//", "https://example.com/src/main/starlark/builtins_bzl/"
        )
    )

    @Before
    fun setUpCollectorState() {
        ruleDocOrigin = HashMap<String?, DocumentationOrigin?>()
        ruleDocEntries = HashMap<String?, RuleDocumentation>()
        attributeDocEntries =
            com.google.common.collect.LinkedListMultimap.create<String?, RuleDocumentationAttribute?>()
    }

    @Throws(java.lang.Exception::class)
    private fun collectModuleInfoDocs(moduleInfo: ModuleInfo?): Int {
        return BuildDocCollector.collectModuleInfoDocs(
            ruleDocOrigin,
            ruleDocEntries,
            com.google.common.collect.ImmutableSet.of<E?>(),
            attributeDocEntries,
            moduleInfo,
            urlMapper
        )
    }

    @Throws(java.lang.Exception::class)
    private fun collectModuleInfoDocsWithDenyList(moduleInfo: ModuleInfo?, denyList: MutableSet<String?>?): Int {
        return BuildDocCollector.collectModuleInfoDocs(
            ruleDocOrigin, ruleDocEntries, denyList, attributeDocEntries, moduleInfo, urlMapper
        )
    }

    private fun getAttribute(
        attributes: MutableSet<RuleDocumentationAttribute>, name: String?
    ): RuleDocumentationAttribute? {
        for (attribute in attributes) {
            if (attribute.getAttributeName().equals(name)) {
                return attribute
            }
        }
        return null
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun collectModuleInfoDocs_basicFunctionality() {
        val moduleInfo: ModuleInfo? =
            ModuleInfo.newBuilder()
                .setModuleDocstring("My Language")
                .setFile("//:test.bzl")
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("binary_rules.my_binary")
                        .setDocString("My language binary")
                        .setExecutable(true)
                        .setOriginKey(
                            OriginKey.newBuilder()
                                .setName("_my_binary")
                                .setFile("@_builtins//:my_lang/my_binary.bzl")
                        )
                        .addAttribute( // starlark_doc_extract always injects the implicit "name" attribute
                            AttributeInfo.newBuilder()
                                .setName("name")
                                .setType(AttributeType.NAME)
                                .setMandatory(true)
                                .setDocString("A unique name for this target.")
                                .build()
                        )
                        .addAttribute(
                            AttributeInfo.newBuilder()
                                .setName("srcs")
                                .setDocString("My sources")
                                .setType(AttributeType.LABEL_LIST)
                                .setMandatory(true)
                        )
                        .addAttribute(
                            AttributeInfo.newBuilder()
                                .setName("deps")
                                .setDocString("My deps")
                                .setType(AttributeType.LABEL_LIST)
                                .setDefaultValue("[]")
                        )
                        .addAttribute(
                            AttributeInfo.newBuilder()
                                .setName("old")
                                .setDocString("Deprecated: do not use")
                                .setType(AttributeType.STRING)
                                .setDefaultValue("\"???\"")
                        )
                )
                .build()

        Truth.assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(1)

        Truth.assertThat(ruleDocEntries.keySet()).containsExactly("my_binary")

        val ruleDoc: RuleDocumentation = ruleDocEntries!!.get("my_binary")
        assertThat(ruleDoc.getRuleName()).isEqualTo("my_binary")
        assertThat(ruleDoc.getRuleType()).isEqualTo(DocgenConsts.RuleType.BINARY)
        assertThat(ruleDoc.getRuleFamily()).isEqualTo("My Language")
        assertThat(ruleDoc.getFamilySummary()).isEmpty()
        assertThat(ruleDoc.getSourceUrl())
            .isEqualTo("https://example.com/src/main/starlark/builtins_bzl/my_lang/my_binary.bzl")
        assertThat(ruleDoc.getHtmlDocumentation()).isEqualTo("My language binary")
        val attributes: MutableSet<RuleDocumentationAttribute> = ruleDoc.getAttributes()
        Truth.assertThat(attributes)
            .containsAtLeastElementsIn(PredefinedAttributes.COMMON_ATTRIBUTES.values())
        Truth.assertThat(attributes)
            .containsAtLeastElementsIn(PredefinedAttributes.BINARY_ATTRIBUTES.values())
        Truth.assertThat(
            attributes.stream()
                .map<Any?>(RuleDocumentationAttribute::getAttributeName)
                .filter(
                    java.util.function.Predicate { attr: Any? ->
                        !(PredefinedAttributes.COMMON_ATTRIBUTES.containsKey(attr)
                                || PredefinedAttributes.BINARY_ATTRIBUTES.containsKey(attr))
                    })
        ) // We do not want the implicit "name" attribute - we inject "name" at template level
            .containsExactly("srcs", "deps", "old")

        val srcsAttribute: RuleDocumentationAttribute? = getAttribute(attributes, "srcs")
        assertThat(srcsAttribute.getAttributeName()).isEqualTo("srcs")
        assertThat(srcsAttribute.getHtmlDocumentation()).isEqualTo("My sources")
        assertThat(srcsAttribute.getSynopsis())
            .isEqualTo("List of <a href=\"\${link build-ref#labels}\">labels</a>; required")

        val depsAttribute: RuleDocumentationAttribute? = getAttribute(attributes, "deps")
        assertThat(depsAttribute.getAttributeName()).isEqualTo("deps")
        assertThat(depsAttribute.getHtmlDocumentation()).isEqualTo("My deps")
        assertThat(depsAttribute.getSynopsis())
            .isEqualTo(
                "List of <a href=\"\${link build-ref#labels}\">labels</a>; default is <code>[]</code>"
            )

        val oldAttribute: RuleDocumentationAttribute? = getAttribute(attributes, "old")
        assertThat(oldAttribute.getAttributeName()).isEqualTo("old")
        assertThat(oldAttribute.isDeprecated()).isTrue()
        assertThat(oldAttribute.getSynopsis()).isEqualTo("String; default is <code>\"???\"</code>")
        assertThat(ruleDoc.getAttribute("old")).isEqualTo(oldAttribute)
        Truth.assertThat(ruleDocOrigin)
            .containsExactly(
                "my_binary", DocumentationOrigin.create("//:test.bzl", "binary_rules.my_binary")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun collectModuleInfoDocs_respectsDenyList() {
        val moduleInfo: ModuleInfo? =
            ModuleInfo.newBuilder()
                .setModuleDocstring("My Language")
                .setFile("//:test.bzl")
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("library_rules.my_library")
                        .setDocString("My language library")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("_my_library").setFile("//:my_library.bzl")
                        )
                )
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("library_rules.my_plugin")
                        .setDocString("My language plugin")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("_my_plugin").setFile("//:my_plugin.bzl")
                        )
                )
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("library_rules.my_import")
                        .setDocString("My language import")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("_my_import").setFile("//:my_import.bzl")
                        )
                )
                .build()

        Truth.assertThat(
            collectModuleInfoDocsWithDenyList(
                moduleInfo,
                com.google.common.collect.ImmutableSet.of<String?>("my_library")
            )
        )
            .isEqualTo(2)

        Truth.assertThat(ruleDocEntries.keySet()).containsExactly("my_plugin", "my_import")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun collectModuleInfoDocs_expectsNonemptyModuleDocstring() {
        val e: BuildEncyclopediaDocException? =
            org.junit.Assert.assertThrows<T?>(
                BuildEncyclopediaDocException::class.java,
                org.junit.function.ThrowingRunnable {
                    collectModuleInfoDocs(
                        ModuleInfo.newBuilder().setModuleDocstring("").build()
                    )
                })
        assertThat(e)
            .hasMessageThat()
            .contains("expected to be a single line representing a rule family name")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun collectModuleInfoDocs_multilineModuleDocstring() {
        val moduleInfo: ModuleInfo? =
            ModuleInfo.newBuilder()
                .setModuleDocstring("My Language\n\nBlah blah blah")
                .setFile("//:test.bzl")
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("library_rules.my_library")
                        .setDocString("My language library")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("_my_library").setFile("//:my_library.bzl")
                        )
                )
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("library_rules.my_plugin")
                        .setDocString("My language plugin")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("_my_plugin").setFile("//:my_plugin.bzl")
                        )
                )
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("test_rules.my_test")
                        .setDocString("My language test")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("_my_test").setFile("//:my_test.bzl")
                        )
                )
                .build()

        Truth.assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(3)

        // We expect family summary to be set only on the first rule, to avoid duplication in final
        // rendered output.
        assertThat(ruleDocEntries!!.get("my_library").getRuleFamily()).isEqualTo("My Language")
        assertThat(ruleDocEntries!!.get("my_library").getFamilySummary()).isEqualTo("Blah blah blah")
        assertThat(ruleDocEntries!!.get("my_plugin").getRuleFamily()).isEqualTo("My Language")
        assertThat(ruleDocEntries!!.get("my_plugin").getFamilySummary()).isEmpty()
        assertThat(ruleDocEntries!!.get("my_test").getRuleFamily()).isEqualTo("My Language")
        assertThat(ruleDocEntries!!.get("my_test").getFamilySummary()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun collectModuleInfoDocs_linksCommonAttrsWithEmptyDocstringToCommonType() {
        val moduleInfo: ModuleInfo? =
            ModuleInfo.newBuilder()
                .setModuleDocstring("My Language")
                .setFile("//:test.bzl")
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("library_rules.my_library")
                        .setDocString("My language library")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("_my_library").setFile("//:my_library.bzl")
                        )
                        .addAttribute(
                            AttributeInfo.newBuilder() // Empty docstring
                                .setName("srcs")
                                .setType(AttributeType.LABEL_LIST)
                                .setDefaultValue("[]")
                        )
                        .addAttribute(
                            AttributeInfo.newBuilder() // Empty docstring
                                .setName("deps")
                                .setType(AttributeType.LABEL_LIST)
                                .setDefaultValue("[]")
                        )
                        .addAttribute(
                            AttributeInfo.newBuilder() // Empty docstring
                                .setName("uncommonly_named_attr")
                                .setType(AttributeType.LABEL_LIST)
                                .setDefaultValue("[]")
                        )
                )
                .build()

        Truth.assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(1)

        val attributes: MutableSet<RuleDocumentationAttribute> = ruleDocEntries!!.get("my_library").getAttributes()
        assertThat(getAttribute(attributes, "srcs").isCommonType()).isTrue()
        assertThat(getAttribute(attributes, "srcs").getGeneratedInRule("my_library"))
            .isEqualTo(DocgenConsts.TYPICAL_ATTRIBUTES)
        assertThat(getAttribute(attributes, "deps").isCommonType()).isTrue()
        assertThat(getAttribute(attributes, "deps").getGeneratedInRule("my_library"))
            .isEqualTo(DocgenConsts.TYPICAL_ATTRIBUTES)
        assertThat(getAttribute(attributes, "uncommonly_named_attr").isCommonType()).isFalse()
        assertThat(getAttribute(attributes, "uncommonly_named_attr").getGeneratedInRule("my_library"))
            .isEqualTo("my_library")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun collectModuleInfoDocs_genericRulesFlaggedAsGeneric() {
        val moduleInfo: ModuleInfo? =
            ModuleInfo.newBuilder()
                .setModuleDocstring("Family")
                .setFile("//:test.bzl")
                .addRuleInfo(
                    RuleInfo.newBuilder()
                        .setRuleName("generic_rules.my_rule")
                        .setDocString("My rule")
                        .setOriginKey(
                            OriginKey.newBuilder().setName("my_rule").setFile("//:my_rule.bzl")
                        )
                )
                .build()

        Truth.assertThat(collectModuleInfoDocs(moduleInfo)).isEqualTo(1)
        assertThat(ruleDocEntries!!.get("my_rule").isLanguageSpecific()).isFalse()
        assertThat(ruleDocEntries!!.get("my_rule").getRuleFamily()).isEqualTo("Family")
    }
}
