// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [Attribute].  */
@RunWith(JUnit4::class)
class AttributeTest {
    @org.junit.Test
    fun testBasics() {
        var attr: Attribute = attr("foo", Type.INTEGER).mandatory().value(StarlarkInt.of(3)).build()
        assertThat(attr.name).isEqualTo("foo")
        assertThat(attr.getDefaultValue(null)).isEqualTo(StarlarkInt.of(3))
        assertThat(attr.getType()).isEqualTo(Type.INTEGER)
        assertThat(attr.isMandatory()).isTrue()
        assertThat(attr.isDocumented()).isTrue()
        assertThat(attr.starlarkDefined()).isFalse()
        attr = attr("\$foo", Type.INTEGER).build()
        assertThat(attr.isDocumented()).isFalse()
    }

    @org.junit.Test
    fun testNonEmptyRequiresListType() {
        val e: java.lang.NullPointerException? =
            org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                java.lang.NullPointerException::class.java,
                org.junit.function.ThrowingRunnable {
                    attr("foo", Type.INTEGER).nonEmpty().value(StarlarkInt.of(3)).build()
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("attribute 'foo' must be a list")
    }

    @org.junit.Test
    fun testNonEmpty() {
        val attr: Attribute = attr("foo", BuildType.LABEL_LIST).nonEmpty().legacyAllowAnyFileType().build()
        assertThat(attr.name).isEqualTo("foo")
        assertThat(attr.getType()).isEqualTo(BuildType.LABEL_LIST)
        assertThat(attr.isNonEmpty()).isTrue()
    }

    @org.junit.Test
    fun testSingleArtifactRequiresLabelType() {
        val e: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    attr("foo", Type.INTEGER).singleArtifact().value(StarlarkInt.of(3)).build()
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("attribute 'foo' must be a label-valued type")
    }

    @org.junit.Test
    fun testSettingConfigurationTwiceDisallowed() {
        val builder: Attribute.Builder<String?> =
            attr("x", STRING)
                .mandatory()
                .cfg(ExecutionTransitionFactory.createFactory())
                .undocumented("")
                .value("y")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { builder.cfg(ExecutionTransitionFactory.createFactory()) })
    }

