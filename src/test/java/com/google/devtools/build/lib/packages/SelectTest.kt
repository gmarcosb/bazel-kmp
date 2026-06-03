// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.starlark.StarlarkGlobalsImpl

/** Tests of `select` function and data type.  */
@RunWith(TestParameterInjector::class)
class SelectTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelect() {
        val result: SelectorList = eval("select({'a': 1})") as SelectorList
        assertThat((com.google.common.collect.Iterables.getOnlyElement<Any?>(result.elements) as SelectorValue).getDictionary())
            .containsExactly("a", StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlus() {
        val x: SelectorList = eval("select({'foo': ['FOO'], 'bar': ['BAR']}) + []") as SelectorList
        val elements: MutableList<Any?>? = x.elements
        Truth.assertThat(elements).hasSize(2)
        Truth.assertThat(elements!!.get(0)).isInstanceOf(SelectorValue::class.java)
        Truth.assertThat(elements.get(1) as Iterable<*>?).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlusIncompatibleType() {
        assertFails(
            "select({'foo': ['FOO'], 'bar': ['BAR']}) + 1",
            "Cannot combine incompatible types (select of list, int)"
        )
        assertFails(
            "select({'foo': ['FOO']}) + select({'bar': 2})",
            "Cannot combine incompatible types (select of list, select of int)"
        )

        assertFails(
            "select({'foo': ['FOO']}) + select({'bar': {'a': 'a'}})",
            "Cannot combine incompatible types (select of list, select of dict)"
        )
        assertFails(
            "select({'bar': {'a': 'a'}}) + select({'foo': ['FOO']})",
            "Cannot combine incompatible types (select of dict, select of list)"
        )
        assertFails(
            "['FOO'] + select({'bar': {'a': 'a'}})",
            "Cannot combine incompatible types (list, select of dict)"
        )
        assertFails(
            "select({'bar': {'a': 'a'}}) + ['FOO']",
            "Cannot combine incompatible types (select of dict, list)"
        )
        assertFails(
            "select({'foo': ['FOO']}) + {'a': 'a'}", "unsupported binary operation: select + dict"
        )
        assertFails(
            "{'a': 'a'} + select({'foo': ['FOO']})", "unsupported binary operation: dict + select"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnionIncompatibleType() {
        assertFails(
            "select({'foo': ['FOO']}) | select({'bar': {'a': 'a'}})",
            "Cannot combine incompatible types (select of list, select of dict)"
        )
        assertFails(
            "select({'bar': {'a': 'a'}}) | select({'foo': ['FOO']})",
            "Cannot combine incompatible types (select of dict, select of list)"
        )
        assertFails(
            "['FOO'] | select({'bar': {'a': 'a'}})", "unsupported binary operation: list | select"
        )
        assertFails(
            "select({'bar': {'a': 'a'}}) | ['FOO']", "unsupported binary operation: select | list"
        )
        assertFails(
            "select({'foo': ['FOO']}) | {'a': 'a'}",
            "Cannot combine incompatible types (select of list, dict)"
        )
        assertFails(
            "{'a': 'a'} | select({'foo': ['FOO']})",
            "Cannot combine incompatible types (dict, select of list)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepr() {
        Truth.assertThat(eval("repr(select({'foo': ['FOO']})+['BAR'])"))
            .isEqualTo("select({\"foo\": [\"FOO\"]}) + [\"BAR\"]")

        Truth.assertThat(eval("repr(['FOO']+select({'bar': ['BAR']}))"))
            .isEqualTo("[\"FOO\"] + select({\"bar\": [\"BAR\"]})")

        Truth.assertThat(eval("repr(select({'foo': ['FOO']})+select({'bar': ['BAR']}))"))
            .isEqualTo("select({\"foo\": [\"FOO\"]}) + select({\"bar\": [\"BAR\"]})")

        Truth.assertThat(eval("repr(select({'foo': {'FOO': 123}})|{'BAR': 456})"))
            .isEqualTo("select({\"foo\": {\"FOO\": 123}}) | {\"BAR\": 456}")

        Truth.assertThat(eval("repr({'FOO': 123}|select({'bar': {'BAR': 456}}))"))
            .isEqualTo("{\"FOO\": 123} | select({\"bar\": {\"BAR\": 456}})")

        Truth.assertThat(eval("repr(select({'foo': {'FOO': 123}})|select({'bar': {'BAR': 456}}))"))
            .isEqualTo("select({\"foo\": {\"FOO\": 123}}) | select({\"bar\": {\"BAR\": 456}})")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeyResolution(@TestParameter resolveSelectKeysEagerly: Boolean) {
        val ctx: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            BazelModuleContext.create(
                BazelModuleKey.createFakeModuleKeyForTesting(
                    Label.parseCanonicalUnchecked("//other/pkg:def.bzl")
                ),
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "",
                        RepositoryName.MAIN,
                        "other_repo",
                        RepositoryName.createUnvalidated("other_repo+")
                    ),
                    RepositoryName.MAIN
                ),
                "other/pkg/def.bzl",  /* loads= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* bzlTransitiveDigest= */
                ByteArray(0),  /* docCommentsMap= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* unusedDocCommentLines= */
                com.google.common.collect.ImmutableList.of<E?>()
            )
        val semantics: StarlarkSemantics? =
            StarlarkSemantics.builder()
                .setBool(
                    BuildLanguageOptions.INCOMPATIBLE_RESOLVE_SELECT_KEYS_EAGERLY,
                    resolveSelectKeysEagerly
                )
                .build()
        val result: SelectorList =
            eval("select({'a': 1, '//pkg:b': 2, '@other_repo//:file': 3})", semantics, ctx) as SelectorList
        val selectDict: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (com.google.common.collect.Iterables.getOnlyElement<Any?>(result.elements) as SelectorValue).getDictionary()
        if (resolveSelectKeysEagerly) {
            assertThat(selectDict)
                .containsExactly(
                    Label.parseCanonicalUnchecked("//other/pkg:a"),
                    StarlarkInt.of(1),
                    Label.parseCanonicalUnchecked("//pkg:b"),
                    StarlarkInt.of(2),
                    Label.parseCanonicalUnchecked("@@other_repo+//:file"),
                    StarlarkInt.of(3)
                )
                .inOrder()
        } else {
            assertThat(selectDict)
                .containsExactly(
                    "a", StarlarkInt.of(1),
                    "//pkg:b", StarlarkInt.of(2),
                    "@other_repo//:file", StarlarkInt.of(3)
                )
                .inOrder()
        }
    }

    companion object {
        @Throws(
            net.starlark.java.syntax.SyntaxError.Exception::class,
            net.starlark.java.eval.EvalException::class,
            java.lang.InterruptedException::class
        )
        private fun eval(
            expr: String?,
            semantics: StarlarkSemantics? = StarlarkSemantics.DEFAULT,
            bazelModuleContext: BazelModuleContext? = null
        ): Any? {
            val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(expr)
            val module: net.starlark.java.eval.Module? =
                net.starlark.java.eval.Module.withPredeclaredAndData(
                    semantics, StarlarkGlobalsImpl.INSTANCE.getUtilToplevels(), bazelModuleContext
                )
            Mutability.create().use { mu ->
                val thread: StarlarkThread? = StarlarkThread.createTransient(mu, semantics)
                return Starlark.eval(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
            }
        }

        private fun assertFails(expr: String?, wantError: String?) {
            val ex: net.starlark.java.eval.EvalException? =
                org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                    net.starlark.java.eval.EvalException::class.java,
                    org.junit.function.ThrowingRunnable { eval(expr) })
            Truth.assertThat(ex).hasMessageThat().contains(wantError)
        }
    }
}
