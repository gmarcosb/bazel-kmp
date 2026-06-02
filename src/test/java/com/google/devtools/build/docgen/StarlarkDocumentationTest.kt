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
package com.google.devtools.build.docgen

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for Starlark documentation.  */
@RunWith(JUnit4::class)
class StarlarkDocumentationTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkRuleClassBuiltInItemsAreDocumented() {
        checkStarlarkTopLevelEnvItemsAreDocumented(StarlarkGlobalsImpl.INSTANCE.getFixedBzlToplevels())
    }

    @Throws(java.lang.Exception::class)
    private fun checkStarlarkTopLevelEnvItemsAreDocumented(globals: MutableMap<String?, Any?>) {
        val allPages: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?> =
            StarlarkDocumentationCollector.getAllDocPages(expander, com.google.common.collect.ImmutableList.of<E?>())
        val documentedItems: com.google.common.collect.ImmutableSet<String?> =
            java.util.stream.Stream.concat<Any?>(
                allPages.get(Category.GLOBAL_FUNCTION).stream()
                    .flatMap<Any?>(java.util.function.Function { p: StarlarkDocPage? -> p.getMembers().stream() }),
                allPages.entrySet().stream()
                    .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>? ->
                        !e.getKey().equals(Category.GLOBAL_FUNCTION)
                    })
                    .flatMap<StarlarkDocPage?>(java.util.function.Function { e: MutableMap.MutableEntry<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage?>?>? ->
                        e.getValue().stream()
                    })
            )
                .filter(java.util.function.Predicate { m: Any? -> !m.getDocumentation().isEmpty() })
                .map<Any?>(StarlarkDoc::getName)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

        Truth.assertThat(
            com.google.common.collect.Sets.difference<String?>(
                com.google.common.collect.Sets.difference<String?>(
                    globals.keySet(),
                    documentedItems
                ),  // These constants are currently undocumented.
                // If they need documentation, the easiest approach would be
                // to hard-code it in StarlarkDocumentationCollector.
                com.google.common.collect.ImmutableSet.of<String?>("True", "False", "None")
            )
        )
            .containsExactlyElementsIn(DEPRECATED_OR_EXPERIMENTAL_UNDOCUMENTED_TOP_LEVEL_SYMBOLS)
    }

    // TODO(bazel-team): come up with better Starlark specific tests.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectJavaMethodsAreGenerated() {
        Truth.assertThat(collect(StarlarkRuleContext::class.java)).isNotEmpty()
    }

    /** MockClassA  */
    @StarlarkBuiltin(
        name = "MockClassA",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassA"
    )
    private open class MockClassA : StarlarkValue {
        @StarlarkMethod(name = "get", doc = "MockClassA#get")
        open fun get(): Int {
            return 0
        }
    }

    /** MockClassD  */
    @StarlarkBuiltin(
        name = "MockClassD",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassD"
    )
    @Suppress("unused") // test code
    private class MockClassD : StarlarkValue {
        @StarlarkMethod(
            name = "test",
            doc = "MockClassD#test",
            parameters = [net.starlark.java.annot.Param(name = "a"), net.starlark.java.annot.Param(name = "b"), net.starlark.java.annot.Param(
                name = "c",
                named = true,
                positional = false
            ), net.starlark.java.annot.Param(name = "d", named = true, positional = false, defaultValue = "1")]
        )
        fun test(a: Int, b: Int, c: Int, d: Int): Int {
            return 0
        }
    }

    /** MockClassE  */
    @StarlarkBuiltin(
        name = "MockClassE",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassE"
    )
    private class MockClassE : MockClassA() {
        override fun get(): Int {
            return 1
        }
    }

    /** MockClassF  */
    @StarlarkBuiltin(
        name = "MockClassF",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassF"
    )
    @Suppress("unused") // test code
    private class MockClassF : StarlarkValue {
        @StarlarkMethod(
            name = "test",
            doc = "MockClassF#test",
            parameters = [net.starlark.java.annot.Param(
                name = "a",
                named = false,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "b",
                named = true,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "c",
                named = true,
                positional = false
            ), net.starlark.java.annot.Param(name = "d", named = true, positional = false, defaultValue = "1")],
            extraPositionals = net.starlark.java.annot.Param(name = "myArgs")
        )
        fun test(a: Int, b: Int, c: Int, d: Int, args: net.starlark.java.eval.Sequence<*>?): Int {
            return 0
        }
    }

    /** MockClassG  */
    @StarlarkBuiltin(
        name = "MockClassG",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassG"
    )
    @Suppress("unused") // test code
    private class MockClassG : StarlarkValue {
        @StarlarkMethod(
            name = "test",
            doc = "MockClassG#test",
            parameters = [net.starlark.java.annot.Param(
                name = "a",
                named = false,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "b",
                named = true,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "c",
                named = true,
                positional = false
            ), net.starlark.java.annot.Param(name = "d", named = true, positional = false, defaultValue = "1")],
            extraKeywords = net.starlark.java.annot.Param(name = "myKwargs")
        )
        fun test(a: Int, b: Int, c: Int, d: Int, kwargs: Dict<*, *>?): Int {
            return 0
        }
    }

    /** MockClassH  */
    @StarlarkBuiltin(
        name = "MockClassH",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassH"
    )
    @Suppress("unused") // test code
    private class MockClassH : StarlarkValue {
        @StarlarkMethod(
            name = "test",
            doc = "MockClassH#test",
            parameters = [net.starlark.java.annot.Param(
                name = "a",
                named = false,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "b",
                named = true,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "c",
                named = true,
                positional = false
            ), net.starlark.java.annot.Param(name = "d", named = true, positional = false, defaultValue = "1")],
            extraPositionals = net.starlark.java.annot.Param(name = "myArgs"),
            extraKeywords = net.starlark.java.annot.Param(name = "myKwargs")
        )
        fun test(a: Int, b: Int, c: Int, d: Int, args: net.starlark.java.eval.Sequence<*>?, kwargs: Dict<*, *>?): Int {
            return 0
        }
    }

    /** MockClassI  */
    @StarlarkBuiltin(
        name = "MockClassI",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassI"
    )
    @Suppress("unused") // test code
    private class MockClassI : StarlarkValue {
        @StarlarkMethod(
            name = "test",
            doc = "MockClassI#test",
            parameters = [net.starlark.java.annot.Param(
                name = "a",
                named = false,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "b",
                named = true,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "c",
                named = true,
                positional = false
            ), net.starlark.java.annot.Param(
                name = "d",
                named = true,
                positional = false,
                defaultValue = "1"
            ), net.starlark.java.annot.Param(
                name = "e",
                named = true,
                positional = false,
                documented = false,
                defaultValue = "2"
            )],
            extraPositionals = net.starlark.java.annot.Param(name = "myArgs")
        )
        fun test(a: Int, b: Int, c: Int, d: Int, e: Int, args: net.starlark.java.eval.Sequence<*>?): Int {
            return 0
        }
    }

    /**
     * MockGlobalLibrary. While nothing directly depends on it, a test method in
     * StarlarkDocumentationTest checks all of the classes under a wide classpath and ensures this one
     * shows up.
     */
    @com.google.devtools.build.docgen.annot.GlobalMethods(environment = com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BZL)
    @Suppress("unused")
    private class MockGlobalLibrary {
        @StarlarkMethod(
            name = "MockGlobalCallable",
            doc = "GlobalCallable documentation",
            parameters = [net.starlark.java.annot.Param(
                name = "a",
                named = false,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "b",
                named = true,
                positional = true
            ), net.starlark.java.annot.Param(
                name = "c",
                named = true,
                positional = false
            ), net.starlark.java.annot.Param(name = "d", named = true, positional = false, defaultValue = "1")],
            extraPositionals = net.starlark.java.annot.Param(name = "myArgs"),
            extraKeywords = net.starlark.java.annot.Param(name = "myKwargs")
        )
        fun test(a: Int, b: Int, c: Int, d: Int, args: net.starlark.java.eval.Sequence<*>?, kwargs: Dict<*, *>?): Int {
            return 0
        }
    }

    /** MockClassWithContainerReturnValues  */
    @StarlarkBuiltin(
        name = "MockClassWithContainerReturnValues",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassWithContainerReturnValues"
    )
    private class MockClassWithContainerReturnValues : StarlarkValue {
        @StarlarkMethod(name = "depset", doc = "depset")
        fun  /*<Integer>*/getNestedSet(): Depset? {
            return null
        }

        @StarlarkMethod(name = "tuple", doc = "tuple")
        fun getTuple(): Tuple? {
            return null
        }

        @StarlarkMethod(name = "immutable", doc = "immutable")
        fun getImmutableList(): com.google.common.collect.ImmutableList<Int?>? {
            return null
        }

        @StarlarkMethod(name = "mutable", doc = "mutable")
        fun getMutableList(): StarlarkList<Int?>? {
            return null
        }

        @StarlarkMethod(name = "starlark", doc = "starlark")
        fun getStarlarkList(): net.starlark.java.eval.Sequence<Int?>? {
            return null
        }
    }

    /** MockClassCommonNameOne  */
    @StarlarkBuiltin(
        name = "MockClassCommonName",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassCommonName"
    )
    private open class MockClassCommonNameOne : StarlarkValue {
        @StarlarkMethod(name = "one", doc = "one")
        fun one(): Int {
            return 1
        }
    }

    /** SubclassOfMockClassCommonNameOne  */
    @StarlarkBuiltin(
        name = "MockClassCommonName",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassCommonName"
    )
    private class SubclassOfMockClassCommonNameOne : MockClassCommonNameOne() {
        @StarlarkMethod(name = "two", doc = "two")
        fun two(): Int {
            return 1
        }
    }

    /** PointsToCommonNameOneWithSubclass  */
    @StarlarkBuiltin(
        name = "PointsToCommonNameOneWithSubclass",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "PointsToCommonNameOneWithSubclass"
    )
    private class PointsToCommonNameOneWithSubclass : StarlarkValue {
        @StarlarkMethod(name = "one", doc = "one")
        fun getOne(): MockClassCommonNameOne? {
            return null
        }

        @StarlarkMethod(name = "one_subclass", doc = "one_subclass")
        fun getOneSubclass(): SubclassOfMockClassCommonNameOne? {
            return null
        }
    }

    /** MockClassCommonNameOneUndocumented  */
    @StarlarkBuiltin(name = "MockClassCommonName", documented = false, doc = "")
    private class MockClassCommonNameUndocumented : StarlarkValue {
        @StarlarkMethod(name = "two", doc = "two")
        fun two(): Int {
            return 1
        }
    }

    /** PointsToCommonNameAndUndocumentedModule  */
    @StarlarkBuiltin(
        name = "PointsToCommonNameAndUndocumentedModule",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "PointsToCommonNameAndUndocumentedModule"
    )
    private class PointsToCommonNameAndUndocumentedModule : StarlarkValue {
        @StarlarkMethod(name = "one", doc = "one")
        fun getOne(): MockClassCommonNameOne? {
            return null
        }

        @StarlarkMethod(name = "undocumented_module", doc = "undocumented_module")
        fun getUndocumented(): MockClassCommonNameUndocumented? {
            return null
        }
    }

    /** A module which has a selfCall method which constructs copies of MockClassA.  */
    @StarlarkBuiltin(
        name = "MockClassWithSelfCallConstructor",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "MockClassWithSelfCallConstructor"
    )
    private class MockClassWithSelfCallConstructor : StarlarkValue {
        @StarlarkMethod(name = "one", doc = "one")
        fun getOne(): MockClassCommonNameOne? {
            return null
        }

        @StarlarkMethod(name = "makeMockClassA", selfCall = true, doc = "makeMockClassA")
        @com.google.devtools.build.docgen.annot.StarlarkConstructor
        fun makeMockClassA(): MockClassA {
            return com.google.devtools.build.docgen.StarlarkDocumentationTest.MockClassA()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkCallableParameters() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(com.google.devtools.build.docgen.StarlarkDocumentationTest.MockClassD::class.java)
        Truth.assertThat(objects.get(Category.BUILTIN)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.BUILTIN).get(0)
        assertThat(moduleDoc.getDocumentation()).isEqualTo("MockClassD")
        assertThat(moduleDoc.getMembers()).hasSize(1)
        val methodDoc: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(methodDoc.getDocumentation()).isEqualTo("MockClassD#test")
        assertThat(methodDoc.getSignature())
            .isEqualTo(
                "<a class=\"anchor\" href=\"../core/int.html\">int</a> MockClassD.test(a, b, *, c,"
                        + " d=1)"
            )
        assertThat(methodDoc.getParams()).hasSize(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkCallableParametersAndArgs() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(MockClassF::class.java)
        Truth.assertThat(objects.get(Category.BUILTIN)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.BUILTIN).get(0)
        assertThat(moduleDoc.getDocumentation()).isEqualTo("MockClassF")
        assertThat(moduleDoc.getMembers()).hasSize(1)
        val methodDoc: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(methodDoc.getDocumentation()).isEqualTo("MockClassF#test")
        assertThat(methodDoc.getSignature())
            .isEqualTo(
                "<a class=\"anchor\" href=\"../core/int.html\">int</a> "
                        + "MockClassF.test(a, b, *myArgs, c, d=1)"
            )
        assertThat(methodDoc.getParams()).hasSize(5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkCallableParametersAndKwargs() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(MockClassG::class.java)
        Truth.assertThat(objects.get(Category.BUILTIN)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.BUILTIN).get(0)
        assertThat(moduleDoc.getDocumentation()).isEqualTo("MockClassG")
        assertThat(moduleDoc.getMembers()).hasSize(1)
        val methodDoc: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(methodDoc.getDocumentation()).isEqualTo("MockClassG#test")
        assertThat(methodDoc.getSignature())
            .isEqualTo(
                "<a class=\"anchor\" href=\"../core/int.html\">int</a> "
                        + "MockClassG.test(a, b, *, c, d=1, **myKwargs)"
            )
        assertThat(methodDoc.getParams()).hasSize(5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkCallableParametersAndArgsAndKwargs() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(MockClassH::class.java)
        Truth.assertThat(objects.get(Category.BUILTIN)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.BUILTIN).get(0)
        assertThat(moduleDoc.getDocumentation()).isEqualTo("MockClassH")
        assertThat(moduleDoc.getMembers()).hasSize(1)
        val methodDoc: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(methodDoc.getDocumentation()).isEqualTo("MockClassH#test")
        assertThat(methodDoc.getSignature())
            .isEqualTo(
                "<a class=\"anchor\" href=\"../core/int.html\">int</a> "
                        + "MockClassH.test(a, b, *myArgs, c, d=1, **myKwargs)"
            )
        assertThat(methodDoc.getParams()).hasSize(6)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkUndocumentedParameters() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(MockClassI::class.java)
        Truth.assertThat(objects.get(Category.BUILTIN)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.BUILTIN).get(0)
        assertThat(moduleDoc.getDocumentation()).isEqualTo("MockClassI")
        assertThat(moduleDoc.getMembers()).hasSize(1)
        val methodDoc: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(methodDoc.getDocumentation()).isEqualTo("MockClassI#test")
        assertThat(methodDoc.getSignature())
            .isEqualTo(
                "<a class=\"anchor\" href=\"../core/int.html\">int</a> "
                        + "MockClassI.test(a, b, *myArgs, c, d=1)"
            )
        assertThat(methodDoc.getParams()).hasSize(5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkGlobalLibraryCallable() {
        val topLevel: StarlarkDocPage =
            StarlarkDocumentationCollector.getAllDocPages(expander, com.google.common.collect.ImmutableList.of<E?>())
                .get(Category.GLOBAL_FUNCTION)
                .stream()
                .filter({ p ->
                    p.getTitle().equals(com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BZL.title)
                })
                .findAny()
                .get()

        var foundGlobalLibrary = false
        for (methodDoc in topLevel.getMembers()) {
            if (methodDoc.getName().equals("MockGlobalCallable")) {
                assertThat(methodDoc.getDocumentation()).isEqualTo("GlobalCallable documentation")
                assertThat(methodDoc.getSignature())
                    .isEqualTo(
                        "<a class=\"anchor\" href=\"../core/int.html\">int</a> "
                                + "MockGlobalCallable(a, b, *myArgs, c, d=1, **myKwargs)"
                    )
                foundGlobalLibrary = true
                break
            }
        }
        Truth.assertThat(foundGlobalLibrary).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkCallableOverriding() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(
                com.google.devtools.build.docgen.StarlarkDocumentationTest.MockClassA::class.java,
                MockClassE::class.java
            )
        val moduleDoc: StarlarkDocPage =
            objects.get(Category.BUILTIN).stream()
                .filter(java.util.function.Predicate { p: StarlarkDocPage -> p.getTitle().equals("MockClassE") })
                .findAny()
                .get()
        assertThat(moduleDoc.getDocumentation()).isEqualTo("MockClassE")
        assertThat(moduleDoc.getMembers()).hasSize(1)
        val methodDoc: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(methodDoc.getDocumentation()).isEqualTo("MockClassA#get")
        assertThat(methodDoc.getSignature())
            .isEqualTo("<a class=\"anchor\" href=\"../core/int.html\">int</a> MockClassE.get()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkContainerReturnTypesWithoutAnnotations() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(MockClassWithContainerReturnValues::class.java)
        Truth.assertThat(objects.get(Category.BUILTIN)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.BUILTIN).get(0)
        val methods: com.google.common.collect.ImmutableList<out MemberDoc?> = moduleDoc.getMembers()

        val signatures: MutableList<String?> =
            methods.stream().map<Any?> { m: MemberDoc? -> m.getSignature() }.collect(Collectors.toList())
        Truth.assertThat(signatures).hasSize(5)
        Truth.assertThat(signatures)
            .contains(
                "<a class=\"anchor\" href=\"../builtins/depset.html\">depset</a> "
                        + "MockClassWithContainerReturnValues.depset()"
            )
        Truth.assertThat(signatures)
            .contains(
                "<a class=\"anchor\" href=\"../core/tuple.html\">tuple</a> "
                        + "MockClassWithContainerReturnValues.tuple()"
            )
        Truth.assertThat(signatures)
            .contains(
                "<a class=\"anchor\" href=\"../core/list.html\">list</a> "
                        + "MockClassWithContainerReturnValues.immutable()"
            )
        Truth.assertThat(signatures)
            .contains(
                "<a class=\"anchor\" href=\"../core/list.html\">list</a> "
                        + "MockClassWithContainerReturnValues.mutable()"
            )
        Truth.assertThat(signatures)
            .contains(
                "<a class=\"anchor\" href=\"../core/list.html\">sequence</a> "
                        + "MockClassWithContainerReturnValues.starlark()"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocumentedModuleTakesPrecedence() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(
                PointsToCommonNameAndUndocumentedModule::class.java,
                MockClassCommonNameOne::class.java,
                MockClassCommonNameUndocumented::class.java
            )
        val methods: com.google.common.collect.ImmutableList<MemberDoc?> =
            objects.get(Category.BUILTIN).stream()
                .filter(java.util.function.Predicate { p: StarlarkDocPage ->
                    p.getTitle().equals("MockClassCommonName")
                })
                .findAny()
                .get()
                .getMembers()
        val methodNames: MutableList<String?> =
            methods.stream().map<Any?>(java.util.function.Function { m: MemberDoc? -> m.getName() })
                .collect(Collectors.toList())
        Truth.assertThat(methodNames).containsExactly("one")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocumentModuleSubclass() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(
                PointsToCommonNameOneWithSubclass::class.java,
                MockClassCommonNameOne::class.java,
                SubclassOfMockClassCommonNameOne::class.java
            )
        val methods: com.google.common.collect.ImmutableList<MemberDoc?> =
            objects.get(Category.BUILTIN).stream()
                .filter(java.util.function.Predicate { p: StarlarkDocPage ->
                    p.getTitle().equals("MockClassCommonName")
                })
                .findAny()
                .get()
                .getMembers()
        val methodNames: MutableList<String?> =
            methods.stream().map<Any?>(java.util.function.Function { m: MemberDoc? -> m.getName() })
                .collect(Collectors.toList())
        Truth.assertThat(methodNames).containsExactly("one", "two")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocumentSelfcallConstructor() {
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(
                com.google.devtools.build.docgen.StarlarkDocumentationTest.MockClassA::class.java,
                MockClassWithSelfCallConstructor::class.java
            )
        val methods: com.google.common.collect.ImmutableList<MemberDoc?> =
            objects.get(Category.BUILTIN).stream()
                .filter(java.util.function.Predicate { p: StarlarkDocPage -> p.getTitle().equals("MockClassA") })
                .findAny()
                .get()
                .getMembers()
        val firstMethod: MemberDoc? = methods.getFirst()
        assertThat(firstMethod).isInstanceOf(AnnotStarlarkConstructorMethodDoc::class.java)

        val methodNames: MutableList<String?> =
            methods.stream().map<Any?>(java.util.function.Function { m: MemberDoc? -> m.getName() })
                .collect(Collectors.toList())
        Truth.assertThat(methodNames).containsExactly("MockClassA", "get")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoStructBasicFunctionality() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//pkg:test.bzl",
                """
            def _bar(a, *args, b = 42, **kwargs):
                '''Blah blah blah'''
                pass
            #: Foo module.
            foo = struct(bar = _bar)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        Truth.assertThat(objects.get(Category.TOP_LEVEL_MODULE)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.TOP_LEVEL_MODULE).getFirst()
        assertThat(moduleDoc.getDocumentation()).isEqualTo("Foo module.")
        assertThat(moduleDoc.getLoadStatement()).isEqualTo("load(\"//pkg:test.bzl\", \"foo\")")
        assertThat(moduleDoc.getSourceFile()).isEqualTo("pkg/test.bzl")
        assertThat(moduleDoc.getConstructor()).isNull()
        assertThat(moduleDoc.getMembers()).hasSize(1)
        val methodDoc: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(methodDoc.getDocumentation()).isEqualTo("Blah blah blah")
        assertThat(methodDoc.getSignature()).isEqualTo("unknown foo.bar(a, *args, b=42, **kwargs)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoFunctionParamDocs() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//pkg:test.bzl",
                """
            def _func(name, *extra_names, answer=42, vals={}, **kwargs):
                '''Blah blah blah

                Args:
                  name: (string): Entity name.
                  *extra_names: Extra names.
                  answer: (int | None) Expected answer.
                  vals: (dict[string, int]) Dict of values.
                '''
                pass
            #: Foo module.
            foo = struct(func = _func)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        val moduleDoc: StarlarkDocPage = objects.get(Category.TOP_LEVEL_MODULE).getFirst()
        // Note that getParams returns parameters in calling convention order, with the varargs
        // parameter in the position immediately before kwargs. (Same as in Stardoc proto output, and in
        // AnnotStarlarkMethodDoc::getParams.)
        assertThat(moduleDoc.getMembers().getFirst().getParams().stream().map(ParamDoc::getName))
            .containsExactly("name", "answer", "vals", "extra_names", "kwargs")
            .inOrder()
        assertThat(moduleDoc.getMembers().getFirst().getParams().stream().map(ParamDoc::getKind))
            .containsExactly(
                ParamDoc.Kind.ORDINARY,
                ParamDoc.Kind.KEYWORD_ONLY,
                ParamDoc.Kind.KEYWORD_ONLY,
                ParamDoc.Kind.VARARGS,
                ParamDoc.Kind.KWARGS
            )
            .inOrder()
        assertThat(
            moduleDoc.getMembers().getFirst().getParams().stream().map(ParamDoc::getDocumentation)
        )
            .containsExactly("Entity name.", "Expected answer.", "Dict of values.", "Extra names.", "")
            .inOrder()
        assertThat(moduleDoc.getMembers().getFirst().getParams().stream().map(ParamDoc::getType))
            .containsExactly(
                "<code><a class=\"anchor\" href=\"../core/string.html\">string</a></code>",
                "<code><a class=\"anchor\" href=\"../core/int.html\">int</a> | None</code>",
                ("<code><a class=\"anchor\" href=\"../core/dict.html\">dict</a>[<a class=\"anchor\""
                        + " href=\"../core/string.html\">string</a>, <a class=\"anchor\""
                        + " href=\"../core/int.html\">int</a>]</code>"),
                "",
                ""
            )
            .inOrder()
        assertThat(
            moduleDoc.getMembers().getFirst().getParams().stream().map(ParamDoc::getDefaultValue)
        )
            .containsExactly("", "42", "{}", "", "")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoFunctionReturnsDocs() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//pkg:test.bzl",
                """
            def _func(**kwargs):
                '''Blah blah blah

                Returns:
                  (set[string]) The answers. Not always accurate.
                '''
                pass
            #: Foo module.
            foo = struct(func = _func)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        val moduleDoc: StarlarkDocPage = objects.get(Category.TOP_LEVEL_MODULE).getFirst()
        assertThat(moduleDoc.getMembers().getFirst().getReturnType())
            .isEqualTo(
                "<code><a class=\"anchor\" href=\"../core/set.html\">set</a>[<a class=\"anchor\""
                        + " href=\"../core/string.html\">string</a>]</code>"
            )
        assertThat(moduleDoc.getMembers().getFirst().getReturnsStanza())
            .isEqualTo("The answers. Not always accurate.")
        // Unused for Stardoc proto-based documentation.
        assertThat(moduleDoc.getMembers().getFirst().getReturnTypeExtraMessage()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoFunctionDeprecatedDocs() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//pkg:test.bzl",
                """
            def _func(**kwargs):
                '''Blah blah blah

                Deprecated:
                  Don't use this function!
                '''
                pass
            #: Foo module.
            foo = struct(func = _func)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        val moduleDoc: StarlarkDocPage = objects.get(Category.TOP_LEVEL_MODULE).getFirst()
        assertThat(moduleDoc.getMembers().getFirst().getDeprecatedStanza())
            .isEqualTo("Don't use this function!")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoStructMembersSorted() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//:test.bzl",
                """
            #: Foo module.
            foo = struct(c = lambda x: x, a = lambda x: x, b = lambda x: x)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        Truth.assertThat(objects.get(Category.TOP_LEVEL_MODULE)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.TOP_LEVEL_MODULE).getFirst()
        assertThat(moduleDoc.getMembers().stream().map(MemberDoc::getName))
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoMultipleStructsInOneFile() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//:test.bzl",
                """
            #: Foo module.
            foo = struct(bar = lambda x: x)
            #: Baz module.
            baz = struct(qux = lambda x: x)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        Truth.assertThat(objects.get(Category.TOP_LEVEL_MODULE).stream().map<Any?>(StarlarkDocPage::getName))
            .containsExactly("foo", "baz")
        Truth.assertThat(
            objects.get(Category.TOP_LEVEL_MODULE).stream().map<Any?>(StarlarkDocPage::getDocumentation)
        )
            .containsExactly("Foo module.", "Baz module.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoMultipleFiles() {
        val fooModuleInfo: ModuleInfo =
            getModuleInfo(
                "//:foo.bzl",
                """
            #: Foo module.
            foo = struct(bar = lambda x: x)
            
            """.trimIndent()
            )
        val bazModuleInfo: ModuleInfo =
            getModuleInfo(
                "//:baz.bzl",
                """
            #: Bar module.
            baz = struct(qux = lambda x: x)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(fooModuleInfo, bazModuleInfo)
        Truth.assertThat(objects.get(Category.TOP_LEVEL_MODULE).stream().map<Any?>(StarlarkDocPage::getName))
            .containsExactly("foo", "baz")
        Truth.assertThat(objects.get(Category.TOP_LEVEL_MODULE).stream().map<Any?>(StarlarkDocPage::getSourceFile))
            .containsExactly("foo.bzl", "baz.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoProviderBasicFunctionality() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//pkg:my_info.bzl",
                """
            MyInfo = provider(doc = "My info.", fields = ["a", "b"])
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        Truth.assertThat(objects.get(Category.PROVIDER)).hasSize(1)
        val providerDoc: StarlarkDocPage = objects.get(Category.PROVIDER).getFirst()
        assertThat(providerDoc.getDocumentation()).isEqualTo("My info.")
        assertThat(providerDoc.getLoadStatement()).isEqualTo("load(\"//pkg:my_info.bzl\", \"MyInfo\")")
        assertThat(providerDoc.getSourceFile()).isEqualTo("pkg/my_info.bzl")
        assertThat(providerDoc.getConstructor()).isNull()
        assertThat(providerDoc.getMembers()).hasSize(2)
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::getName))
            .containsExactly("a", "b")
            .inOrder()
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::isCallable))
            .containsExactly(false, false)
            .inOrder()
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::getReturnType))
            .containsExactly("unknown", "unknown")
            .inOrder()
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::getDocumentation))
            .containsExactly("", "")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoProviderFieldDocs() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//pkg:my_info.bzl",
                """
            MyInfo = provider(
                doc = "My info.",
                fields = {"b": "Field B.", "a": "(list[string] | None) Field A."},
            )
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        Truth.assertThat(objects.get(Category.PROVIDER)).hasSize(1)
        val providerDoc: StarlarkDocPage = objects.get(Category.PROVIDER).getFirst()
        assertThat(providerDoc.getMembers()).hasSize(2)
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::getName))
            .containsExactly("a", "b") // sorted!
            .inOrder()
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::getReturnType))
            .containsExactly(
                "<code><a class=\"anchor\" href=\"../core/list.html\">list</a>[<a class=\"anchor\""
                        + " href=\"../core/string.html\">string</a>] | None</code>",
                "unknown"
            )
            .inOrder()
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::getDocumentation))
            .containsExactly("Field A.", "Field B.")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoProviderInit() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//pkg:my_info.bzl",
                """
            def _init_my_info(a, b=1, **kwargs):
                '''Initializes a MyInfo instance.'''
                return {"a": a + b}
            MyInfo, _new_my_info = provider(doc = "My info.", fields = ["a"], init = _init_my_info)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        Truth.assertThat(objects.get(Category.PROVIDER)).hasSize(1)
        val providerDoc: StarlarkDocPage = objects.get(Category.PROVIDER).getFirst()
        assertThat(providerDoc.getMembers().stream().map(MemberDoc::getName))
            .containsExactly("MyInfo", "a")
            .inOrder()
        val constructor: MemberDoc = providerDoc.getConstructor()
        assertThat(constructor.getName()).isEqualTo("MyInfo")
        assertThat(constructor.getDocumentation()).isEqualTo("Initializes a MyInfo instance.")
        assertThat(constructor.getSignature())
            .isEqualTo(
                "<code><a class=\"anchor\" href=\"../providers/MyInfo.html\">MyInfo</a></code>"
                        + " MyInfo(a, b=1, **kwargs)"
            )
        assertThat(constructor.getParams().stream().map(ParamDoc::getName))
            .containsExactly("a", "b", "kwargs")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStardocProtoProviderAlias() {
        val moduleInfo: ModuleInfo =
            getModuleInfo(
                "//:test.bzl",
                """
            MyInfo = provider(doc = "My info.", fields = ["a", "b"])
            #: Foo module.
            foo = struct(my_aliased_info = MyInfo)
            
            """.trimIndent()
            )
        val objects: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            collect(moduleInfo)
        Truth.assertThat(objects.get(Category.TOP_LEVEL_MODULE)).hasSize(1)
        val moduleDoc: StarlarkDocPage = objects.get(Category.TOP_LEVEL_MODULE).getFirst()
        val providerAlias: MemberDoc = moduleDoc.getMembers().getFirst()
        assertThat(providerAlias.getName()).isEqualTo("my_aliased_info")
        assertThat(providerAlias.getSignature())
            .isEqualTo(
                "<code><a class=\"anchor\" href=\"../builtins/Provider.html\">Provider</a></code>"
                        + " my_aliased_info"
            )
        assertThat(providerAlias.getDocumentation())
            .isEqualTo(
                "A convenience alias for the <code><a class=\"anchor\""
                        + " href=\"../providers/MyInfo.html\">MyInfo</a></code> provider symbol."
            )
    }

    @Throws(IOException::class)
    private fun collect(vararg classObjects: java.lang.Class<*>?): com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> {
        return StarlarkDocumentationCollector.collectDocPages(
            expander,
            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (classObjects),
            com.google.common.collect.ImmutableList.of<E?>()
        )
    }

    @Throws(IOException::class, ClassPathException::class)
    private fun collect(
        vararg apiStardocProtos: ModuleInfo?
    ): com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> {
        return StarlarkDocumentationCollector.collectDocPages(
            expander,
            getCoreStarlarkClasses(),
            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (apiStardocProtos)
        )
    }

    @Throws(ClassPathException::class)
    private fun getCoreStarlarkClasses(): com.google.common.collect.ImmutableList<java.lang.Class<*>?> {
        val classes: com.google.common.collect.ImmutableList.Builder<java.lang.Class<*>?> =
            com.google.common.collect.ImmutableList.builder<java.lang.Class<*>?>()
        return classes
            .addAll(Classpath.findClasses("net/starlark/java"))
            .add(ProviderApi::class.java)
            .build()
    }

    /**
     * Parses and evaluates a .bzl file, extracts documentation from it into a Stardoc proto, and
     * writes the binary proto data to a new scratch file.
     */
    @Throws(java.lang.Exception::class)
    private fun getModuleInfo(bzlLabelString: String?, vararg lines: String?): ModuleInfo {
        val ev: BazelEvaluationTestCase = BazelEvaluationTestCase(bzlLabelString)
        val moduleForCompilation: net.starlark.java.eval.Module? = ev.newModule()
        val bzlLabel: Label? = Label.parseCanonical(bzlLabelString)
        ev.setThreadOwner(keyForBuild(bzlLabel))
        val input: net.starlark.java.syntax.ParserInput =
            net.starlark.java.syntax.ParserInput.Companion.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile = net.starlark.java.syntax.StarlarkFile.Companion.parse(
            input,
            net.starlark.java.syntax.FileOptions.Companion.DEFAULT
        )
        val program: net.starlark.java.syntax.Program = compileFile(file, moduleForCompilation)
        val moduleForEvaluation: net.starlark.java.eval.Module? = ev.newModule(program)
        BzlLoadFunction.execAndExport(
            program, bzlLabel, ev.getEventHandler(), moduleForEvaluation, ev.getStarlarkThread()
        )
        val extractor: ModuleInfoExtractor = ModuleInfoExtractor({ name -> true }, LabelRenderer.DEFAULT)
        return extractor.extractFrom(moduleForEvaluation)
    }

    companion object {
        private val DEPRECATED_OR_EXPERIMENTAL_UNDOCUMENTED_TOP_LEVEL_SYMBOLS: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("Actions")

        private val expander: StarlarkDocExpander = object : StarlarkDocExpander(null) {
            public override fun expand(docString: String?): String? {
                return docString
            }
        }
    }
}
