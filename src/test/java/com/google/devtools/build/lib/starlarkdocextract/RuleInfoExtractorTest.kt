// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.Attribute.attr

@RunWith(JUnit4::class)
class RuleInfoExtractorTest : PackageLoadingTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicFunctionality() {
        val ruleClass: RuleClass? =
            Builder("test_rule", RuleClass.Builder.RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))
                .build()
        val extractorContext: ExtractorContext? =
            ExtractorContext.builder()
                .labelRenderer(LabelRenderer.DEFAULT)
                .extractNativelyDefinedAttrs(true)
                .build()
        val ruleInfo: RuleInfo? =
            RuleInfoExtractor.buildRuleInfo(extractorContext, "namespace.test_rule", ruleClass)
        assertThat(ruleInfo)
            .isEqualTo(
                RuleInfo.newBuilder()
                    .setRuleName("namespace.test_rule")
                    .setOriginKey(OriginKey.newBuilder().setName("test_rule").setFile("<native>"))
                    .addAllAttribute(RuleInfoExtractor.IMPLICIT_RULE_ATTRIBUTES.values())
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            .setName("tags")
                            .setType(AttributeType.STRING_LIST)
                            .setDefaultValue("[]")
                            .setMandatory(false)
                            .setNativelyDefined(true)
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allBuiltinAttributeTypesSupported() {
        val context: ExtractorContext? =
            ExtractorContext.builder().labelRenderer(LabelRenderer.DEFAULT).build()
        for (type in BuildTypeTestHelper.getAllBuildTypes( /* publicOnly= */true)) {
            Truth.assertWithMessage("attr type '%s'", type)
                .that(AttributeInfoExtractor.getAttributeType(context, type, "test_attr"))
                .isNotEqualTo(AttributeType.UNKNOWN)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allNativeRulesAreSupported() {
        val extractorContext: ExtractorContext? =
            ExtractorContext.builder()
                .labelRenderer(LabelRenderer.DEFAULT)
                .extractNativelyDefinedAttrs(true)
                .build()
        for (ruleClass in ruleClassProvider.getRuleClassMap().values()) {
            val ruleInfo: RuleInfo =
                RuleInfoExtractor.buildRuleInfo(extractorContext, ruleClass.getName(), ruleClass)
            assertThat(ruleInfo.getRuleName()).isEqualTo(ruleClass.getName())
            assertThat(ruleInfo.getOriginKey().getName()).isEqualTo(ruleClass.getName())
            assertWithMessage("rule '%s'", ruleClass.getName())
                .that(ruleInfo.getOriginKey().getFile())
                .isEqualTo("<native>")
            assertWithMessage("rule '%s'", ruleClass.getName())
                .that(ruleInfo.getAttributeList().getFirst())
                .isEqualTo(RuleInfoExtractor.IMPLICIT_RULE_ATTRIBUTES.get("name"))
            assertWithMessage("rule '%s'", ruleClass.getName())
                .that(ruleInfo.getAttributeList().stream().map(AttributeInfo::getName))
                .containsNoDuplicates()
            assertWithMessage("rule '%s'", ruleClass.getName())
                .that(ruleInfo.getAttributeList().stream().map(AttributeInfo::getDefaultValue))
                .doesNotContain(AttributeInfoExtractor.UNREPRESENTABLE_VALUE)
        }
    }

    companion object {
        private val DUMMY_CONFIGURED_TARGET_FACTORY: RuleClass.ConfiguredTargetFactory<Any?, Any?, java.lang.Exception?> =
            object : ConfiguredTargetFactory<Any?, Any?, java.lang.Exception?>() {
                public override fun create(ruleContext: Any?): Any? {
                    throw java.lang.IllegalStateException()
                }
            }
    }
}
