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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for StarlarkRuleClassFunctions.  */
@RunWith(TestParameterInjector::class)
class StarlarkRuleClassFunctionsTest : BuildViewTestCase() {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()

    @Throws(java.lang.Exception::class)
    private fun createRuleContext(label: String?): StarlarkRuleContext {
        return StarlarkRuleContext(getRuleContextForStarlark(getConfiguredTarget(label)), null)
    }

    @Throws(OptionsParsingException::class, java.lang.InterruptedException::class, AbruptExitException::class)
    override fun setBuildLanguageOptions(vararg options: String?) {
        super.setBuildLanguageOptions(*options) // for BuildViewTestCase
        ev.setSemantics(*options) // for StarlarkThread
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addBzlToplevel(
            "parametrized_native_aspect",
            TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER
        )
        builder.addNativeAspectClass(TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER)
        return builder.build()
    }

    @org.junit.Rule
    var thrown: org.junit.rules.ExpectedException = org.junit.rules.ExpectedException.none()

    @Before
    @Throws(java.lang.Exception::class)
    fun createBuildFile() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        genrule(
            name = "foo",
            srcs = [
                "a.txt",
                "b.img",
            ],
            outs = ["c.txt"],
            cmd = "dummy_cmd",
            tools = ["t.exe"],
        )

        genrule(
            name = "bar",
            srcs = [
                ":jl",
                ":gl",
            ],
            outs = ["d.txt"],
            cmd = "dummy_cmd",
        )

        java_library(
            name = "jl",
            srcs = ["a.java"],
        )

