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

/** Tests for [RuleClass].  */
@RunWith(JUnit4::class)
class RuleClassTest : PackageLoadingTestCase() {
    private class DummyFragment : Fragment()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassBasics() {
        val ruleClassA: RuleClass = createRuleClassA()

        assertThat(ruleClassA.getName()).isEqualTo("ruleA")
        assertThat(ruleClassA.getAttributeProvider().getAttributeCount()).isEqualTo(8)

        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("name")).isEqualTo(0)
        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("my-string-attr")).isEqualTo(1)
        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("my-label-attr")).isEqualTo(2)
        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("my-labellist-attr"))
            .isEqualTo(3)
        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("my-integer-attr")).isEqualTo(4)
        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("my-string-attr2")).isEqualTo(5)
        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("my-stringlist-attr"))
            .isEqualTo(6)
        assertThat(ruleClassA.getAttributeProvider().getAttributeIndex("my-sorted-stringlist-attr"))
            .isEqualTo(7)

        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("name"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(0))
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("my-string-attr"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(1))
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("my-label-attr"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(2))
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("my-labellist-attr"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(3))
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("my-integer-attr"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(4))
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("my-string-attr2"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(5))
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("my-stringlist-attr"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(6))
        assertThat(ruleClassA.getAttributeProvider().getAttributeByName("my-sorted-stringlist-attr"))
            .isEqualTo(ruleClassA.getAttributeProvider().getAttribute(7))

        // default based on type
        assertThat(ruleClassA.getAttributeProvider().getAttribute(0).getDefaultValue(null))
            .isEqualTo("")
        assertThat(ruleClassA.getAttributeProvider().getAttribute(1).getDefaultValue(null))
            .isEqualTo("")
        assertThat(ruleClassA.getAttributeProvider().getAttribute(2).getDefaultValue(null))
            .isEqualTo(Label.parseCanonical("//default:label"))
        assertThat(ruleClassA.getAttributeProvider().getAttribute(3).getDefaultValue(null))
            .isEqualTo(com.google.common.collect.ImmutableList.of<Any?>())
        assertThat(ruleClassA.getAttributeProvider().getAttribute(4).getDefaultValue(null))
            .isEqualTo(StarlarkInt.of(42))
        // default explicitly specified
        assertThat(ruleClassA.getAttributeProvider().getAttribute(5).getDefaultValue(null)).isNull()
        assertThat(ruleClassA.getAttributeProvider().getAttribute(6).getDefaultValue(null))
            .isEqualTo(com.google.common.collect.ImmutableList.of<Any?>())
        assertThat(ruleClassA.getAttributeProvider().getAttribute(7).getDefaultValue(null))
            .isEqualTo(com.google.common.collect.ImmutableList.of<Any?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassInheritance() {
        val ruleClassA: RuleClass = createRuleClassA()
        val ruleClassB: RuleClass = createRuleClassB(ruleClassA)

        assertThat(ruleClassB.getName()).isEqualTo("ruleB")
        assertThat(ruleClassB.getAttributeProvider().getAttributeCount()).isEqualTo(9)

        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("name")).isEqualTo(0)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("my-string-attr")).isEqualTo(1)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("my-label-attr")).isEqualTo(2)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("my-labellist-attr"))
            .isEqualTo(3)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("my-integer-attr")).isEqualTo(4)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("my-string-attr2")).isEqualTo(5)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("my-stringlist-attr"))
            .isEqualTo(6)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("my-sorted-stringlist-attr"))
            .isEqualTo(7)
        assertThat(ruleClassB.getAttributeProvider().getAttributeIndex("another-string-attr"))
            .isEqualTo(8)

        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("name"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(0))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("my-string-attr"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(1))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("my-label-attr"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(2))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("my-labellist-attr"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(3))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("my-integer-attr"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(4))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("my-string-attr2"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(5))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("my-stringlist-attr"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(6))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("my-sorted-stringlist-attr"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(7))
        assertThat(ruleClassB.getAttributeProvider().getAttributeByName("another-string-attr"))
            .isEqualTo(ruleClassB.getAttributeProvider().getAttribute(8))
    }

    private var testBuildfilePath: Path? = null
    private var testRuleLocation: net.starlark.java.syntax.Location? = null

    @Before
    fun setRuleLocation() {
        testBuildfilePath = root.getRelative("testpackage/BUILD")
        testRuleLocation =
            net.starlark.java.syntax.Location.fromFileLineColumn(
                testBuildfilePath.toString(),
                TEST_RULE_DEFINED_AT_LINE,
                0
            )
    }

    private fun createDummyPackageBuilder(): Package.Builder {
        return packageFactory.newPackageBuilder(
            PackageIdentifier.createInMainRepo(TEST_PACKAGE_NAME),
            RootedPath.toRootedPath(root, testBuildfilePath),
            java.util.Optional.empty<T?>(),
            java.util.Optional.empty<T?>(),
            StarlarkSemantics.DEFAULT,  /* repositoryMapping= */
            RepositoryMapping.EMPTY,  /* mainRepositoryMapping= */
            null,  /* cpuBoundSemaphore= */
            null,  /* generatorMap= */
            null,  /* configSettingVisibilityPolicy= */
            null,  /* globber= */
            null
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDeps() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>(),
                true,
                attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
                attr("list2", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
                attr("list3", LABEL_LIST).mandatory().legacyAllowAnyFileType().build()
            )

        // LinkedHashMap -> predictable iteration order for testing
        val attributeValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        attributeValues.put(
            "list1",
            com.google.common.collect.Lists.newArrayList<String?>("//testpackage:dup1", ":dup1", ":nodup")
        )
        attributeValues.put("list2", com.google.common.collect.Lists.newArrayList<String?>(":nodup1", ":nodup2"))
        attributeValues.put(
            "list3",
            com.google.common.collect.Lists.newArrayList<String?>(":dup1", ":dup1", ":dup2", ":dup2")
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        createRule(depsRuleClass, "depsRule", attributeValues)

        Truth.assertThat(eventCollector.count()).isSameInstanceAs(3)
        assertDupError("//testpackage:dup1", "list1", "depsRule")
        assertDupError("//testpackage:dup1", "list3", "depsRule")
        assertDupError("//testpackage:dup2", "list3", "depsRule")
    }

    private fun assertDupError(label: String?, attrName: String?, ruleName: String?) {
        assertContainsEvent(
            String.format(
                "Label '%s' is duplicated in the '%s' attribute of rule '%s'",
                label, attrName, ruleName
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDepsWithinSingleSelectConditionError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build()
            )

        val selectorList1: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableList.of<String?>(":dup1", ":dup1")
                    ), ""
                )
            )

        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("list1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)

        Truth.assertThat(eventCollector.count()).isSameInstanceAs(1)
        assertDupError("//testpackage:dup1", "list1", "depsRule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDepsWithinConditionMultipleSelectsErrors() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build()
            )

        val selectorList1a: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a", com.google.common.collect.ImmutableList.of<String?>(":dup1", "dup1"),
                        "//conditions:b", com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                    ),
                    ""
                )
            )
        val selectorList1b: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:c", com.google.common.collect.ImmutableList.of<String?>(":dup2", "dup2"),
                        "//conditions:d", com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                    ),
                    ""
                )
            )
        val selectorList1: SelectorList? = SelectorList.concat(selectorList1a, selectorList1b)

        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("list1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)

        Truth.assertThat(eventCollector.count()).isSameInstanceAs(2)
        assertDupError("//testpackage:dup1", "list1", "depsRule")
        assertDupError("//testpackage:dup2", "list1", "depsRule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSameDepAcrossMultipleSelectsNoDuplicateNoError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build()
            )

        // ignore duplicatess across selects where values appear duplicated but are not
        val selectorList1a: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a", com.google.common.collect.ImmutableList.of<String?>(":nodup1"),
                        "//conditions:b", com.google.common.collect.ImmutableList.of<String?>(":nodup2")
                    ),
                    ""
                )
            )
        val selectorList1b: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a", com.google.common.collect.ImmutableList.of<String?>(":nodup2"),
                        "//conditions:b", com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                    ),
                    ""
                )
            )
        val selectorList1: SelectorList? = SelectorList.concat(selectorList1a, selectorList1b)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("list1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSameDepAcrossMultipleSelectsIsDuplicateNoError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build()
            )

        // repetition of dup1 is identified at analysis time, not loading time
        val selectorList1a: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a", com.google.common.collect.ImmutableList.of<String?>(":dup1"),
                        "//conditions:b", com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                    ),
                    ""
                )
            )
        val selectorList1b: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a", com.google.common.collect.ImmutableList.of<String?>(":dup1"),
                        "//conditions:b", com.google.common.collect.ImmutableList.of<String?>(":nodup2")
                    ),
                    ""
                )
            )
        val selectorList1: SelectorList? = SelectorList.concat(selectorList1a, selectorList1b)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("list1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSameDepAcrossConditionsInSelectNoError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build()
            )

        val selectorList1: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a", com.google.common.collect.ImmutableList.of<String?>(":nodup1"),
                        "//conditions:b", com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                    ),
                    ""
                )
            )

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("list1", selectorList1)

        createRule(depsRuleClass, "depsRule", attributeValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDepsInLabelListDict() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>(),
                true,
                attr("dict1", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build(),
                attr("dict2", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build(),
                attr("dict3", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build()
            )

        // LinkedHashMap -> predictable iteration order for testing
        val attributeValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        // Duplicates within a key's list should error
        attributeValues.put(
            "dict1",
            com.google.common.collect.ImmutableMap.of<String?, java.util.ArrayList<String?>?>(
                "key1", com.google.common.collect.Lists.newArrayList<String?>("//testpackage:dup1", ":dup1", ":nodup"),
                "key2", com.google.common.collect.Lists.newArrayList<String?>(":nodup1")
            )
        )
        // No duplicates - should pass
        attributeValues.put(
            "dict2",
            com.google.common.collect.ImmutableMap.of<String?, java.util.ArrayList<String?>?>(
                "key1",
                com.google.common.collect.Lists.newArrayList<String?>(":nodup1", ":nodup2")
            )
        )
        // Duplicates within key3's list
        attributeValues.put(
            "dict3",
            com.google.common.collect.ImmutableMap.of<String?, java.util.ArrayList<String?>?>(
                "key3", com.google.common.collect.Lists.newArrayList<String?>(":dup1", ":dup1", ":dup2", ":dup2"),
                "key4", com.google.common.collect.Lists.newArrayList<String?>(":nodup3")
            )
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        createRule(depsRuleClass, "depsRule", attributeValues)

        Truth.assertThat(eventCollector.count()).isSameInstanceAs(3)
        assertDupError("//testpackage:dup1", "dict1", "depsRule")
        assertDupError("//testpackage:dup1", "dict3", "depsRule")
        assertDupError("//testpackage:dup2", "dict3", "depsRule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDepsInLabelListDictWithinSingleSelectConditionError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("dict1", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build()
            )

        val selectorList1: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":dup1", ":dup1")
                        )
                    ),
                    ""
                )
            )

        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("dict1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)

        Truth.assertThat(eventCollector.count()).isSameInstanceAs(1)
        assertDupError("//testpackage:dup1", "dict1", "depsRule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDepsInLabelListDictWithinConditionMultipleSelectsErrors() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("dict1", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build()
            )

        val selectorList1a: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":dup1", "dup1")
                        ),
                        "//conditions:b",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                        )
                    ),
                    ""
                )
            )
        val selectorList1b: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:c",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key2",
                            com.google.common.collect.ImmutableList.of<String?>(":dup2", "dup2")
                        ),
                        "//conditions:d",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key2",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                        )
                    ),
                    ""
                )
            )
        val selectorList1: SelectorList? = SelectorList.concat(selectorList1a, selectorList1b)

        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("dict1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)

        Truth.assertThat(eventCollector.count()).isSameInstanceAs(2)
        assertDupError("//testpackage:dup1", "dict1", "depsRule")
        assertDupError("//testpackage:dup2", "dict1", "depsRule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSameDepInLabelListDictAcrossMultipleSelectsNoDuplicateNoError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("dict1", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build()
            )

        // ignore duplicates across selects where values appear duplicated but are not
        val selectorList1a: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                        ),
                        "//conditions:b",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup2")
                        )
                    ),
                    ""
                )
            )
        val selectorList1b: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup2")
                        ),
                        "//conditions:b",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                        )
                    ),
                    ""
                )
            )
        val selectorList1: SelectorList? = SelectorList.concat(selectorList1a, selectorList1b)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("dict1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSameDepInLabelListDictAcrossMultipleSelectsIsDuplicateNoError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("dict1", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build()
            )

        // repetition of dup1 is identified at analysis time, not loading time
        val selectorList1a: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":dup1")
                        ),
                        "//conditions:b",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                        )
                    ),
                    ""
                )
            )
        val selectorList1b: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":dup1")
                        ),
                        "//conditions:b",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup2")
                        )
                    ),
                    ""
                )
            )
        val selectorList1: SelectorList? = SelectorList.concat(selectorList1a, selectorList1b)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("dict1", selectorList1)
        createRule(depsRuleClass, "depsRule", attributeValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSameDepInLabelListDictAcrossConditionsInSelectNoError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("dict1", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build()
            )

        val selectorList1: SelectorList? =
            SelectorList.of(
                SelectorValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "//conditions:a",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                        ),
                        "//conditions:b",
                        com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                            "key1",
                            com.google.common.collect.ImmutableList.of<String?>(":nodup1")
                        )
                    ),
                    ""
                )
            )

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("dict1", selectorList1)

        createRule(depsRuleClass, "depsRule", attributeValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDepsInLabelListDictAcrossKeysNoError() {
        val depsRuleClass: RuleClass =
            newRuleClass(
                "ruleDeps",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("dict1", LABEL_LIST_DICT).mandatory().legacyAllowAnyFileType().build()
            )

        // LinkedHashMap -> predictable iteration order for testing
        val attributeValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        // Same label appearing in different keys is allowed
        attributeValues.put(
            "dict1",
            com.google.common.collect.ImmutableMap.of<String?, java.util.ArrayList<String?>?>(
                "key1", com.google.common.collect.Lists.newArrayList<String?>(":dep1", ":dep2"),
                "key2", com.google.common.collect.Lists.newArrayList<String?>(":dep1", ":dep3")
            )
        )

        createRule(depsRuleClass, "depsRule", attributeValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateRule() {
        val ruleClassA: RuleClass = createRuleClassA()

        // LinkedHashMap -> predictable iteration order for testing
        val attributeValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        attributeValues.put("my-labellist-attr", "foobar") // wrong type
        attributeValues.put("bogus-attr", "foobar") // no such attr
        attributeValues.put("my-stringlist-attr", mutableListOf<String?>("foo", "bar"))

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val collector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.ERRORS)
        reporter.addHandler(collector)

        val rule: Rule = createRule(ruleClassA, TEST_RULE_NAME, attributeValues)

        // TODO(blaze-team): (2009) refactor to use assertContainsEvent
        val expectedMessages: MutableIterator<String?> =
            mutableListOf<String?>(
                """
                expected value of type 'list(label)' for attribute 'my-labellist-attr' of 'ruleA', but got ${'"'}foobar${'"'} (string)
                """.trimIndent(),
                "no such attribute 'bogus-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-string-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-label-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-labellist-attr' in 'ruleA' rule",
                "missing value for mandatory attribute 'my-string-attr2' in 'ruleA' rule"
            ).iterator()

        for (event in collector) {
            Truth.assertThat(event.getLocation().line()).isEqualTo(TEST_RULE_DEFINED_AT_LINE)
            Truth.assertThat(event.getLocation().file()).isEqualTo(testBuildfilePath.toString())
            Truth.assertThat(event.getMessage())
                .isEqualTo(TEST_RULE_LABEL.substring(1) + ": " + expectedMessages.next())
        }

        // Test basic rule properties:
        assertThat(rule.getRuleClass()).isEqualTo("ruleA")
        assertThat(rule.getName()).isEqualTo(TEST_RULE_NAME)
        assertThat(rule.getLabel().toString()).isEqualTo(TEST_RULE_LABEL.substring(1))

        // Test attribute access:
        val attributes: AttributeMap = RawAttributeMapper.of(rule)
        assertThat(attributes.get("my-label-attr", BuildType.LABEL).toString())
            .isEqualTo("//default:label")
        assertThat(attributes.get("my-integer-attr", Type.INTEGER).toIntUnchecked()).isEqualTo(42)
        // missing attribute -> default chosen based on type
        assertThat(attributes.get("my-string-attr", Type.STRING)).isEmpty()
        assertThat(attributes.get("my-labellist-attr", BuildType.LABEL_LIST)).isEmpty()
        assertThat(attributes.get("my-stringlist-attr", Types.STRING_LIST))
            .isEqualTo(mutableListOf<String?>("foo", "bar"))
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { attributes.get("my-labellist-attr", Type.STRING) })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Attribute my-labellist-attr is of type list(label) "
                        + "and not of type string in ruleA rule //testpackage:my-rule-A"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitOutputs() {
        val ruleClassC: RuleClass =
            newRuleClass(
                "ruleC",
                false,
                false,
                false,
                false,
                false,
                ImplicitOutputsFunction.fromTemplates(
                    "foo-%{name}.bar", "lib%{name}-wazoo-%{name}.mumble", "stuff-%{outs}-bar"
                ),
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("outs", OUTPUT_LIST).build()
            )

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("outs", mutableListOf<String?>("explicit_out"))
        attributeValues.put("name", "myrule")

        val rule: Rule = createRule(ruleClassC, "myrule", attributeValues)

        val set: MutableSet<String?> = HashSet<String?>()
        for (outputFile in rule.getOutputFiles()) {
            set.add(outputFile.getName())
            assertThat(outputFile.getGeneratingRule()).isSameInstanceAs(rule)
        }
        Truth.assertThat(set).containsExactly(
            "foo-myrule.bar", "libmyrule-wazoo-myrule.mumble",
            "stuff-explicit_out-bar", "explicit_out"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitOutsWithBasenameDirname() {
        val ruleClass: RuleClass =
            newRuleClass(
                "ruleClass",
                false,
                false,
                false,
                false,
                false,
                ImplicitOutputsFunction.fromTemplates("%{dirname}lib%{basename}.bar"),
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>(),
                true
            )

        val rule: Rule = createRule(ruleClass, "myRule", com.google.common.collect.ImmutableMap.of<String?, Any?>())
        assertThat(com.google.common.collect.Iterables.getOnlyElement<T?>(rule.getOutputFiles()).getName())
            .isEqualTo("libmyRule.bar")

        val ruleWithSlash: Rule =
            createRule(ruleClass, "myRule/with/slash", com.google.common.collect.ImmutableMap.of<String?, Any?>())
        assertThat(com.google.common.collect.Iterables.getOnlyElement<T?>(ruleWithSlash.getOutputFiles()).getName())
            .isEqualTo("myRule/with/libslash.bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implicitOutputs_usingUnsupportedAttributeType_failsCleanly() {
        val ruleClass: RuleClass =
            newRuleClass(
                "ruleClass",
                false,
                false,
                false,
                false,
                false,
                ImplicitOutputsFunction.fromTemplates("%{truthiness}"),
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("truthiness", BOOLEAN).build()
            )

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("truthiness", true)
        attributeValues.put("name", "myrule")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val myRule: Rule = createRule(ruleClass, "myrule", attributeValues)
        assertThat(myRule.containsErrors()).isTrue()
        assertContainsEvent(
            "In rule //testpackage:myrule: For attribute 'truthiness' in outputs: Attributes of type"
                    + " boolean cannot be used in an outputs substitution template"
        )
    }

    /**
     * Helper routine that checks that a computed default is valid and bound to the expected value.
     */
    @Throws(java.lang.Exception::class)
    private fun checkValidComputedDefault(
        expectedValue: Any?, computedDefault: Attribute,
        attrValueMap: com.google.common.collect.ImmutableMap<String?, Any?>
    ) {
        assertThat(computedDefault.defaultValueUnchecked)
            .isInstanceOf(Attribute.ComputedDefault::class.java)
        val rule: Rule =
            createRule(getRuleClassWithComputedDefault(computedDefault), "myRule", attrValueMap)
        val attributes: AttributeMap = RawAttributeMapper.of(rule)
        assertThat(attributes.get(computedDefault.name, computedDefault.getType()))
            .isEqualTo(expectedValue)
    }

    /**
     * Helper routine that checks that a computed default is invalid due to declared dependency issues
     * and fails with the expected message.
     */
    private fun checkInvalidComputedDefault(computedDefault: Attribute?, expectedMessage: String?) {
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    createRule(
                        getRuleClassWithComputedDefault(computedDefault),
                        "myRule",
                        com.google.common.collect.ImmutableMap.of<String?, Any?>()
                    )
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo(expectedMessage)
    }

    /** Tests computed default values are computed as expected.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputedDefault() {
        val computedDefault: Attribute =
            attr("\$result", BOOLEAN)
                .value(
                    object : ComputedDefault("condition") {
                        public override fun getDefault(rule: AttributeMap): Any {
                            return rule.get("condition", Type.BOOLEAN)
                        }
                    })
                .build()

        checkValidComputedDefault(
            java.lang.Boolean.FALSE,
            computedDefault,
            com.google.common.collect.ImmutableMap.of<String?, Any?>("condition", java.lang.Boolean.FALSE)
        )
        checkValidComputedDefault(
            java.lang.Boolean.TRUE,
            computedDefault,
            com.google.common.collect.ImmutableMap.of<String?, Any?>("condition", java.lang.Boolean.TRUE)
        )
    }

    /**
     * Tests that computed defaults can only read attribute values for configurable attributes that
     * have been explicitly declared.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputedDefaultDeclarations() {
        checkValidComputedDefault(
            java.lang.Boolean.FALSE,
            attr("\$good_default_no_declares", BOOLEAN)
                .value(
                    object : ComputedDefault() {
                        public override fun getDefault(rule: AttributeMap): Any {
                            // OK: not a value check:
                            return rule.isAttributeValueExplicitlySpecified("undeclared")
                        }
                    })
                .build(),
            com.google.common.collect.ImmutableMap.of<String?, Any?>()
        )

        checkValidComputedDefault(
            java.lang.Boolean.FALSE,
            attr("\$good_default_one_declare", BOOLEAN)
                .value(
                    object : ComputedDefault("declared1") {
                        public override fun getDefault(rule: AttributeMap): Any {
                            return rule.get("declared1", Type.BOOLEAN)
                        }
                    })
                .build(),
            com.google.common.collect.ImmutableMap.of<String?, Any?>()
        )

        checkValidComputedDefault(
            java.lang.Boolean.FALSE,
            attr("\$good_default_two_declares", BOOLEAN)
                .value(
                    object : ComputedDefault("declared1", "declared2") {
                        public override fun getDefault(rule: AttributeMap): Any {
                            return rule.get("declared1", Type.BOOLEAN)
                                    && rule.get("declared2", Type.BOOLEAN)
                        }
                    })
                .build(),
            com.google.common.collect.ImmutableMap.of<String?, Any?>()
        )

        checkInvalidComputedDefault(
            attr("\$bad_default_no_declares", BOOLEAN).value(
                object : ComputedDefault() {
                    public override fun getDefault(rule: AttributeMap): Any {
                        return rule.get("declared1", Type.BOOLEAN)
                    }
                }).build(),
            "attribute \"declared1\" isn't available in this computed default context"
        )

        checkInvalidComputedDefault(
            attr("\$bad_default_one_declare", BOOLEAN).value(
                object : ComputedDefault("declared1") {
                    public override fun getDefault(rule: AttributeMap): Any {
                        return rule.get("declared1", Type.BOOLEAN) || rule.get("declared2", Type.BOOLEAN)
                    }
                }).build(),
            "attribute \"declared2\" isn't available in this computed default context"
        )

        checkInvalidComputedDefault(
            attr("\$bad_default_two_declares", BOOLEAN).value(
                object : ComputedDefault("declared1", "declared2") {
                    public override fun getDefault(rule: AttributeMap): Any {
                        return rule.get("condition", Type.BOOLEAN)
                    }
                }).build(),
            "attribute \"condition\" isn't available in this computed default context"
        )
    }

    /**
     * Tests that computed defaults *can* read attribute values for non-configurable attributes
     * without needing to explicitly declare them.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputedDefaultWithNonConfigurableAttributes() {
        checkValidComputedDefault(
            java.lang.Boolean.FALSE,
            attr("\$good_default_reading_undeclared_nonconfigurable_attribute", BOOLEAN)
                .value(
                    object : ComputedDefault() {
                        public override fun getDefault(rule: AttributeMap): Any {
                            return rule.get("nonconfigurable", Type.BOOLEAN)
                        }
                    })
                .build(),
            com.google.common.collect.ImmutableMap.of<String?, Any?>()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputsAreOrdered() {
        val ruleClassC: RuleClass =
            newRuleClass(
                "ruleC",
                false,
                false,
                false,
                false,
                false,
                ImplicitOutputsFunction.fromTemplates("first-%{name}", "second-%{name}", "out-%{outs}"),
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attr("outs", OUTPUT_LIST).build()
            )

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("outs", com.google.common.collect.ImmutableList.of<String?>("third", "fourth"))
        attributeValues.put("name", "myrule")

        val rule: Rule = createRule(ruleClassC, "myrule", attributeValues)

        val actual: MutableList<String?> = java.util.ArrayList<String?>()
        for (outputFile in rule.getOutputFiles()) {
            actual.add(outputFile.getName())
            assertThat(outputFile.getGeneratingRule()).isSameInstanceAs(rule)
        }
        Truth.assertWithMessage("unexpected output set").that(actual).containsExactly(
            "first-myrule",
            "second-myrule", "out-third", "out-fourth", "third", "fourth"
        )
        Truth.assertWithMessage("invalid output ordering").that(actual).containsExactly(
            "first-myrule",
            "second-myrule", "out-third", "out-fourth", "third", "fourth"
        ).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubstitutePlaceholderIntoTemplate() {
        val ruleClass: RuleClass =
            newRuleClass(
                "ruleA",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>(),
                true,
                attr("a", STRING_LIST).mandatory().build(),
                attr("b", STRING_LIST).mandatory().build(),
                attr("c", STRING_LIST).mandatory().build(),
                attr("baz", STRING_LIST).mandatory().build(),
                attr("empty", STRING_LIST).build()
            )

        val attributeValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        attributeValues.put("a", com.google.common.collect.ImmutableList.of<String?>("a", "A"))
        attributeValues.put("b", com.google.common.collect.ImmutableList.of<String?>("b", "B"))
        attributeValues.put("c", com.google.common.collect.ImmutableList.of<String?>("c", "C"))
        attributeValues.put("baz", com.google.common.collect.ImmutableList.of<String?>("baz", "BAZ"))
        attributeValues.put("empty", com.google.common.collect.ImmutableList.of<String?>())

        val rule: AttributeMap? = RawAttributeMapper.of(createRule(ruleClass, "testrule", attributeValues))

        assertThat(substitutePlaceholderIntoTemplate("foo", rule)).containsExactly("foo")
        assertThat(substitutePlaceholderIntoTemplate("foo-%{baz}-bar", rule)).containsExactly(
            "foo-baz-bar", "foo-BAZ-bar"
        ).inOrder()
        assertThat(substitutePlaceholderIntoTemplate("%{a}-%{b}-%{c}", rule)).containsExactly(
            "a-b-c",
            "a-b-C", "a-B-c", "a-B-C", "A-b-c", "A-b-C", "A-B-c", "A-B-C"
        ).inOrder()
        assertThat(substitutePlaceholderIntoTemplate("%{a", rule)).containsExactly("%{a")
        assertThat(substitutePlaceholderIntoTemplate("%{a}}", rule)).containsExactly("a}", "A}")
            .inOrder()
        assertThat(substitutePlaceholderIntoTemplate("x%{a}y%{empty}", rule)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOrderIndependentAttribute() {
        val ruleClassA: RuleClass = createRuleClassA()

        val list: MutableList<String?> = mutableListOf<String?>("foo", "bar", "baz")
        val attributeValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        // mandatory values
        attributeValues.put("my-string-attr", "")
        attributeValues.put("my-label-attr", "//project")
        attributeValues.put("my-string-attr2", "")
        attributeValues.put("my-labellist-attr", mutableListOf<Any?>())
        // to compare the effect of .orderIndependent()
        attributeValues.put("my-stringlist-attr", list)
        attributeValues.put("my-sorted-stringlist-attr", list)

        val rule: Rule = createRule(ruleClassA, "testrule", attributeValues)
        val attributes: AttributeMap = RawAttributeMapper.of(rule)

        assertThat(attributes.get("my-stringlist-attr", Types.STRING_LIST)).isEqualTo(list)
        assertThat(attributes.get("my-sorted-stringlist-attr", Types.STRING_LIST))
            .isEqualTo(mutableListOf<String?>("bar", "baz", "foo"))
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(
        LabelSyntaxException::class,
        java.lang.InterruptedException::class,
        CannotPrecomputeDefaultsException::class
    )
    private fun createRule(ruleClass: RuleClass, name: String?, attributeValues: MutableMap<String?, Any?>): Rule {
        var attributeValues = attributeValues
        val pkgBuilder: Package.Builder = createDummyPackageBuilder()
        val ruleLabel: Label?
        try {
            ruleLabel = pkgBuilder.createLabel(name)
        } catch (e: LabelSyntaxException) {
            throw java.lang.IllegalArgumentException("Rule has illegal label", e)
        }
        attributeValues = ensureNameAttrValuePresent(attributeValues)
        val rule: Rule =
            ruleClass.createRule(
                pkgBuilder,
                ruleLabel,
                BuildLangTypedAttributeValuesMap(attributeValues),
                true,
                com.google.common.collect.ImmutableList.of<E?>(
                    StarlarkThread.callStackEntry(StarlarkThread.TOP_LEVEL, testRuleLocation)
                )
            )
        pkgBuilder.getLocalEventHandler().replayOn(reporter)
        return rule
    }

    @org.junit.Test
    fun testOverrideWithWrongType() {
        val parentRuleClass: RuleClass = createParentRuleClass()

        val childRuleClassBuilder: RuleClass.Builder =
            Builder("child_rule", RuleClassType.NORMAL, false, parentRuleClass)
        val e: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { childRuleClassBuilder.override(attr("attr", INTEGER)) })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "The type of the new attribute 'int' is different from "
                        + "the original one 'string'."
            )
    }

    @org.junit.Test
    fun testOverrideWithRightType() {
        val parentRuleClass: RuleClass = createParentRuleClass()

        val childRuleClassBuilder: RuleClass.Builder = Builder(
            "child_rule", RuleClassType.NORMAL, false, parentRuleClass
        )
        childRuleClassBuilder.override(attr("attr", STRING))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyAndOverrideAttribute() {
        val parentRuleClass: RuleClass = createParentRuleClass()
        val childRuleClass: RuleClass = createChildRuleClass(parentRuleClass)

        val parentValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        val childValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        childValues.put("attr", "somevalue")
        createRule(parentRuleClass, "parent_rule", parentValues)
        createRule(childRuleClass, "child_rule", childValues)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopyAndOverrideAttributeMandatoryMissing() {
        val parentRuleClass: RuleClass = createParentRuleClass()
        val childRuleClass: RuleClass = createChildRuleClass(parentRuleClass)

        val childValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        createRule(childRuleClass, "child_rule", childValues)

        Truth.assertThat(eventCollector.count()).isSameInstanceAs(1)
        assertContainsEvent(
            "//testpackage:child_rule: missing value for mandatory "
                    + "attribute 'attr' in 'child_rule' rule"
        )
    }

    @org.junit.Test
    fun testRequiredFragmentInheritance() {
        val parentRuleClass: RuleClass = createParentRuleClass()
        val childRuleClass: RuleClass = createChildRuleClass(parentRuleClass)
        assertThat(parentRuleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments())
            .containsExactly(DummyFragment::class.java)
        assertThat(childRuleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments())
            .containsExactly(DummyFragment::class.java)
    }

    @org.junit.Test
    fun testBadRuleClassNames() {
        expectError(RuleClassType.NORMAL, "8abc")
        expectError(RuleClassType.NORMAL, "!abc")
        expectError(RuleClassType.NORMAL, "a b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainTypes() {
        val ruleClassBuilder: RuleClass.Builder =
            Builder("ruleClass", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))

        ruleClassBuilder.addToolchainTypes(
            ToolchainTypeRequirement.create(Label.parseCanonical("//toolchain:tc1")),
            ToolchainTypeRequirement.create(Label.parseCanonical("//toolchain:tc2"))
        )

        val ruleClass: RuleClass? = ruleClassBuilder.build()

        assertThat(ruleClass).hasToolchainType("//toolchain:tc1")
        assertThat(ruleClass).hasToolchainType("//toolchain:tc2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionPlatformConstraints() {
        val ruleClassBuilder: RuleClass.Builder =
            Builder("ruleClass", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))

        ruleClassBuilder.addExecutionPlatformConstraints(
            Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2")
        )

        val ruleClass: RuleClass = ruleClassBuilder.build()

        assertThat(ruleClass.getExecutionPlatformConstraints())
            .containsExactly(
                Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionPlatformConstraints_inheritConstraintsFromParent() {
        val parentRuleClass: RuleClass? =
            Builder("\$parentRuleClass", RuleClassType.ABSTRACT, false)
                .add(attr("tags", STRING_LIST))
                .addExecutionPlatformConstraints(
                    Label.parseCanonical("//constraints:cv1"),
                    Label.parseCanonical("//constraints:cv2")
                )
                .build()

        val childRuleClass: RuleClass =
            Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .build()

        assertThat(childRuleClass.getExecutionPlatformConstraints())
            .containsExactly(
                Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionPlatformConstraints_inheritAndAddConstraints() {
        val parentRuleClass: RuleClass? =
            Builder("\$parentRuleClass", RuleClassType.ABSTRACT, false)
                .add(attr("tags", STRING_LIST))
                .build()

        val childRuleClassBuilder: RuleClass.Builder =
            Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .addExecutionPlatformConstraints(
                    Label.parseCanonical("//constraints:cv1"),
                    Label.parseCanonical("//constraints:cv2")
                )

        val childRuleClass: RuleClass = childRuleClassBuilder.build()

        assertThat(childRuleClass.getExecutionPlatformConstraints())
            .containsExactly(
                Label.parseCanonical("//constraints:cv1"), Label.parseCanonical("//constraints:cv2")
            )
    }

    @org.junit.Test
    fun testDeclaredExecGroups() {
        val ruleClassBuilder: RuleClass.Builder =
            Builder("ruleClass", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))

        val toolchain: Label? = Label.parseCanonicalUnchecked("//toolchain")
        val constraint: Label = Label.parseCanonicalUnchecked("//constraint")

        // TODO(https://github.com/bazelbuild/bazel/issues/14726): Add tests of optional toolchains.
        ruleClassBuilder.addExecGroups(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "cherry",
                DeclaredExecGroup.builder()
                    .addToolchainType(ToolchainTypeRequirement.create(toolchain))
                    .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>(constraint))
                    .build()
            ),
            false
        )

        val ruleClass: RuleClass = ruleClassBuilder.build()

        assertThat(ruleClass.getDeclaredExecGroups()).hasSize(1)
        assertThat(ruleClass.getDeclaredExecGroups().get("cherry")).hasToolchainType(toolchain)
        assertThat(ruleClass.getDeclaredExecGroups().get("cherry"))
            .toolchainType(toolchain)
            .isMandatory()
        assertThat(ruleClass.getDeclaredExecGroups().get("cherry")).hasExecCompatibleWith(constraint)
    }

    @org.junit.Test
    fun testBuildSetting_createsDefaultAttribute() {
        val labelFlag: RuleClass =
            Builder("label_flag", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))
                .setBuildSetting(BuildSetting.create(true, NODEP_LABEL))
                .build()
        val stringSetting: RuleClass =
            Builder("string_setting", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))
                .setBuildSetting(BuildSetting.create(false, STRING))
                .build()

        assertThat(
            labelFlag
                .getAttributeProvider()
                .hasAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, NODEP_LABEL)
        )
            .isTrue()
        assertThat(
            stringSetting
                .getAttributeProvider()
                .hasAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, STRING)
        )
            .isTrue()
    }

    @org.junit.Test
    fun testBuildSetting_doesNotCreateDefaultAttributeIfNotBuildSetting() {
        val stringSetting: RuleClass =
            Builder("non_build_setting", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))
                .build()

        assertThat(
            stringSetting
                .getAttributeProvider()
                .hasAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, LABEL)
        )
            .isFalse()
    }

    @org.junit.Test
    fun testBuildTooManyAttributesRejected() {
        val builder: RuleClass.Builder =
            Builder("myclass", RuleClassType.NORMAL,  /*starlark=*/false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))
        for (i in 0..199) {
            builder.add(attr("attr" + i, STRING))
        }

        val expected: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                builder::build
            )

        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo("Rule class myclass declared too many attributes (202 > 200)")
    }

    @org.junit.Test
    fun testBuildTooLongAttributeNameRejected() {
        val expected: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder("myclass", RuleClassType.NORMAL,  /*starlark=*/false)
                        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                        .add(attr("tags", STRING_LIST))
                        .add(attr("x".repeat(150), STRING))
                        .build()
                })

        Truth.assertThat(expected)
            .hasMessageThat()
            .matches("Attribute myclass\\.x{150}'s name is too long \\(150 > 128\\)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageMetadataAlternateName() {
        val noopClass: RuleClass =
            Builder("noop", RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(attr("tags", STRING_LIST))
                .add(attr(RuleClass.APPLICABLE_METADATA_ATTR, LABEL_LIST).legacyAllowAnyFileType())
                .build()
        val attributeValues: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        attributeValues.put("applicable_licenses", com.google.common.collect.Lists.newArrayList<String?>(":info"))
        val noopRule: Rule = createRule(noopClass, "noop", attributeValues)

        assertThat(noopRule.getAttr(RuleClass.APPLICABLE_METADATA_ATTR, LABEL_LIST))
            .isEqualTo(com.google.common.collect.Lists.newArrayList(Label.parseCanonical("//testpackage:info")))
    }

    companion object {
        private val DUMMY_CONFIGURED_TARGET_FACTORY: RuleClass.ConfiguredTargetFactory<Any?, Any?, java.lang.Exception?> =
            RuleClass.ConfiguredTargetFactory { ruleContext ->
                throw java.lang.IllegalStateException()
            }

        private val DUMMY_STACK: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                StarlarkThread.callStackEntry(
                    StarlarkThread.TOP_LEVEL, net.starlark.java.syntax.Location.fromFileLineColumn("BUILD", 10, 1)
                ),
                StarlarkThread.callStackEntry(
                    "bar",
                    net.starlark.java.syntax.Location.fromFileLineColumn("bar.bzl", 42, 1)
                ),
                StarlarkThread.callStackEntry("rule", net.starlark.java.syntax.Location.BUILTIN)
            )

        @Throws(LabelSyntaxException::class)
        private fun createRuleClassA(): RuleClass {
            return newRuleClass(
                "ruleA",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>(),
                true,
                attr("my-string-attr", STRING).mandatory().build(),
                attr("my-label-attr", LABEL)
                    .mandatory()
                    .legacyAllowAnyFileType()
                    .value(Label.parseCanonical("//default:label"))
                    .build(),
                attr("my-labellist-attr", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
                attr("my-integer-attr", INTEGER).value(StarlarkInt.of(42)).build(),
                attr("my-string-attr2", STRING).mandatory().value(null as String?).build(),
                attr("my-stringlist-attr", STRING_LIST).build(),
                attr("my-sorted-stringlist-attr", STRING_LIST).orderIndependent().build()
            )
        }

        private fun createRuleClassB(ruleClassA: RuleClass): RuleClass {
            // emulates attribute inheritance
            val attributes: MutableList<Attribute?> =
                java.util.ArrayList<Any?>(ruleClassA.getAttributeProvider().getAttributes())
            attributes.add(attr("another-string-attr", STRING).mandatory().build())
            return newRuleClass(
                "ruleB",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(),
                true,
                attributes.< T > toArray < T ? > (arrayOfNulls<Attribute>(0))
            )
        }

        // Helper method to paper over how some test cases don't supply the mandatory name attribute for
        // historic reasons.
        private fun ensureNameAttrValuePresent(
            attrValues: MutableMap<String?, Any?>
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            if (attrValues.containsKey("name")) {
                return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(attrValues)
            } else {
                return com.google.common.collect.ImmutableMap.builder<String?, Any?>()
                    .putAll(attrValues)
                    .put("name", "my-name")
                    .buildOrThrow()
            }
        }

        private const val TEST_PACKAGE_NAME = "testpackage"

        private const val TEST_RULE_NAME = "my-rule-A"

        private const val TEST_RULE_DEFINED_AT_LINE = 42

        private val TEST_RULE_LABEL = "@//" + TEST_PACKAGE_NAME + ":" + TEST_RULE_NAME

        /**
         * Helper routine that instantiates a rule class with the given computed default and supporting
         * attributes for the default to reference.
         */
        private fun getRuleClassWithComputedDefault(computedDefault: Attribute?): RuleClass {
            return newRuleClass(
                "ruleClass",
                false,
                false,
                false,
                false,
                false,
                ImplicitOutputsFunction.fromTemplates("empty"),
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>(),
                true,
                attr("condition", BOOLEAN).value(false).build(),
                attr("declared1", BOOLEAN).value(false).build(),
                attr("declared2", BOOLEAN).value(false).build(),
                attr("nonconfigurable", BOOLEAN).nonconfigurable("test").value(false).build(),
                computedDefault
            )
        }

        private fun newRuleClass(
            name: String?,
            starlarkExecutable: Boolean,
            documented: Boolean,
            binaryOutput: Boolean,
            outputsDefaultExecutable: Boolean,
            isAnalysisTest: Boolean,
            implicitOutputsFunction: ImplicitOutputsFunction?,
            transitionFactory: TransitionFactory<RuleTransitionData?>?,
            configuredTargetFactory: ConfiguredTargetFactory<*, *, *>?,
            advertisedProviders: AdvertisedProviderSet?,
            configuredTargetFunction: StarlarkFunction?,
            allowedConfigurationFragments: MutableSet<java.lang.Class<out Fragment?>?>?,
            supportsConstraintChecking: Boolean,
            vararg attributes: Attribute?
        ): RuleClass {
            return RuleClass(
                name,
                DUMMY_STACK,  /* key= */
                name,
                RuleClassType.NORMAL,  /* starlarkParent= */
                null,  /* initializer= */
                null,  /* labelConverterForInitializer= */
                null,  /* isStarlark= */
                starlarkExecutable,  /* starlarkExtensionLabel= */
                null,  /* starlarkDocumentation= */
                null,  /* extendable= */
                false,  /* extendableAllowlist= */
                null,  /* starlarkTestable= */
                false,
                documented,
                binaryOutput,  /* dependencyResolutionRule= */
                false,  /* isMaterializerRule= */
                false,  /* materializerRuleAllowsRealDeps= */
                false,
                outputsDefaultExecutable,
                isAnalysisTest,  /* hasAnalysisTestTransition= */
                false,  /* allowlistCheckers= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* ignoreLicenses= */
                false,
                implicitOutputsFunction,
                transitionFactory,
                configuredTargetFactory,
                advertisedProviders,
                configuredTargetFunction,  /* optionReferenceFunction= */
                RuleClass.NO_OPTION_REFERENCE,  /* ruleDefinitionEnvironmentLabel= */
                null,  /* ruleDefinitionEnvironmentDigest= */
                null,
                Builder()
                    .requiresConfigurationFragments(allowedConfigurationFragments)
                    .build(),
                supportsConstraintChecking,  /* toolchainTypes= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* toolchainResolutionMode= */
                ToolchainResolutionMode.ENABLED,  /* executionPlatformConstraints= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* declaredExecGroups= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                AutoExecGroupsMode.DYNAMIC,
                OutputFile.Kind.FILE,
                if (attributes.size > 0 && attributes[0]!!.equals(RuleClass.NAME_ATTRIBUTE))
                    com.google.common.collect.ImmutableList.< E > copyOf < E ? > (attributes)
                else
                    com.google.common.collect.ImmutableList.builder<Attribute?>()
                        .add(RuleClass.NAME_ATTRIBUTE)
                        .add(*attributes)
                        .build(),  /* buildSetting= */
                null,  /* subrules= */
                com.google.common.collect.ImmutableList.of<E?>()
            )
        }

        private fun createParentRuleClass(): RuleClass {
            return newRuleClass(
                "parent_rule",
                false,
                false,
                false,
                false,
                false,
                SafeImplicitOutputsFunction.NONE,
                null,
                DUMMY_CONFIGURED_TARGET_FACTORY,
                AdvertisedProviderSet.EMPTY,
                null,
                com.google.common.collect.ImmutableSet.of<E?>(DummyFragment::class.java),
                true,
                attr("attr", STRING).build()
            )
        }

        private fun createChildRuleClass(parentRuleClass: RuleClass?): RuleClass {
            val childRuleClassBuilder: RuleClass.Builder = Builder(
                "child_rule", RuleClassType.NORMAL, false, parentRuleClass
            )
            return childRuleClassBuilder.override(
                childRuleClassBuilder
                    .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                    .copy("attr").mandatory()
            )
                .add(attr("tags", STRING_LIST))
                .build()
        }

        private fun expectError(type: RuleClassType, name: String?) {
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { type.checkName(name) })
        }
    }
}