    /**
     * Tests the "convenience factories" (string, label, etc) for default
     * values.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConvenienceFactoriesDefaultValues() {
        assertDefaultValue(StarlarkInt.of(0), attr("x", INTEGER).build())
        assertDefaultValue(StarlarkInt.of(42), attr("x", INTEGER).value(StarlarkInt.of(42)).build())

        assertDefaultValue(
            "",
            attr("x", STRING).build()
        )
        assertDefaultValue(
            "foo",
            attr("x", STRING).value("foo").build()
        )

        val label: Label? = Label.parseCanonical("//foo:bar")
        assertDefaultValue(
            null,
            attr("x", LABEL).legacyAllowAnyFileType().build()
        )
        assertDefaultValue(
            label,
            attr("x", LABEL).legacyAllowAnyFileType().value(label).build()
        )

        val slist: MutableList<String?> = mutableListOf<String?>("foo", "bar")
        assertDefaultValue(
            mutableListOf<Any?>(),
            attr("x", STRING_LIST).build()
        )
        assertDefaultValue(
            slist,
            attr("x", STRING_LIST).value(slist).build()
        )

        val llist: MutableList<Label?> =
            java.util.Arrays.asList<T?>(Label.parseCanonical("//foo:bar"), Label.parseCanonical("//foo:wiz"))
        assertDefaultValue(
            mutableListOf<Any?>(),
            attr("x", LABEL_LIST).legacyAllowAnyFileType().build()
        )
        assertDefaultValue(
            llist,
            attr("x", LABEL_LIST).legacyAllowAnyFileType().value(llist).build()
        )
    }

    /**
     * Tests the "convenience factories" (string, label, etc) for types.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConvenienceFactoriesTypes() {
        assertType(
            INTEGER,
            attr("x", INTEGER).build()
        )
        assertType(INTEGER, attr("x", INTEGER).value(StarlarkInt.of(42)).build())

        assertType(
            STRING,
            attr("x", STRING).build()
        )
        assertType(
            STRING,
            attr("x", STRING).value("foo").build()
        )

        val label: Label? = Label.parseCanonical("//foo:bar")
        assertType(
            LABEL,
            attr("x", LABEL).legacyAllowAnyFileType().build()
        )
        assertType(
            LABEL,
            attr("x", LABEL).legacyAllowAnyFileType().value(label).build()
        )

        val slist: MutableList<String?> = mutableListOf<String?>("foo", "bar")
        assertType(
            STRING_LIST,
            attr("x", STRING_LIST).build()
        )
        assertType(
            STRING_LIST,
            attr("x", STRING_LIST).value(slist).build()
        )

        val llist: MutableList<Label?> =
            java.util.Arrays.asList<T?>(Label.parseCanonical("//foo:bar"), Label.parseCanonical("//foo:wiz"))
        assertType(
            LABEL_LIST,
            attr("x", LABEL_LIST).legacyAllowAnyFileType().build()
        )
        assertType(
            LABEL_LIST,
            attr("x", LABEL_LIST).legacyAllowAnyFileType().value(llist).build()
        )
    }

    @org.junit.Test
    fun testCloneBuilder() {
        val txtFiles: FileTypeSet? = FileTypeSet.of(FileType.of("txt"))
        val ruleClasses: RuleClassNamePredicate = RuleClassNamePredicate.only("mock_rule")

        val parentAttr: Attribute =
            attr("x", LABEL_LIST)
                .allowedFileTypes(txtFiles)
                .mandatory()
                .aspect(TestAspects.SIMPLE_ASPECT)
                .build()

        run {
            val childAttr1: Attribute = parentAttr.cloneBuilder().build()
            assertThat(childAttr1.name).isEqualTo("x")
            assertThat(childAttr1.getAllowedFileTypesPredicate()).isEqualTo(txtFiles)
            assertThat(childAttr1.getAllowedRuleClassObjectPredicate())
                .isEqualTo(com.google.common.base.Predicates.alwaysTrue<Any?>())
            assertThat(childAttr1.isMandatory()).isTrue()
            assertThat(childAttr1.isNonEmpty()).isFalse()
            assertThat(childAttr1.getAspects( /* rule= */null)).hasSize(1)
        }

        run {
            val childAttr2: Attribute =
                parentAttr
                    .cloneBuilder()
                    .nonEmpty()
                    .allowedRuleClasses(ruleClasses)
                    .aspect(TestAspects.ERROR_ASPECT)
                    .build()
            assertThat(childAttr2.name).isEqualTo("x")
            assertThat(childAttr2.getAllowedFileTypesPredicate()).isEqualTo(txtFiles)
            assertThat(childAttr2.getAllowedRuleClassObjectPredicate())
                .isEqualTo(ruleClasses.asPredicateOfRuleClassObject())
            assertThat(childAttr2.isMandatory()).isTrue()
            assertThat(childAttr2.isNonEmpty()).isTrue()
            assertThat(childAttr2.getAspects( /* rule= */null)).hasSize(2)
        }

        // Check if the parent attribute is unchanged
        assertThat(parentAttr.isNonEmpty()).isFalse()
        assertThat(parentAttr.getAllowedRuleClassObjectPredicate()).isEqualTo(com.google.common.base.Predicates.alwaysTrue<Any?>())
    }

    /**
     * Tests that configurability settings are properly received.
     */
    @org.junit.Test
    fun testConfigurability() {
        assertThat(
            attr("foo_configurable", BuildType.LABEL_LIST)
                .legacyAllowAnyFileType()
                .build()
                .isConfigurable()
        )
            .isTrue()
        assertThat(
            attr("foo_nonconfigurable", BuildType.LABEL_LIST)
                .legacyAllowAnyFileType()
                .nonconfigurable("test")
                .build()
                .isConfigurable()
        )
            .isFalse()
    }

    @org.junit.Test
    fun testSplitTransitionProvider() {
        val splitTransitionProvider = TestSplitTransitionProvider()
        val attr: Attribute = attr("foo", LABEL).cfg(splitTransitionProvider).allowedFileTypes().build()
        assertThat(attr.getTransitionFactory().isSplit()).isTrue()
    }

    @org.junit.Test
    fun testExecTransition() {
        val attr: Attribute =
            attr("foo", LABEL)
                .cfg(ExecutionTransitionFactory.createFactory())
                .allowedFileTypes()
                .build()
        assertThat(attr.getTransitionFactory().isTool()).isTrue()
        assertThat(attr.getTransitionFactory().isSplit()).isFalse()
    }

    private class TestSplitTransitionProvider

        : TransitionFactory<AttributeTransitionData?> {
        public override fun create(data: AttributeTransitionData?): SplitTransition {
            return SplitTransition { buildOptions, eventHandler ->
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "test0",
                    buildOptions.clone().underlying(),
                    "test1",
                    buildOptions.clone().underlying()
                )
            }
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ATTRIBUTE
        }

        val isSplit: Boolean
            get() = true
    }

    @org.junit.Test
    fun allowedRuleClassesAndAllowedRuleClassesWithWarningsCannotOverlap() {
        val e: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    attr("x", LABEL_LIST)
                        .allowedRuleClasses("foo", "bar", "baz")
                        .allowedRuleClassesWithWarning("bar")
                        .allowedFileTypes()
                        .build()
                })
        Truth.assertThat(e).hasMessageThat().contains("may not contain the same rule classes")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun factoryEquality() {
        EqualsTester()
            .addEqualityGroup(attr("foo", LABEL).buildPartial(), attr("foo", LABEL).buildPartial())
            .addEqualityGroup(
                attr("foo", LABEL).value(Label.parseCanonicalUnchecked("//a:b")).buildPartial(),
                attr("foo", LABEL).value(Label.parseCanonicalUnchecked("//a:b")).buildPartial()
            )
            .addEqualityGroup(
                attr("foo", NODEP_LABEL).value(Label.parseCanonicalUnchecked("//a:b")).buildPartial(),
                attr("foo", NODEP_LABEL).value(Label.parseCanonicalUnchecked("//a:b")).buildPartial()
            )
            .addEqualityGroup(
                attr("foo", LABEL).value(Label.parseCanonicalUnchecked("//c:d")).buildPartial(),
                attr("foo", LABEL).value(Label.parseCanonicalUnchecked("//c:d")).buildPartial()
            )
            .addEqualityGroup(
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .setDoc("My doc")
                    .buildPartial(),
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .setDoc("My doc")
                    .buildPartial()
            )
            .addEqualityGroup( // PredicateWithMessage does not define any particular equality semantics
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .allowedValues(AllowedValueSet(Label.parseCanonical("//a:b")))
                    .buildPartial()
            )
            .addEqualityGroup(
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .allowedRuleClasses("java_binary")
                    .buildPartial(),
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .allowedRuleClasses("java_binary")
                    .buildPartial()
            )
            .addEqualityGroup(
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .buildPartial(),
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .buildPartial()
            )
            .addEqualityGroup(
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .mandatoryProviders(DefaultInfo.PROVIDER.id())
                    .buildPartial(),
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .mandatoryProviders(DefaultInfo.PROVIDER.id())
                    .buildPartial()
            )
            .addEqualityGroup( // Aspects list builder does not define any particular equality semantics
                attr("foo", LABEL)
                    .value(Label.parseCanonicalUnchecked("//a:b"))
                    .aspect(TestAspects.SIMPLE_ASPECT)
                    .buildPartial()
            )
            .testEquals()
    }

    companion object {
        private fun assertDefaultValue(expected: Any?, attr: Attribute) {
            assertThat(attr.getDefaultValue(null)).isEqualTo(expected)
        }

        private fun assertType(expectedType: Type<*>?, attr: Attribute) {
            assertThat(attr.getType()).isEqualTo(expectedType)
        }
    }
}
