// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Tests for `native.existing_rule` and `native.existing_rules` functions.  */
@RunWith(JUnit4::class)
class NativeExistingRulesTest : BuildViewTestCase() {
    private var testStarlarkBuiltin: TestStarlarkBuiltin? = null // initialized by createRuleClassProvider()

    @StarlarkBuiltin(name = "test")
    private class TestStarlarkBuiltin : StarlarkValue {
        private val saved: MutableMap<String?, Any?> = HashMap<String?, Any?>()

        @StarlarkMethod(
            name = "save",
            parameters = [net.starlark.java.annot.Param(
                name = "name",
                doc = "Name under which to save the value"
            ), net.starlark.java.annot.Param(name = "value", doc = "Value to save")],
            doc = "Saves a Starlark value for testing from Java"
        )
        @kotlin.jvm.Synchronized
        fun save(name: String?, value: Any?) {
            saved.put(name, value)
        }
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        testStarlarkBuiltin = com.google.devtools.build.lib.packages.NativeExistingRulesTest.TestStarlarkBuiltin()
        builder.addBzlToplevel("test", testStarlarkBuiltin)
        return builder.build()
    }

    private fun getSaved(name: String?): Any? {
        return testStarlarkBuiltin.saved.get(name)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_handlesSelect() {
        scratch.file("test/starlark/BUILD")
        scratch.file(
            "test/starlark/rulestr.bzl",
            """
        def rule_dict(name):
            return native.existing_rule(name)
        
        """.trimIndent()
        )

        scratch.file(
            "test/getrule/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test/starlark:rulestr.bzl", "rule_dict")

        cc_library(
            name = "x",
            srcs = select({"//conditions:default": []}),
        )

        rule_dict("x")
        
        """.trimIndent()
        )

        // Parse the BUILD file, to make sure select() makes it out of native.existing_rule().
        assertThat(getConfiguredTarget("//test/getrule:x")).isNotNull()
    }

    // Regression test for b/355432322
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_handlesSelectWithNoneValues_forLabelValuedAttributes() {
        scratch.file("test/starlark/BUILD")
        scratch.file(
            "test/starlark/rulestr.bzl",
            """
        def save_dep(rule_name):
            r = native.existing_rule(rule_name)
            test.save("dep", r["dep"])

        def _impl(ctx):
            pass

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/getrule/BUILD",
            """
        load("//test/starlark:rulestr.bzl", "my_rule", "save_dep")

        # Needed to avoid select() being eliminated as trivial.
        config_setting(
            name = "config",
            values = {"define": "pi=3"},
        )

        my_rule(
            name = "x",
            dep = select({
                ":config": None,
                "//conditions:default": None,
            }),
        )

        save_dep("x")
        
        """.trimIndent()
        )

        // Parse the BUILD file, to make sure select() makes it out of native.existing_rule().
        assertThat(getConfiguredTarget("//test/getrule:x")).isNotNull()

        Truth.assertThat(getSaved("dep"))
            .isEqualTo(
                SelectorList.of(
                    SelectorValue(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            Label.parseCanonicalUnchecked("//test/getrule:config"),
                            Starlark.NONE,
                            Label.parseCanonicalUnchecked("//conditions:default"),
                            Starlark.NONE
                        ),
                        ""
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_returnsNone() {
        scratch.file(
            "test/rulestr.bzl",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        def test_rule(name, x):
            print(native.existing_rule(x))
            if native.existing_rule(x) == None:
                cc_library(name = name)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rulestr.bzl", "test_rule")

        test_rule("a", "does not exist")

        test_rule("b", "BUILD")
        
        """.trimIndent()
        ) // exists, but as a target and not a rule

        assertThat(getConfiguredTarget("//test:a")).isNotNull()
        assertThat(getConfiguredTarget("//test:b")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_roundTripsSelect() {
        scratch.file(
            "test/existing_rule.bzl",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        def macro():
            s = select({"//foo": ["//bar"]})
            test.save("passed", s)
            cc_library(name = "x", srcs = s)
            test.save("returned", native.existing_rule("x")["srcs"])

            # The value returned here should round-trip fine.
            cc_library(name = "y", srcs = native.existing_rule("x")["srcs"])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test:existing_rule.bzl", "macro")

        macro()

        cc_library(
            name = "a",
            srcs = [],
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//test:a")
        Truth.assertThat(getSaved("passed"))
            .isEqualTo(
                SelectorList.of(
                    SelectorValue(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "//foo",
                            StarlarkList.of<String?>(Mutability.create("temp"), "//bar")
                        ),
                        ""
                    )
                )
            )
        // The select key is now a label, the short label string is in canonical form, and the sequence
        // is represented as tuple instead of list, but the meaning is unchanged.
        Truth.assertThat(getSaved("returned"))
            .isEqualTo(
                SelectorList.of(
                    SelectorValue(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            Label.parseCanonicalUnchecked("//foo:foo"), Tuple.of("//bar:bar")
                        ),
                        ""
                    )
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_labelStringification() {
        scratch.file(
            "test/existing_rule.bzl",
            """
        def save_deps():
            r = native.existing_rule("b")
            test.save("r['deps']", r["deps"])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:existing_rule.bzl", "save_deps")
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        cc_library(
            name = "a",
            srcs = [],
        )

        cc_binary(
            name = "b",
            deps = [
                "//test:a",
                "//other_package:a",
                "@bazel_tools//test:a",
            ],
        )

        save_deps()
        
        """.trimIndent()
        )
        getTarget("//test:b")
        Truth.assertThat(Starlark.toIterable(getSaved("r['deps']")))
            .containsExactly(":a", "//other_package:a", "@@bazel_tools//test:a")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_findsRulesAndAttributes() {
        scratch.file("test/BUILD")
        scratch.file("test/starlark/BUILD")
        scratch.file(
            "test/starlark/rulestr.bzl",
            """
        def rule_dict(name):
            return native.existing_rule(name)

        def rules_dict():
            return native.existing_rules()

        def nop(ctx):
            pass

        nop_rule = rule(attrs = {"x": attr.label()}, implementation = nop)

        def test_save(name, value):
            test.save(name, value)
        
        """.trimIndent()
        )

        scratch.file(
            "test/getrule/BUILD",
            """
        load("//test/starlark:rulestr.bzl", "nop_rule", "rule_dict", "rules_dict", "test_save")

        genrule(
            name = "a",
            outs = ["a.txt"],
            cmd = "touch ${'$'}@",
            licenses = ["notice"],
            output_to_bindir = False,
            tools = ["//test:bla"],
        )

        nop_rule(
            name = "c",
            x = ":a",
        )

        rlist = rules_dict()

        test_save(
            "all_str",
            [
                rlist["a"]["kind"],
                rlist["a"]["name"],
                rlist["c"]["kind"],
                rlist["c"]["name"],
            ],
        )

        adict = rule_dict("a")

        cdict = rule_dict("c")

        test_save(
            "a_str",
            [
                adict["kind"],
                adict["name"],
                adict["outs"][0],
                adict["tools"][0],
            ],
        )

        test_save(
            "c_str",
            [
                cdict["kind"],
                cdict["name"],
                cdict["x"],
            ],
        )

        test_save(
            "adict.keys()",
            adict.keys(),
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//test/getrule:BUILD")
        Truth.assertThat(Starlark.toIterable(getSaved("all_str")))
            .containsExactly("genrule", "a", "nop_rule", "c")
            .inOrder()
        Truth.assertThat(Starlark.toIterable(getSaved("a_str")))
            .containsExactly("genrule", "a", ":a.txt", "//test:bla")
            .inOrder()
        Truth.assertThat(Starlark.toIterable(getSaved("c_str")))
            .containsExactly("nop_rule", "c", ":a")
            .inOrder()
        Truth.assertThat(Starlark.toIterable(getSaved("adict.keys()")))
            .containsAtLeast(
                "name",
                "visibility",
                "transitive_configs",
                "tags",
                "generator_name",
                "generator_function",
                "generator_location",
                "features",
                "compatible_with",
                "target_compatible_with",
                "restricted_to",
                "srcs",
                "tools",
                "toolchains",
                "outs",
                "cmd",
                "output_to_bindir",
                "local",
                "message",
                "executable",
                "stamp",
                "heuristic_label_expansion",
                "kind"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_ignoresHiddenAttributes() {
        scratch.file(
            "test/inc.bzl",
            """
        def _check_hidden_attr_exists(ctx):
            if ctx.attr._hidden_attr != "hidden_val":
                fail('ctx.attr._hidden_attr != "hidden_val"')
            pass

        my_rule = rule(
            attrs = {
                "_hidden_attr": attr.string(default = "hidden_val"),
                "normal_attr": attr.string(default = "normal_val"),
            },
            implementation = _check_hidden_attr_exists,
        )

        def f():
            my_rule(name = "rulename")
            r = native.existing_rule("rulename")
            test.save("r.keys()", r.keys())
            test.save("r.values()", r.values())
            test.save('"_hidden_attr" in r', "_hidden_attr" in r)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("inc.bzl", "f")

        f()
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//test:rulename")).isNotNull()
        Truth.assertThat(Starlark.toIterable(getSaved("r.keys()")))
            .containsAtLeast("name", "kind", "normal_attr")
        Truth.assertThat(Starlark.toIterable(getSaved("r.keys()"))).doesNotContain("_hidden_attr")
        Truth.assertThat(Starlark.toIterable(getSaved("r.values()")))
            .containsAtLeast("rulename", "my_rule", "normal_val")
        Truth.assertThat(Starlark.toIterable(getSaved("r.values()"))).doesNotContain("hidden_val")
        Truth.assertThat(getSaved("\"_hidden_attr\" in r") as Boolean?).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_returnsImmutableObject() {
        scratch.file(
            "test/BUILD",
            """
        load("inc.bzl", "f")

        f()
        
        """.trimIndent()
        )
        scratch.file(
            "test/inc.bzl",
            """
        def f():
            native.config_setting(name = "x", define_values = {"key": "value"})
            r = native.existing_rule("x")
            r["no_such_attribute"] = 123
        
        """.trimIndent()
        ) // mutate the view

        reporter.removeHandler(failFastHandler)
        assertThat(getConfiguredTarget("//test:BUILD")).isNull() // mutation fails
        assertContainsEvent("can only assign an element in a dictionary or a list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_returnsDictLikeObject() {
        scratch.file(
            "test/BUILD",
            """
        load("inc.bzl", "f")

        f()
        
        """.trimIndent()
        )
        scratch.file(
            "test/inc.bzl",
            """
        def f():
            native.config_setting(name = "x", define_values = {"key": "value"})
            r = native.existing_rule("x")
            print("r == %s" % repr(r))
            test.save("[key for key in r]", [key for key in r])
            test.save("list(r)", list(r))
            test.save("r.keys()", r.keys())
            test.save("r.values()", r.values())
            test.save("r.items()", r.items())
            test.save("r['define_values']", r["define_values"])
            test.save("r.get('define_values', 123)", r.get("define_values", 123))
            test.save("r.get('invalid_attr', 123)", r.get("invalid_attr", 123))
            test.save("'define_values' in r", "define_values" in r)
            test.save("'invalid_attr' in r", "invalid_attr" in r)
        
        """.trimIndent()
        )

        val expectedDefineValues: Dict<*, *>? = Dict.builder<Any?, Any?>().put("key", "value").buildImmutable()
        assertThat(getConfiguredTarget("//test:BUILD")).isNotNull() // no error
        Truth.assertThat(Starlark.toIterable(getSaved("[key for key in r]")))
            .containsAtLeast("define_values", "name", "kind")
        Truth.assertThat(Starlark.toIterable(getSaved("list(r)")))
            .containsAtLeast("define_values", "name", "kind")
        Truth.assertThat(Starlark.toIterable(getSaved("r.keys()")))
            .containsAtLeast("define_values", "name", "kind")
        Truth.assertThat(Starlark.toIterable(getSaved("r.values()")))
            .containsAtLeast(expectedDefineValues, "x", "config_setting")
        Truth.assertThat(Starlark.toIterable(getSaved("r.items()")))
            .containsAtLeast(
                Tuple.of("define_values", expectedDefineValues),
                Tuple.of("name", "x"),
                Tuple.of("kind", "config_setting")
            )
        Truth.assertThat(getSaved("r['define_values']")).isEqualTo(expectedDefineValues)
        Truth.assertThat(getSaved("r.get('define_values', 123)")).isEqualTo(expectedDefineValues)
        Truth.assertThat(getSaved("r.get('invalid_attr', 123)")).isEqualTo(StarlarkInt.of(123))
        Truth.assertThat(getSaved("'define_values' in r")).isEqualTo(true)
        Truth.assertThat(getSaved("'invalid_attr' in r")).isEqualTo(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_asDictArgument() {
        scratch.file(
            "test/test.bzl",
            """
        def save_as_dict(r):
            test.save("type(dict(r))", type(dict(r)))
            test.save('dict(r)["name"]', dict(r)["name"])
            test.save('dict(r)["kind"]', dict(r)["kind"])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test:test.bzl", "save_as_dict")

        cc_library(
            name = "rulename",
        )

        save_as_dict(existing_rule("rulename"))
        
        """.trimIndent()
        )
        getConfiguredTarget("//test:rulename")
        Truth.assertThat(getSaved("type(dict(r))")).isEqualTo("dict")
        Truth.assertThat(getSaved("dict(r)[\"name\"]")).isEqualTo("rulename")
        Truth.assertThat(getSaved("dict(r)[\"kind\"]")).isEqualTo("cc_library")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_asDictUpdateArgument() {
        // We do not test `existing_rule(r).update({...})` because `existing_rule(r)` may be immutable
        // (as verified by other test cases).
        scratch.file(
            "test/test.bzl",
            """
        def save_as_updated_dict(r):
            updated_dict = {"name": "dictname", "dictkey": 1}
            updated_dict.update(r)
            test.save('updated_dict["name"]', updated_dict["name"])
            test.save('updated_dict["kind"]', updated_dict["kind"])
            test.save('updated_dict["dictkey"]', updated_dict["dictkey"])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test:test.bzl", "save_as_updated_dict")

        cc_library(
            name = "rulename",
        )

        save_as_updated_dict(existing_rule("rulename"))
        
        """.trimIndent()
        )
        getConfiguredTarget("//test:rulename")
        Truth.assertThat(getSaved("updated_dict[\"name\"]")).isEqualTo("rulename")
        Truth.assertThat(getSaved("updated_dict[\"kind\"]")).isEqualTo("cc_library")
        Truth.assertThat(getSaved("updated_dict[\"dictkey\"]")).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_unionableWithDict() {
        scratch.file(
            "test/test.bzl",
            """
        def save_as_union(dict_val, r):
            test.save("dict_val | r", dict_val | r)
            test.save("r | dict_val", r | dict_val)
            dict_val |= r
            test.save("dict_val |= r", dict_val)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test:test.bzl", "save_as_union")

        cc_library(
            name = "rulename",
        )

        save_as_union(
            {
                "name": "dictname",
                "dictkey": 1,
            },
            existing_rule("rulename"),
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//test:rulename")
        val unionDictWithExistingRule: MutableMap<String?, Any?> =
            Dict.cast<String?, Any?>(getSaved("dict_val | r"), String::class.java, Any::class.java, "dict_val | r")
        Truth.assertThat(unionDictWithExistingRule)
            .containsAtLeast("name", "rulename", "dictkey", StarlarkInt.of(1), "kind", "cc_library")
        val unionExistingRuleWithDict: MutableMap<String?, Any?> =
            Dict.cast<String?, Any?>(getSaved("r | dict_val"), String::class.java, Any::class.java, "r | dict_val")
        Truth.assertThat(unionExistingRuleWithDict)
            .containsAtLeast("name", "dictname", "dictkey", StarlarkInt.of(1), "kind", "cc_library")
        val inPlaceUnionDictWithExistingRule: MutableMap<String?, Any?> =
            Dict.cast<String?, Any?>(getSaved("dict_val |= r"), String::class.java, Any::class.java, "dict_val | r")
        Truth.assertThat(inPlaceUnionDictWithExistingRule)
            .containsAtLeast("name", "rulename", "dictkey", StarlarkInt.of(1), "kind", "cc_library")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_asKwargs() {
        scratch.file(
            "test/test.bzl",
            """
        def save_kwargs(**kwargs):
            test.save('kwargs["name"]', kwargs["name"])
            test.save('kwargs["kind"]', kwargs["kind"])

        def save_kwargs_of_existing_rule(name):
            save_kwargs(**native.existing_rule(name))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test:test.bzl", "save_kwargs_of_existing_rule")

        cc_library(
            name = "rulename",
        )

        save_kwargs_of_existing_rule("rulename")
        
        """.trimIndent()
        )
        getConfiguredTarget("//test:rulename")
        Truth.assertThat(getSaved("kwargs[\"name\"]")).isEqualTo("rulename")
        Truth.assertThat(getSaved("kwargs[\"kind\"]")).isEqualTo("cc_library")
    }

    // Regression test for https://github.com/bazelbuild/bazel/issues/16256
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_encodesToJson() {
        // We need a Starlark rule - native rules can have attribute values that the json encoder
        // doesn't handle.
        scratch.file(
            "test/test.bzl",
            """
        def _dummy_impl(ctx):
            pass

        test_library = rule(
            implementation = _dummy_impl,
            attrs = {"srcs": attr.label_list(allow_files = True)},
        )

        # TODO(b/249397668): simplifying this to `json_encode = json.encode` etc. causes a
        # NoCodecException. Need to investigate.
        def json_encode(value):
            return json.encode(value)

        def json_decode(text):
            return json.decode(text)

        def save(name, object):
            test.save(name, object)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:test.bzl", "json_decode", "json_encode", "save", "test_library")

        test_library(
            name = "foo",
            srcs = ["foo.cc"],
        )

        save(
            "foo",
            json_decode(json_encode(existing_rule("foo"))),
        )
        
        """.trimIndent()
        )
        scratch.file("test/foo.cc")
        getConfiguredTarget("//test:foo")
        // We test a subset of attributes after an encode-decode round trip because the rule also has
        // default attributes with default values, which will get encoded to json and which will change
        // whenever default attributes get introduced, making string comparison of encoded json fragile.
        val jsonRoundTripValue: MutableMap<String?, Any?> =
            Dict.cast<String?, Any?>(
                getSaved("foo"), String::class.java, Any::class.java, "json round trip of existing_rule('foo')"
            )
        Truth.assertThat(jsonRoundTripValue)
            .containsAtLeast(
                "name", "foo", "kind", "test_library", "srcs", StarlarkList.immutableOf<String?>(":foo.cc")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_returnsImmutableObject() {
        scratch.file(
            "test/BUILD",
            """
        load("inc.bzl", "f")

        f()
        
        """.trimIndent()
        )
        scratch.file(
            "test/inc.bzl",
            """
        def f():
            native.config_setting(name = "x", define_values = {"key": "value"})
            rs = native.existing_rules()
            rs["no_such_rule"] = {"name": "no_such_rule", "kind": "config_setting"}
        
        """.trimIndent()
        ) // mutate

        reporter.removeHandler(failFastHandler)
        assertThat(getConfiguredTarget("//test:BUILD")).isNull() // mutation fails
        assertContainsEvent("can only assign an element in a dictionary or a list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_returnsDeeplyImmutableView() {
        scratch.file(
            "test/BUILD",
            """
        load("inc.bzl", "f")

        f()
        
        """.trimIndent()
        )
        scratch.file(
            "test/inc.bzl",
            """
        def f():
            native.config_setting(name = "x", define_values = {"key": "value"})
            rs = native.existing_rules()
            rs["x"]["define_values"]["key"] = 123
        
        """.trimIndent()
        ) // mutate an attribute value within the view

        reporter.removeHandler(failFastHandler)
        assertThat(getConfiguredTarget("//test:BUILD")).isNull()
        assertContainsEvent("trying to mutate a frozen dict value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_returnsDictLikeObject() {
        scratch.file(
            "test/BUILD",
            """
        load("inc.bzl", "f")

        f()
        
        """.trimIndent()
        )
        scratch.file(
            "test/inc.bzl",  //
            "def f():",
            "  native.config_setting(name='x', define_values={'key_x': 'value_x'})",
            "  native.config_setting(name='y', define_values={'key_y': 'value_y'})",
            "  rs = native.existing_rules()",
            "  print('rs == %s' % repr(rs))",
            "  test.save('[key for key in rs]', [key for key in rs])",
            "  test.save('list(rs)', list(rs))",
            "  test.save('rs.keys()', rs.keys())",
            "  test.save(\"[v['name'] for v in rs.values()]\", [v['name'] for v in rs.values()])",
            "  test.save(\"[(i[0], i[1]['name']) for i in rs.items()]\", [(i[0], i[1]['name']) for i in"
                    + " rs.items()])",
            "  test.save(\"rs['x']['define_values']\", rs['x']['define_values'])",
            "  test.save(\"rs.get('x', {'name': 'z'})['name']\", rs.get('x', {'name': 'z'})['name'])",
            "  test.save(\"rs.get('invalid_rule', {'name': 'invalid_rule'})\", rs.get('invalid_rule',"
                    + " {'name': 'invalid_rule'}))",
            "  test.save(\"'x' in rs\", 'x' in rs)",
            "  test.save(\"'invalid_rule' in rs\", 'invalid_rule' in rs)"
        )

        assertThat(getConfiguredTarget("//test:BUILD")).isNotNull() // no error
        Truth.assertThat(Starlark.toIterable(getSaved("[key for key in rs]"))).containsExactly("x", "y")
        Truth.assertThat(Starlark.toIterable(getSaved("list(rs)"))).containsExactly("x", "y")
        Truth.assertThat(Starlark.toIterable(getSaved("rs.keys()"))).containsExactly("x", "y")
        Truth.assertThat(Starlark.toIterable(getSaved("[v['name'] for v in rs.values()]")))
            .containsExactly("x", "y")
        Truth.assertThat(Starlark.toIterable(getSaved("[(i[0], i[1]['name']) for i in rs.items()]")))
            .containsExactly(Tuple.of("x", "x"), Tuple.of("y", "y"))
        Truth.assertThat(getSaved("rs['x']['define_values']"))
            .isEqualTo(Dict.builder<Any?, Any?>().put("key_x", "value_x").buildImmutable())
        Truth.assertThat(getSaved("rs.get('x', {'name': 'z'})['name']")).isEqualTo("x")
        Truth.assertThat(getSaved("rs.get('invalid_rule', {'name': 'invalid_rule'})"))
            .isEqualTo(Dict.builder<Any?, Any?>().put("name", "invalid_rule").buildImmutable())
        Truth.assertThat(getSaved("'x' in rs")).isEqualTo(true)
        Truth.assertThat(getSaved("'invalid_rule' in rs")).isEqualTo(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_returnsSnapshotOfOnlyRulesInstantiatedUpToThatPoint() {
        scratch.file(
            "test/BUILD",
            """
        load("inc.bzl", "f")

        f()
        
        """.trimIndent()
        )
        scratch.file(
            "test/inc.bzl",
            """
        def f():
            native.config_setting(name = "x", define_values = {"key_x": "value_x"})
            rs1 = native.existing_rules()
            native.config_setting(name = "y", define_values = {"key_y": "value_y"})
            rs2 = native.existing_rules()
            native.config_setting(name = "z", define_values = {"key_z": "value_z"})
            rs3 = native.existing_rules()
            test.save("rs1.keys()", rs1.keys())
            test.save("rs2.keys()", rs2.keys())
            test.save("rs3.keys()", rs3.keys())
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//test:BUILD")).isNotNull() // no error
        Truth.assertThat(Starlark.toIterable(getSaved("rs1.keys()"))).containsExactly("x")
        Truth.assertThat(Starlark.toIterable(getSaved("rs2.keys()"))).containsExactly("x", "y")
        Truth.assertThat(Starlark.toIterable(getSaved("rs3.keys()"))).containsExactly("x", "y", "z")
    }

    // Regression test for https://github.com/bazelbuild/bazel/issues/16256
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_encodeToJson() {
        // We need a Starlark rule - native rules can have attribute values that the json encoder
        // doesn't handle.
        scratch.file(
            "test/test.bzl",
            """
        def _dummy_impl(ctx):
            pass

        test_library = rule(
            implementation = _dummy_impl,
            attrs = {"srcs": attr.label_list(allow_files = True)},
        )

        # TODO(b/249397668): simplifying this to `json_encode = json.encode` etc. causes a
        # NoCodecException. Need to investigate.
        def json_encode(value):
            return json.encode(value)

        def json_decode(text):
            return json.decode(text)

        def save(name, object):
            test.save(name, object)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:test.bzl", "json_decode", "json_encode", "save", "test_library")

        test_library(
            name = "foo",
            srcs = ["foo.cc"],
        )

        test_library(
            name = "bar",
            srcs = ["bar.cc"],
        )

        save(
            "rules",
            json_decode(json_encode(existing_rules())),
        )
        
        """.trimIndent()
        )
        scratch.file("test/foo.cc")
        getConfiguredTarget("//test:bar")
        // We test a subset of attributes after an encode-decode round trip because the rule also has
        // default attributes with default values, which will get encoded to json and which will change
        // whenever default attributes get introduced, making string comparison of encoded json fragile.
        val jsonRoundTripRulesValue: Dict<String?, Any?> =
            Dict.cast<String?, Any?>(
                getSaved("rules"), String::class.java, Any::class.java, "json round trip of `existing_rules()`"
            )
        Truth.assertThat(jsonRoundTripRulesValue.keys).containsExactly("foo", "bar")
        val jsonRoundTripFooValue: MutableMap<String?, Any?> =
            Dict.cast<String?, Any?>(
                jsonRoundTripRulesValue.get("foo"),
                String::class.java,
                Any::class.java,
                "json round trip of `existing_rule('foo')`"
            )
        Truth.assertThat(jsonRoundTripFooValue)
            .containsAtLeast(
                "name", "foo", "kind", "test_library", "srcs", StarlarkList.immutableOf<String?>(":foo.cc")
            )
        val jsonRoundTripBarValue: MutableMap<String?, Any?> =
            Dict.cast<String?, Any?>(
                jsonRoundTripRulesValue.get("bar"),
                String::class.java,
                Any::class.java,
                "json round trip of `existing_rule('bar')`"
            )
        Truth.assertThat(jsonRoundTripBarValue)
            .containsAtLeast(
                "name", "bar", "kind", "test_library", "srcs", StarlarkList.immutableOf<String?>(":bar.cc")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRule_roundTripThroughRule() {
        // Verify that the set of native attributes captured by native.existing_rule() are
        // round-trippable (e.g. we don't attempt to capture computed defaults, etc.), with the
        // exception of name/kind pseudo-attributes, and restricted_to/visibility which have special
        // semantics.
        scratch.file(
            "test/macros.bzl",
            """
        def lib(name, **kwargs):
            native.filegroup(name=name, **kwargs)

        def get_round_trippable_attrs(r):
            return {
                k: v
                for k, v in native.existing_rule(r).items()
                if k not in ("name", "kind", "restricted_to", "visibility")
            }

        def copy(name, src):
            src_attrs = get_round_trippable_attrs(src)
            test.save("src_attrs", src_attrs)
            native.filegroup(name = name, **src_attrs)
            test.save("copy_attrs", get_round_trippable_attrs(name))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":macros.bzl", "lib", "copy")
        lib(name = "a", srcs = ["BUILD"])
        copy(name = "b", src = "a")
        
        """.trimIndent()
        )
        getConfiguredTarget("//test:b")
        val srcAttrs: Dict<String?, Any?> =
            Dict.cast<String?, Any?>(getSaved("src_attrs"), String::class.java, Any::class.java, "copy_args")
        val copyAttrs: Dict<String?, Any?> =
            Dict.cast<String?, Any?>(getSaved("copy_attrs"), String::class.java, Any::class.java, "copy_attrs")

        Truth.assertThat(copyAttrs.entries).containsExactlyElementsIn(srcAttrs.entries)
    }
}