        genrule(
            name = "gl",
            srcs = ["a.go"],
            outs = [
                "gl.a",
                "gl.gcgox",
            ],
            cmd = "touch ${'$'}(OUTS)",
            output_to_bindir = 1,
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotOverrideBuiltInAttribute() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "def impl(ctx):",  //
            "  return",
            "r = rule(impl, attrs = {'tags': attr.string_list()})"
        )
        ev.assertContainsError(
            "Error in rule: attribute `tags`: built-in attributes cannot be overridden."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotOverrideBuiltInAttributeName() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "def impl(ctx):",  //
            "  return",
            "r = rule(impl, attrs = {'name': attr.string()})"
        )
        ev.assertContainsError(
            "Error in rule: attribute `name`: built-in attributes cannot be overridden."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtInAttributesAreNotStarlarkDefined() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "def impl(ctx):",  //
            "  return",
            "r = rule(impl, attrs = {'a': attr.string(), 'b': attr.label()})"
        )
        val builtInAttributes: java.util.stream.Stream<Attribute?> =
            getRuleClass("r").getAttributeProvider().getAttributes().stream()
                .filter({ attr -> !(attr.name.equals("a") || attr.name.equals("b")) })
        Truth.assertThat(builtInAttributes.map<Any?>(Attribute::starlarkDefined)).doesNotContain(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitArgsAttribute() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "def _impl(ctx):",
            "  pass",
            "exec_rule = rule(implementation = _impl, executable = True)",
            "non_exec_rule = rule(implementation = _impl)"
        )
        assertThat(getRuleClass("exec_rule").getAttributeProvider().hasAttr("args", Types.STRING_LIST))
            .isTrue()
        assertThat(
            getRuleClass("non_exec_rule").getAttributeProvider().hasAttr("args", Types.STRING_LIST)
        )
            .isFalse()
    }

    /**
     * Returns a package by the given name (no leading "//"), or null upon [ ].
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun getPackage(pkgName: String?): Package? {
        try {
            return packageManager.getPackage(reporter, PackageIdentifier.createInMainRepo(pkgName))
        } catch (unused: NoSuchPackageException) {
            return null
        }
    }

    private fun assertPackageNotInError(pkg: Package?) {
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_failsWithoutFlag() {
        setBuildLanguageOptions("--experimental_enable_first_class_macros=false")

        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNull()
        assertContainsEvent("requires --experimental_enable_first_class_macros")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_instantiationRegistersOnPackage() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="ghi")  # alphabetized when read back
        my_macro(name="abc")
        my_macro(name="def")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertThat(pkg.getMacrosById().keySet()).containsExactly("abc:1", "def:1", "ghi:1").inOrder()
        assertThat(pkg.getMacrosById().get("abc:1").getMacroClass().name).isEqualTo("my_macro")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_instantiationRequiresExport() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        s = struct(m = macro(implementation=_impl))
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "s")
        s.m(name="abc")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("Cannot instantiate a macro that has not been exported")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_cannotInstantiateInBzlThread() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)

        # Calling it from a function during .bzl load time is a little more interesting than
        # calling it directly at the top level, since it forces us to check thread state rather
        # than call stack state.
        def some_func():
            my_macro(name="nope")
        some_func()
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNull()
        assertContainsEvent(
            "a symbolic macro can only be instantiated while evaluating a BUILD file or a legacy or"
                    + " symbolic macro"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_requiresNameAttribute() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro()
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("missing value for mandatory attribute 'name' in 'my_macro' macro")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_prohibitsPositionalArgs() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro("a positional arg", name = "abc")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("unexpected positional arguments")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacroCanAcceptAttributes() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, target_suffix):
            native.filegroup(name = name + "_" + target_suffix)
        my_macro = macro(
            implementation=_impl,
            attrs = {
                "target_suffix": attr.string(configurable=False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            target_suffix = "xyz"
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey("abc_xyz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_rejectsUnknownAttribute() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xzz": attr.string(doc="This attr is public"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = "UNKNOWN",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("no such attribute 'xyz' in 'my_macro' macro (did you mean 'xzz'?)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_rejectsReservedAttributeName() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "visibility": attr.string(),
            },
        )
        
        """.trimIndent()
        )

        ev.assertContainsError("Cannot declare a macro attribute named 'visibility'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_requiresMandatoryAttribute() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xyz": attr.string(mandatory=True),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("missing value for mandatory attribute 'xyz' in 'my_macro' macro")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_cannotOverrideImplicitAttribute() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, _xyz):
            print("_xyz is %s" % _xyz)
        my_macro = macro(
            implementation=_impl,
            attrs = {
              "_xyz": attr.string(default="IMPLICIT")
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            _xyz = "CAN'T SET THIS",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("cannot set value of implicit attribute '_xyz'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_doesNotSupportComputedDefaults() {
        ev.checkEvalErrorContains(
            "In macro attribute 'xyz': Macros do not support computed defaults or late-bound defaults",
            """
        def _impl(name, visibility, xyz): pass
        def _computed_default(): return "DEFAULT"
        my_macro = macro(
            implementation=_impl,
            attrs = {
              "xyz": attr.label(default=_computed_default)
            },
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_doesNotSupportLateBoundDefaults() {
        // We need to ensure there's a fragment registered on the BazelEvaluationTestCase for
        // `configuration_field()` to retrieve.
        //
        // (Ordinarily we would use the BuildViewTestCase machinery (scratch + getPackage()) and rely on
        // the analysis mock to register the fragment. But since our expected failure occurs during
        // .bzl loading, our test machinery doesn't process the error correctly, and instead
        // getPackage() returns null and no events are emitted.)
        ev.setFragmentNameToClass(
            com.google.common.collect.ImmutableMap.of<String?, java.lang.Class<*>?>(
                "coverage",
                CoverageConfiguration::class.java
            )
        )

        ev.checkEvalErrorContains(
            "In macro attribute 'xyz': Macros do not support computed defaults or late-bound defaults",
            """
        def _impl(name, visibility, xyz): pass
        _latebound_default = configuration_field(fragment = "coverage", name = "output_generator")
        my_macro = macro(
            implementation=_impl,
            attrs = {
              "xyz": attr.label(default=_latebound_default)
            },
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroFunctionApi() {
        evalAndExport(
            ev,
            """
        def _impl(name, visibility):
            pass
        exported = macro(
            implementation=_impl,
            doc = "Exported macro",
            attrs = {
                "abc": attr.int(),
                "xyz": attr.string(),
            }
        )
        s = struct(
            unexported = macro(
                implementation=_impl,
                doc = "Unexported macro",
                attrs = {
                    "abc": attr.int(),
                    "xyz": attr.string(),
                }
            ),
        )
        
        """.trimIndent()
        )

        val exported: MacroFunction = ev.lookup("exported") as MacroFunction
        val unexported: MacroFunction = ev.eval("s.unexported") as MacroFunction

        assertThat(exported.getName()).isEqualTo("exported")
        assertThat(unexported.getName()).isEqualTo("unexported macro")

        assertThat(exported.isExported()).isTrue()
        assertThat(unexported.isExported()).isFalse()

        Truth.assertThat(ev.eval("repr(exported)")).isEqualTo("<macro exported>")
        Truth.assertThat(ev.eval("repr(s.unexported)")).isEqualTo("<macro>")

        assertThat(exported.getDocumentation()).hasValue("Exported macro")
        assertThat(unexported.getDocumentation()).hasValue("Unexported macro")

        assertThat(exported.getExtensionLabel()).isEqualTo(FAKE_LABEL)
        assertThat(unexported.getExtensionLabel()).isNull()

        assertThat(exported.getMacroClass().getName()).isEqualTo("exported")
        assertThat(exported.getMacroClass().getAttributeProvider().getAttributes())
            .containsExactly(
                RuleClass.NAME_ATTRIBUTE,
                MacroClass.VISIBILITY_ATTRIBUTE,
                Attribute.attr("abc", Type.INTEGER).starlarkDefined().build(),
                Attribute.attr("xyz", Type.STRING).starlarkDefined().build()
            )
        assertThat(unexported.getMacroClass()).isNull()
    }

    @Throws(java.lang.Exception::class)
    private fun getRuleClass(name: String?): RuleClass {
        return (ev.lookup(name) as StarlarkRuleFunction).getRuleClass()
    }

    @Throws(java.lang.Exception::class)
    private fun registerDummyStarlarkFunction() {
        ev.exec("def impl():", "  pass")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithOnlyType() {
        val attr: Attribute? = buildAttribute("a1", "attr.string_list()")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getType()).isEqualTo(Types.STRING_LIST)
    }

    @Throws(java.lang.Exception::class)
    private fun buildAttribute(name: String?, vararg lines: String?): Attribute? {
        val strings: Array<String?> = lines.clone()
        strings[strings.size - 1] = String.format("%s = %s", name, strings[strings.size - 1])
        evalAndExport(ev, *strings)
        val lookup: StarlarkAttrModule.Descriptor? = ev.lookup(name) as StarlarkAttrModule.Descriptor?
        return if (lookup != null) lookup.build(name) else null
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputListAttr() {
        val attr: Attribute? = buildAttribute("a1", "attr.output_list()")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT_LIST)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIntListAttr() {
        val attr: Attribute? = buildAttribute("a1", "attr.int_list()")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getType()).isEqualTo(Types.INTEGER_LIST)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputAttr() {
        val attr: Attribute? = buildAttribute("a1", "attr.output()")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringDictAttr() {
        val attr: Attribute? = buildAttribute("a1", "attr.string_dict(default = {'a': 'b'})")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getType()).isEqualTo(Types.STRING_DICT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringListDictAttr() {
        val attr: Attribute? = buildAttribute("a1", "attr.string_list_dict(default = {'a': ['b', 'c']})")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getType()).isEqualTo(Types.STRING_LIST_DICT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrAllowedFileTypesAnyFile() {
        val attr: Attribute? = buildAttribute("a1", "attr.label_list(allow_files = True)")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getAllowedFileTypesPredicate()).isEqualTo(FileTypeSet.ANY_FILE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrAllowedFileTypesWrongType() {
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'bool, sequence, or NoneType'",
            "attr.label_list(allow_files = 18)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrNameSpecialCharactersAreForbidden() {
        ev.setFailFast(false)
        evalAndExport(ev, "def impl(ctx): return", "r = rule(impl, attrs = {'ab\$c': attr.int()})")
        ev.assertContainsError("attribute name `ab\$c` is not a valid identifier")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrNameCannotStartWithDigit() {
        ev.setFailFast(false)
        evalAndExport(ev, "def impl(ctx): return", "r = rule(impl, attrs = {'2_foo': attr.int()})")
        ev.assertContainsError("attribute name `2_foo` is not a valid identifier")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrEquality() {
        EqualsTester()
            .addEqualityGroup(
                buildAttribute("foo", "attr.string_list(default = [])"),
                buildAttribute("foo", "attr.string_list(default = [])")
            )
            .addEqualityGroup(
                buildAttribute("bar", "attr.string_list(default = [])"),
                buildAttribute("bar", "attr.string_list(default = [])")
            )
            .addEqualityGroup(
                buildAttribute("bar", "attr.label_list(default = [])"),
                buildAttribute("bar", "attr.label_list(default = [])")
            )
            .addEqualityGroup(
                buildAttribute("foo", "attr.string_list(default = ['hello'])"),
                buildAttribute("foo", "attr.string_list(default = ['hello'])")
            )
            .addEqualityGroup(
                buildAttribute("foo", "attr.string_list(doc = 'Blah blah blah', default = [])"),
                buildAttribute("foo", "attr.string_list(doc = 'Blah blah blah', default = [])")
            )
            .addEqualityGroup(
                buildAttribute("foo", "attr.string_list(mandatory = True, default = [])"),
                buildAttribute("foo", "attr.string_list(mandatory = True, default = [])")
            )
            .addEqualityGroup(
                buildAttribute("foo", "attr.string_list(allow_empty = False, default = [])"),
                buildAttribute("foo", "attr.string_list(allow_empty = False, default = [])")
            )
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassTooManyAttributes() {
        ev.setFailFast(false)

        val linesBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add("def impl(ctx): return")
                .add("r = rule(impl, attrs = {")
        for (i in 0..199) {
            linesBuilder.add("    'attr" + i + "': attr.int(),")
        }
        linesBuilder.add("})")

        evalAndExport(ev, *linesBuilder.build().toTypedArray<String?>())

        Truth.assertThat(ev.getEventCollector()).hasSize(1)
        val event: com.google.devtools.build.lib.events.Event = ev.getEventCollector().iterator().next()
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(event.getKind())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.ERROR)
        Truth.assertThat(event.getMessage()).contains("Rule class r declared too many attributes")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleClassTooLongAttributeName() {
        ev.setFailFast(false)

        evalAndExport(
            ev,
            "def impl(ctx): return;",
            "r = rule(impl, attrs = { '" + "x".repeat(150) + "': attr.int() })"
        )

        Truth.assertThat(ev.getEventCollector()).hasSize(1)
        val event: com.google.devtools.build.lib.events.Event = ev.getEventCollector().iterator().next()
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(event.getKind())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.ERROR)
        Truth.assertThat(event.getLocation().toString()).isEqualTo(":2:9")
        Truth.assertThat(event.getMessage())
            .matches("Attribute r\\.x{150}'s name is too long \\(150 > 128\\)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrAllowedSingleFileTypesWrongType() {
        ev.checkEvalErrorContains(
            "allow_single_file should be a boolean or a string list",
            "attr.label(allow_single_file = 18)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithList() {
        val attr: Attribute? = buildAttribute("a1", "attr.label_list(allow_files = ['.xml'])")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue()
        assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse()
        assertThat(attr.isSingleArtifact()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrSingleFileWithList() {
        val attr: Attribute? = buildAttribute("a1", "attr.label(allow_single_file = ['.xml'])")
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue()
        assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse()
        assertThat(attr.isSingleArtifact()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleCannotSetConfigurableOnAttr() {
        scratch.file("lib/BUILD")
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_rule = rule(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(configurable=False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_rule")
        my_rule(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNull()
        assertContainsEvent(
            "attribute 'xyz' has the 'configurable' argument set, which is not allowed in"
                    + " rule definitions"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectCannotSetConfigurableOnAttr() {
        scratch.file("lib/BUILD")
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_aspect = aspect(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(configurable=False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_aspect")
        my_aspect(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg).isNull()
        assertContainsEvent(
            "attribute 'xyz' has the 'configurable' argument set, which is not allowed in aspect"
                    + " definitions"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithProviders() {
        val attr: Attribute? =
            buildAttribute(
                "a1",  //
                "a = provider()",
                "b = provider()",
                "attr.label_list(allow_files = True, providers = [a, b])"
            )
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getRequiredProviders().isSatisfiedBy(set(declared("a"), declared("b"))))
            .isTrue()
        assertThat(attr.getRequiredProviders().isSatisfiedBy(set(declared("a")))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithProvidersOneEmpty() {
        val attr: Attribute? =
            buildAttribute(
                "a1",
                "a = provider()",
                "b = provider()",
                "attr.label_list(allow_files = True, providers = [[a, b],[]])"
            )
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getRequiredProviders().acceptsAny()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithProvidersList() {
        val attr: Attribute? =
            buildAttribute(
                "a1",
                "a = provider()",
                "b = provider()",
                "c = provider()",
                "attr.label_list(allow_files = True, providers = [[a, b], [c]])"
            )
        assertThat(attr.starlarkDefined()).isTrue()
        assertThat(attr.getRequiredProviders().isSatisfiedBy(set(declared("a"), declared("b"))))
            .isTrue()
        assertThat(attr.getRequiredProviders().isSatisfiedBy(set(declared("c")))).isTrue()
        assertThat(attr.getRequiredProviders().isSatisfiedBy(set(declared("a")))).isFalse()
    }

    @Throws(java.lang.Exception::class)
    private fun checkAttributeError(expectedMessage: String?, vararg lines: String?) {
        ev.setFailFast(false)
        buildAttribute("fakeAttribute", *lines)
        assertContainsEvent(ev.getEventCollector(), expectedMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithWrongProvidersList() {
        checkAttributeError(
            "Error in label_list: at index 1 of providers, got element of type int, want Provider",
            "a = provider()",
            "c = provider()",
            "attr.label_list(allow_files = True,  providers = [[a, 1], [c]])"
        )

        checkAttributeError(
            "Error in label_list: at index 1 of providers, got element of type string, want sequence",
            "b = provider()",
            "attr.label_list(allow_files = True,  providers = [['a', b], 'c'])"
        )

        checkAttributeError(
            "Error in label_list: at index 1 of providers, got element of type Provider, want sequence",
            "a = provider()",
            "b = provider()",
            "c = provider()",
            "attr.label_list(allow_files = True,  providers = [[a, b], c])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListWithAspects() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(implementation = _impl)",
            "a = attr.label_list(aspects = [my_aspect])"
        )
        val attr: StarlarkAttrModule.Descriptor = ev.lookup("a") as StarlarkAttrModule.Descriptor
        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        assertThat(aspect).isNotNull()
        assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelWithAspects() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(implementation = _impl)",
            "a = attr.label(aspects = [my_aspect])"
        )
        val attr: StarlarkAttrModule.Descriptor = ev.lookup("a") as StarlarkAttrModule.Descriptor
        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        assertThat(aspect).isNotNull()
        assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListWithAspectsError() {
        ev.setThreadOwner(keyForBuild(FAKE_LABEL))
        ev.checkEvalErrorContains(
            "at index 1 of aspects, got element of type int, want Aspect",
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(implementation = _impl)",
            "attr.label_list(aspects = [my_aspect, 123])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithAspectRequiringAspects_stackOfRequiredAspects() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "aspect_c = aspect(implementation = _impl)",
            "aspect_b = aspect(implementation = _impl, requires = [aspect_c])",
            "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
            "a = attr.label_list(aspects = [aspect_a])"
        )
        val attr: StarlarkAttrModule.Descriptor = ev.lookup("a") as StarlarkAttrModule.Descriptor

        val aspectA: StarlarkDefinedAspect = ev.lookup("aspect_a") as StarlarkDefinedAspect
        assertThat(aspectA).isNotNull()
        val aspectB: StarlarkDefinedAspect = ev.lookup("aspect_b") as StarlarkDefinedAspect
        assertThat(aspectB).isNotNull()
        val aspectC: StarlarkDefinedAspect = ev.lookup("aspect_c") as StarlarkDefinedAspect
        assertThat(aspectC).isNotNull()
        val expectedAspects: MutableList<AspectClass?> =
            java.util.Arrays.asList<T?>(aspectA.getAspectClass(), aspectB.getAspectClass(), aspectC.getAspectClass())
        assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithAspectRequiringAspects_aspectRequiredByMultipleAspects() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "aspect_c = aspect(implementation = _impl)",
            "aspect_b = aspect(implementation = _impl, requires = [aspect_c])",
            "aspect_a = aspect(implementation = _impl, requires = [aspect_c])",
            "a = attr.label_list(aspects = [aspect_a, aspect_b])"
        )
        val attr: StarlarkAttrModule.Descriptor = ev.lookup("a") as StarlarkAttrModule.Descriptor

        val aspectA: StarlarkDefinedAspect = ev.lookup("aspect_a") as StarlarkDefinedAspect
        assertThat(aspectA).isNotNull()
        val aspectB: StarlarkDefinedAspect = ev.lookup("aspect_b") as StarlarkDefinedAspect
        assertThat(aspectB).isNotNull()
        val aspectC: StarlarkDefinedAspect = ev.lookup("aspect_c") as StarlarkDefinedAspect
        assertThat(aspectC).isNotNull()
        val expectedAspects: MutableList<AspectClass?> =
            java.util.Arrays.asList<T?>(aspectA.getAspectClass(), aspectB.getAspectClass(), aspectC.getAspectClass())
        assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithAspectRequiringAspects_aspectRequiredByMultipleAspects2() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "aspect_d = aspect(implementation = _impl)",
            "aspect_c = aspect(implementation = _impl, requires = [aspect_d])",
            "aspect_b = aspect(implementation = _impl, requires = [aspect_d])",
            "aspect_a = aspect(implementation = _impl, requires = [aspect_b, aspect_c])",
            "a = attr.label_list(aspects = [aspect_a])"
        )
        val attr: StarlarkAttrModule.Descriptor = ev.lookup("a") as StarlarkAttrModule.Descriptor

        val aspectA: StarlarkDefinedAspect = ev.lookup("aspect_a") as StarlarkDefinedAspect
        assertThat(aspectA).isNotNull()
        val aspectB: StarlarkDefinedAspect = ev.lookup("aspect_b") as StarlarkDefinedAspect
        assertThat(aspectB).isNotNull()
        val aspectC: StarlarkDefinedAspect = ev.lookup("aspect_c") as StarlarkDefinedAspect
        assertThat(aspectC).isNotNull()
        val aspectD: StarlarkDefinedAspect = ev.lookup("aspect_d") as StarlarkDefinedAspect
        assertThat(aspectD).isNotNull()
        val expectedAspects: MutableList<AspectClass?> =
            java.util.Arrays.asList<T?>(
                aspectA.getAspectClass(),
                aspectB.getAspectClass(),
                aspectC.getAspectClass(),
                aspectD.getAspectClass()
            )
        assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithAspectRequiringAspects_requireExistingAspect_passed() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "aspect_b = aspect(implementation = _impl)",
            "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
            "a = attr.label_list(aspects = [aspect_b, aspect_a])"
        )
        val attr: StarlarkAttrModule.Descriptor = ev.lookup("a") as StarlarkAttrModule.Descriptor

        val aspectA: StarlarkDefinedAspect = ev.lookup("aspect_a") as StarlarkDefinedAspect
        assertThat(aspectA).isNotNull()
        val aspectB: StarlarkDefinedAspect = ev.lookup("aspect_b") as StarlarkDefinedAspect
        assertThat(aspectB).isNotNull()
        val expectedAspects: MutableList<AspectClass?> =
            java.util.Arrays.asList<T?>(aspectA.getAspectClass(), aspectB.getAspectClass())
        assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithAspectRequiringAspects_requireExistingAspect_failed() {
        ev.setFailFast(false)

        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "aspect_b = aspect(implementation = _impl)",
            "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
            "attr.label_list(aspects = [aspect_a, aspect_b])"
        )

        ev.assertContainsError(
            String.format(
                "aspect %s%%aspect_b was added before as a required aspect of aspect %s%%aspect_a",
                FAKE_LABEL, FAKE_LABEL
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectExtraDeps() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { '_extra_deps' : attr.label(default = Label('//foo/bar:baz')) }",
            ")"
        )
        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attribute: Attribute? = com.google.common.collect.Iterables.getOnlyElement<T?>(aspect.getAttributes())
        assertThat(attribute.name).isEqualTo("\$extra_deps")
        assertThat(attribute.getDefaultValue(null))
            .isEqualTo(Label.parseCanonicalUnchecked("//foo/bar:baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectParameter() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { 'param' : attr.string(values=['a', 'b']) }",
            ")"
        )
        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attribute: Attribute? = com.google.common.collect.Iterables.getOnlyElement<T?>(aspect.getAttributes())
        assertThat(attribute.name).isEqualTo("param")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectParameterWithDefaultValue() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { 'param' : attr.string(default = 'a', values=['a', 'b']) }",
            ")"
        )
        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attribute: Attribute? = com.google.common.collect.Iterables.getOnlyElement<T?>(aspect.getAttributes())
        assertThat(attribute.name).isEqualTo("param")
        Truth.assertThat((attribute.defaultValueUnchecked as String?)).isEqualTo("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectParameterBadDefaultValue() {
        ev.checkEvalErrorContains(
            "Aspect parameter attribute 'param' has a bad default value: has to be"
                    + " one of 'b' instead of 'a'",
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { 'param' : attr.string(default = 'a', values = ['b']) }",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectParameterNotRequireValues() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { 'param' : attr.string(default = 'val') }",
            ")"
        )
        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attribute: Attribute? = com.google.common.collect.Iterables.getOnlyElement<T?>(aspect.getAttributes())
        assertThat(attribute.name).isEqualTo("param")
        Truth.assertThat((attribute.defaultValueUnchecked as String?)).isEqualTo("val")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectParameterBadType() {
        ev.checkEvalErrorContains(
            "Aspect parameter attribute 'param' must have type 'bool', 'int' or 'string'.",
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { 'param' : attr.label(default = Label('//foo/bar:baz')) }",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectParameterAndExtraDeps() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { 'param' : attr.string(values=['a', 'b']),",
            "             '_extra' : attr.label(default = Label('//foo/bar:baz')) }",
            ")"
        )
        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        assertThat(aspect.getAttributes()).hasSize(2)
        assertThat(aspect.getParamAttributes()).containsExactly("param")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectNoDefaultValueAttribute() {
        ev.checkEvalErrorContains(
            "Aspect attribute '_extra_deps' has no default value",
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   attrs = { '_extra_deps' : attr.label() }",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectAddToolchain() {
        evalAndExport(
            ev,
            "def _impl(ctx): pass",
            "a1 = aspect(_impl,",
            "    toolchains=[",
            "        '//test:my_toolchain_type1',",
            "        config_common.toolchain_type('//test:my_toolchain_type2'),",
            "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
            "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
            "    ],",
            ")"
        )
        val a: StarlarkDefinedAspect? = ev.lookup("a1") as StarlarkDefinedAspect?
        assertThat(a).hasToolchainType("//test:my_toolchain_type1")
        assertThat(a).toolchainType("//test:my_toolchain_type1").isMandatory()
        assertThat(a).hasToolchainType("//test:my_toolchain_type2")
        assertThat(a).toolchainType("//test:my_toolchain_type2").isMandatory()
        assertThat(a).hasToolchainType("//test:my_toolchain_type3")
        assertThat(a).toolchainType("//test:my_toolchain_type3").isOptional()
        assertThat(a).hasToolchainType("//test:my_toolchain_type4")
        assertThat(a).toolchainType("//test:my_toolchain_type4").isMandatory()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonLabelAttrWithProviders() {
        ev.checkEvalErrorContains(
            "unexpected keyword argument 'providers'", "attr.string(providers = ['a'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrAllowedRuleClassesSpecificRuleClasses() {
        val attr: Attribute? =
            buildAttribute(
                "a",  //
                "attr.label_list(allow_rules = ['java_binary'], allow_files = True)"
            )
        assertThat(attr.getAllowedRuleClassObjectPredicate().apply(ruleClass("java_binary"))).isTrue()
        assertThat(attr.getAllowedRuleClassObjectPredicate().apply(ruleClass("genrule"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrDefaultValue() {
        val attr: Attribute? = buildAttribute("a1", "attr.string(default = 'some value')")
        assertThat(attr.defaultValueUnchecked).isEqualTo("some value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelAttrDefaultValueAsString() {
        val sligleAttr: Attribute? = buildAttribute("a1", "attr.label(default = '//foo:bar')")
        assertThat(sligleAttr.defaultValueUnchecked)
            .isEqualTo(Label.parseCanonicalUnchecked("//foo:bar"))

        val listAttr: Attribute? =
            buildAttribute("a2", "attr.label_list(default = ['//foo:bar', '//bar:foo'])")
        assertThat(listAttr.defaultValueUnchecked)
            .isEqualTo(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked("//foo:bar"),
                    Label.parseCanonicalUnchecked("//bar:foo")
                )
            )

        val dictAttr: Attribute? =
            buildAttribute("a3", "attr.label_keyed_string_dict(default = {'//foo:bar': 'my value'})")
        assertThat(dictAttr.defaultValueUnchecked)
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//foo:bar"),
                    "my value"
                )
            )

        val labelListDictAttr: Attribute? =
            buildAttribute(
                "a4",
                "attr.label_list_dict(default = {'my': ['//foo:bar', '//bar:foo'], 'value':"
                        + " ['//bar:foo']})"
            )
        assertThat(labelListDictAttr.defaultValueUnchecked)
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "my",
                    com.google.common.collect.ImmutableList.of<E?>(
                        Label.parseCanonicalUnchecked("//foo:bar"),
                        Label.parseCanonicalUnchecked("//bar:foo")
                    ),
                    "value",
                    com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonicalUnchecked("//bar:foo"))
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelAttrDefaultValueAsStringBadValue() {
        ev.checkEvalErrorContains(
            "invalid label '/foo:bar' in parameter 'default' of attribute 'label': "
                    + "invalid package name '/foo': package names may not start with '/'",
            "attr.label(default = '/foo:bar')"
        )

        ev.checkEvalErrorContains(
            "invalid label '/bar:foo' in element 1 of parameter 'default' of attribute "
                    + "'label_list': invalid package name '/bar': package names may not start with '/'",
            "attr.label_list(default = ['//foo:bar', '/bar:foo'])"
        )

        ev.checkEvalErrorContains(
            "invalid label '/bar:foo' in dict key element: invalid package name '/bar': "
                    + "package names may not start with '/'",
            "attr.label_keyed_string_dict(default = {'//foo:bar': 'a', '/bar:foo': 'b'})"
        )

        ev.checkEvalErrorContains(
            "invalid label '/bar:foo' in element 1 of dict value element: invalid package name"
                    + " '/bar': package names may not start with '/'",
            "attr.label_list_dict(default = {'my': ['//foo:bar', '/bar:foo']})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrDefaultValueBadType() {
        ev.checkEvalErrorContains("got value of type 'int', want 'string'", "attr.string(default = 1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrMandatory() {
        val attr: Attribute? = buildAttribute("a1", "attr.string(mandatory=True)")
        assertThat(attr.isMandatory()).isTrue()
        assertThat(attr.isNonEmpty()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrAllowEmpty() {
        val attr: Attribute? = buildAttribute("a1", "attr.string_list(allow_empty=False)")
        assertThat(attr.isNonEmpty()).isTrue()
        assertThat(attr.isMandatory()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrBadKeywordArguments() {
        ev.checkEvalErrorContains(
            "string() got unexpected keyword argument 'bad_keyword'", "attr.string(bad_keyword = '')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrCfgHostDisabled() {
        setBuildLanguageOptions("--incompatible_disable_starlark_host_transitions")

        val ex: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("attr.label(cfg = 'host')") })
        Truth.assertThat(ex).hasMessageThat().contains("Please use 'cfg = \"exec\"' instead")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrCfgTarget_string() {
        val attr: Attribute? = buildAttribute("a1", "attr.label(cfg = 'target', allow_files = True)")
        assertThat(NoTransition.isInstance(attr.getTransitionFactory())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrCfgTarget_object() {
        val attr: Attribute? = buildAttribute("a1", "attr.label(cfg = config.target(), allow_files = True)")
        assertThat(NoTransition.isInstance(attr.getTransitionFactory())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrCfgNone() {
        val attr: Attribute? = buildAttribute("a1", "attr.label(cfg = config.none(), allow_files = True)")
        assertThat(NoConfigTransition.isInstance(attr.getTransitionFactory())).isTrue()
    }

    @Throws(java.lang.Exception::class)
    private fun writeRuleCfgTestRule(cfg: String?) {
        scratch.file(
            "rule_testing/rule.bzl",
            """
        def _impl(ctx):
            pass

        demo_rule = rule(
            implementation = _impl,
            cfg = %s,
        )
        
        """
                .trimIndent()
                .formatted(cfg)
        )
        scratch.file(
            "rule_testing/BUILD",
            """
        load(":rule.bzl", "demo_rule")

        demo_rule(name = "my_target")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleCfg_exec_string_fails() {
        writeRuleCfgTestRule("'exec'")

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//rule_testing:my_target")

        ev.assertContainsError("`cfg` must be set to a transition object")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleCfg_exec_obj_fails() {
        writeRuleCfgTestRule("config.exec()")

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//rule_testing:my_target")

        ev.assertContainsError("`cfg` must be set to a transition object")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleCfg_starlark() {
        scratchStarlarkTransition()
        scratch.file(
            "rule_testing/rule.bzl",
            """
        load("//test:transitions.bzl", "parent_transition")

        def _impl(ctx):
            pass

        demo_rule = rule(
            implementation = _impl,
            cfg = parent_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "rule_testing/BUILD",
            """
        load(":rule.bzl", "demo_rule")

        demo_rule(name = "my_target")
        
        """.trimIndent()
        )

        val configuration: BuildConfigurationValue =
            getConfiguration(getConfiguredTarget("//rule_testing:my_target"))

        val options: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            configuration.getOptions().getStarlarkOptions()
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag")))
            .isEqualTo("parent-changed")
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
            .isEqualTo("parent-child-changed-in-parent")
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag"))).isNull()
    }

    @org.junit.Test
    fun incompatibleDataTransition() {
        val expected: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("attr.label(cfg = 'data')") })
        Truth.assertThat(expected).hasMessageThat().contains("cfg must be either 'target', 'exec'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrValues() {
        val attr: Attribute? = buildAttribute("a1", "attr.string(values = ['ab', 'cd'])")
        val predicate: PredicateWithMessage<Any?> = attr.getAllowedValues()
        assertThat(predicate.apply("ab")).isTrue()
        assertThat(predicate.apply("xy")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrIntValues() {
        val attr: Attribute? = buildAttribute("a1", "attr.int(values = [1, 2])")
        val predicate: PredicateWithMessage<Any?> = attr.getAllowedValues()
        assertThat(predicate.apply(StarlarkInt.of(2))).isTrue()
        assertThat(predicate.apply(StarlarkInt.of(3))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrDoc(
        @TestParameter(
            "bool",
            "int",
            "int_list",
            "label",
            "label_keyed_string_dict",
            "label_list",
            "label_list_dict",
            "output",
            "output_list",
            "string",
            "string_dict",
            "string_list",
            "string_list_dict"
        ) attrType: String?
    ) {
        val documented: Attribute? =
            buildAttribute("documented", String.format("attr.%s(doc='foo')", attrType))
        assertThat(documented.doc).isEqualTo("foo")
        val documentedNeedingDedent: Attribute? =
            buildAttribute(
                "documented",
                String.format("attr.%s(doc='''foo\n\n    More details.\n    ''')", attrType)
            )
        assertThat(documentedNeedingDedent.doc).isEqualTo("foo\n\nMore details.")
        val undocumented: Attribute? = buildAttribute("undocumented", String.format("attr.%s()", attrType))
        assertThat(undocumented.doc).isNull()
    }

    @org.junit.Test
    fun testNoAttrLicense() {
        val expected: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("attr.license()") })
        Truth.assertThat(expected).hasMessageThat().contains("'attr' value has no field or method 'license'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrDocValueBadType() {
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'string or NoneType'", "attr.string(doc = 1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleImplementation() {
        evalAndExport(ev, "def impl(ctx): return None", "rule1 = rule(impl)")
        val c: RuleClass = (ev.lookup("rule1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c.getConfiguredTargetFunction().getName()).isEqualTo("impl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleDoc() {
        evalAndExport(
            ev,
            "def impl(ctx):",
            "    return None",
            "documented_rule = rule(impl, doc = 'My doc string')",
            "long_documented_rule = rule(",
            "    impl,",
            "    doc = '''Long doc",
            "",
            "             With details",
            "''',",
            ")",
            "undocumented_rule = rule(impl)"
        )
        val documentedRule: StarlarkRuleFunction = ev.lookup("documented_rule") as StarlarkRuleFunction
        val longDocumentedRule: StarlarkRuleFunction =
            ev.lookup("long_documented_rule") as StarlarkRuleFunction
        val undocumentedRule: StarlarkRuleFunction = ev.lookup("undocumented_rule") as StarlarkRuleFunction
        assertThat(documentedRule.getRuleClass().getStarlarkDocumentation()).isEqualTo("My doc string")
        assertThat(longDocumentedRule.getRuleClass().getStarlarkDocumentation())
            .isEqualTo("Long doc\n\nWith details")
        assertThat(undocumentedRule.getRuleClass().getStarlarkDocumentation()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkLabelAndName() {
        scratch.file(
            "p/rule_meta_constructor.bzl",
            """
        def rule_meta_constructor(impl):
            return rule(impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/rule_definition.bzl",
            """
        load("rule_meta_constructor.bzl", "rule_meta_constructor")

        def _impl(ctx):
            pass

        original_rule_symbol = rule_meta_constructor(_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/rule_alias.bzl",
            """
        load("rule_definition.bzl", "original_rule_symbol")

        aliased_rule_symbol = original_rule_symbol
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load("rule_alias.bzl", "aliased_rule_symbol")

        aliased_rule_symbol(name = "p")
        
        """.trimIndent()
        )

        val ruleClass: RuleClass = createRuleContext("//p").getRuleClassUnderEvaluation()
        assertThat(ruleClass.getRuleDefinitionEnvironmentLabel())
            .isEqualTo(Label.parseCanonicalUnchecked("//p:rule_definition.bzl"))
        assertThat(ruleClass.getStarlarkExtensionLabel())
            .isEqualTo(Label.parseCanonicalUnchecked("//p:rule_definition.bzl"))
        assertThat(ruleClass.getName()).isEqualTo("original_rule_symbol")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionAsAttrDefault() {
        ev.exec("def f(): pass")

        // Late-bound attributes, which are computed during analysis as a function
        // of the configuration, are only available for attributes involving labels:
        //   attr.label
        //   attr.label_list
        //   attr.label_keyed_string_dict
        //   attr.output,
        //   attr.output_list
        // (See testRuleClassImplicitOutputFunctionDependingOnComputedAttribute
        // for a more detailed positive test.)
        evalAndExport(
            ev,
            "attr.label(default=f)",
            "attr.label_list(default=f)",
            "attr.label_keyed_string_dict(default=f)"
        )
        // For all other attribute types, the default value may not be a function.
        //
        // (This is a regression test for github.com/bazelbuild/bazel/issues/9463.
        // The loading-phase feature of "computed attribute defaults" is not exposed
        // to Starlark; the bug was that the @StarlarkMethod
        // annotation was more permissive than the method declaration.)
        ev.checkEvalErrorContains(
            "got value of type 'function', want 'string'", "attr.string(default=f)"
        )
        ev.checkEvalErrorContains(
            "got value of type 'function', want 'sequence'", "attr.string_list(default=f)"
        )
        ev.checkEvalErrorContains("got value of type 'function', want 'int'", "attr.int(default=f)")
        ev.checkEvalErrorContains(
            "got value of type 'function', want 'sequence'", "attr.int_list(default=f)"
        )
        ev.checkEvalErrorContains("got value of type 'function', want 'bool'", "attr.bool(default=f)")
        ev.checkEvalErrorContains(
            "got value of type 'function', want 'dict'", "attr.string_dict(default=f)"
        )
        ev.checkEvalErrorContains(
            "got value of type 'function', want 'dict'", "attr.string_list_dict(default=f)"
        )
        // Note: attr.license appears to be disabled already.
        // (see --incompatible_no_attr_license)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAddAttribute() {
        evalAndExport(ev, "def impl(ctx): return None", "r1 = rule(impl, attrs={'a1': attr.string()})")
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c.getAttributeProvider().hasAttr("a1", Type.STRING)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportAliasedName() {
        // When there are multiple names aliasing the same StarlarkExportable, the first one to be
        // declared should be used. Make sure we're not using lexicographical order, hash order,
        // non-deterministic order, or anything else.
        evalAndExport(
            ev,
            "def _impl(ctx): pass",
            "d = rule(implementation = _impl)",
            "a = d",  // Having more names improves the chance that non-determinism will be caught.
            "b = d",
            "c = d",
            "e = d",
            "f = d",
            "foo = d",
            "bar = d",
            "baz = d",
            "x = d",
            "y = d",
            "z = d"
        )
        val dName: String? = (ev.lookup("d") as StarlarkRuleFunction).getRuleClass().getName()
        val fooName: String? = (ev.lookup("foo") as StarlarkRuleFunction).getRuleClass().getName()
        Truth.assertThat(dName).isEqualTo("d")
        Truth.assertThat(fooName).isEqualTo("d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputToGenfiles() {
        evalAndExport(ev, "def impl(ctx): pass", "r1 = rule(impl, output_to_genfiles=True)")
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c.outputsToBindir()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAddMultipleAttributes() {
        evalAndExport(
            ev,
            "def impl(ctx): return None",
            "r1 = rule(impl,",
            "     attrs = {",
            "            'a1': attr.label_list(allow_files=True),",
            "            'a2': attr.int()",
            "})"
        )
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c.getAttributeProvider().hasAttr("a1", BuildType.LABEL_LIST)).isTrue()
        assertThat(c.getAttributeProvider().hasAttr("a2", Type.INTEGER)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAttributeFlag() {
        evalAndExport(
            ev,
            "def impl(ctx): return None",
            "r1 = rule(impl, attrs = {'a1': attr.string(mandatory=True)})"
        )
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c.getAttributeProvider().getAttributeByName("a1").isMandatory()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownRuleAttributeFlags_forbidden() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "def _impl(ctx): return None",
            "r1 = rule(_impl, attrs = { 'srcs': attr.label_list(flags = ['NO-SUCH-FLAG']) })"
        )
        ev.assertContainsError("unknown attribute flag 'NO-SUCH-FLAG'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleOutputs() {
        evalAndExport(
            ev,
            "def impl(ctx): return None",  //
            "r1 = rule(impl, outputs = {'a': 'a.txt'})"
        )
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        val function: ImplicitOutputsFunction = c.getDefaultImplicitOutputsFunction()
        assertThat(function.getImplicitOutputs(ev.getEventHandler(), null)).containsExactly("a.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleUnknownKeyword() {
        registerDummyStarlarkFunction()
        ev.checkEvalErrorContains(
            "unexpected keyword argument 'bad_keyword'", "rule(impl, bad_keyword = 'some text')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleImplementationMissing() {
        ev.checkEvalErrorContains(
            "rule() missing 1 required positional argument: implementation", "rule(attrs = {})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleBadTypeForAdd() {
        registerDummyStarlarkFunction()
        ev.checkEvalErrorContains(
            "in call to rule(), parameter 'attrs' got value of type 'string', want 'dict'",
            "rule(impl, attrs = 'some text')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleBadTypeInAdd() {
        registerDummyStarlarkFunction()
        ev.checkEvalErrorContains(
            "got dict<string, string> for 'attrs', want dict<string, Attribute>",
            "rule(impl, attrs = {'a1': 'some text'})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleBadTypeForDoc() {
        registerDummyStarlarkFunction()
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'string or NoneType'", "rule(impl, doc = 1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabel() {
        val result: Any = ev.eval("Label('//foo/foo:foo')")
        Truth.assertThat(result).isInstanceOf(Label::class.java)
        Truth.assertThat(result.toString()).isEqualTo("//foo/foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelIdempotence() {
        val result: Any = ev.eval("Label(Label('//foo/foo:foo'))")
        Truth.assertThat(result).isInstanceOf(Label::class.java)
        Truth.assertThat(result.toString()).isEqualTo("//foo/foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelSameInstance() {
        val l1: Any = ev.eval("Label('//foo/foo:foo')")
        // Implicitly creates a new pkgContext and environment, yet labels should be the same.
        val l2: Any = ev.eval("Label('//foo/foo:foo')")
        Truth.assertThat(l1).isSameInstanceAs(l2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelNameAndPackage() {
        var result: Any = ev.eval("Label('//foo/bar:baz').name")
        Truth.assertThat(result).isEqualTo("baz")
        // NB: implicitly creates a new pkgContext and environments, yet labels should be the same.
        result = ev.eval("Label('//foo/bar:baz').package")
        Truth.assertThat(result).isEqualTo("foo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelReprRoundTrip() {
        // TODO(bazel-team): Test that an actual Label object can be repr'd and then eval'd in any
        // context (in particular - in a non-main-repo context) to arrive back at the original Label.
        val labelRepr = "Label(\"@@//:foo\")"
        Truth.assertThat(ev.eval(labelRepr)).isInstanceOf(Label::class.java)
        Truth.assertThat(ev.eval(String.format("repr(%s)", labelRepr))).isEqualTo(labelRepr)

        setBuildLanguageOptions("--noincompatible_unambiguous_label_stringification")
        Truth.assertThat(ev.eval(String.format("repr(%s)", labelRepr))).isNotEqualTo(labelRepr)
        Truth.assertThat(ev.eval(String.format("repr(%s)", labelRepr)) as String?).doesNotContain("@@")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleLabelDefaultValue() {
        evalAndExport(
            ev,
            ("def impl(ctx): return None\n"
                    + "r1 = rule(impl, attrs = {'a1': "
                    + "attr.label(default = Label('//foo:foo'), allow_files=True)})")
        )
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        val a: Attribute = c.getAttributeProvider().getAttributeByName("a1")
        assertThat(a.defaultValueUnchecked).isInstanceOf(Label::class.java)
        assertThat(a.defaultValueUnchecked.toString()).isEqualTo("//foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIntDefaultValue() {
        evalAndExport(
            ev,
            "def impl(ctx): return None",
            "r1 = rule(impl, attrs = {'a1': attr.int(default = 40+2)})"
        )
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        val a: Attribute = c.getAttributeProvider().getAttributeByName("a1")
        assertThat(a.defaultValueUnchecked).isEqualTo(StarlarkInt.of(42))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIntDefaultValueMustBeInt32() {
        // This is a test of the loading phase. Move somewhere more appropriate.
        ev.checkEvalErrorContains(
            "for parameter 'default' of attribute '', got 4294967296, want value in signed 32-bit"
                    + " range",
            "attr.int(default = 0x10000 * 0x10000)"
        )
        ev.checkEvalErrorContains(
            "for element 0 of parameter 'default' of attribute '', got 4294967296, want value in"
                    + " signed 32-bit range",
            "attr.int_list(default = [0x10000 * 0x10000])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIntAttributeValueMustBeInt32() {
        // This is a test of the loading phase. Move somewhere more appropriate.
        scratch.file(
            "p/inc.bzl",
            """
        def _impl(ctx):
            pass

        r = rule(_impl, attrs = dict(i = attr.int()))
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load("inc.bzl", "r")

        r(
            name = "p",
            i = 0x10000 * 0x10000,
        )
        
        """.trimIndent()
        )
        val expected: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { createRuleContext("//p") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("for attribute 'i' of 'r', got 4294967296, want value in signed 32-bit range")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIntegerConcatTruncates() {
        // The Type.INTEGER.concat operator, as used to resolve select(int)+select(int)
        // after rule construction, has a range of int32.
        scratch.file(
            "p/BUILD",
            """
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        # -0x7fffffff + -0x7fffffff = 2
        s = select({"//conditions:default": -0x7fffffff})

        cc_test(
            name = "c",
            shard_count = s + s,
        )
        
        """.trimIndent()
        )
        val context: StarlarkRuleContext = createRuleContext("//p:c")
        assertThat(context.getAttr().getValue("shard_count")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleInheritsBaseRuleAttributes() {
        evalAndExport(ev, "def impl(ctx): return None", "r1 = rule(impl)")
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c.getAttributeProvider().hasAttr("tags", Types.STRING_LIST)).isTrue()
        assertThat(c.getAttributeProvider().hasAttr("visibility", BuildType.NODEP_LABEL_LIST)).isTrue()
        assertThat(c.getAttributeProvider().hasAttr("deprecation", Type.STRING)).isTrue()
        assertThat(c.getAttributeProvider().hasAttr(":action_listener", BuildType.LABEL_LIST))
            .isTrue() // required for extra actions
    }

    @Throws(java.lang.Exception::class)
    private fun checkTextMessage(from: String?, vararg lines: String?) {
        val result: Any = ev.eval(from)
        var expect = ""
        if (lines.size > 0) {
            expect = com.google.common.base.Joiner.on("\n").join(lines) + "\n"
        }
        Truth.assertThat(result).isEqualTo(expect)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleTextMessages() {
        checkTextMessage("proto.encode_text(struct(name='value'))", "name: \"value\"")
        checkTextMessage("proto.encode_text(struct(name=[]))") // empty lines
        checkTextMessage("proto.encode_text(struct(name=['a', 'b']))", "name: \"a\"", "name: \"b\"")
        checkTextMessage("proto.encode_text(struct(name=123))", "name: 123")
        checkTextMessage(
            "proto.encode_text(struct(a=1.2e34, b=float('nan'), c=float('-inf'), d=float('+inf')))",
            "a: 1.2e+34",
            "b: nan",
            "c: -inf",  // Caution! textproto requires +inf be encoded as "inf" rather than "+inf"
            "d: inf"
        )
        checkTextMessage("proto.encode_text(struct(name=123))", "name: 123")
        checkTextMessage("proto.encode_text(struct(name=[1, 2, 3]))", "name: 1", "name: 2", "name: 3")
        checkTextMessage("proto.encode_text(struct(a=struct(b='b')))", "a {", "  b: \"b\"", "}")
        checkTextMessage(
            "proto.encode_text(struct(a=[struct(b='x'), struct(b='y')]))",
            "a {",
            "  b: \"x\"",
            "}",
            "a {",
            "  b: \"y\"",
            "}"
        )
        checkTextMessage(
            "proto.encode_text(struct(a=struct(b=struct(c='c'))))",
            "a {",
            "  b {",
            "    c: \"c\"",
            "  }",
            "}"
        )
        // dict to_proto tests
        checkTextMessage("proto.encode_text(struct(name={}))") // empty lines
        checkTextMessage(
            "proto.encode_text(struct(name={'a': 'b'}))",
            "name {",
            "  key: \"a\"",
            "  value: \"b\"",
            "}"
        )
        checkTextMessage(
            "proto.encode_text(struct(name={'c': 'd', 'a': 'b'}))",
            "name {",
            "  key: \"c\"",
            "  value: \"d\"",
            "}",
            "name {",
            "  key: \"a\"",
            "  value: \"b\"",
            "}"
        )
        checkTextMessage(
            "proto.encode_text(struct(x=struct(y={'a': 1})))",
            "x {",
            "  y {",
            "    key: \"a\"",
            "    value: 1",
            "  }",
            "}"
        )
        checkTextMessage(
            "proto.encode_text(struct(name={'a': struct(b=1, c=2)}))",
            "name {",
            "  key: \"a\"",
            "  value {",
            "    b: 1",
            "    c: 2",
            "  }",
            "}"
        )
        checkTextMessage(
            "proto.encode_text(struct(name={'a': struct(b={4: 'z', 3: 'y'}, c=2)}))",
            "name {",
            "  key: \"a\"",
            "  value {",
            "    b {",
            "      key: 4",
            "      value: \"z\"",
            "    }",
            "    b {",
            "      key: 3",
            "      value: \"y\"",
            "    }",
            "    c: 2",
            "  }",
            "}"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoneStructValue() {
        checkTextMessage(
            "proto.encode_text(struct(a=1, b=None, nested=struct(c=2, d=None)))",
            "a: 1",
            "nested {",
            "  c: 2",
            "}"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProtoFieldsOrder() {
        checkTextMessage(
            "proto.encode_text(struct(d=4, b=2, c=3, a=1))", "a: 1", "b: 2", "c: 3", "d: 4"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTextMessageEscapes() {
        checkTextMessage("proto.encode_text(struct(name='a\"b'))", "name: \"a\\\"b\"")
        checkTextMessage("proto.encode_text(struct(name='a\\'b'))", "name: \"a'b\"")
        checkTextMessage("proto.encode_text(struct(name='a\\nb'))", "name: \"a\\nb\"")

        // struct(name="a\\\"b") -> name: "a\\\"b"
        checkTextMessage("proto.encode_text(struct(name='a\\\\\\\"b'))", "name: \"a\\\\\\\"b\"")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTextMessageInvalidStructure() {
        // list in list
        ev.checkEvalErrorContains(
            "in struct field .a: at list index 0: got list, want string, int, float, bool, or struct",
            "proto.encode_text(struct(a=[['b']]))"
        )

        // dict in list
        ev.checkEvalErrorContains(
            "in struct field .a: at list index 0: got dict, want string, int, float, bool, or struct",
            "proto.encode_text(struct(a=[{'b': 1}]))"
        )

        // tuple as dict key
        ev.checkEvalErrorContains(
            "in struct field .a: invalid dict key: got tuple, want int or string",
            "proto.encode_text(struct(a={(1, 2): 3}))"
        )

        // dict in dict
        ev.checkEvalErrorContains(
            "in struct field .name: in value for dict key \"a\": got dict, want string, int, float,"
                    + " bool, or struct",
            "proto.encode_text(struct(name={'a': {'b': [1, 2]}}))"
        )

        // callable in field
        ev.checkEvalErrorContains(
            "in struct field .a: got builtin_function_or_method, want string, int, float, bool, or"
                    + " struct",
            "proto.encode_text(struct(a=rule))"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun checkJson(from: String?, expected: String?) {
        val result: Any = ev.eval(from)
        Truth.assertThat(result).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkJsonModule() {
        checkJson("json.encode(struct(name=True))", "{\"name\":true}")
        checkJson("json.encode([1, 2])", "[1,2]") // works for non-structs too
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonBooleanFields() {
        checkJson("json.encode(struct(name=True))", "{\"name\":true}")
        checkJson("json.encode(struct(name=False))", "{\"name\":false}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonDictFields() {
        checkJson("json.encode(struct(config={}))", "{\"config\":{}}")
        checkJson("json.encode(struct(config={'key': 'value'}))", "{\"config\":{\"key\":\"value\"}}")
        ev.checkEvalErrorContains(
            "in struct field .config: dict has int key, want string",
            "json.encode(struct(config={1:2}))"
        )
        ev.checkEvalErrorContains(
            "in struct field .config: in dict key \"foo\": dict has int key, want string",
            "json.encode(struct(config={'foo':{1:2}}))"
        )
        ev.checkEvalErrorContains(
            "in struct field .config: dict has bool key, want string",
            "json.encode(struct(config={True: False}))"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonEncoding() {
        checkJson("json.encode(struct(name='value'))", "{\"name\":\"value\"}")
        checkJson("json.encode(struct(name=['a', 'b']))", "{\"name\":[\"a\",\"b\"]}")
        checkJson("json.encode(struct(name=123))", "{\"name\":123}")
        checkJson("json.encode(struct(name=[1, 2, 3]))", "{\"name\":[1,2,3]}")
        checkJson("json.encode(struct(a=struct(b='b')))", "{\"a\":{\"b\":\"b\"}}")
        checkJson(
            "json.encode(struct(a=[struct(b='x'), struct(b='y')]))",
            "{\"a\":[{\"b\":\"x\"},{\"b\":\"y\"}]}"
        )
        checkJson("json.encode(struct(a=struct(b=struct(c='c'))))", "{\"a\":{\"b\":{\"c\":\"c\"}}}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonEscapes() {
        checkJson("json.encode(struct(name='a\"b'))", "{\"name\":\"a\\\"b\"}")
        checkJson("json.encode(struct(name='a\\'b'))", "{\"name\":\"a'b\"}")
        checkJson("json.encode(struct(name='a\\\\b'))", "{\"name\":\"a\\\\b\"}")
        checkJson("json.encode(struct(name='a\\nb'))", "{\"name\":\"a\\nb\"}")
        checkJson("json.encode(struct(name='a\\rb'))", "{\"name\":\"a\\rb\"}")
        checkJson("json.encode(struct(name='a\\tb'))", "{\"name\":\"a\\tb\"}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonNestedListStructure() {
        checkJson("json.encode(struct(a=[['b']]))", "{\"a\":[[\"b\"]]}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonInvalidStructure() {
        ev.checkEvalErrorContains(
            "in struct field .a: cannot encode builtin_function_or_method as JSON",
            "json.encode(struct(a=rule))"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonAndProtoFileEncoding() {
        // Test that File objects can be encoded as JSON and proto.
        scratch.file(
            "test/BUILD",
            """
        load("//test:rule.bzl", "test_rule")
        test_rule(
            name = "test",
            src = "test.txt",
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx):
            json_input = {"file": ctx.file.src, "other": True}
            json_expected_output = {
                "file": {
                    "path": ctx.file.src.path,
                    "short_path": ctx.file.src.short_path,
                    "root": ctx.file.src.root.path,
                },
                "other": True
            }
            json_encoded = json.encode(json_input)
            json_decoded = json.decode(json_encoded)

            if json_decoded != json_expected_output:
                fail("JSON encode/decode of File did not round-trip. Expected: {}, actual: {}".format(repr(json_expected_output), repr(json_decoded)))

            proto_input = struct(input = json_input)  # proto input must be wrapped in a struct
            proto_expected_encoded = 'input {\
              key: "file"\
              value {\
                path: "%s"\
                root: "%s"\
                short_path: "%s"\
              }\
            }\
            input {\
              key: "other"\
              value: true\
            }\
            ' % (
                ctx.file.src.path,
                ctx.file.src.root.path,
                ctx.file.src.short_path,
            )
            proto_encoded = proto.encode_text(proto_input)
            if proto_encoded != proto_expected_encoded:
                fail("Proto encoding of File failed. Expected: {}, actual: {}".format(repr(proto_expected_encoded), repr(proto_encoded)))

            return []

        test_rule = rule(
            implementation = _impl,
            attrs = {"src": attr.label(allow_single_file=True, mandatory=True)}
        )
        
        """.trimIndent()
        )

        scratch.file("test/test.txt", "test content")

        val unused: StarlarkRuleContext = createRuleContext("//test:test")
        // The rule implementation tests the JSON encoding internally
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJsonAndProtoNativeInfoEncoding() {
        // FeatureFlagInfo is a NativeInfo having both struct fields (value, error) and non-struct-field
        // methods (is_valid_value), which makes it a good test case for NativeInfo method filtering in
        // json and textproto encoding.
        // Note for future maintainers: If FeatureFlagInfo ever evolves to not have non-struct-field
        // methods, update this test case to use a different NativeInfo subclass having some
        // non-constructor @StarlarkMethod-annotatated methods with structField = true, and some
        // without; for example, PackageSpecificationInfo.
        // If no such NativeInfo subclass exists or is ever likely to be added, consider removing this
        // test case and having NativeInfo trivially implement Structure rather than StarlarkEncodable.
        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx):
            feature_flag_info = config_common.FeatureFlagInfo(value = "val")
            if "is_valid_value" not in dir(feature_flag_info):
                fail("feature_flag_info.is_valid_value not found, got %s" % repr(dir(feature_flag_info)))
            json_encoded = json.encode(feature_flag_info)
            proto_encoded = proto.encode_text(feature_flag_info)
            # We expect no `is_valid_value` method in json encoding.
            if json_encoded != '{"error":null,"value":"val"}':
                fail("json.encode(feature_flag_info) not as expected, got %s" % repr(json_encoded))
            # We expect no `is_valid_value` method or `error` None-valued field in proto encoding.
            if proto_encoded != 'value: "val"\
            ':
                fail("proto.encode_text(feature_flag_info) not as expected, got %s" % repr(proto_encoded))
            return []

        test_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rule.bzl", "test_rule")
        test_rule(name = "test")
        
        """.trimIndent()
        )

        val unused: StarlarkRuleContext = createRuleContext("//test:test")
        // The rule implementation tests the json and proto encoding internally
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelAttrWrongDefault() {
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'Label, string, LateBoundDefault, function, or NoneType'",
            "attr.label(default = 123)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelGetRelative() {
        Truth.assertThat(ev.eval("Label('//foo:bar').relative('baz')").toString()).isEqualTo("//foo:baz")
        Truth.assertThat(ev.eval("Label('//foo:bar').relative('//baz:qux')").toString())
            .isEqualTo("//baz:qux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelGetRelativeSyntaxError() {
        ev.checkEvalErrorContains(
            "invalid target name 'bad//syntax': target names may not contain '//' path separators",
            "Label('//foo:bar').relative('bad//syntax')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructCreation() {
        // TODO(fwe): cannot be handled by current testing suite
        ev.exec("x = struct(a = 1, b = 2)")
        Truth.assertThat(ev.lookup("x")).isInstanceOf(Structure::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFields() {
        // TODO(fwe): cannot be handled by current testing suite
        ev.exec("x = struct(a = 1, b = 2)")
        val x: Structure = ev.lookup("x") as Structure
        Truth.assertThat(x.getValue("a")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(x.getValue("b")).isEqualTo(StarlarkInt.of(2))

        // Update is prohibited.
        ev.checkEvalErrorContains(
            "struct value does not support field assignment", "x = struct(a = 1); x.a = 2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructEquality() {
        Truth.assertThat(ev.eval("struct(a = 1, b = 2) == struct(b = 2, a = 1)") as Boolean?).isTrue()
        Truth.assertThat(ev.eval("struct(a = 1) == struct(a = 1, b = 2)") as Boolean?).isFalse()
        Truth.assertThat(ev.eval("struct(a = 1, b = 2) == struct(a = 1)") as Boolean?).isFalse()
        // Compare a recursive object to itself to make sure reference equality is checked
        ev.exec("s = struct(a = 1, b = []); s.b.append(s)")
        Truth.assertThat(ev.eval("s == s") as Boolean?).isTrue()
        Truth.assertThat(ev.eval("struct(a = 1, b = 2) == struct(a = 1, b = 3)") as Boolean?).isFalse()
        Truth.assertThat(ev.eval("struct(a = 1) == [1]") as Boolean?).isFalse()
        Truth.assertThat(ev.eval("[1] == struct(a = 1)") as Boolean?).isFalse()
        Truth.assertThat(ev.eval("struct() == struct()") as Boolean?).isTrue()
        Truth.assertThat(ev.eval("struct() == struct(a = 1)") as Boolean?).isFalse()

        ev.exec("foo = provider(); bar = provider()")
        Truth.assertThat(ev.eval("struct(a = 1) == foo(a = 1)") as Boolean?).isFalse()
        Truth.assertThat(ev.eval("foo(a = 1) == struct(a = 1)") as Boolean?).isFalse()
        Truth.assertThat(ev.eval("foo(a = 1) == bar(a = 1)") as Boolean?).isFalse()
        Truth.assertThat(ev.eval("foo(a = 1) == foo(a = 1)") as Boolean?).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructIncomparability() {
        ev.checkEvalErrorContains(
            "unsupported comparison: struct <=> struct", "struct(a = 1) < struct(a = 2)"
        )
        ev.checkEvalErrorContains(
            "unsupported comparison: struct <=> struct", "struct(a = 1) > struct(a = 2)"
        )
        ev.checkEvalErrorContains(
            "unsupported comparison: struct <=> struct", "struct(a = 1) <= struct(a = 2)"
        )
        ev.checkEvalErrorContains(
            "unsupported comparison: struct <=> struct", "struct(a = 1) >= struct(a = 2)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessingFieldsFromStarlark() {
        ev.exec("x = struct(a = 1, b = 2)", "x1 = x.a", "x2 = x.b")
        Truth.assertThat(ev.lookup("x1")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("x2")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessingUnknownField() {
        ev.checkEvalErrorContains(
            "'struct' value has no field or method 'c'\n" + "Available attributes: a, b",
            "x = struct(a = 1, b = 2)",
            "y = x.c"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessingUnknownFieldWithArgs() {
        ev.checkEvalErrorContains(
            "'struct' value has no field or method 'c'", "x = struct(a = 1, b = 2)", "y = x.c()"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessingNonFunctionFieldWithArgs() {
        ev.checkEvalErrorContains(
            "'int' object is not callable", "x = struct(a = 1, b = 2)", "x1 = x.a(1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructAccessingFunctionFieldWithArgs() {
        ev.exec("def f(x): return x+5", "x = struct(a = f, b = 2)", "x1 = x.a(1)")
        Truth.assertThat(ev.lookup("x1")).isEqualTo(StarlarkInt.of(6))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructPosArgs() {
        ev.checkEvalErrorContains(
            "struct() got unexpected positional argument", "x = struct(1, b = 2)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructConcatenationFieldNames() {
        // TODO(fwe): cannot be handled by current testing suite
        ev.exec(
            "x = struct(a = 1, b = 2)",  //
            "y = struct(c = 1, d = 2)",
            "z = x + y\n"
        )
        val z: StructImpl = ev.lookup("z") as StructImpl
        assertThat(z.getFieldNames()).containsExactly("a", "b", "c", "d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructConcatenationFieldValues() {
        // TODO(fwe): cannot be handled by current testing suite
        ev.exec(
            "x = struct(a = 1, b = 2)",  //
            "y = struct(c = 1, d = 2)",
            "z = x + y\n"
        )
        val z: StructImpl = ev.lookup("z") as StructImpl
        assertThat(z.getValue("a")).isEqualTo(StarlarkInt.of(1))
        assertThat(z.getValue("b")).isEqualTo(StarlarkInt.of(2))
        assertThat(z.getValue("c")).isEqualTo(StarlarkInt.of(1))
        assertThat(z.getValue("d")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructConcatenationCommonFields() {
        ev.checkEvalErrorContains(
            "cannot add struct instances with common field 'a'",
            "x = struct(a = 1, b = 2)",
            "y = struct(c = 1, a = 2)",
            "z = x + y\n"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConditionalStructConcatenation() {
        // TODO(fwe): cannot be handled by current testing suite
        ev.exec(
            "def func():",
            "  x = struct(a = 1, b = 2)",
            "  if True:",
            "    x += struct(c = 1, d = 2)",
            "  return x",
            "x = func()"
        )
        val x: StructImpl = ev.lookup("x") as StructImpl
        assertThat(x.getValue("a")).isEqualTo(StarlarkInt.of(1))
        assertThat(x.getValue("b")).isEqualTo(StarlarkInt.of(2))
        assertThat(x.getValue("c")).isEqualTo(StarlarkInt.of(1))
        assertThat(x.getValue("d")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetattrNoAttr() {
        ev.checkEvalErrorContains(
            "'struct' value has no field or method 'b'\nAvailable attributes: a",
            "s = struct(a='val')",
            "getattr(s, 'b')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetattr() {
        ev.exec("s = struct(a='val')", "x = getattr(s, 'a')", "y = getattr(s, 'b', 'def')")
        Truth.assertThat(ev.lookup("x")).isEqualTo("val")
        Truth.assertThat(ev.lookup("y")).isEqualTo("def")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHasattr() {
        ev.exec(
            "s = struct(a=1)",  //
            "x = hasattr(s, 'a')",
            "y = hasattr(s, 'b')\n"
        )
        Truth.assertThat(ev.lookup("x")).isEqualTo(true)
        Truth.assertThat(ev.lookup("y")).isEqualTo(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructStr() {
        Truth.assertThat(ev.eval("str(struct(x = 2, y = 3, z = 4))"))
            .isEqualTo("struct(x = 2, y = 3, z = 4)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructsInSets() {
        ev.exec("depset([struct(a='a')])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructsInDicts() {
        ev.exec("d = {struct(a = 1): 'aa', struct(b = 2): 'bb'}")
        Truth.assertThat(ev.eval("d[struct(a = 1)]")).isEqualTo("aa")
        Truth.assertThat(ev.eval("d[struct(b = 2)]")).isEqualTo("bb")
        Truth.assertThat(ev.eval("str([d[k] for k in d])")).isEqualTo("[\"aa\", \"bb\"]")

        ev.checkEvalErrorContains("unhashable type: 'struct'", "{struct(a = []): 'foo'}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructDictMembersAreMutable() {
        ev.exec(
            "s = struct(x = {'a' : 1})",  //
            "s.x['b'] = 2\n"
        )
        assertThat((ev.lookup("s") as StructImpl).getValue("x"))
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, StarlarkInt?>(
                    "a",
                    StarlarkInt.of(1),
                    "b",
                    StarlarkInt.of(2)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepsetGoodCompositeItem() {
        ev.exec("def func():", "  return depset([struct(a='a')])", "s = func()")
        val result: com.google.common.collect.ImmutableList<*>? = (ev.lookup("s") as Depset).toList()
        Truth.assertThat(result).hasSize(1)
        Truth.assertThat(result.get(0)).isInstanceOf(StructImpl::class.java)
    }

    @org.junit.Test
    fun testStructMutabilityShallow() {
        Truth.assertThat(Starlark.isImmutable(makeStruct("a", StarlarkInt.of(1)))).isTrue()
    }

    @org.junit.Test
    fun testStructMutabilityDeep() {
        Truth.assertThat(Starlark.isImmutable(Tuple.of(makeList(null)))).isTrue()
        Truth.assertThat(Starlark.isImmutable(makeStruct("a", makeList(null)))).isTrue()
        Truth.assertThat(Starlark.isImmutable(makeBigStruct(null))).isTrue()

        val mu: Mutability? = Mutability.create("test")
        Truth.assertThat(Starlark.isImmutable(Tuple.of(makeList(mu)))).isFalse()
        Truth.assertThat(Starlark.isImmutable(makeStruct("a", makeList(mu)))).isFalse()
        Truth.assertThat(Starlark.isImmutable(makeBigStruct(mu))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProviders() {
        evalAndExport(ev, "data = provider()", "d = data(x = 1, y ='abc')", "d_x = d.x", "d_y = d.y")
        Truth.assertThat(ev.lookup("d_x")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("d_y")).isEqualTo("abc")
        val dataConstructor: StarlarkProvider = ev.lookup("data") as StarlarkProvider
        val data: StructImpl = ev.lookup("d") as StructImpl
        assertThat(data.getProvider()).isEqualTo(dataConstructor)
        assertThat(dataConstructor.isExported()).isTrue()
        assertThat(dataConstructor.getPrintableName()).isEqualTo("data")
        assertThat(dataConstructor.getKey())
            .isEqualTo(Key(keyForBuild(FAKE_LABEL), "data"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProviderDocumentation() {
        evalAndExport(
            ev,
            "UndocumentedInfo = provider()",
            "DocumentedInfo = provider(doc = '''",
            "    My documented provider",
            "",
            "    Details''')",  // Note fields below are not alphabetized
            "SchemafulWithoutDocsInfo = provider(fields = ['b', 'a'])",
            "SchemafulWithDocsInfo = provider(fields = {'b': 'Field b', 'a': 'Field\\n    a'})"
        )

        val undocumentedInfo: StarlarkProvider = ev.lookup("UndocumentedInfo") as StarlarkProvider
        val documentedInfo: StarlarkProvider = ev.lookup("DocumentedInfo") as StarlarkProvider
        val schemafulWithoutDocsInfo: StarlarkProvider =
            ev.lookup("SchemafulWithoutDocsInfo") as StarlarkProvider
        val schemafulWithDocsInfo: StarlarkProvider = ev.lookup("SchemafulWithDocsInfo") as StarlarkProvider

        assertThat(undocumentedInfo.getDocumentation()).isEmpty()
        assertThat(documentedInfo.getDocumentation()).hasValue("My documented provider\n\nDetails")
        assertThat(schemafulWithoutDocsInfo.getSchema())
            .containsExactly("b", java.util.Optional.empty<T?>(), "a", java.util.Optional.empty<T?>())
        assertThat(schemafulWithDocsInfo.getSchema())
            .containsExactly("b", java.util.Optional.of<T?>("Field b"), "a", java.util.Optional.of<T?>("Field\na"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithInit() {
        evalAndExport(
            ev,
            "def _data_init(x, y = 'abc'):",  //
            "    return {'x': x, 'y': y}",
            "data, _new_data = provider(init = _data_init)",
            "d1 = data(x = 1)  # normal provider constructor",
            "d1_x = d1.x",
            "d1_y = d1.y",
            "d2 = data(1, 'def')  # normal provider constructor invoked with positional arguments",
            "d2_x = d2.x",
            "d2_y = d2.y",
            "d3 = _new_data(x = 2, y = 'xyz')  # raw constructor",
            "d3_x = d3.x",
            "d3_y = d3.y"
        )

        Truth.assertThat(ev.lookup("d1_x")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("d1_y")).isEqualTo("abc")
        Truth.assertThat(ev.lookup("d2_x")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("d2_y")).isEqualTo("def")
        Truth.assertThat(ev.lookup("d3_x")).isEqualTo(StarlarkInt.of(2))
        Truth.assertThat(ev.lookup("d3_y")).isEqualTo("xyz")
        val dataConstructor: StarlarkProvider = ev.lookup("data") as StarlarkProvider
        val rawConstructor: StarlarkCallable? = ev.lookup("_new_data") as StarlarkCallable?
        Truth.assertThat(rawConstructor).isNotInstanceOf(Provider::class.java)
        assertThat(dataConstructor.getInit().getName()).isEqualTo("_data_init")

        val data1: StructImpl = ev.lookup("d1") as StructImpl
        val data2: StructImpl = ev.lookup("d2") as StructImpl
        val data3: StructImpl = ev.lookup("d3") as StructImpl
        assertThat(data1.getProvider()).isEqualTo(dataConstructor)
        assertThat(data2.getProvider()).isEqualTo(dataConstructor)
        assertThat(data3.getProvider()).isEqualTo(dataConstructor)
        assertThat(dataConstructor.isExported()).isTrue()
        assertThat(dataConstructor.getPrintableName()).isEqualTo("data")
        assertThat(dataConstructor.getKey())
            .isEqualTo(Key(keyForBuild(FAKE_LABEL), "data"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithFailingInit_rawConstructorSucceeds() {
        evalAndExport(
            ev,
            "def _data_failing_init(x):",  //
            "    fail('_data_failing_init fails')",
            "data, _new_data = provider(init = _data_failing_init)"
        )

        val dataConstructor: StarlarkProvider? = ev.lookup("data") as StarlarkProvider?

        evalAndExport(ev, "d = _new_data(x = 1)  # raw constructor")
        val data: StructImpl = ev.lookup("d") as StructImpl
        assertThat(data.getProvider()).isEqualTo(dataConstructor)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithFailingInit_normalConstructorFails() {
        evalAndExport(
            ev,
            "def _data_failing_init(x):",  //
            "    fail('_data_failing_init fails')",
            "data, _new_data = provider(init = _data_failing_init)"
        )

        ev.checkEvalErrorContains("_data_failing_init fails", "d = data(x = 1)  # normal constructor")
        Truth.assertThat(ev.lookup("d")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithInitReturningInvalidType_normalConstructorFails() {
        evalAndExport(
            ev,
            "def _data_invalid_init(x):",  //
            "    return 'INVALID'",
            "data, _new_data = provider(init = _data_invalid_init)"
        )

        ev.checkEvalErrorContains(
            "got string for 'return value of provider init()', want dict",
            "d = data(x = 1)  # normal constructor"
        )
        Truth.assertThat(ev.lookup("d")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithInitReturningInvalidDict_normalConstructorFails() {
        evalAndExport(
            ev,
            "def _data_invalid_init(x):",  //
            "    return {('x', 'x', 'x'): x}",
            "data, _new_data = provider(init = _data_invalid_init)"
        )

        ev.checkEvalErrorContains(
            "got dict<tuple, int> for 'return value of provider init()'",
            "d = data(x = 1)  # normal constructor"
        )
        Truth.assertThat(ev.lookup("d")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithInitReturningUnexpectedFields_normalConstructorFails() {
        evalAndExport(
            ev,
            "def _data_unexpected_fields_init(x):",  //
            "    return {'x': x, 'y': x * 2}",
            "data, _new_data = provider(fields = ['x'], init = _data_unexpected_fields_init)"
        )

        ev.checkEvalErrorContains(
            "got unexpected field 'y' in call to instantiate provider data",
            "d = data(x = 1)  # normal constructor"
        )
        Truth.assertThat(ev.lookup("d")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersConcatSuccess() {
        evalAndExport(
            ev,
            "data = provider()",
            "dx = data(x = 1)",
            "dy = data(y = 'abc')",
            "dxy = dx + dy",
            "x = dxy.x",
            "y = dxy.y"
        )
        Truth.assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("y")).isEqualTo("abc")
        val dataConstructor: StarlarkProvider? = ev.lookup("data") as StarlarkProvider?
        val dx: StructImpl = ev.lookup("dx") as StructImpl
        assertThat(dx.getProvider()).isEqualTo(dataConstructor)
        val dy: StructImpl = ev.lookup("dy") as StructImpl
        assertThat(dy.getProvider()).isEqualTo(dataConstructor)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithInitConcatSuccess() {
        evalAndExport(
            ev,
            "def _data_init(x):",
            "    return {'x': x}",
            "data, _new_data = provider(init = _data_init)",
            "dx = data(x = 1)  # normal constructor",
            "dy = _new_data(y = 'abc')  # raw constructor",
            "dxy = dx + dy",
            "x = dxy.x",
            "y = dxy.y"
        )
        Truth.assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("y")).isEqualTo("abc")
        val dataConstructor: StarlarkProvider? = ev.lookup("data") as StarlarkProvider?
        val dx: StructImpl = ev.lookup("dx") as StructImpl
        assertThat(dx.getProvider()).isEqualTo(dataConstructor)
        val dy: StructImpl = ev.lookup("dy") as StructImpl
        assertThat(dy.getProvider()).isEqualTo(dataConstructor)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersConcatError() {
        evalAndExport(ev, "data1 = provider()", "data2 = provider()")

        ev.checkEvalErrorContains(
            "Cannot use '+' operator on instances of different providers (data1 and data2)",
            "d1 = data1(x = 1)",
            "d2 = data2(y = 2)",
            "d = d1 + d2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithFieldsConcatSuccess() {
        evalAndExport(
            ev,
            "data = provider(fields=['f1', 'f2'])",
            "d1 = data(f1 = 4)",
            "d2 = data(f2 = 5)",
            "d3 = d1 + d2",
            "f1 = d3.f1",
            "f2 = d3.f2"
        )
        Truth.assertThat(ev.lookup("f1")).isEqualTo(StarlarkInt.of(4))
        Truth.assertThat(ev.lookup("f2")).isEqualTo(StarlarkInt.of(5))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithFieldsConcatError() {
        evalAndExport(ev, "data1 = provider(fields=['f1', 'f2'])", "data2 = provider(fields=['f3'])")
        ev.checkEvalErrorContains(
            "Cannot use '+' operator on instances of different providers (data1 and data2)",
            "d1 = data1(f1=1, f2=2)",
            "d2 = data2(f3=3)",
            "d = d1 + d2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersWithOverlappingFieldsConcatError() {
        evalAndExport(ev, "data = provider(fields=['f1', 'f2'])")
        ev.checkEvalErrorContains(
            "cannot add struct instances with common field 'f1'",
            "d1 = data(f1 = 4)",
            "d2 = data(f1 = 5)",
            "d1 + d2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun structsAsDeclaredProvidersTest() {
        evalAndExport(ev, "data = struct(x = 1)")
        val data: StructImpl = ev.lookup("data") as StructImpl
        assertThat(StructProvider.STRUCT.isExported()).isTrue()
        assertThat(data.getProvider()).isEqualTo(StructProvider.STRUCT)
        assertThat(data.getProvider().getKey()).isEqualTo(StructProvider.STRUCT.key)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersDoc() {
        evalAndExport(ev, "data1 = provider(doc='foo')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declaredProvidersBadTypeForDoc() {
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'string or NoneType'", "provider(doc = 1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAttrs() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",  //
            "   pass",
            "my_aspect = aspect(_impl, attr_aspects=['srcs', 'data'])"
        )

        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attrAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            myAspect.getAttributeAspects()

        assertThat(attrAspects).isInstanceOf(FixedListSupplier::class.java)
        assertThat((attrAspects as FixedListSupplier<String?>).getList()).containsExactly("srcs", "data")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAllAttrs() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",  //
            "   pass",
            "my_aspect = aspect(_impl, attr_aspects=['*'])"
        )

        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attrAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            myAspect.getAttributeAspects()

        assertThat(attrAspects).isInstanceOf(FixedListSupplier::class.java)
        assertThat((attrAspects as FixedListSupplier<String?>).getList()).containsExactly("*")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectEmptyAttrs() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",  //
            "   pass",
            "my_aspect = aspect(_impl, attr_aspects=[])"
        )

        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attrAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            myAspect.getAttributeAspects()

        assertThat(attrAspects).isInstanceOf(FixedListSupplier::class.java)
        assertThat((attrAspects as FixedListSupplier<String?>).getList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDefaultAttrs() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",  //
            "   pass",
            "my_aspect = aspect(_impl)"
        )

        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val attrAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            myAspect.getAttributeAspects()

        assertThat(attrAspects).isInstanceOf(FixedListSupplier::class.java)
        assertThat((attrAspects as FixedListSupplier<String?>).getList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredAspectProvidersSingle() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "java = provider()",
            "cc = provider()",
            "my_aspect = aspect(_impl, required_aspect_providers=[java, cc])"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder()
                    .addStarlark(declared("cc"))
                    .addStarlark(declared("java"))
                    .build()
            )
        )
            .isTrue()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredAspectProvidersAlternatives() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "java = provider()",
            "cc = provider()",
            "my_aspect = aspect(_impl, required_aspect_providers=[[java], [cc]])"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("java")).build()
            )
        )
            .isTrue()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()
            )
        )
            .isTrue()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("prolog")).build()
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredAspectProvidersEmpty() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl, required_aspect_providers=[])"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredAspectProvidersDefault() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",  //
            "   pass",
            "my_aspect = aspect(_impl)"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredProvidersNotAllowedWithApplyToGeneratingRules() {
        ev.checkEvalErrorContains(
            "An aspect cannot simultaneously have required providers and apply to generating rules.",
            "prov = provider()",
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   required_providers = [prov],",
            "   apply_to_generating_rules = True",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredProvidersSingle() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "java = provider()",
            "cc = provider()",
            "my_aspect = aspect(_impl, required_providers=[java, cc])"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders()

        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder()
                    .addStarlark(declared("cc"))
                    .addStarlark(declared("java"))
                    .build()
            )
        )
            .isTrue()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredProvidersAlternatives() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "java = provider()",
            "cc = provider()",
            "my_aspect = aspect(_impl, required_providers=[[java], [cc]])"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders()

        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("java")).build()
            )
        )
            .isTrue()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()
            )
        )
            .isTrue()
        assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("prolog")).build()
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredProvidersEmpty() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl, required_providers=[])"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders()

        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiredProvidersDefault() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",  //
            "   pass",
            "my_aspect = aspect(_impl)"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val requiredProviders: RequiredProviders =
            myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders()

        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue()
        assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectProvides() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "y = provider()",
            "my_aspect = aspect(_impl, provides = [y])"
        )
        val myAspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val advertisedProviders: AdvertisedProviderSet =
            myAspect.getDefinition(AspectParameters.EMPTY).getAdvertisedProviders()
        assertThat(advertisedProviders.canHaveAnyProvider()).isFalse()
        assertThat(advertisedProviders.getStarlarkProviders()).containsExactly(declared("y"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectProvidesError() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "def _impl(target, ctx):",
            "   pass",
            "y = provider()",
            "my_aspect = aspect(_impl, provides = [y, 1])"
        )
        assertContainsEvent(
            ev.getEventCollector(),
            "Error in aspect: at index 1 of provides, got element of type int, want Provider"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDoc() {
        evalAndExport(
            ev,
            "def _impl(target, ctx):",  //
            "   pass",
            "documented_aspect = aspect(_impl, doc='My doc string')",
            "long_documented_aspect = aspect(",
            "    implementation = _impl,",
            "    doc='''",
            "           My doc string",
            "           ",
            "           With details''',",
            ")",
            "undocumented_aspect = aspect(_impl)"
        )

        val documentedAspect: StarlarkDefinedAspect = ev.lookup("documented_aspect") as StarlarkDefinedAspect
        assertThat(documentedAspect.getDocumentation()).hasValue("My doc string")
        val longDocumentedAspect: StarlarkDefinedAspect =
            ev.lookup("long_documented_aspect") as StarlarkDefinedAspect
        assertThat(longDocumentedAspect.getDocumentation()).hasValue("My doc string\n\nWith details")
        val undocumentedAspect: StarlarkDefinedAspect =
            ev.lookup("undocumented_aspect") as StarlarkDefinedAspect
        assertThat(undocumentedAspect.getDocumentation()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectBadTypeForDoc() {
        registerDummyStarlarkFunction()
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'string or NoneType'", "aspect(impl, doc = 1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fancyExports() {
        evalAndExport(
            ev,
            "def _impla(target, ctx): pass",
            "p, (a, p1) = [",
            "   provider(),",
            "   [ aspect(_impla),",
            "     provider() ]",
            "]"
        )
        val p: StarlarkProvider = ev.lookup("p") as StarlarkProvider
        val a: StarlarkDefinedAspect = ev.lookup("a") as StarlarkDefinedAspect
        val p1: StarlarkProvider = ev.lookup("p1") as StarlarkProvider
        assertThat(p.getPrintableName()).isEqualTo("p")
        assertThat(p.getKey()).isEqualTo(Key(keyForBuild(FAKE_LABEL), "p"))
        assertThat(p1.getPrintableName()).isEqualTo("p1")
        assertThat(p1.getKey()).isEqualTo(Key(keyForBuild(FAKE_LABEL), "p1"))
        assertThat(a.getAspectClass()).isEqualTo(StarlarkAspectClass(keyForBuild(FAKE_LABEL), "a"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleTopLevels() {
        evalAndExport(
            ev,
            "p = provider()",  //
            "p1 = p"
        )
        val p: StarlarkProvider = ev.lookup("p") as StarlarkProvider
        val p1: StarlarkProvider = ev.lookup("p1") as StarlarkProvider
        assertThat(p).isEqualTo(p1)
        assertThat(p.getKey()).isEqualTo(Key(keyForBuild(FAKE_LABEL), "p"))
        assertThat(p1.getKey()).isEqualTo(Key(keyForBuild(FAKE_LABEL), "p"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithFields() {
        evalAndExport(
            ev,
            "p = provider(fields = ['x', 'y'])",  //
            "p1 = p(x = 1, y = 2)",
            "x = p1.x",
            "y = p1.y"
        )
        val p: StarlarkProvider? = ev.lookup("p") as StarlarkProvider?
        val p1: StarlarkInfo = ev.lookup("p1") as StarlarkInfo

        assertThat(p1.getProvider()).isEqualTo(p)
        Truth.assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithFieldsDict() {
        evalAndExport(
            ev,
            "p = provider(fields = { 'x' : 'I am x', 'y' : 'I am y'})",
            "p1 = p(x = 1, y = 2)",
            "x = p1.x",
            "y = p1.y"
        )
        val p: StarlarkProvider? = ev.lookup("p") as StarlarkProvider?
        val p1: StarlarkInfo = ev.lookup("p1") as StarlarkInfo

        assertThat(p1.getProvider()).isEqualTo(p)
        Truth.assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1))
        Truth.assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithFieldsOptional() {
        evalAndExport(
            ev,
            "p = provider(fields = ['x', 'y'])",  //
            "p1 = p(y = 2)",
            "y = p1.y"
        )
        val p: StarlarkProvider? = ev.lookup("p") as StarlarkProvider?
        val p1: StarlarkInfo = ev.lookup("p1") as StarlarkInfo

        assertThat(p1.getProvider()).isEqualTo(p)
        Truth.assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithFieldsOptionalError() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "p = provider(fields = ['x', 'y'])",  //
            "p1 = p(y = 2)",
            "x = p1.x"
        )
        assertContainsEvent(
            ev.getEventCollector(), " 'p' value has no field or method 'x'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithExtraFieldsError() {
        ev.setFailFast(false)
        evalAndExport(ev, "p = provider(fields = ['x', 'y'])", "p1 = p(x = 1, y = 2, z = 3)")
        assertContainsEvent(
            ev.getEventCollector(), "got unexpected field 'z' in call to instantiate provider p"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithEmptyFieldsError() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "p = provider(fields = [])",  //
            "p1 = p(x = 1, y = 2, z = 3)"
        )
        assertContainsEvent(
            ev.getEventCollector(),
            "got unexpected fields 'x', 'y', 'z' in call to instantiate provider p"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithDuplicateFieldsError() {
        ev.setFailFast(false)
        evalAndExport(
            ev,
            "p = provider(fields = ['a', 'b'])",  //
            "p(a = 1, b = 2, **dict(b = 3))"
        )
        assertContainsEvent(
            ev.getEventCollector(),
            "got multiple values for parameter b in call to instantiate provider p"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starTheOnlyAspectArg() {
        ev.checkEvalErrorContains(
            "'*' must be the only string in 'attr_aspects' list",
            "def _impl(target, ctx):",
            "   pass",
            "aspect(_impl, attr_aspects=['*', 'foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidAttrAspectsType() {
        ev.checkEvalErrorContains(
            "'attr_aspects' got value of type 'string', want 'sequence or function'",
            "def _impl(target, ctx):",
            "   pass",
            "aspect(_impl, attr_aspects='foo')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidToolchainsAspectsType() {
        ev.checkEvalErrorContains(
            "'toolchains_aspects' got value of type 'string', want 'sequence or function'",
            "def _impl(target, ctx):",
            "   pass",
            "aspect(_impl, toolchains_aspects='foo')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMandatoryConfigParameterForExecutableLabels() {
        scratch.file(
            "third_party/foo/extension.bzl",
            """
        def _main_rule_impl(ctx):
            pass

        my_rule = rule(
            _main_rule_impl,
            attrs = {
                "exe": attr.label(executable = True, allow_files = True),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "third_party/foo/BUILD",
            """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "main",
            exe = ":tool.sh",
        )
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { createRuleContext("//third_party/foo:main") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("cfg parameter is mandatory when executable=True is provided.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAddToolchain() {
        evalAndExport(
            ev,
            "def impl(ctx): return None",
            "r1 = rule(impl,",
            "    toolchains=[",
            "        '//test:my_toolchain_type1',",
            "        config_common.toolchain_type('//test:my_toolchain_type2'),",
            "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
            "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
            "    ],",
            ")"
        )
        val c: RuleClass? = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c).hasToolchainType("//test:my_toolchain_type1")
        assertThat(c).toolchainType("//test:my_toolchain_type1").isMandatory()
        assertThat(c).hasToolchainType("//test:my_toolchain_type2")
        assertThat(c).toolchainType("//test:my_toolchain_type2").isMandatory()
        assertThat(c).hasToolchainType("//test:my_toolchain_type3")
        assertThat(c).toolchainType("//test:my_toolchain_type3").isOptional()
        assertThat(c).hasToolchainType("//test:my_toolchain_type4")
        assertThat(c).toolchainType("//test:my_toolchain_type4").isMandatory()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAddToolchain_duplicate() {
        evalAndExport(
            ev,
            "def impl(ctx): return None",
            "r1 = rule(impl,",
            "    toolchains=[",
            "        '//test:my_toolchain_type1',",
            "        config_common.toolchain_type('//test:my_toolchain_type1'),",
            "        config_common.toolchain_type('//test:my_toolchain_type2', mandatory = False),",
            "        config_common.toolchain_type('//test:my_toolchain_type2', mandatory = True),",
            "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory = False),",
            "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory = False),",
            "    ],",
            ")"
        )

        val c: RuleClass? = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c).hasToolchainType("//test:my_toolchain_type1")
        assertThat(c).toolchainType("//test:my_toolchain_type1").isMandatory()
        assertThat(c).hasToolchainType("//test:my_toolchain_type2")
        assertThat(c).toolchainType("//test:my_toolchain_type2").isMandatory()
        assertThat(c).hasToolchainType("//test:my_toolchain_type3")
        assertThat(c).toolchainType("//test:my_toolchain_type3").isOptional()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAddExecutionConstraints() {
        registerDummyStarlarkFunction()
        evalAndExport(
            ev,
            "r1 = rule(",
            "  implementation = impl,",
            "  exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
            ")"
        )
        val c: RuleClass = (ev.lookup("r1") as StarlarkRuleFunction).getRuleClass()
        assertThat(c.getExecutionPlatformConstraints())
            .containsExactly(
                Label.parseCanonicalUnchecked("//constraint:cv1"),
                Label.parseCanonicalUnchecked("//constraint:cv2")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAddExecGroup() {
        registerDummyStarlarkFunction()
        evalAndExport(
            ev,
            "plum = rule(",
            "  implementation = impl,",
            "  exec_groups = {",
            "    'group': exec_group(",
            "      toolchains=[",
            "        '//test:my_toolchain_type1',",
            "        config_common.toolchain_type('//test:my_toolchain_type2'),",
            "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
            "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
            "      ],",
            "      exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
            "    ),",
            "  },",
            ")"
        )
        val plum: RuleClass = (ev.lookup("plum") as StarlarkRuleFunction).getRuleClass()
        assertThat(plum.getToolchainTypes()).isEmpty()
        val declaredExecGroup: DeclaredExecGroup? = plum.getDeclaredExecGroups().get("group")
        assertThat(declaredExecGroup).hasToolchainType("//test:my_toolchain_type1")
        assertThat(declaredExecGroup).toolchainType("//test:my_toolchain_type1").isMandatory()
        assertThat(declaredExecGroup).hasToolchainType("//test:my_toolchain_type2")
        assertThat(declaredExecGroup).toolchainType("//test:my_toolchain_type2").isMandatory()
        assertThat(declaredExecGroup).hasToolchainType("//test:my_toolchain_type3")
        assertThat(declaredExecGroup).toolchainType("//test:my_toolchain_type3").isOptional()
        assertThat(declaredExecGroup).hasToolchainType("//test:my_toolchain_type4")
        assertThat(declaredExecGroup).toolchainType("//test:my_toolchain_type4").isMandatory()

        assertThat(plum.getExecutionPlatformConstraints()).isEmpty()
        assertThat(declaredExecGroup).hasExecCompatibleWith("//constraint:cv1")
        assertThat(declaredExecGroup).hasExecCompatibleWith("//constraint:cv2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleOrderedRequirements() {
        registerDummyStarlarkFunction()
        evalAndExport(
            ev,
            "plum = rule(",
            "  implementation = impl,",
            "  exec_compatible_with = [",
            "    '//constraint:cv5',",
            "    '//constraint:cv4',",
            "    '//constraint:cv3',",
            "    '//constraint:cv2',",
            "    '//constraint:cv1',",
            "  ],",
            "  toolchains = [",
            "    '//test:my_toolchain_type5',",
            "    '//test:my_toolchain_type4',",
            "    '//test:my_toolchain_type3',",
            "    '//test:my_toolchain_type2',",
            "    '//test:my_toolchain_type1',",
            "  ],",
            "  exec_groups = {",
            "    'group5': exec_group(",
            "      toolchains = [",
            "        '//test:my_toolchain_type5',",
            "        '//test:my_toolchain_type4',",
            "        '//test:my_toolchain_type3',",
            "        '//test:my_toolchain_type2',",
            "        '//test:my_toolchain_type1',",
            "      ],",
            "    ),",
            "    'group4': exec_group(",
            "      exec_compatible_with = [",
            "        '//constraint:cv5',",
            "        '//constraint:cv4',",
            "        '//constraint:cv3',",
            "        '//constraint:cv2',",
            "        '//constraint:cv1',",
            "      ],",
            "    ),",
            "    'group3': exec_group(),",
            "    'group2': exec_group(),",
            "    'group1': exec_group(),",
            "  },",
            ")"
        )
        val plum: RuleClass = (ev.lookup("plum") as StarlarkRuleFunction).getRuleClass()
        assertThat(plum.getToolchainTypes().stream().map(ToolchainTypeRequirement::toolchainType))
            .containsExactly(
                Label.parseCanonicalUnchecked("//test:my_toolchain_type5"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type4"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type3"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type2"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type1")
            )
            .inOrder()
        assertThat(plum.getExecutionPlatformConstraints())
            .containsExactly(
                Label.parseCanonicalUnchecked("//constraint:cv5"),
                Label.parseCanonicalUnchecked("//constraint:cv4"),
                Label.parseCanonicalUnchecked("//constraint:cv3"),
                Label.parseCanonicalUnchecked("//constraint:cv2"),
                Label.parseCanonicalUnchecked("//constraint:cv1")
            )
            .inOrder()
        assertThat(plum.getDeclaredExecGroups().keySet())
            .containsExactly("group5", "group4", "group3", "group2", "group1")
            .inOrder()
        assertThat(plum.getDeclaredExecGroups().get("group5").toolchainTypesMap().keySet())
            .containsExactly(
                Label.parseCanonicalUnchecked("//test:my_toolchain_type5"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type4"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type3"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type2"),
                Label.parseCanonicalUnchecked("//test:my_toolchain_type1")
            )
            .inOrder()
        assertThat(plum.getDeclaredExecGroups().get("group4").execCompatibleWith())
            .containsExactly(
                Label.parseCanonicalUnchecked("//constraint:cv5"),
                Label.parseCanonicalUnchecked("//constraint:cv4"),
                Label.parseCanonicalUnchecked("//constraint:cv3"),
                Label.parseCanonicalUnchecked("//constraint:cv2"),
                Label.parseCanonicalUnchecked("//constraint:cv1")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleFunctionReturnsNone() {
        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx):
            pass

        foo_rule = rule(
            implementation = _impl,
            attrs = {"params": attr.string_list()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":rule.bzl", "foo_rule")

        # Custom rule should return None
        r = foo_rule(name = "foo")

        # Native rule should return None
        c = filegroup(name = "cc")

        foo_rule(
            name = "check",
            params = [
                type(r),
                type(c),
            ],
        )
        
        """.trimIndent()
        )
        invalidatePackages()
        val context: StarlarkRuleContext = createRuleContext("//test:check")
        val params: StarlarkList<Any?> = context.getAttr().getValue("params") as StarlarkList<Any?>
        Truth.assertThat(params.get(0)).isEqualTo("NoneType")
        Truth.assertThat(params.get(1)).isEqualTo("NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTypeOfStruct() {
        ev.exec("p = type(struct)", "s = type(struct())")

        Truth.assertThat(ev.lookup("p")).isEqualTo("Provider")
        Truth.assertThat(ev.lookup("s")).isEqualTo("struct")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateExecGroup() {
        evalAndExport(
            ev,
            "group = exec_group(",
            "  toolchains=[",
            "    '//test:my_toolchain_type1',",
            "    config_common.toolchain_type('//test:my_toolchain_type2'),",
            "    config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
            "    config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
            "  ],",
            "  exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
            ")"
        )
        val group: DeclaredExecGroup? = (ev.lookup("group") as DeclaredExecGroup?)
        assertThat(group).hasToolchainType("//test:my_toolchain_type1")
        assertThat(group).toolchainType("//test:my_toolchain_type1").isMandatory()
        assertThat(group).hasToolchainType("//test:my_toolchain_type2")
        assertThat(group).toolchainType("//test:my_toolchain_type2").isMandatory()
        assertThat(group).hasToolchainType("//test:my_toolchain_type3")
        assertThat(group).toolchainType("//test:my_toolchain_type3").isOptional()
        assertThat(group).hasToolchainType("//test:my_toolchain_type4")
        assertThat(group).toolchainType("//test:my_toolchain_type4").isMandatory()

        assertThat(group).hasExecCompatibleWith("//constraint:cv1")
        assertThat(group).hasExecCompatibleWith("//constraint:cv2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleDefinitionEnvironmentDigest_unaffectedByTargetAttrValueChange() {
        scratch.file(
            "r/def.bzl",
            """
        Info = provider()
        def _r(ctx):
            return Info(value = ctx.attr.text)

        r = rule(implementation = _r, attrs = {"text": attr.string()})
        
        """.trimIndent()
        )
        scratch.file(
            "r/BUILD",
            """
        load(":def.bzl", "r")

        r(
            name = "r",
            text = "old",
        )
        
        """.trimIndent()
        )
        val oldDigest: ByteArray? =
            createRuleContext("//r:r")
                .getRuleContext()
                .getRule()
                .getRuleClassObject()
                .getRuleDefinitionEnvironmentDigest()

        scratch.deleteFile("r/BUILD")
        scratch.file(
            "r/BUILD",
            """
        load(":def.bzl", "r")

        r(
            name = "r",
            text = "new",
        )
        
        """.trimIndent()
        )
        // Signal SkyFrame to discover changed files.
        skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE)
        val newDigest: ByteArray? =
            createRuleContext("//r:r")
                .getRuleContext()
                .getRule()
                .getRuleClassObject()
                .getRuleDefinitionEnvironmentDigest()

        Truth.assertThat(newDigest).isEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleDefinitionEnvironmentDigest_accountsForFunctionWhenCreatingRuleWithAMacro() {
        scratch.file("r/create.bzl", "def create(impl): return rule(implementation=impl)")
        scratch.file(
            "r/def.bzl",
            """
        load(":create.bzl", "create")
        Info = provider()
        def f(ctx):
            return Info(value = "OLD")

        r = create(f)
        
        """.trimIndent()
        )
        scratch.file(
            "r/BUILD",
            """
        load(":def.bzl", "r")

        r(name = "r")
        
        """.trimIndent()
        )
        val oldDigest: ByteArray? =
            createRuleContext("//r:r")
                .getRuleContext()
                .getRule()
                .getRuleClassObject()
                .getRuleDefinitionEnvironmentDigest()

        scratch.deleteFile("r/def.bzl")
        scratch.file(
            "r/def.bzl",
            """
        load(":create.bzl", "create")
        Info = provider()
        def f(ctx):
            return Info(value = "NEW")

        r = create(f)
        
        """.trimIndent()
        )
        // Signal SkyFrame to discover changed files.
        skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE)
        val newDigest: ByteArray? =
            createRuleContext("//r:r")
                .getRuleContext()
                .getRule()
                .getRuleClassObject()
                .getRuleDefinitionEnvironmentDigest()

        Truth.assertThat(newDigest).isNotEqualTo(oldDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleDefinitionEnvironmentDigest_accountsForAttrsWhenCreatingRuleWithMacro() {
        scratch.file(
            "r/create.bzl",
            """
        Info = provider()
        def f(ctx):
            return Info(value = json.encode(ctx.attr))

        def create(attrs):
            return rule(implementation = f, attrs = attrs)
        
        """.trimIndent()
        )
        scratch.file(
            "r/def.bzl",
            """
        load(":create.bzl", "create")

        r = create({})
        
        """.trimIndent()
        )
        scratch.file(
            "r/BUILD",
            """
        load(":def.bzl", "r")

        r(name = "r")
        
        """.trimIndent()
        )
        val oldDigest: ByteArray? =
            createRuleContext("//r:r")
                .getRuleContext()
                .getRule()
                .getRuleClassObject()
                .getRuleDefinitionEnvironmentDigest()

        scratch.deleteFile("r/def.bzl")
        scratch.file(
            "r/def.bzl",
            """
        load(":create.bzl", "create")

        r = create({"value": attr.string(default = "")})
        
        """.trimIndent()
        )
        // Signal SkyFrame to discover changed files.
        skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE)
        val newDigest: ByteArray? =
            createRuleContext("//r:r")
                .getRuleContext()
                .getRule()
                .getRuleClassObject()
                .getRuleDefinitionEnvironmentDigest()

        Truth.assertThat(newDigest).isNotEqualTo(oldDigest)
    }

    /**
     * This test is crucial for correctness of [RuleClass.getRuleDefinitionEnvironmentDigest]
     * since we use a dummy bzl transitive digest in that case. It is correct to do that only because
     * a rule class created by a BUILD thread cannot be instantiated.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleClassDefinedInBuildFile_fails() {
        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        scratch.file("r/create.bzl", "def create(impl): return rule(implementation=impl)")
        scratch.file(
            "r/def.bzl",
            """
        load(":create.bzl", "create")

        r = create({})
        
        """.trimIndent()
        )
        scratch.file("r/impl.bzl", "def make_struct(ctx): return struct(value='hello')")
        scratch.file(
            "r/BUILD",
            """
        load(":create.bzl", "create")
        load(":impl.bzl", "make_struct")

        r = create(make_struct)

        r(name = "r")
        
        """.trimIndent()
        )

        getConfiguredTarget("//r:r")

        ev.assertContainsError(
            "rule() can only be used during .bzl initialization (top-level evaluation)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrWithAspectRequiringAspects_requiredNativeAspect_getsParamsFromBaseRules() {
        scratch.file(
            "lib.bzl",
            """
        rule_prov = provider()

        def _impl(target, ctx):
            pass

        aspect_a = aspect(
            implementation = _impl,
            requires = [parametrized_native_aspect],
            attr_aspects = ["deps"],
            required_providers = [rule_prov],
        )

        def impl(ctx):
            return None

        my_rule = rule(
            impl,
            attrs = {
                "deps": attr.label_list(aspects = [aspect_a]),
                "aspect_attr": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD", "load(':lib.bzl', 'my_rule')", "my_rule(name = 'main', aspect_attr = 'v1')"
        )

        val ruleContext: RuleContext = createRuleContext("//:main").getRuleContext()

        val rule: Rule = ruleContext.getRule()
        val attr: Attribute = rule.getRuleClassObject().getAttributeProvider().getAttributeByName("deps")
        val aspects: com.google.common.collect.ImmutableList<Aspect> = attr.getAspects(rule)
        val requiredNativeAspect: Aspect = aspects.get(0)
        assertThat(requiredNativeAspect.getAspectClass().getName())
            .isEqualTo("ParametrizedAspectWithProvider")
        assertThat(
            requiredNativeAspect
                .getDefinition()
                .getAttributes()
                .get("aspect_attr").defaultValueUnchecked
        )
            .isEqualTo("v1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_allowlist() {
        scratch.file(
            "p/b.bzl",
            """
        def initializer(**kwargs):
            return kwargs

        def impl(ctx):
            pass

        my_rule = rule(impl, initializer = initializer)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        
        """.trimIndent()
        )
        setBuildLanguageOptions("--noexperimental_rule_extension_api")

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//p:my_target")

        ev.assertContainsError("Non-allowlisted attempt to use initializer.")
    }

    // TODO b/298561048 - move the initializers tests below into a separate file
    /**
     * Verifies that precisely returned attributes are modified.
     * 
     * 
     * When an attribute is not returned it's unaffected.
     * 
     * 
     * It also verifies that the keyword arguments passed to the initializer are exactly the values
     * of the declared attributes.".
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_basic() {
        scratch.file(
            "BUILD",  //
            "filegroup(name = 'initial')",
            "filegroup(name = 'added')"
        )
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(name, srcs = [], deps = []):
            return {"deps": deps + ["//:added"]}

        def impl(ctx):
            return [MyInfo(
                srcs = [s.short_path for s in ctx.files.srcs],
                deps = [str(d.label) for d in ctx.attr.deps],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
            deps = ["//:initial"],
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat(info.getValue("srcs") as MutableList<String?>?).containsExactly("initializer_testing/a.ml")
        Truth.assertThat(info.getValue("deps") as MutableList<String?>?).containsExactly("@@//:initial", "@@//:added")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_nameUnchanged() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, **kwargs):
            if name != "my_target":
                fail()
            return {"name": name} | kwargs

        MyInfo = provider()

        def impl(ctx):
            pass

        my_rule = rule(impl, initializer = initializer)
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        
        """.trimIndent()
        )

        getConfiguredTarget("//initializer_testing:my_target")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_nameChanged() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, **kwargs):
            return {"name": "my_new_name"}

        def impl(ctx):
            pass

        my_rule = rule(impl, initializer = initializer)
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("Error in my_rule: Initializer can't change the name of the target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_stringListDict() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(**kwargs):
            return {}

        MyInfo = provider()

        def impl(ctx):
            return [MyInfo(dict = ctx.attr.dict)]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "dict": attr.string_list_dict(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            dict = {"k": ["val"]},
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat((info.getValue("dict") as MutableMap<String?, MutableList<String?>?>).keys)
            .containsExactly("k")
        Truth.assertThat((info.getValue("dict") as MutableMap<String?, MutableList<String?>?>).get("k"))
            .containsExactly("val")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stringKeyedLabelDictWithSplitConfiguration() {
        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "a")
        a(name="a", dict={"foo_key": ":foo", "gen_key": ":gen"})

        filegroup(name="foo", srcs=["foo.txt"])
        genrule(name="gen", srcs=[], outs=["gen.txt"], cmd="exit 1")
        
        """.trimIndent()
        )

        scratch.file(
            "a/a.bzl",
            """
        DictInfo = provider(fields=["dict"])

        def _a_impl(ctx):
            return [DictInfo(dict = ctx.split_attr.dict)]

        def _trans_impl(settings, attr):
          return {
            "fastbuild_key": {"//command_line_option:compilation_mode": "fastbuild"},
            "dbg_key": {"//command_line_option:compilation_mode": "dbg"},
          }

        trans = transition(
            implementation = _trans_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"])

        a = rule(
            implementation=_a_impl,
            attrs={"dict": attr.string_keyed_label_dict(cfg=trans)})
        
        """.trimIndent()
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        val info: StructImpl =
            a.get(
                Key(
                    keyForBuild(Label.parseCanonical("//a:a.bzl")), "DictInfo"
                )
            ) as StructImpl
        val dict: MutableMap<String?, MutableMap<String?, ConfiguredTarget?>> =
            info.getValue("dict") as MutableMap<String?, MutableMap<String?, ConfiguredTarget?>>
        Truth.assertThat(dict.keys).containsExactly("fastbuild_key", "dbg_key")
        val fastbuild: MutableMap<String?, ConfiguredTarget?> = dict.get("fastbuild_key")
        val dbg: MutableMap<String?, ConfiguredTarget?> = dict.get("dbg_key")
        Truth.assertThat(fastbuild.keys).containsExactly("foo_key", "gen_key")
        Truth.assertThat(dbg.keys).containsExactly("foo_key", "gen_key")

        assertThat(getFilesToBuild(fastbuild.get("foo_key")).getSingleton().getExecPathString())
            .isEqualTo("a/foo.txt")
        assertThat(getFilesToBuild(dbg.get("foo_key")).getSingleton().getExecPathString())
            .isEqualTo("a/foo.txt")
        assertThat(getFilesToBuild(fastbuild.get("gen_key")).getSingleton().getExecPathString())
            .endsWith("-fastbuild/bin/a/gen.txt")
        assertThat(getFilesToBuild(dbg.get("gen_key")).getSingleton().getExecPathString())
            .endsWith("-dbg/bin/a/gen.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stringKeyedLabelDict() {
        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "a")
        a(name="a", dict={"foo_key": ":foo", "gen_key": ":gen"})

        filegroup(name="foo", srcs=["foo.txt"])
        genrule(name="gen", srcs=[], outs=["gen.txt"], cmd="exit 1")
        
        """.trimIndent()
        )

        scratch.file(
            "a/a.bzl",
            """
        DictInfo = provider(fields=["dict"])

        def _a_impl(ctx):
            return [DictInfo(dict = ctx.attr.dict)]

        a = rule(implementation=_a_impl, attrs={"dict": attr.string_keyed_label_dict()})
        
        """.trimIndent()
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        val info: StructImpl =
            a.get(
                Key(
                    keyForBuild(Label.parseCanonical("//a:a.bzl")), "DictInfo"
                )
            ) as StructImpl
        val dict: MutableMap<String?, ConfiguredTarget?> =
            info.getValue("dict") as MutableMap<String?, ConfiguredTarget?>
        Truth.assertThat(dict.keys).containsExactly("foo_key", "gen_key")
        assertThat(dict.get("foo_key").getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//a:foo"))
        assertThat(dict.get("gen_key").getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//a:gen"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_labelKeyedStringDict() {
        scratch.file(
            "BUILD",  //
            "filegroup(name = 'key')"
        )
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(**kwargs):
            return {}

        MyInfo = provider()

        def impl(ctx):
            return [MyInfo(dict = ctx.attr.dict)]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "dict": attr.label_keyed_string_dict(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            dict = {"//:key": "val"},
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val key: ConfiguredTarget = getConfiguredTarget("//:key")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat((info.getValue("dict") as MutableMap<ConfiguredTarget?, String?>).keys)
            .containsExactly(key)
        Truth.assertThat((info.getValue("dict") as MutableMap<ConfiguredTarget?, String?>).get(key)).isEqualTo("val")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun labelListDict() {
        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "a")
        a(name="a", dict={"foo_key": [":foo", ":gen"], "gen_key": [":gen"]})

        filegroup(name="foo", srcs=["foo.txt"])
        genrule(name="gen", srcs=[], outs=["gen.txt"], cmd="exit 1")
        
        """.trimIndent()
        )

        scratch.file(
            "a/a.bzl",
            """
        DictInfo = provider(fields=["dict"])

        def _a_impl(ctx):
            return [DictInfo(dict = ctx.attr.dict)]

        a = rule(implementation=_a_impl, attrs={"dict": attr.label_list_dict()})
        
        """.trimIndent()
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        val info: StructImpl =
            a.get(
                Key(
                    keyForBuild(Label.parseCanonical("//a:a.bzl")), "DictInfo"
                )
            ) as StructImpl
        val dict: MutableMap<String?, MutableList<ConfiguredTarget?>?> =
            info.getValue("dict") as MutableMap<String?, MutableList<ConfiguredTarget?>?>
        Truth.assertThat(dict.keys).containsExactly("foo_key", "gen_key")
        Truth.assertThat(dict.get("foo_key").stream().map<Any?>(ConfiguredTarget::getLabel))
            .containsExactly(
                Label.parseCanonicalUnchecked("//a:foo"), Label.parseCanonicalUnchecked("//a:gen")
            )
        Truth.assertThat(dict.get("gen_key").stream().map<Any?>(ConfiguredTarget::getLabel))
            .containsExactly(Label.parseCanonicalUnchecked("//a:gen"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun labelListDictWithSplitConfiguration() {
        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "a")
        a(name="a", dict={"foo_key": [":foo", ":gen"], "gen_key": [":gen"]})

        filegroup(name="foo", srcs=["foo.txt"])
        genrule(name="gen", srcs=[], outs=["gen.txt"], cmd="exit 1")
        
        """.trimIndent()
        )

        scratch.file(
            "a/a.bzl",
            """
        DictInfo = provider(fields=["dict"])

        def _a_impl(ctx):
            return [DictInfo(dict = ctx.split_attr.dict)]

        def _trans_impl(settings, attr):
          return {
            "fastbuild_key": {"//command_line_option:compilation_mode": "fastbuild"},
            "dbg_key": {"//command_line_option:compilation_mode": "dbg"},
          }

        trans = transition(
            implementation = _trans_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"])

        a = rule(
            implementation=_a_impl,
            attrs={"dict": attr.label_list_dict(cfg=trans)})
        
        """.trimIndent()
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        val info: StructImpl =
            a.get(
                Key(
                    keyForBuild(Label.parseCanonical("//a:a.bzl")), "DictInfo"
                )
            ) as StructImpl
        val dict: MutableMap<String?, MutableMap<String?, MutableList<ConfiguredTarget?>?>> =
            info.getValue("dict") as MutableMap<String?, MutableMap<String?, MutableList<ConfiguredTarget?>?>>
        Truth.assertThat(dict.keys).containsExactly("fastbuild_key", "dbg_key")
        val fastbuild: MutableMap<String?, MutableList<ConfiguredTarget?>?> = dict.get("fastbuild_key")
        val dbg: MutableMap<String?, MutableList<ConfiguredTarget?>?> = dict.get("dbg_key")
        Truth.assertThat(fastbuild.keys).containsExactly("foo_key", "gen_key")
        Truth.assertThat(dbg.keys).containsExactly("foo_key", "gen_key")

        val endsWith: Correspondence<String?, String?> =
            Correspondence.from<String?, String?>(BinaryPredicate { first: String?, second: String? ->
                first.endsWith(
                    second
                )
            }, "ends with")
        Truth.assertThat(
            fastbuild.get("foo_key").stream()
                .map<Any?> { target: ConfiguredTarget? -> getFilesToBuild(target).getSingleton().getExecPathString() }
                .toList())
            .comparingElementsUsing<String?, String?>(endsWith)
            .containsExactly("a/foo.txt", "-fastbuild/bin/a/gen.txt")
        Truth.assertThat(
            dbg.get("foo_key").stream()
                .map<Any?> { target: ConfiguredTarget? -> getFilesToBuild(target).getSingleton().getExecPathString() }
                .toList())
            .comparingElementsUsing<String?, String?>(endsWith)
            .containsExactly("a/foo.txt", "-dbg/bin/a/gen.txt")
        Truth.assertThat(
            fastbuild.get("gen_key").stream()
                .map<Any?> { target: ConfiguredTarget? -> getFilesToBuild(target).getSingleton().getExecPathString() }
                .toList())
            .comparingElementsUsing<String?, String?>(endsWith)
            .containsExactly("-fastbuild/bin/a/gen.txt")
        Truth.assertThat(
            dbg.get("gen_key").stream()
                .map<Any?> { target: ConfiguredTarget? -> getFilesToBuild(target).getSingleton().getExecPathString() }
                .toList())
            .comparingElementsUsing<String?, String?>(endsWith)
            .containsExactly("-dbg/bin/a/gen.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_labelListDict() {
        scratch.file(
            "BUILD",
            """
        filegroup(name = 'val1')
        filegroup(name = 'val2')
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(**kwargs):
            return {}

        MyInfo = provider()

        def impl(ctx):
            return [MyInfo(dict = ctx.attr.dict)]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "dict": attr.label_list_dict(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            dict = {"key1": ["//:val1", "//:val2"], "key2": ["//:val1"]},
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val val1: ConfiguredTarget = getConfiguredTarget("//:val1")
        val val2: ConfiguredTarget = getConfiguredTarget("//:val2")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat((info.getValue("dict") as MutableMap<String?, MutableList<ConfiguredTarget?>?>?))
            .containsExactly(
                "key1", StarlarkList.immutableOf<Any?>(val1, val2), "key2", StarlarkList.immutableOf<Any?>(val1)
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_legacyAnyType() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(name, tristate = -1):
            return {"tristate": int(tristate)}

        def impl(ctx):
            return [MyInfo(tristate = ctx.attr.tristate)]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "tristate": attr.int(),
                "_legacy_any_type_attrs": attr.string_list(default = ["tristate"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            tristate = True,
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat<StarlarkInt?>(info.getValue("tristate") as StarlarkInt?).isEqualTo(StarlarkInt.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_wrongType() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(srcs = []):
            return {"srcs": ["a.ml"]}

        def impl(ctx):
            return [MyInfo(
                srcs = [s.short_path for s in ctx.files.srcs],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = "default_files",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError(
            """
        expected value of type 'list(label)' for attribute 'srcs' of 'my_rule', but got "default_files" (string)
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_withSelect() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(name, srcs = []):
            return {"srcs": srcs + ["b.ml"]}

        def impl(ctx):
            return [MyInfo(
                srcs = [s.short_path for s in ctx.files.srcs],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = select({"//conditions:default": ["a.ml"]}),
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat(info.getValue("srcs") as MutableList<String?>?)
            .containsExactly("initializer_testing/a.ml", "initializer_testing/b.ml")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_passThrough() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(**kwargs):
            pass

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//initializer_testing:my_target")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_overridesAttributeDefault() {
        scratch.file(
            "BUILD",  //
            "filegroup(name = 'initializer_default')",
            "filegroup(name = 'attr_default')"
        )
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(name, deps = ["//:initializer_default"]):
            return {"deps": deps}

        def impl(ctx):
            return [MyInfo(
                deps = [str(d.label) for d in ctx.attr.deps],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "deps": attr.label_list(default = ["//:attr_default"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat(info.getValue("deps") as MutableList<String?>?).containsExactly("@@//:initializer_default")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_returningNoneSetsDefault() {
        scratch.file(
            "BUILD",  //
            "filegroup(name = 'initializer_default')",
            "filegroup(name = 'attr_default')"
        )
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(name, deps = ["//:initializer_default"]):
            return {"deps": None}

        def impl(ctx):
            return [MyInfo(
                deps = [str(d.label) for d in ctx.attr.deps],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "deps": attr.label_list(default = ["//:attr_default"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat(info.getValue("deps") as MutableList<String?>?).containsExactly("@@//:attr_default")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_omittedValueIsNotPassed() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(name, srcs):
            return {"srcs": srcs}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        // TODO: b/298561048 - Fix error messages to match a rule without initializer
        ev.assertContainsError("initializer() missing 1 required positional argument: srcs")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_noneValueIsNotPassed() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        MyInfo = provider()

        def initializer(name, srcs):
            return {"srcs": srcs}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = None,
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("initializer() missing 1 required positional argument: srcs")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_incorrectReturnType() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, srcs = []):
            return [srcs]

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("got list for 'rule's initializer return value', want dict")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_incorrectReturnDicts() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, srcs = []):
            return {True: srcs}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("got dict<bool, list> for 'rule's initializer return value', want dict")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_failsSettingBaseAttribute() {
        // 'args' is an attribute defined for all executable rules
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "deps": deps, "args": ["a"]}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            executable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("Initializer can only set Starlark defined attributes, not 'args'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_failsSettingPrivateAttribute_outsideBuiltins() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "_tool": ":my_tool"}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "_tool": attr.label(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        filegroup(name = "my_tool")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("file '//initializer_testing:b.bzl' cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_settingPrivateAttribute_insideBuiltins() {
        // Because it's hard to test something that needs to be in builtins,
        // this is also allowed in a special testing location: {@link
        // StarlarkRuleClassFunctions.ALLOWLIST_RULE_EXTENSION_API_EXPERIMENTAL}
        scratch.file("initializer_testing/builtins/BUILD", "filegroup(name='my_tool')")
        scratch.file(
            "initializer_testing/builtins/b.bzl",
            """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "_tool": ":my_tool"}

        MyInfo = provider()

        def impl(ctx):
            return MyInfo(_tool = str(ctx.attr._tool.label))

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "_tool": attr.label(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load("//initializer_testing/builtins:b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing/builtins:b.bzl")),
                    "MyInfo"
                )
            ) as StructImpl

        assertThat(info.getValue("_tool").toString())
            .isEqualTo("@@//initializer_testing/builtins:my_tool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_failsSettingUnknownAttr() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "my_deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("no such attribute 'my_deps' in 'my_rule' rule (did you mean 'deps'?)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_failsCreatingAnotherRule() {
        scratch.file(
            "initializer_testing/b.bzl",
            analysisMock.javaSupport().getLoadStatementForRule("java_library"),
            """
        def initializer(name, srcs = [], deps = []):
            java_library(name = "jl", srcs = ["a.java"])
            return {"srcs": srcs, "deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError(
            "a rule can only be instantiated while evaluating a BUILD file or a legacy or symbolic"
                    + " macro"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_failsWithExistingRules() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, srcs = [], deps = []):
            native.existing_rules()
            return {"srcs": srcs, "deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError(
            "existing_rules() can only be used while evaluating a BUILD file, a legacy macro, or a rule"
                    + " finalizer"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_withFails() {
        scratch.file(
            "initializer_testing/b.bzl",
            """
        def initializer(name, srcs = [], deps = []):
            fail("Fail called in initializer")
            return {"srcs": srcs, "deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "initializer_testing/BUILD",
            """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//initializer_testing:my_target")

        ev.assertContainsError("Fail called in initializer")
        // TODO: b/298561048 - fix that the whole package doesn't fail if possible
        ev.assertContainsError("target 'my_target' not declared in package 'initializer_testing'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initializer_nativeModule() {
        scratch.overwriteFile("MODULE.bazel", "module(name = 'my_mod', version = '1.2.3')")
        scratch.file("initializer_testing/rules/BUILD")
        scratch.file(
            "initializer_testing/rules/b.bzl",
            "MyInfo = provider()",
            "def initializer(name, **kwargs):",
            "  return {'props': {",
            "    'package_relative_label': str(native.package_relative_label(':target')),",
            "  }}",
            "def impl(ctx): ",
            "  return [MyInfo(props = ctx.attr.props)]",
            "my_rule = rule(impl,",
            "  initializer = initializer,",
            "  attrs = {",
            "    'props': attr.string_dict(),",
            "  })"
        )
        scratch.file(
            "initializer_testing/targets/BUILD",
            "load('//initializer_testing/rules:b.bzl','my_rule')",
            "my_rule(name = 'my_target')"
        )

        invalidatePackages()

        val myTarget: ConfiguredTarget = getConfiguredTarget("//initializer_testing/targets:my_target")
        val info: StructImpl =
            myTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing/rules:b.bzl")),
                    "MyInfo"
                )
            ) as StructImpl

        Truth.assertThat(info.getValue("props") as MutableMap<String?, String?>?)
            .containsExactly("package_relative_label", "@@//initializer_testing/targets:target")
    }

    @Throws(IOException::class)
    private fun scratchParentRule(rule: String?, vararg ruleArgs: String?) {
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            "ParentInfo = provider()",
            "def _impl(ctx):",
            "  return [ParentInfo()]",
            rule + " = rule(",
            "  implementation = _impl,",
            "  extendable = True,",
            "  attrs = { ",
            "    'srcs': attr.label_list(allow_files = ['.parent']),",
            "    'deps': attr.label_list(providers = [ParentInfo]),",
            "  },",
            java.lang.String.join("\n", *ruleArgs),
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_allowlist() {
        scratchParentRule("parent_library")
        scratch.file(
            "bar/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file("bar/a.parent")
        scratch.file(
            "bar/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
        )
        
        """.trimIndent()
        )
        setBuildLanguageOptions("--noexperimental_rule_extension_api")

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//bar:my_target")

        ev.assertContainsError("Non-allowlisted attempt to use extend rule APIs.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_ccBinary() {
        if (analysisMock.isThisBazel) {
            return
        }
        mockToolsConfig.overwrite(
            "tools/allowlists/extend_rule_allowlist/BUILD",
            """
        package_group(
            name = "extend_rule_allowlist",
            packages = ["//..."],
        )
        package_group(
            name = "extend_rule_api_allowlist",
            packages = ["//..."],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("@rules_cc//cc/private/rules_impl:cc_binary.bzl", "cc_binary")

        def _impl(ctx):
            return ctx.super()

        my_binary = rule(
            implementation = _impl,
            parent = cc_binary,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_binary")

        my_binary(
            name = "my_target",
            srcs = ["a.cc"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//extend_rule_testing:my_target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_basicUse() {
        scratchParentRule("parent_library") // parent has srcs and deps attribute
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            return ctx.super() + [MyInfo(
                srcs = ctx.files.srcs,
                deps = ctx.attr.deps,
                runtime_deps = ctx.attr.runtime_deps,
            )]

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "runtime_deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
            runtime_deps = [":dep"],
        )

        filegroup(name = "dep")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()
        val myInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing:child.bzl")),
                "MyInfo"
            )
        val myInfo: StarlarkInfo = myTarget.get(myInfoKey) as StarlarkInfo

        assertNoEvents()
        assertThat(rule.getRuleClassObject().isExecutableStarlark).isFalse()
        assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL)
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(myInfo.getValue("srcs"), Artifact::class.java, "srcs").stream()
                .map<Any?>(Artifact::getFilename)
        )
            .containsExactly("a.parent")
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(myInfo.getValue("deps"), ConfiguredTarget::class.java, "deps")
                .stream()
                .map<Any?>(ConfiguredTarget::getLabel)
                .map<Any?>(Label::getName)
        )
            .containsExactly()
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(
                myInfo.getValue("runtime_deps"),
                ConfiguredTarget::class.java,
                "runtime_deps"
            )
                .stream()
                .map<Any?>(ConfiguredTarget::getLabel)
                .map<Any?>(Label::getName)
        )
            .containsExactly("dep")
        val parentInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
                "ParentInfo"
            )
        assertThat(myTarget.get(parentInfoKey)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_withInitializers() {
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        # only parents attributes
        def _parent_initializer(name, srcs, deps):
            return {"deps": deps + ["//extend_rule_testing:parent_dep"]}

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            initializer = _parent_initializer,
            extendable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = [".parent"]),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        ChildInfo = provider()

        def _child_initializer(name, srcs, deps, runtime_deps = []):
            return {"deps": deps + [":child_dep"], "runtime_deps": runtime_deps + [":runtime_dep"]}

        def _impl(ctx):
            return ctx.super() + [ChildInfo(
                srcs = ctx.files.srcs,
                deps = ctx.attr.deps,
                runtime_deps = ctx.attr.runtime_deps,
            )]

        child_library = rule(
            implementation = _impl,
            initializer = _child_initializer,
            parent = parent_library,
            attrs = {
                "runtime_deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "child_library")

        child_library(
            name = "my_target",
            srcs = ["a.parent"],
            deps = [":dep"],
        )

        filegroup(name = "dep")

        filegroup(name = "child_dep")

        filegroup(name = "parent_dep")

        filegroup(name = "runtime_dep")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()
        val myInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing:child.bzl")),
                "ChildInfo"
            )
        val myInfo: StarlarkInfo = myTarget.get(myInfoKey) as StarlarkInfo

        assertNoEvents()
        assertThat(rule.getRuleClassObject().isExecutableStarlark).isFalse()
        assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL)
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(myInfo.getValue("srcs"), Artifact::class.java, "srcs").stream()
                .map<Any?>(Artifact::getFilename)
        )
            .containsExactly("a.parent")
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(myInfo.getValue("deps"), ConfiguredTarget::class.java, "deps")
                .stream()
                .map<Any?>(ConfiguredTarget::getLabel)
                .map<Any?>(Label::getName)
        )
            .containsExactly("dep", "child_dep", "parent_dep")
            .inOrder()
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(
                myInfo.getValue("runtime_deps"),
                ConfiguredTarget::class.java,
                "runtime_deps"
            )
                .stream()
                .map<Any?>(ConfiguredTarget::getLabel)
                .map<Any?>(Label::getName)
        )
            .containsExactly("runtime_dep")
        val parentInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
                "ParentInfo"
            )
        assertThat(myTarget.get(parentInfoKey)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_superNotCalled() {
        scratchParentRule("parent_library") // parent has srcs and deps attribute
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return []

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError(
            "in my_library rule //extend_rule_testing:my_target: 'super' was not called."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_superCalledTwice() {
        scratchParentRule("parent_library") // parent has srcs and deps attribute
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()
            ctx.super()
            return []

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError("Error in super: 'super' called the second time.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_noParent_superCalled() {
        scratchParentRule("parent_library") // parent has srcs and deps attribute
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        def _impl(ctx):
            ctx.super()
            return []

        my_library = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError("Error in super: Can't use 'super' call, the rule has no parent.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_extendRuleTwice() {
        scratchParentRule("parent_library") // parent has srcs and deps attribute
        scratch.file(
            "extend_rule_testing/first_extension.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        MyInfo1 = provider()

        def _impl(ctx):
            return ctx.super() + [MyInfo1()]

        library_extended_once = rule(
            implementation = _impl,
            parent = parent_library,
            extendable = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/second_extension.bzl",
            """
        load("//extend_rule_testing:first_extension.bzl", "library_extended_once")

        MyInfo2 = provider()

        def _impl(ctx):
            return ctx.super() + [MyInfo2()]

        library_extended_twice = rule(
            implementation = _impl,
            parent = library_extended_once,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":second_extension.bzl", "library_extended_twice")

        library_extended_twice(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val myInfo1Key: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing:first_extension.bzl")),
                "MyInfo1"
            )
        val myInfo2Key: StarlarkProvider.Key =
            Key(
                keyForBuild(
                    Label.parseCanonicalUnchecked("//extend_rule_testing:second_extension.bzl")
                ),
                "MyInfo2"
            )
        val parentInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
                "ParentInfo"
            )

        assertThat(myTarget.get(myInfo1Key)).isNotNull()
        assertThat(myTarget.get(myInfo2Key)).isNotNull()
        assertThat(myTarget.get(parentInfoKey)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_extendRuleTwice_superNotCalled() {
        scratchParentRule("parent_library") // parent has srcs and deps attribute
        scratch.file(
            "extend_rule_testing/first_extension.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            # <- here we didn't call ctx.super()
            return []

        library_extended_once = rule(
            implementation = _impl,
            parent = parent_library,
            extendable = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/second_extension.bzl",
            """
        load("//extend_rule_testing:first_extension.bzl", "library_extended_once")

        def _impl(ctx):
            return ctx.super()

        library_extended_twice = rule(
            implementation = _impl,
            parent = library_extended_once,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":second_extension.bzl", "library_extended_twice")

        library_extended_twice(name = "my_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError(
            "in library_extended_twice rule //extend_rule_testing:my_target: in library_extended_once"
                    + " rule: 'super' was not called."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ctxSuper_calledFromAspect() {
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        def _aspect_impl(target, ctx):
            ctx.super()
            return []

        my_aspect = aspect(_aspect_impl)

        def _impl(ctx):
            pass

        my_library = rule(
            implementation = _impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            deps = [":dep"],
        )

        filegroup(name = "dep")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError("Error in super: Can't use 'super' call in an aspect.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_attributeAdditionalAspects() {
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        def _aspect_impl(ctx, target):
            return []

        parent_aspect = aspect(_aspect_impl)

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = [".parent"]),
                "deps": attr.label_list(aspects = [parent_aspect]),
                "tool": attr.label(providers = [ParentInfo]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _aspect_impl(ctx, target):
            return []

        my_aspect = aspect(_aspect_impl)

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "deps": attr.label_list(aspects = [my_aspect]),
                "tool": attr.label(aspects = [my_aspect]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            deps = [":dep"],
        )

        filegroup(name = "dep")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()
        assertNoEvents()

        assertThat(rule.getRuleClassObject().isExecutableStarlark).isFalse()
        assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL)
        assertThat(
            rule
                .getRuleClassObject()
                .getAttributeProvider()
                .getAttributeByName("deps")
                .getAspectClasses()
                .stream()
                .map(AspectClass::toString)
        )
            .containsExactly(
                "//extend_rule_testing/parent:parent.bzl%parent_aspect",
                "//extend_rule_testing:child.bzl%my_aspect"
            )
        assertThat(
            rule
                .getRuleClassObject()
                .getAttributeProvider()
                .getAttributeByName("tool")
                .getAspectClasses()
                .stream()
                .map(AspectClass::toString)
        )
            .containsExactly("//extend_rule_testing:child.bzl%my_aspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithInvalidToolchainsAspects_fails() {
        scratch.file(
            "pkg/def.bzl",
            """
        def _aspect_impl(target, ctx):
            return []
        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains_aspects = ["@:invalid_toolchain_label"]
        )

        def _rule_impl(ctx):
            pass
        my_rule = rule(
            implementation = _rule_impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])})
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":def.bzl", "my_rule")
        my_rule(
            name = "t1",
            deps = [":t2"],
        )
        my_rule(
            name = "t2",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val target: ConfiguredTarget = getConfiguredTarget("//pkg:t1")

        assertThat(target).isNull()
        assertContainsEvent(
            "Error in aspect: Unable to parse label '@:invalid_toolchain_label' in attribute"
                    + " 'toolchains_aspects'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithValidToolchainsAspects_parsesCorrectly() {
        evalAndExport(
            ev,
            """
        def _aspect_impl(target, ctx):
            return []
        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains_aspects = ["//toolchains:type1", "//toolchains:type2", "//toolchains:type2"]
        )

        

        """.trimIndent()
        )

        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val toolchainsAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aspect.getToolchainsAspects()

        assertThat(toolchainsAspects).isInstanceOf(FixedListSupplier::class.java)
        assertThat((toolchainsAspects as FixedListSupplier<Label?>).getList()).hasSize(2)
        assertThat((toolchainsAspects as FixedListSupplier<Label?>).getList())
            .containsExactly(
                Label.parseCanonicalUnchecked("//toolchains:type1"),
                Label.parseCanonicalUnchecked("//toolchains:type2")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithWildcardToolchainsAspects_parsesCorrectly() {
        evalAndExport(
            ev,
            """
        def _aspect_impl(target, ctx):
            return []
        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains_aspects = ["*"]
        )
        
        """.trimIndent()
        )

        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val toolchainsAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aspect.getToolchainsAspects()

        assertThat(toolchainsAspects).isInstanceOf(FixedListSupplier::class.java)
        assertThat((toolchainsAspects as FixedListSupplier<Label?>).getList()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithWildcardToolchainsAspects_mixedWithLabels_fails() {
        reporter.removeHandler(failFastHandler)

        val failFastException: FailFastException? =
            org.junit.Assert.assertThrows<T?>(
                FailFastException::class.java,
                org.junit.function.ThrowingRunnable {
                    evalAndExport(
                        ev,
                        """
                    def _aspect_impl(target, ctx):
                        return []
                    my_aspect = aspect(
                        implementation = _aspect_impl,
                        toolchains_aspects = ["*", "//toolchains:type1"]
                    )
                    
                    """.trimIndent()
                    )
                })

        assertThat(failFastException)
            .hasMessageThat()
            .contains("'*' must be the only item in 'toolchains_aspects' list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainsAspectsDefault_emptyList() {
        evalAndExport(
            ev,
            """
        def _aspect_impl(target, ctx):
            return []
        my_aspect = aspect(
            implementation = _aspect_impl,
        )
        
        """.trimIndent()
        )

        val aspect: StarlarkDefinedAspect = ev.lookup("my_aspect") as StarlarkDefinedAspect
        val toolchainsAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            aspect.getToolchainsAspects()

        assertThat(toolchainsAspects).isInstanceOf(FixedListSupplier::class.java)
        assertThat((toolchainsAspects as FixedListSupplier<Label?>).getList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_overridePrivateAttribute_fails() {
        scratch.file(
            "extend_rule_testing/parent/BUILD",  //
            "filegroup(name = 'parent_tool')"
        )
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        def _impl(ctx):
            return []

        parent_library = rule(
            implementation = _impl,
            attrs = {
                "_tool": attr.label(default = ":parent_tool"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "_tool": attr.label(default = ":child_tool"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")

        filegroup(name = "child_tool")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:BUILD")

        ev.assertContainsError(
            "Error in rule: attribute `_tool`: private attributes cannot be overridden."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_parentPrivateAttrDefault_visibilityCheckedAgainstParent() {
        scratch.file(
            "extend_rule_testing/parent/BUILD",
            """
        exports_files(
            ["parent_tool.txt"],
            visibility = ["//extend_rule_testing/parent:__pkg__"],
        )
        
        """.trimIndent()
        )
        scratch.file("extend_rule_testing/parent/parent_tool.txt")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        def _impl(ctx):
            return []

        parent_library = rule(
            implementation = _impl,
            extendable = True,
            attrs = {
                "_tool": attr.label(
                    allow_single_file = True,
                    default = "//extend_rule_testing/parent:parent_tool.txt",
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        // This should succeed because the visibility of the parent's private attribute default should
        // be checked against the parent rule's package, not the child rule's package.
        // See https://github.com/bazelbuild/bazel/issues/28618
        getConfiguredTarget("//extend_rule_testing:my_target")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_attributeOverrideDefault() {
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo(deps = ctx.attr.deps, tools = [ctx.attr.tool])]

        parent_library = rule(
            implementation = _impl,
            extendable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = [".parent"]),
                "deps": attr.label_list(),
                "tool": attr.label(default = ":tool_parent"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "deps": attr.label_list(default = [":dep"]),
                "tool": attr.label(default = ":tool_child"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")

        filegroup(name = "dep")

        filegroup(name = "tool_child")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val parentInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
                "ParentInfo"
            )
        val parentInfo: StarlarkInfo = myTarget.get(parentInfoKey) as StarlarkInfo

        assertNoEvents()
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(parentInfo.getValue("deps"), ConfiguredTarget::class.java, "deps")
                .stream()
                .map<Any?>(ConfiguredTarget::getLabel)
                .map<Any?>(Label::getName)
        )
            .containsExactly("dep")
        Truth.assertThat(
            net.starlark.java.eval.Sequence.cast<T?>(
                parentInfo.getValue("tools"),
                ConfiguredTarget::class.java,
                "tools"
            ).stream()
                .map<Any?>(ConfiguredTarget::getLabel)
                .map<Any?>(Label::getName)
        )
            .containsExactly("tool_child")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_attributeCollision() {
        // TODO b/300201845 - encapsulate parents and childs private attributes
        scratchParentRule("parent_library")
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            pass

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "srcs": attr.string(),  # srcs already defined as label_list in parent
            },
        )
        
        """.trimIndent()
        )
        scratch.file("extend_rule_testing/BUILD", "load(':child.bzl', 'my_library')")

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:BUILD")

        ev.assertContainsError(
            "Error in rule: attribute `srcs`: Types of parent and child's attributes mismatch."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_executableMatches() {
        scratchParentRule("parent_binary", "executable = True,")
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_binary")

        MyInfo = provider()

        def _impl(ctx):
            exec = ctx.actions.declare_file("my_exec")
            ctx.actions.write(exec, "")
            ctx.super()
            return DefaultInfo(executable = exec)

        my_binary = rule(
            implementation = _impl,
            parent = parent_binary,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_binary")

        my_binary(
            name = "my_target",
            srcs = ["a.parent"],
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()

        assertNoEvents()
        assertThat(rule.getRuleClassObject().isExecutableStarlark).isTrue()
        assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_testMatches() {
        scratchParentRule("parent_test", "test = True,")
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_test")

        MyInfo = provider()

        def _impl(ctx):
            exec = ctx.actions.declare_file("my_exec")
            ctx.actions.write(exec, "")
            ctx.super()
            return DefaultInfo(executable = exec)

        my_test = rule(
            implementation = _impl,
            parent = parent_test,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_test")

        my_test(
            name = "my_target",
            srcs = ["a.parent"],
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()

        assertNoEvents()
        assertThat(rule.getRuleClassObject().isExecutableStarlark).isTrue()
        assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.TEST)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_controlledParameters_fail() {
        val ev: BazelEvaluationTestCase = BazelEvaluationTestCase("//extend_rule_testing:child.bzl")
        ev.exec(
            "def impl():",  //
            "  pass"
        )
        ev.execAndExport("parent_library = rule(impl)")

        ev.checkEvalError(
            "Omit test parameter when extending rules.",
            "rule(impl, test = False, parent = parent_library)"
        )
        ev.checkEvalError(
            "Omit executable parameter when extending rules.",
            "rule(impl, executable = False, parent = parent_library)"
        )
        ev.checkEvalError(
            "output_to_genfiles are not supported when extending rules (deprecated).",
            "rule(impl, output_to_genfiles = True, parent = parent_library)"
        )
        ev.checkEvalError(
            "host_fragments are not supported when extending rules (deprecated).",
            "rule(impl, host_fragments = ['a'], parent = parent_library)"
        )
        ev.checkEvalError(
            "_skylark_testable is not supported when extending rules.",
            "rule(impl, _skylark_testable = True, parent = parent_library)"
        )
        ev.checkEvalError(
            "analysis_test is not supported when extending rules.",
            "rule(impl, analysis_test = True, parent = parent_library)"
        )

        ev.update("config", StarlarkConfig())
        ev.checkEvalError(
            "build_setting is not supported when extending rules.",
            "rule(impl, build_setting = config.int(), parent = parent_library)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_fragments_merged() {
        scratchParentRule(
            "parent_library",  //
            "fragments = ['java']"
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            fragments = ["cc"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()

        assertNoEvents()
        assertThat(
            rule.getRuleClassObject()
                .getConfigurationFragmentPolicy()
                .getRequiredStarlarkFragments()
        )
            .containsExactly("java", "cc")
    }

    private fun notExtendableError(rule: String?): String? {
        return String.format(
            ("The rule '%s' is not extendable. Only Starlark rules not using deprecated features (like"
                    + " implicit outputs, output to genfiles) may be extended. Special rules like"
                    + " analysis tests or rules using build_settings cannot be extended."),
            rule
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_notExtendable() {
        val ev: BazelEvaluationTestCase = BazelEvaluationTestCase("//extend_rule_testing:child.bzl")
        ev.exec(
            "def impl():",  //
            "  pass"
        )

        ev.execAndExport("parent_library = rule(impl, output_to_genfiles = True)")
        ev.checkEvalError(notExtendableError("parent_library"), "rule(impl, parent = parent_library)")

        ev.execAndExport("parent_library = rule(impl, _skylark_testable = True)")
        ev.checkEvalError(notExtendableError("parent_library"), "rule(impl, parent = parent_library)")

        ev.execAndExport("parent_test = rule(impl, analysis_test = True)")
        ev.checkEvalError(notExtendableError("parent_test"), "rule(impl, parent = parent_test)")

        ev.update("config", StarlarkConfig())
        ev.execAndExport("parent_library = rule(impl, build_setting = config.int())")
        ev.checkEvalError(notExtendableError("parent_library"), "rule(impl, parent = parent_library)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_nativeRule_notExtendable() {
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = native.alias,
            fragments = ["cc"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError("Parent needs to be a Starlark rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_extendableAllowed() {
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        getConfiguredTarget("//extend_rule_testing:my_target")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_extendableDisallowed() {
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = False,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError("The rule 'parent_library' is not extendable.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_extendableAllowlisted() {
        scratch.file(
            "extend_rule_testing/parent/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = ["//extend_rule_testing"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = "//extend_rule_testing/parent:allowlist",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )
        scratch.file(
            "not_on_allowlist/BUILD",
            """
        load("//extend_rule_testing:child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        getConfiguredTarget("//extend_rule_testing:my_target")
        getConfiguredTarget("//not_on_allowlist:my_target")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_extendableAllowlistDenied() {
        scratch.file(
            "extend_rule_testing/parent/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = "//extend_rule_testing/parent:allowlist",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//extend_rule_testing:my_target")

        ev.assertContainsError("Non-allowlisted attempt to extend a rule.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_extendableDefault() {
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        if (!analysisMock.isThisBazel) {
            reporter.removeHandler(failFastHandler)
            reporter.addHandler(ev.getEventCollector())
        }

        getConfiguredTarget("//extend_rule_testing:my_target")

        if (analysisMock.isThisBazel) {
            assertNoEvents()
        } else {
            ev.assertContainsError("Non-allowlisted attempt to extend a rule.")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_toolchains_merged() {
        scratchParentRule(
            "parent_library",  //
            "toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']"
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            "load('//extend_rule_testing/parent:parent.bzl', 'parent_library')",
            "MyInfo = provider()",
            "def _impl(ctx):",
            "  ctx.super()",
            "my_library = rule(",
            "  implementation = _impl,",
            "  parent = parent_library,",
            "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "']",
            ")"
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()

        assertNoEvents()
        assertThat(
            rule.getRuleClassObject().getToolchainTypes().stream()
                .map(ToolchainTypeRequirement::toolchainType)
                .map(Label::toString)
        )
            .containsExactly(TestConstants.JAVA_TOOLCHAIN_TYPE, TestConstants.CPP_TOOLCHAIN_TYPE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_advertisedProviders_merged() {
        scratchParentRule(
            "parent_library",  //
            "provides = [ParentInfo]"
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "ParentInfo", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            ctx.super()
            return [MyInfo(), ParentInfo()]

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            provides = [MyInfo],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()

        assertNoEvents()
        assertThat(
            rule.getRuleClassObject().getAdvertisedProviders().getStarlarkProviders().stream()
                .map(StarlarkProviderIdentifier::getKey)
                .map({ key -> (key as StarlarkProvider.Key).exportedName })
        )
            .containsExactly("MyInfo", "ParentInfo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_execCompatibleWith_merged() {
        val constr1 = TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64"
        val constr2 = TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux"
        scratchParentRule(
            "parent_library",  //
            "exec_compatible_with = ['" + constr1 + "']"
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            "load('//extend_rule_testing/parent:parent.bzl', 'parent_library', 'ParentInfo')",
            "MyInfo = provider()",
            "def _impl(ctx):",
            "  ctx.super()",
            "  return [MyInfo(), ParentInfo()]",
            "my_library = rule(",
            "  implementation = _impl,",
            "  parent = parent_library,",
            "  exec_compatible_with = ['" + constr2 + "']",
            ")"
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()

        assertNoEvents()
        assertThat(rule.getRuleClassObject().getExecutionPlatformConstraints())
            .containsExactly(
                Label.parseCanonicalUnchecked(constr1), Label.parseCanonicalUnchecked(constr2)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_execGroups_merged() {
        scratchParentRule(
            "parent_library",  //
            "exec_groups = {'parent_exec_group': exec_group()}"
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "ParentInfo", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            ctx.super()
            return [MyInfo(), ParentInfo()]

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            exec_groups = {"child_exec_group": exec_group()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//extend_rule_testing:my_target")
        val rule: Rule = getRuleContext(myTarget).getRule()

        assertNoEvents()
        assertThat(rule.getRuleClassObject().getDeclaredExecGroups().keySet())
            .containsExactly("parent_exec_group", "child_exec_group")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_execGroups_overwritten() {
        registerDummyStarlarkFunction()
        evalAndExport(
            ev,
            """
        parent_test = rule(
            implementation = impl,
            test = True,
            exec_groups = {
                "parent_group": exec_group(
                    exec_compatible_with=[':cv1'],
                ),
                "overridden_group": exec_group(
                    exec_compatible_with=[':cv2'],
                ),
            },
        )

        my_test = rule(
            implementation = impl,
            parent = parent_test,
            exec_groups = {
                "test": exec_group(
                    exec_compatible_with=[':cv3'],
                ),
                "child_group": exec_group(
                    exec_compatible_with=[':cv4'],
                ),
                "overridden_group": exec_group(
                    exec_compatible_with=[':cv5'],
                ),
            },
        )
        
        """.trimIndent()
        )

        val ruleClass: RuleClass = (ev.lookup("my_test") as StarlarkRuleFunction).getRuleClass()
        assertThat(ruleClass.getDeclaredExecGroups().keySet())
            .containsExactly("test", "child_group", "overridden_group", "parent_group")
        val testExecGroup: DeclaredExecGroup? = ruleClass.getDeclaredExecGroups().get("test")
        assertThat(testExecGroup).hasExecCompatibleWith("//test:cv3")

        val parentExecGroup: DeclaredExecGroup? = ruleClass.getDeclaredExecGroups().get("parent_group")
        assertThat(parentExecGroup).hasExecCompatibleWith("//test:cv1")

        val childExecGroup: DeclaredExecGroup? = ruleClass.getDeclaredExecGroups().get("child_group")
        assertThat(childExecGroup).hasExecCompatibleWith("//test:cv4")

        val overriddenExecGroup: DeclaredExecGroup? =
            ruleClass.getDeclaredExecGroups().get("overridden_group")
        assertThat(overriddenExecGroup).hasExecCompatibleWith("//test:cv5")
    }

    @Throws(IOException::class)
    private fun scratchStarlarkTransition() {
        if (TestConstants.PRODUCT_NAME != "bazel") {
            scratch.overwriteFile(
                TestConstants.TOOLS_REPOSITORY_SCRATCH
                        + "tools/allowlists/function_transition_allowlist/BUILD",
                """
          package_group(
              name = "function_transition_allowlist",
              packages = [
                  # Allow all packages for testing.
                  "//...",
              ],
          )
          
          """.trimIndent()
            )
        }
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_flag")

        string_flag(
            name = "parent-flag",
            build_setting_default = "default-parent",
        )

        string_flag(
            name = "parent-child-flag",
            build_setting_default = "default-parent-child",
        )

        string_flag(
            name = "child-flag",
            build_setting_default = "child-default",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/transitions.bzl",
            """
        def _parent_trans_impl(settings, attr):
            return {
                "//test:parent-flag": "parent-changed",
                "//test:parent-child-flag": "parent-child-changed-in-parent",
            }

        parent_transition = transition(
            implementation = _parent_trans_impl,
            inputs = [],
            outputs = ["//test:parent-flag", "//test:parent-child-flag"],
        )

        def _child_trans_impl(settings, attr):
            return {
                "//test:child-flag": "child-changed",
                "//test:parent-child-flag": "parent-child-changed-in-child",
            }

        child_transition = transition(
            implementation = _child_trans_impl,
            inputs = [],
            outputs = ["//test:child-flag", "//test:parent-child-flag"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_cfg_fromParent() {
        scratchStarlarkTransition()
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        load("//test:transitions.bzl", "parent_transition")

        def _impl(ctx):
            pass

        parent_rule = rule(
            implementation = _impl,
            extendable = True,
            cfg = parent_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_rule")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_rule,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val configuration: BuildConfigurationValue =
            getConfiguration(getConfiguredTarget("//extend_rule_testing:my_target"))

        val options: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            configuration.getOptions().getStarlarkOptions()
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag")))
            .isEqualTo("parent-changed")
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
            .isEqualTo("parent-child-changed-in-parent")
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag"))).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_cfg_onChild() {
        scratchStarlarkTransition()
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        def _impl(ctx):
            pass

        parent_rule = rule(
            implementation = _impl,
            extendable = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_rule")
        load("//test:transitions.bzl", "child_transition")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_rule,
            cfg = child_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val configuration: BuildConfigurationValue =
            getConfiguration(getConfiguredTarget("//extend_rule_testing:my_target"))

        val options: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            configuration.getOptions().getStarlarkOptions()
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag"))).isNull()
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
            .isEqualTo("parent-child-changed-in-child")
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag")))
            .isEqualTo("child-changed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extendRule_cfg_onChildAndFromParent() {
        scratchStarlarkTransition()
        scratch.file("extend_rule_testing/parent/BUILD")
        scratch.file(
            "extend_rule_testing/parent/parent.bzl",
            """
        load("//test:transitions.bzl", "parent_transition")

        def _impl(ctx):
            pass

        parent_rule = rule(
            implementation = _impl,
            extendable = True,
            cfg = parent_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/child.bzl",
            """
        load("//extend_rule_testing/parent:parent.bzl", "parent_rule")
        load("//test:transitions.bzl", "child_transition")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_rule,
            cfg = child_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "extend_rule_testing/BUILD",
            """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        
        """.trimIndent()
        )

        val configuration: BuildConfigurationValue =
            getConfiguration(getConfiguredTarget("//extend_rule_testing:my_target"))

        val options: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            configuration.getOptions().getStarlarkOptions()
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag")))
            .isEqualTo("parent-changed")
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
            .isEqualTo("parent-child-changed-in-parent")
        assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag")))
            .isEqualTo("child-changed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTest() {
        scratch.file(
            "p/b.bzl",
            """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            testing.analysis_test(name = name, implementation = impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        
        """.trimIndent()
        )

        getConfiguredTarget("//p:my_test_target")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestAttrs() {
        scratch.file(
            "p/b.bzl",
            """
        def impl(ctx):
            ctx.attr.target_under_test
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            native.filegroup(name = "my_subject", srcs = [])
            testing.analysis_test(
                name = name,
                implementation = impl,
                attrs = {"target_under_test": attr.label_list()},
                attr_values = {"target_under_test": [":my_subject"]},
            )
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        
        """.trimIndent()
        )

        getConfiguredTarget("//p:my_test_target")

        assertNoEvents()
    }

    /** Tests two analysis_test calls with same name.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestDuplicateName_samePackage() {
        scratch.file(
            "p/a.bzl",
            """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro1(name):
            testing.analysis_test(name = name, implementation = impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/b.bzl",
            """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro2(name):
            testing.analysis_test(name = name, implementation = impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":a.bzl", "my_test_macro1")
        load(":b.bzl", "my_test_macro2")

        my_test_macro1(name = "my_test_target")

        my_test_macro2(name = "my_test_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//p:my_test_target")

        ev.assertContainsError(
            "Error in analysis_test: my_test_target_test rule 'my_test_target' conflicts with existing"
                    + " my_test_target_test rule"
        )
    }

    // Regression test for b/291752414 (Digest for Starlark-defined rules is wrong for analysis_test).
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestDuplicateName_differentAttrs_differentPackage() {
        scratch.file("p/BUILD")
        scratch.file(
            "p/make.bzl",
            """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def make(name, additional_string_attr_name):
            testing.analysis_test(
                name = name,
                implementation = impl,
                attrs = {additional_string_attr_name: attr.string()},
                attr_values = {additional_string_attr_name: "whatever"},
            )
        
        """.trimIndent()
        )
        scratch.file(
            "p1/BUILD",
            """
        load("//p:make.bzl", "make")

        make(
            name = "my_test_target",
            additional_string_attr_name = "p1",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "p2/BUILD",
            """
        load("//p:make.bzl", "make")

        make(
            name = "my_test_target",
            additional_string_attr_name = "p2",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "s/BUILD",  //
            "test_suite(name = 'suite', tests = ['//p1:my_test_target', '//p2:my_test_target'])"
        )

        // Confirm we can [transitively] analyze both targets together without errors.
        getConfiguredTarget("//s:suite")

        // Also confirm the definition environment digests differ for the rule classes synthesized under
        // the hood for these two targets.
        val p1Target: Rule =
            packageManager
                .getTarget(ev.getEventHandler(), Label.parseCanonical("//p1:my_test_target")) as Rule
        val p2Target: Rule =
            packageManager
                .getTarget(ev.getEventHandler(), Label.parseCanonical("//p2:my_test_target")) as Rule
        assertThat(p1Target.getRuleClassObject().ruleDefinitionEnvironmentDigest)
            .isNotEqualTo(p2Target.getRuleClassObject().ruleDefinitionEnvironmentDigest)
    }

    /**
     * Tests analysis_test call with a name that is not Starlark identifier (but still a good target
     * name).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestBadName() {
        scratch.file(
            "p/b.bzl",
            """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            testing.analysis_test(name = name, implementation = impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my+test+target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//p:my+test+target")

        ev.assertContainsError(
            "Error in analysis_test: 'name' is limited to Starlark identifiers, got my+test+target"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestBadArgs() {
        scratch.file(
            "p/b.bzl",
            """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            testing.analysis_test(
                name = name,
                implementation = impl,
                attr_values = {"notthere": []},
            )
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//p:my_test_target")

        ev.assertContainsError("no such attribute 'notthere' in 'my_test_target_test' rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestErrorOnExport() {
        scratch.file(
            "p/b.bzl",
            "def impl(ctx): ",
            "  return  [AnalysisTestResultInfo(",
            "    success = True,",
            "    message = ''",
            "  )]",
            "def my_test_macro(name):",
            "  testing.analysis_test(name = name, implementation = impl, attrs = {'name':"
                    + " attr.string()})"
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//p:my_test_target")

        ev.assertContainsError(
            "Error in analysis_test: attribute `name`: built-in attributes cannot be overridden"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisTestErrorOverridingName() {
        scratch.file(
            "p/b.bzl",
            "def impl(ctx): ",
            "  return  [AnalysisTestResultInfo(",
            "    success = True,",
            "    message = ''",
            "  )]",
            "def my_test_macro(name):",
            "  testing.analysis_test(name = name, implementation = impl, attr_values = {'name':"
                    + " 'override'})"
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//p:override")

        ev.assertContainsError(
            "Error in analysis_test: 'name' cannot be set or overridden in 'attr_values'"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun eval(module: net.starlark.java.eval.Module?, vararg lines: String?): Any? {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(lines)
        return Starlark.eval(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, ev.getStarlarkThread())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelWithStrictVisibility() {
        val currentRepo: RepositoryName = RepositoryName.createUnvalidated("module+1.2.3")
        val otherRepo: RepositoryName = RepositoryName.createUnvalidated("dep+4.5")
        val bzlLabel: Label? =
            Label.create(
                PackageIdentifier.create(currentRepo, PathFragment.create("lib")), "label.bzl"
            )
        val clientData: Any? =
            BazelModuleContext.create(
                BazelModuleKey.createFakeModuleKeyForTesting(bzlLabel),
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("my_module", currentRepo, "dep", otherRepo),
                    currentRepo
                ),
                "lib/label.bzl",  /* loads= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* bzlTransitiveDigest= */
                ByteArray(0),  /* docCommentsMap= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* unusedDocCommentLines= */
                com.google.common.collect.ImmutableList.of<E?>()
            )
        val module: net.starlark.java.eval.Module? =
            net.starlark.java.eval.Module.withPredeclaredAndData(
                StarlarkSemantics.DEFAULT,
                StarlarkGlobalsImpl.INSTANCE.getFixedBzlToplevels(),
                clientData
            )

        Truth.assertThat(eval(module, "Label('//foo:bar').workspace_root"))
            .isEqualTo("external/module+1.2.3")
        Truth.assertThat(eval(module, "Label('@my_module//foo:bar').workspace_root"))
            .isEqualTo("external/module+1.2.3")
        Truth.assertThat(eval(module, "Label('@@module+1.2.3//foo:bar').workspace_root"))
            .isEqualTo("external/module+1.2.3")
        Truth.assertThat(eval(module, "Label('@dep//foo:bar').workspace_root")).isEqualTo("external/dep+4.5")
        Truth.assertThat(eval(module, "Label('@@dep+4.5//foo:bar').workspace_root"))
            .isEqualTo("external/dep+4.5")
        Truth.assertThat(eval(module, "Label('@@//foo:bar').workspace_root")).isEqualTo("")

        Truth.assertThat(eval(module, "str(Label('@@//foo:bar'))")).isEqualTo("@@//foo:bar")
        Truth.assertThat(
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { eval(module, "Label('@//foo:bar').workspace_name") })
        )
            .hasMessageThat()
            .isEqualTo(
                "'workspace_name' is not allowed on invalid Label @@[unknown repo '' requested from"
                        + " @@module+1.2.3]//foo:bar"
            )
        Truth.assertThat(
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { eval(module, "Label('@//foo:bar').workspace_root") })
        )
            .hasMessageThat()
            .isEqualTo(
                "'workspace_root' is not allowed on invalid Label @@[unknown repo '' requested from"
                        + " @@module+1.2.3]//foo:bar"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageMetadataAttrOnAllRules() {
        scratch.file(
            "p/b.bzl",
            """
        def _my_rule_impl(ctx):
            license_file = ctx.attr.package_metadata[0][DefaultInfo].files.to_list()[0]
            print("ctx.attr.package_metadata: %s" % license_file.path)

        my_rule = rule(_my_rule_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":b.bzl", "my_rule")

        filegroup(
            name = "licenses",
            srcs = ["LICENSE"],
        )

        my_rule(
            name = "my_target",
            package_metadata = [":licenses"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//p:my_target")

        assertContainsEvent("ctx.attr.package_metadata: p/LICENSE")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListComputedDefaultChecksElementTypes() {
        scratch.file(
            "p/a.bzl",
            """
        def _compute():
            return ["this is a string"]
        my_rule = rule(lambda ctx: [], attrs = {"_a" : attr.label_list(default = _compute)})
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":a.bzl", "my_rule")
        my_rule(name = "bad")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//p:bad") })

        Truth.assertThat(error).hasMessageThat().contains("expected 'label', but got 'string'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRuleFunctionCodec() {
        scratch.file("lib/BUILD")
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_rule = rule(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_rule")
        my_rule(
            name = "abc",
            xyz = "value",
        )
        
        """.trimIndent()
        )

        // Evaluates pkg to populate my_rule in Skyframe.
        assertThat(getPackage("pkg")).isNotNull()

        // Pulls my_rule's value out of Skyframe from its BzlLoadValue.
        val bzlLoadKey: BzlLoadValue.Key? = keyForBuild(Label.parseCanonical("//pkg:foo.bzl"))
        val fooBzl: BzlLoadValue = getDoneValue(bzlLoadKey) as BzlLoadValue
        val myRule: StarlarkRuleFunction = com.google.common.base.Preconditions.checkNotNull<T?>(
            fooBzl.getModule().getGlobal("my_rule")
        ) as StarlarkRuleFunction

        val deserialized: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RoundTripping.roundTripWithSkyframe({ key: SkyKey? -> this.getDoneValue(key) }, myRule)
        assertThat(myRule).isSameInstanceAs(deserialized)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagationPredicateNotFunction_fails() {
        ev.checkEvalErrorContains(
            "parameter 'propagation_predicate' got value of type 'string', want 'function or NoneType'",
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(_impl,",
            "   propagation_predicate = 'not_a_function'",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectApplyToGeneratingRules_hasPropagationPredicate_fails() {
        ev.checkEvalErrorContains(
            "An aspect cannot simultaneously have a propagation predicate and apply to generating"
                    + " rules.",
            "def _impl(target, ctx):",
            "   pass",
            "def _function():",
            "  return True",
            "my_aspect = aspect(_impl,",
            "   propagation_predicate = _function,",
            "   apply_to_generating_rules = True",
            ")"
        )
    }

    private fun getDoneValue(key: SkyKey?): SkyValue {
        try {
            return skyframeExecutor.getDoneSkyValueForIntrospection(key)
        } catch (e: SkyframeExecutor.FailureToRetrieveIntrospectedValueException) {
            throw java.lang.AssertionError(e)
        }
    }

    companion object {
        private fun declared(exportedName: String?): StarlarkProviderIdentifier {
            return StarlarkProviderIdentifier.forKey(
                Key(keyForBuild(FAKE_LABEL), exportedName)
            )
        }

        private fun set(vararg ids: StarlarkProviderIdentifier?): AdvertisedProviderSet {
            val builder: AdvertisedProviderSet.Builder = AdvertisedProviderSet.builder()
            for (id in ids) {
                builder.addStarlark(id)
            }
            return builder.build()
        }

        private val DUMMY_CONFIGURED_TARGET_FACTORY: RuleClass.ConfiguredTargetFactory<Any?, Any?, java.lang.Exception?> =
            RuleClass.ConfiguredTargetFactory { ruleContext ->
                throw java.lang.IllegalStateException()
            }

        private fun ruleClass(name: String?): RuleClass {
            return Builder(name, RuleClassType.NORMAL, false)
                .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
                .add(Attribute.attr("tags", Types.STRING_LIST))
                .build()
        }

        private val FAKE_LABEL: Label? = Label.parseCanonicalUnchecked("//fake/label.bzl")

        @Throws(java.lang.Exception::class)
        private fun evalAndExport(ev: BazelEvaluationTestCase, vararg lines: String?) {
            ev.setThreadOwner(keyForBuild(FAKE_LABEL))
            ev.execAndExport(FAKE_LABEL, *lines)
        }

        private fun makeStruct(field: String, value: Any): StructImpl {
            return StructProvider.STRUCT.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(field, value),
                "no field '%'"
            )
        }

        private fun makeBigStruct(mu: Mutability?): StructImpl {
            // struct(a=[struct(x={1:1}), ()], b=(), c={2:2})
            return StructProvider.STRUCT.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "a",
                    StarlarkList.of<Any?>(
                        mu,
                        StructProvider.STRUCT.create(
                            com.google.common.collect.ImmutableMap.of<K?, V?>("x", dictOf(mu, 1, 1)), "no field '%s'"
                        ),
                        Tuple.of()
                    ),
                    "b", Tuple.of(),
                    "c", dictOf(mu, 2, 2)
                ),
                "no field '%s'"
            )
        }

        private fun dictOf(mu: Mutability?, k: Int, v: Int): Dict<Any?, Any?>? {
            return Dict.builder<Any?, Any?>().put(StarlarkInt.of(k), StarlarkInt.of(v)).build(mu)
        }

        private fun makeList(mu: Mutability?): StarlarkList<Any?>? {
            return StarlarkList.of<Any?>(mu, StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
        }
    }
}
