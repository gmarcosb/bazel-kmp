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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

@RunWith(TestParameterInjector::class)
class ModuleInfoExtractorTest {
    private var fakeLabelString: String? = null // set by exec()

    @Throws(java.lang.Exception::class)
    private fun exec(vararg lines: String?): net.starlark.java.eval.Module? {
        return execWithOptions(com.google.common.collect.ImmutableList.of<String?>(), *lines)
    }

    @Throws(java.lang.Exception::class)
    private fun execWithOptions(
        options: com.google.common.collect.ImmutableList<String?>,
        vararg lines: String?
    ): net.starlark.java.eval.Module? {
        val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()
        ev.setSemantics(*options.toTypedArray<String?>())
        val moduleForCompilation: net.starlark.java.eval.Module? = ev.newModule()
        val fakeLabel: Label = BazelModuleContext.of(moduleForCompilation).label()
        ev.setThreadOwner(keyForBuild(fakeLabel))
        fakeLabelString = fakeLabel.getCanonicalForm()
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(lines)
        val file: net.starlark.java.syntax.StarlarkFile? =
            net.starlark.java.syntax.StarlarkFile.parse(input, net.starlark.java.syntax.FileOptions.DEFAULT)
        val program: net.starlark.java.syntax.Program =
            net.starlark.java.syntax.Program.compileFile(file, moduleForCompilation)
        val moduleForEvaluation: net.starlark.java.eval.Module? = ev.newModule(program)
        BzlLoadFunction.execAndExport(
            program, fakeLabel, ev.getEventHandler(), moduleForEvaluation, ev.getStarlarkThread()
        )
        return moduleForEvaluation
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moduleDocstring() {
        val moduleWithDocstring: net.starlark.java.eval.Module? = exec("'''This is my docstring'''", "foo = 1")
        assertThat(extractor.extractFrom(moduleWithDocstring).getModuleDocstring())
            .isEqualTo("This is my docstring")

        val moduleWithoutDocstring: net.starlark.java.eval.Module? = exec("foo = 1")
        assertThat(extractor.extractFrom(moduleWithoutDocstring).getModuleDocstring()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extractOnlyWantedLoadablePublicNames() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def loadable_unwanted():
                pass

            def loadable_wanted():
                pass

            def _nonloadable():
                pass

            def _nonloadable_matches_wanted_predicate():
                pass

            def _f():
                pass

            def _g():
                pass

            def _h():
                pass

            namespace = struct(
                public_field_wanted = _f,
                public_field_unwanted = _g,
                _hidden_field_matches_wanted_predicate = _h,
            )
            
            """.trimIndent()
            )

        val moduleInfo: ModuleInfo =
            getExtractor(java.util.function.Predicate { name: String? -> name.contains("_wanted") }).extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
            .containsExactly("loadable_wanted", "namespace.public_field_wanted")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun namespacedEntities() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_func(**kwargs):
                pass

            _my_binary = rule(implementation = _my_func)
            _my_aspect = aspect(implementation = _my_func)
            _MyInfo = provider()
            name = struct(
                spaced = struct(
                    my_func = _my_func,
                    my_binary = _my_binary,
                    my_aspect = _my_aspect,
                    MyInfo = _MyInfo,
                ),
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
            .containsExactly("name.spaced.my_func")
        assertThat(
            moduleInfo.getFuncInfoList().stream()
                .map(StarlarkFunctionInfo::getOriginKey)
                .map(OriginKey::getName)
        )
            .containsExactly("_my_func")

        assertThat(moduleInfo.getRuleInfoList().stream().map(RuleInfo::getRuleName))
            .containsExactly("name.spaced.my_binary")
        assertThat(
            moduleInfo.getRuleInfoList().stream()
                .map(RuleInfo::getOriginKey)
                .map(OriginKey::getName)
        )
            .containsExactly("_my_binary")

        assertThat(moduleInfo.getAspectInfoList().stream().map(AspectInfo::getAspectName))
            .containsExactly("name.spaced.my_aspect")
        assertThat(
            moduleInfo.getAspectInfoList().stream()
                .map(AspectInfo::getOriginKey)
                .map(OriginKey::getName)
        )
            .containsExactly("_my_aspect")

        assertThat(moduleInfo.getProviderInfoList().stream().map(ProviderInfo::getProviderName))
            .containsExactly("name.spaced.MyInfo")
        assertThat(
            moduleInfo.getProviderInfoList().stream()
                .map(ProviderInfo::getOriginKey)
                .map(OriginKey::getName)
        )
            .containsExactly("_MyInfo")
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val isWantedQualifiedName_appliesToQualifiedNamePrefixes: Unit
        get() {
            val module: net.starlark.java.eval.Module? =
                exec(
                    """
            def _f():
                pass

            def _g():
                pass

            def _h():
                pass

            def _i():
                pass

            def _j():
                pass

            foo = struct(
                bar = struct(
                    f = _f,
                ),
                baz = struct(
                    g = _g,
                ),
                h = _h,
            )
            baz = struct(
                qux = struct(
                    i = _i,
                ),
                j = _j,
            )
            
            """.trimIndent()
                )

            val moduleInfo: ModuleInfo =
                getExtractor(java.util.function.Predicate { name: String? -> name == "foo.bar" || name == "baz" })
                    .extractFrom(module)
            assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
                .containsExactly("foo.bar.f", "baz.qux.i", "baz.j")
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun functionDocstring() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def with_detailed_docstring():
                '''My function

                This function does things.
                '''
                pass

            def with_one_line_docstring():
                '''My function'''
                pass

            def without_docstring():
                pass
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList())
            .containsExactly(
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("with_detailed_docstring")
                    .setDocString("My function\n\nThis function does things.")
                    .setOriginKey(
                        OriginKey.newBuilder()
                            .setName("with_detailed_docstring")
                            .setFile(fakeLabelString)
                    )
                    .build(),
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("with_one_line_docstring")
                    .setDocString("My function")
                    .setOriginKey(
                        OriginKey.newBuilder()
                            .setName("with_one_line_docstring")
                            .setFile(fakeLabelString)
                    )
                    .build(),
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("without_docstring")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("without_docstring").setFile(fakeLabelString)
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun functionParams() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def my_func(documented, undocumented, has_default = {"foo": "bar"}, *args, **kwargs):
                '''My function

                Args:
                  documented: Documented param
                '''
                pass
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList().get(0).getParameterList())
            .containsExactly(
                FunctionParamInfo.newBuilder()
                    .setName("documented")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDocString("Documented param")
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("undocumented")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setMandatory(true)
                    .build(),
                FunctionParamInfo.newBuilder()
                    .setName("has_default")
                    .setRole(PARAM_ROLE_ORDINARY)
                    .setDefaultValue("{\"foo\": \"bar\"}")
                    .build(),
                FunctionParamInfo.newBuilder().setName("args").setRole(PARAM_ROLE_VARARGS).build(),
                FunctionParamInfo.newBuilder().setName("kwargs").setRole(PARAM_ROLE_KWARGS).build()
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun functionReturn() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def with_return():
                '''My doc

                Returns:
                  None
                '''
                return None

            def without_return():
                '''My doc'''
                pass
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList())
            .ignoringFields(StarlarkFunctionInfo.ORIGIN_KEY_FIELD_NUMBER)
            .containsExactly(
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("with_return")
                    .setDocString("My doc")
                    .setReturn(FunctionReturnInfo.newBuilder().setDocString("None").build())
                    .build(),
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("without_return")
                    .setDocString("My doc")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun functionDeprecated() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def with_deprecated():
                '''My doc

                Deprecated:
                  This is deprecated
                '''
                pass

            def without_deprecated():
                '''My doc'''
                pass
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList())
            .ignoringFields(StarlarkFunctionInfo.ORIGIN_KEY_FIELD_NUMBER)
            .containsExactly(
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("with_deprecated")
                    .setDocString("My doc")
                    .setDeprecated(
                        FunctionDeprecationInfo.newBuilder().setDocString("This is deprecated").build()
                    )
                    .build(),
                StarlarkFunctionInfo.newBuilder()
                    .setFunctionName("without_deprecated")
                    .setDocString("My doc")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexportedLambdaFunction() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            s = struct(
                lambda_function = lambda x: x * 2,
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList())
            .containsExactly(
                StarlarkFunctionInfo.newBuilder() // Note that origin key name is unset
                    .setOriginKey(OriginKey.newBuilder().setFile(fakeLabelString))
                    .setFunctionName("s.lambda_function")
                    .addParameter(
                        FunctionParamInfo.newBuilder()
                            .setName("x")
                            .setRole(PARAM_ROLE_ORDINARY)
                            .setMandatory(true)
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexportedGeneratedFunction() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _multiply_by(y):
                def multiply(x):
                    '''Multiplies x by constant y'''
                    return x * y
                return multiply

            s = struct(
                generated = _multiply_by(2),
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getFuncInfoList())
            .containsExactly(
                StarlarkFunctionInfo.newBuilder() // Note that origin key name is unset
                    .setOriginKey(OriginKey.newBuilder().setFile(fakeLabelString))
                    .setFunctionName("s.generated")
                    .setDocString("Multiplies x by constant y")
                    .addParameter(
                        FunctionParamInfo.newBuilder()
                            .setName("x")
                            .setRole(PARAM_ROLE_ORDINARY)
                            .setMandatory(true)
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerDocstring() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            DocumentedInfo = provider(doc = "My doc")
            UndocumentedInfo = provider()
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getProviderInfoList())
            .containsExactly(
                ProviderInfo.newBuilder()
                    .setProviderName("DocumentedInfo")
                    .setDocString("My doc")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("DocumentedInfo").setFile(fakeLabelString)
                    )
                    .build(),
                ProviderInfo.newBuilder()
                    .setProviderName("UndocumentedInfo")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("UndocumentedInfo").setFile(fakeLabelString)
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerFields() {
        val module: net.starlark.java.eval.Module? =
            exec( // Note fields below are not alphabetized
                """
            DocumentedInfo = provider(fields = {"c": "C", "a": "A", "b": "B", "_hidden": "Hidden"})
            UndocumentedInfo = provider(fields = ["c", "a", "b", "_hidden"])
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getProviderInfoList())
            .ignoringFields(ProviderInfo.ORIGIN_KEY_FIELD_NUMBER)
            .containsExactly(
                ProviderInfo.newBuilder()
                    .setProviderName("DocumentedInfo")
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("c").setDocString("C"))
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("a").setDocString("A"))
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("b").setDocString("B"))
                    .build(),
                ProviderInfo.newBuilder()
                    .setProviderName("UndocumentedInfo")
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("c"))
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("a"))
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("b"))
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerInit() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_info_init(x_value, y_value = 0):
                '''MyInfo constructor

                Args:
                    x_value: my x value
                    y_value: my y value
                '''
                return {"x": x_value, "y": y_value}

            _MyInfo, _new_my_info = provider(
                doc = '''My provider''',
                fields = ["x", "y"],
                init = _my_info_init,
            )

            namespace = struct(
                MyInfo = _MyInfo,
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getProviderInfoList())
            .containsExactly(
                ProviderInfo.newBuilder()
                    .setProviderName("namespace.MyInfo")
                    .setDocString("My provider")
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("x"))
                    .addFieldInfo(ProviderFieldInfo.newBuilder().setName("y"))
                    .setInit(
                        StarlarkFunctionInfo.newBuilder()
                            .setFunctionName("namespace.MyInfo")
                            .setDocString("MyInfo constructor")
                            .addParameter(
                                FunctionParamInfo.newBuilder()
                                    .setName("x_value")
                                    .setRole(PARAM_ROLE_ORDINARY)
                                    .setDocString("my x value")
                                    .setMandatory(true)
                                    .build()
                            )
                            .addParameter(
                                FunctionParamInfo.newBuilder()
                                    .setName("y_value")
                                    .setRole(PARAM_ROLE_ORDINARY)
                                    .setDocString("my y value")
                                    .setDefaultValue("0")
                                    .build()
                            )
                            .setOriginKey(
                                OriginKey.newBuilder()
                                    .setName("_my_info_init")
                                    .setFile(fakeLabelString)
                            )
                    )
                    .setOriginKey(OriginKey.newBuilder().setName("_MyInfo").setFile(fakeLabelString))
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexportedProvider_notDocumented() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            s = struct(
                MyUnexportedInfo = provider(),
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getProviderInfoList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleDocstring() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(ctx):
                pass

            documented_lib = rule(doc = "My doc", implementation = _my_impl)
            undocumented_lib = rule(implementation = _my_impl)
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getRuleInfoList())
            .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
            .containsExactly(
                RuleInfo.newBuilder()
                    .setRuleName("documented_lib")
                    .setDocString("My doc")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("documented_lib").setFile(fakeLabelString)
                    )
                    .build(),
                RuleInfo.newBuilder()
                    .setRuleName("undocumented_lib")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("undocumented_lib").setFile(fakeLabelString)
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleAdvertisedProviders() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                provides = [MyInfo, DefaultInfo],
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getRuleInfoList())
            .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
            .containsExactly(
                RuleInfo.newBuilder()
                    .setRuleName("my_lib")
                    .setOriginKey(OriginKey.newBuilder().setName("my_lib").setFile(fakeLabelString))
                    .setAdvertisedProviders(
                        ProviderNameGroup.newBuilder()
                            .addProviderName("MyInfo")
                            .addProviderName("DefaultInfo")
                            .addOriginKey(
                                OriginKey.newBuilder().setName("MyInfo").setFile(fakeLabelString)
                            )
                            .addOriginKey(
                                OriginKey.newBuilder().setName("DefaultInfo").setFile("<native>")
                            )
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleTest() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_test = rule(
                implementation = _my_impl,
                test = True,
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getRuleInfoList())
            .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
            .containsExactly(
                RuleInfo.newBuilder()
                    .setRuleName("my_test")
                    .setOriginKey(OriginKey.newBuilder().setName("my_test").setFile(fakeLabelString))
                    .setTest(true)
                    .setExecutable(true)
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleExecutable() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_binary = rule(
                implementation = _my_impl,
                executable = True,
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getRuleInfoList())
            .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
            .containsExactly(
                RuleInfo.newBuilder()
                    .setRuleName("my_binary")
                    .setOriginKey(OriginKey.newBuilder().setName("my_binary").setFile(fakeLabelString))
                    .setExecutable(true)
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleAttributes() {
        val module: net.starlark.java.eval.Module? =
            execWithOptions( // TODO(https://github.com/bazelbuild/bazel/issues/6420): attr.license() is deprecated,
                // and will eventually be removed from Bazel.
                com.google.common.collect.ImmutableList.of<String?>("--noincompatible_no_attr_license"),
                """
            MyInfo1 = provider()
            MyInfo2 = provider()
            MyInfo3 = provider()

            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "a": attr.string(doc = "My doc", default = "foo"),
                    "b": attr.string(mandatory = True, values = ["foo", "bar"]),
                    "c": attr.label(providers = [MyInfo1, MyInfo2]),
                    "d": attr.label(providers = [[MyInfo1, MyInfo2], [MyInfo3]]),
                    "_e": attr.string(doc = "Hidden attribute"),
                    "deprecated_license": attr.license(),
                },
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getRuleInfoList().get(0).getAttributeList())
            .containsExactlyElementsIn(
                com.google.common.collect.ImmutableList.builder<Any?>()
                    .addAll(IMPLICIT_RULE_ATTRIBUTES.values())
                    .add(
                        AttributeInfo.newBuilder()
                            .setName("a")
                            .setType(AttributeType.STRING)
                            .setDocString("My doc")
                            .setDefaultValue("\"foo\"")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("b")
                            .setType(AttributeType.STRING)
                            .setMandatory(true)
                            .addAllValues(com.google.common.collect.ImmutableList.of<E?>("\"foo\"", "\"bar\""))
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("c")
                            .setType(AttributeType.LABEL)
                            .setDefaultValue("None")
                            .addProviderNameGroup(
                                ProviderNameGroup.newBuilder()
                                    .addProviderName("MyInfo1")
                                    .addProviderName("MyInfo2")
                                    .addOriginKey(
                                        OriginKey.newBuilder()
                                            .setName("MyInfo1")
                                            .setFile(fakeLabelString)
                                    )
                                    .addOriginKey(
                                        OriginKey.newBuilder()
                                            .setName("MyInfo2")
                                            .setFile(fakeLabelString)
                                    )
                            )
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("d")
                            .setType(AttributeType.LABEL)
                            .setDefaultValue("None")
                            .addProviderNameGroup(
                                ProviderNameGroup.newBuilder()
                                    .addProviderName("MyInfo1")
                                    .addProviderName("MyInfo2")
                                    .addOriginKey(
                                        OriginKey.newBuilder()
                                            .setName("MyInfo1")
                                            .setFile(fakeLabelString)
                                    )
                                    .addOriginKey(
                                        OriginKey.newBuilder()
                                            .setName("MyInfo2")
                                            .setFile(fakeLabelString)
                                    )
                            )
                            .addProviderNameGroup(
                                ProviderNameGroup.newBuilder()
                                    .addProviderName("MyInfo3")
                                    .addOriginKey(
                                        OriginKey.newBuilder()
                                            .setName("MyInfo3")
                                            .setFile(fakeLabelString)
                                    )
                            )
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("deprecated_license")
                            .setType(AttributeType.STRING_LIST)
                            .setDefaultValue("[\"none\"]")
                            .setNonconfigurable(true)
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attributeOrder() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "foo": attr.int(),
                    "bar": attr.int(),
                    "baz": attr.int(),
                },
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(
            moduleInfo.getRuleInfoList().get(0).getAttributeList().stream()
                .map(AttributeInfo::getName)
        )
            .containsExactly("name", "foo", "bar", "baz")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attributeTypes() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "a": attr.int(),
                    "b": attr.label(),
                    "c": attr.string(),
                    "d": attr.string_list(),
                    "e": attr.int_list(),
                    "f": attr.label_list(),
                    "g": attr.bool(),
                    "h": attr.label_keyed_string_dict(),
                    "i": attr.string_dict(),
                    "j": attr.string_list_dict(),
                    "k": attr.output(),
                    "l": attr.output_list(),
                    "m": attr.label_list_dict(),
                },
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getRuleInfoList().get(0).getAttributeList())
            .containsExactlyElementsIn(
                com.google.common.collect.ImmutableList.builder<Any?>()
                    .addAll(IMPLICIT_RULE_ATTRIBUTES.values())
                    .add(
                        AttributeInfo.newBuilder()
                            .setName("a")
                            .setType(AttributeType.INT)
                            .setDefaultValue("0")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("b")
                            .setType(AttributeType.LABEL)
                            .setDefaultValue("None")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("c")
                            .setType(AttributeType.STRING)
                            .setDefaultValue("\"\"")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("d")
                            .setType(AttributeType.STRING_LIST)
                            .setDefaultValue("[]")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("e")
                            .setType(AttributeType.INT_LIST)
                            .setDefaultValue("[]")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("f")
                            .setType(AttributeType.LABEL_LIST)
                            .setDefaultValue("[]")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("g")
                            .setType(AttributeType.BOOLEAN)
                            .setDefaultValue("False")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("h")
                            .setType(AttributeType.LABEL_STRING_DICT)
                            .setDefaultValue("{}")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("i")
                            .setType(AttributeType.STRING_DICT)
                            .setDefaultValue("{}")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("j")
                            .setType(AttributeType.STRING_LIST_DICT)
                            .setDefaultValue("{}")
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("k")
                            .setType(AttributeType.OUTPUT)
                            .setDefaultValue("None")
                            .setNonconfigurable(true)
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("l")
                            .setType(AttributeType.OUTPUT_LIST)
                            .setDefaultValue("[]")
                            .setNonconfigurable(true)
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("m")
                            .setType(AttributeType.LABEL_LIST_DICT)
                            .setDefaultValue("{}")
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexportedRule_notDocumented() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(ctx):
                pass

            s = struct(
                my_rule = rule(
                    doc = "Unexported rule",
                    implementation = _my_impl,
                )
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getRuleInfoList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroDocstring() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(name, visibility):
                pass

            documented_macro = macro(
                doc = "My doc",
                implementation = _my_impl,
            )
            undocumented_macro = macro(
                implementation = _my_impl,
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getMacroInfoList())
            .containsExactly(
                MacroInfo.newBuilder()
                    .setMacroName("documented_macro")
                    .setDocString("My doc")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("documented_macro").setFile(fakeLabelString)
                    )
                    .addAllAttribute(IMPLICIT_MACRO_ATTRIBUTES.values())
                    .build(),
                MacroInfo.newBuilder()
                    .setMacroName("undocumented_macro")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("undocumented_macro").setFile(fakeLabelString)
                    )
                    .addAllAttribute(IMPLICIT_MACRO_ATTRIBUTES.values())
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroFinalizer() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(name, visibility):
                pass

            my_finalizer = macro(
                doc = "My finalizer",
                implementation = _my_impl,
                finalizer = True,
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getMacroInfoList())
            .containsExactly(
                MacroInfo.newBuilder()
                    .setMacroName("my_finalizer")
                    .setDocString("My finalizer")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("my_finalizer").setFile(fakeLabelString)
                    )
                    .addAllAttribute(IMPLICIT_MACRO_ATTRIBUTES.values())
                    .setFinalizer(true)
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroAttributes() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(name):
                pass

            my_macro = macro(
                attrs = {
                    "some_attr": attr.label(mandatory = True),
                    "another_attr": attr.int(doc = "An integer", default = 42),
                    "_implicit_attr": attr.string(default = "IMPLICIT"),
                },
                implementation = _my_impl,
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getMacroInfoList().get(0).getAttributeList())
            .containsExactlyElementsIn(
                com.google.common.collect.ImmutableList.builder<Any?>()
                    .addAll(IMPLICIT_MACRO_ATTRIBUTES.values())
                    .add(
                        AttributeInfo.newBuilder()
                            .setName("some_attr")
                            .setType(AttributeType.LABEL)
                            .setMandatory(true)
                            .build(),
                        AttributeInfo.newBuilder()
                            .setName("another_attr")
                            .setType(AttributeType.INT)
                            .setDocString("An integer")
                            .setDefaultValue("42")
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroInheritedAttributes() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
def _my_rule_impl(ctx):
    pass

_my_rule = rule(
    implementation = _my_rule_impl,
    attrs = {
       "srcs": attr.label_list(doc = "My rule sources"),
    },
)

def _my_macro_impl(name, visibility, srcs, **kwargs):
    _my_rule(name = name, visibility = visibility, srcs = srcs, **kwargs)

my_macro = macro(
    inherit_attrs = _my_rule,
    implementation = _my_macro_impl,
)

""".trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        val attributes: MutableList<AttributeInfo?> = moduleInfo.getMacroInfoList().get(0).getAttributeList()
        assertThat(attributes.get(0)).isEqualTo(IMPLICIT_MACRO_ATTRIBUTES.get("name"))
        assertThat(attributes.get(1)).isEqualTo(IMPLICIT_MACRO_ATTRIBUTES.get("visibility"))
        // Starlark-defined inherited attribute
        Truth.assertThat(attributes)
            .contains(
                AttributeInfo.newBuilder()
                    .setName("srcs")
                    .setType(AttributeType.LABEL_LIST)
                    .setDocString("My rule sources")
                    .setDefaultValue("None") // Default value of inherited attributes is always None
                    .build()
            )
        // Native inherited attributes may not be documented, so ignore doc string for them.
        Truth.assertThat(attributes)
            .ignoringFields(AttributeInfo.DOC_STRING_FIELD_NUMBER)
            .contains(
                AttributeInfo.newBuilder()
                    .setName("tags")
                    .setType(AttributeType.STRING_LIST)
                    .setDefaultValue("None") // Default value of inherited attributes is always None
                    .setNonconfigurable(true)
                    .setNativelyDefined(true)
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexportedMacro_notDocumented() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(name):
                pass

            s = struct(
                my_macro = macro(
                    doc = "Unexported macro",
                    implementation = _my_impl,
                )
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getMacroInfoList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerNameGroups_useFirstDocumentableProviderName() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            _MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "foo": attr.label(providers = [_MyInfo]),
                },
                provides = [_MyInfo],
            )
            namespace1 = struct(_MyUndocumentedInfo = _MyInfo)
            namespace2 = struct(MyInfoB = _MyInfo, MyInfoA = _MyInfo)
            namespace3 = struct(MyInfo = _MyInfo)
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(
            moduleInfo.getRuleInfoList().get(0).getAdvertisedProviders().getProviderName(0)
        ) // Struct fields are extracted in field name alphabetical order, so namespace2.MyInfoA
            // (despite being declared after namespace2.MyInfoB) wins.
            .isEqualTo("namespace2.MyInfoA")
        assertThat(
            moduleInfo
                .getRuleInfoList()
                .get(0)
                .getAttribute(1) // 0 is the implicit name attribute
                .getProviderNameGroup(0)
                .getProviderName(0)
        )
            .isEqualTo("namespace2.MyInfoA")
        assertThat(moduleInfo.getProviderInfoList().stream().map(ProviderInfo::getProviderName))
            .containsExactly("namespace2.MyInfoA", "namespace2.MyInfoB", "namespace3.MyInfo")
        // TODO(arostovtsev): instead of producing a separate ProviderInfo message per each alias, add a
        // repeated alias name field, and produce a single ProviderInfo message listing its aliases.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun labelStringification() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "label": attr.label(default = "//test:foo"),
                    "label_list": attr.label_list(
                        default = ["//x", "@@canonical//y", "@@canonical//y:z"],
                    ),
                    "label_keyed_string_dict": attr.label_keyed_string_dict(
                        default = {"//x": "label_in_main", "@@canonical//y": "label_in_dep"},
                    ),
                    "label_list_dict": attr.label_list_dict(
                        default = {"a": ["//x", "@@canonical//y", "@@canonical//y:z"]},
                    ),
                },
            )
            
            """.trimIndent()
            )
        val canonicalName: RepositoryName = RepositoryName.create("canonical")
        val repositoryMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("local", canonicalName),
                RepositoryName.MAIN
            )
        val moduleInfo: ModuleInfo = getExtractor(repositoryMapping, "my_repo").extractFrom(module)
        assertThat(
            moduleInfo.getRuleInfoList().get(0).getAttributeList().stream()
                .filter({ attr -> !IMPLICIT_RULE_ATTRIBUTES.containsKey(attr.getName()) })
                .map(AttributeInfo::getDefaultValue)
        )
            .containsExactly(
                "\"@my_repo//test:foo\"",
                "[\"@my_repo//x\", \"@local//y\", \"@local//y:z\"]",
                "{\"@my_repo//x\": \"label_in_main\", \"@local//y\": \"label_in_dep\"}",
                "{\"a\": [\"@my_repo//x\", \"@local//y\", \"@local//y:z\"]}"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDocstring() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(target, ctx):
                pass

            documented_aspect = aspect(doc = "My doc", implementation = _my_impl)
            undocumented_aspect = aspect(implementation = _my_impl)
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getAspectInfoList())
            .ignoringFields(AspectInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
            .containsExactly(
                AspectInfo.newBuilder()
                    .setAspectName("documented_aspect")
                    .setDocString("My doc")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("documented_aspect").setFile(fakeLabelString)
                    )
                    .build(),
                AspectInfo.newBuilder()
                    .setAspectName("undocumented_aspect")
                    .setOriginKey(
                        OriginKey.newBuilder().setName("undocumented_aspect").setFile(fakeLabelString)
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAttributes() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(target, ctx):
                pass

            my_aspect = aspect(
                implementation = _my_impl,
                attr_aspects = ["deps", "srcs", "_private"],
                attrs = {
                    "a": attr.string(doc = "My doc", default = "foo"),
                    "b": attr.string(mandatory = True),
                    "_c": attr.string(doc = "Hidden attribute"),
                },
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getAspectInfoList())
            .containsExactly(
                AspectInfo.newBuilder()
                    .setAspectName("my_aspect")
                    .setOriginKey(OriginKey.newBuilder().setName("my_aspect").setFile(fakeLabelString))
                    .addAspectAttribute("deps")
                    .addAspectAttribute("srcs")
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            .setName("a")
                            .setType(AttributeType.STRING)
                            .setDocString("My doc")
                            .setDefaultValue("\"foo\"")
                    )
                    .addAttribute(
                        AttributeInfo.newBuilder()
                            .setName("b")
                            .setType(AttributeType.STRING)
                            .setMandatory(true)
                            .build()
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unexportedAspect_notDocumented() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_impl(target, ctx):
                pass

            s = struct(
                my_aspect = aspect(
                    doc = "Unexported aspect",
                    implementation = _my_impl,
                )
            )
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getAspectInfoList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkOtherSymbols_extractedIfExportableAndDocumented() {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            #: Exportable and documented
            NAMES = ["foo", "bar"]

            # Exportable but not documented
            MORE_NAMES = ["baz", "qux"]

            #: Ignored - non-exportable symbol
            _PRIVATE_CONSTANT = 42

            #: Struct
            S = struct(answer = _PRIVATE_CONSTANT)
            
            """.trimIndent()
            )
        val moduleInfo: ModuleInfo = extractor.extractFrom(module)
        assertThat(moduleInfo.getStarlarkOtherSymbolInfoList())
            .containsExactly(
                StarlarkOtherSymbolInfo.newBuilder()
                    .setName("NAMES")
                    .setDoc("Exportable and documented")
                    .setTypeName("list")
                    .build(),
                StarlarkOtherSymbolInfo.newBuilder()
                    .setName("S")
                    .setDoc("Struct")
                    .setTypeName("struct")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkOtherSymbols_conflictingDocComments(
        @TestParameter allowUnusedDocComments: Boolean
    ) {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            #: Leading doc comment
            ANSWER = 42 #: Trailing doc comment
            
            """.trimIndent()
            )
        if (allowUnusedDocComments) {
            assertThat(
                extractor
                    .allowUnusedDocComments()
                    .extractFrom(module)
                    .getStarlarkOtherSymbolInfoList()
            )
                .containsExactly(
                    StarlarkOtherSymbolInfo.newBuilder()
                        .setName("ANSWER")
                        .setDoc("Trailing doc comment") // Overrides leading doc comment.
                        .setTypeName("int")
                        .build()
                )
        } else {
            val exception: ExtractionException? =
                org.junit.Assert.assertThrows<T?>(
                    ExtractionException::class.java,
                    org.junit.function.ThrowingRunnable { extractor.extractFrom(module) })
            assertThat(exception)
                .hasMessageThat()
                .contains("unexpected or conflicting doc comments on line 1")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun functions_cannotUseDocComments(@TestParameter allowUnusedDocComments: Boolean) {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _my_function():
                pass

            #: Unexpected doc comment
            MY_FUNCTION_ALIAS = _my_function
            
            """.trimIndent()
            )
        if (allowUnusedDocComments) {
            assertThat(extractor.allowUnusedDocComments().extractFrom(module).getFuncInfoList())
                .hasSize(1)
        } else {
            val exception: ExtractionException? =
                org.junit.Assert.assertThrows<T?>(
                    ExtractionException::class.java,
                    org.junit.function.ThrowingRunnable { extractor.extractFrom(module) })
            assertThat(exception)
                .hasMessageThat()
                .contains(
                    "unexpected doc comment for MY_FUNCTION_ALIAS on line 4; API documentation for a"
                            + " function must be provided in a docstring at the top of the function body"
                )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rules_cannotUseDocComments(@TestParameter allowUnusedDocComments: Boolean) {
        val module: net.starlark.java.eval.Module? =
            exec(
                """
            def _impl(ctx):
                pass

            #: Unexpected doc comment
            my_rule = rule(implementation = _impl)
            
            """.trimIndent()
            )
        if (allowUnusedDocComments) {
            assertThat(extractor.allowUnusedDocComments().extractFrom(module).getRuleInfoList())
                .hasSize(1)
        } else {
            val exception: ExtractionException? =
                org.junit.Assert.assertThrows<T?>(
                    ExtractionException::class.java,
                    org.junit.function.ThrowingRunnable { extractor.extractFrom(module) })
            assertThat(exception)
                .hasMessageThat()
                .contains(
                    "unexpected doc comment for my_rule on line 4; API documentation for a rule must be"
                            + " provided in the doc argument to rule()"
                )
        }
    }

    companion object {
        private val extractor: ModuleInfoExtractor
            get() {
                val repositoryMapping: RepositoryMapping? = RepositoryMapping.EMPTY
                return ModuleInfoExtractor(
                    { name -> true }, LabelRenderer(repositoryMapping, java.util.Optional.empty<T?>())
                )
            }

        private fun getExtractor(isWantedQualifiedName: java.util.function.Predicate<String?>?): ModuleInfoExtractor {
            val repositoryMapping: RepositoryMapping? = RepositoryMapping.EMPTY
            return ModuleInfoExtractor(
                isWantedQualifiedName, LabelRenderer(repositoryMapping, java.util.Optional.empty<T?>())
            )
        }

        private fun getExtractor(
            repositoryMapping: RepositoryMapping?, mainRepoName: String
        ): ModuleInfoExtractor {
            return ModuleInfoExtractor(
                { name -> true }, LabelRenderer(repositoryMapping, java.util.Optional.of<T?>(mainRepoName))
            )
        }
    }
}
