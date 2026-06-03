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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.actions.Artifact

/** Tests JavaInfo API for Starlark.  */
@RunWith(JUnit4::class)
class JavaInfoStarlarkApiTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkJavaOutputsCanBeAddedToJavaPluginInfo() {
        val classJar: Artifact = createArtifact("foo.jar")
        val starlarkJavaOutput: StarlarkInfo =
            makeStruct(
                com.google.common.collect.ImmutableMap.of<String?, Any?>(
                    "source_jars",
                    Starlark.NONE,
                    "class_jar",
                    classJar
                )
            )
        val starlarkPluginInfo: StarlarkInfo =
            makeStruct(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "java_outputs", StarlarkList.immutableOf<Any?>(starlarkJavaOutput),
                    "plugins", JavaPluginData.empty(),
                    "api_generating_plugins", JavaPluginData.empty()
                )
            )

        val pluginInfo: JavaPluginInfo? = JavaPluginInfo.wrap(starlarkPluginInfo)

        Truth.assertThat(pluginInfo).isNotNull()
        assertThat(pluginInfo.getJavaOutputs()).hasSize(1)
        assertThat(pluginInfo.getJavaOutputs().get(0).classJar()).isEqualTo(classJar)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeAndStarlarkJavaOutputsCanBeAddedToADepset() {
        scratch.file(
            "foo/extension.bzl",
            """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        def _impl(ctx):
            f = ctx.actions.declare_file(ctx.label.name + ".jar")
            ctx.actions.write(f, "")
            return [JavaInfo(output_jar = f, compile_jar = None)]

        my_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":extension.bzl", "my_rule")

        my_rule(name = "my_starlark_rule")
        
        """.trimIndent()
        )
        val nativeOutput: com.google.devtools.build.lib.rules.java.JavaOutput =
            com.google.devtools.build.lib.rules.java.JavaOutput.builder().setClassJar(createArtifact("native.jar"))
                .build()
        val starlarkOutputs: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.java.JavaOutput?> =
            JavaInfo.Companion.getJavaInfo(getConfiguredTarget("//foo:my_starlark_rule")).javaOutputs

        val depset: Depset =
            Depset.fromDirectAndTransitive(
                Order.STABLE_ORDER,  /* direct= */
                com.google.common.collect.ImmutableList.builder<Any?>().add(nativeOutput).addAll(starlarkOutputs)
                    .build(),  /* transitive= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* strict= */
                true
            )

        assertThat(depset).isNotNull()
        assertThat(depset.toList()).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNeverlinkIsStoredAsABoolean() {
        scratch.file(
            "foo/extension.bzl",
            """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        def _impl(ctx):
            f = ctx.actions.declare_file(ctx.label.name + ".jar")
            ctx.actions.write(f, "")
            return [JavaInfo(output_jar = f, compile_jar = None, neverlink = 1)]

        my_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":extension.bzl", "my_rule")

        my_rule(name = "my_starlark_rule")
        
        """.trimIndent()
        )

        val javaInfo: JavaInfo? = JavaInfo.Companion.getJavaInfo(getConfiguredTarget("//foo:my_starlark_rule"))

        Truth.assertThat(javaInfo).isNotNull()
        Truth.assertThat(javaInfo.isNeverlink).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun translateStarlarkJavaInfo_minimal() {
        val fields: com.google.common.collect.ImmutableMap<String?, Any?> = builderWithMandataryFields.buildOrThrow()
        val starlarkInfo: StarlarkInfo = makeStruct(fields)

        val javaInfo: JavaInfo? = JavaInfo.Companion.wrap(starlarkInfo)

        Truth.assertThat(javaInfo).isNotNull()
        Truth.assertThat(javaInfo.getProvider<JavaCompilationArgsProvider?>(JavaCompilationArgsProvider::class.java))
            .isNotNull()
        Truth.assertThat(javaInfo.compilationInfoProvider).isNull()
        Truth.assertThat(javaInfo.javaModuleFlagsInfo).isEqualTo(JavaModuleFlagsProvider.Companion.EMPTY)
        Truth.assertThat(javaInfo.getJavaPluginInfo())
            .isEqualTo(JavaPluginInfo.empty(JavaPluginInfo.PROVIDER))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun translateStarlarkJavaInfo_binariesDoNotContainCompilationArgs() {
        val fields: com.google.common.collect.ImmutableMap<String?, Any?> =
            builderWithMandataryFields.put("_is_binary", true).buildOrThrow()
        val starlarkInfo: StarlarkInfo = makeStruct(fields)

        val javaInfo: JavaInfo? = JavaInfo.Companion.wrap(starlarkInfo)

        Truth.assertThat(javaInfo).isNotNull()
        Truth.assertThat(javaInfo.getProvider<JavaCompilationArgsProvider?>(JavaCompilationArgsProvider::class.java))
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun translateStarlarkJavaInfo_compilationInfo() {
        val fields: com.google.common.collect.ImmutableMap<String?, Any?> =
            builderWithMandataryFields
                .put(
                    "compilation_info",
                    makeStruct(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "javac_options",
                            Depset.of(
                                String::class.java,
                                NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, "opt1", "opt2")
                            ),
                            "boot_classpath", StarlarkList.immutableOf<Any?>(createArtifact("cp.jar"))
                        )
                    )
                )
                .buildOrThrow()
        val starlarkInfo: StarlarkInfo = makeStruct(fields)

        val javaInfo: JavaInfo? = JavaInfo.Companion.wrap(starlarkInfo)

        Truth.assertThat(javaInfo).isNotNull()
        Truth.assertThat(javaInfo.compilationInfoProvider).isNotNull()
        Truth.assertThat(javaInfo.compilationInfoProvider.getJavacOptsList())
            .containsExactly("opt1", "opt2")
        Truth.assertThat(javaInfo.compilationInfoProvider.bootClasspathList).hasSize(1)
        assertThat(prettyArtifactNames(javaInfo.compilationInfoProvider.bootClasspathList))
            .containsExactly("cp.jar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun translatedStarlarkCompilationInfoEqualsNativeInstance() {
        val bootClasspathArtifact: Artifact = createArtifact("boot.jar")
        val compilationClasspath: NestedSet<Artifact?>? =
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, createArtifact("compile.jar"))
        val runtimeClasspath: NestedSet<Artifact?>? =
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, createArtifact("runtime.jar"))
        val starlarkInfo: StarlarkInfo =
            makeStruct(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "compilation_classpath", Depset.of(Artifact::class.java, compilationClasspath),
                    "runtime_classpath", Depset.of(Artifact::class.java, runtimeClasspath),
                    "javac_options",
                    Depset.of(
                        String::class.java,
                        NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, "opt1", "opt2")
                    ),
                    "boot_classpath", StarlarkList.immutableOf<Any?>(bootClasspathArtifact)
                )
            )
        val nativeCompilationInfo: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setCompilationClasspath(compilationClasspath)
                .setRuntimeClasspath(runtimeClasspath)
                .setJavacOpts(NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, "opt1", "opt2"))
                .setBootClasspath(
                    NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, bootClasspathArtifact)
                )
                .build()

        val starlarkCompilationInfo: JavaCompilationInfoProvider? =
            JavaCompilationInfoProvider.Companion.fromStarlarkCompilationInfo(starlarkInfo)

        Truth.assertThat(starlarkCompilationInfo).isNotNull()
        Truth.assertThat(starlarkCompilationInfo).isEqualTo(nativeCompilationInfo)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun translateStarlarkJavaInfo_moduleFlagsInfo() {
        val fields: com.google.common.collect.ImmutableMap<String?, Any?> =
            builderWithMandataryFields
                .put(
                    "module_flags_info",
                    makeStruct(
                        com.google.common.collect.ImmutableMap.of<String?, Any?>(
                            "add_exports", makeDepset<String?>(String::class.java, "export1", "export2"),
                            "add_opens", makeDepset<String?>(String::class.java, "open1", "open2")
                        )
                    )
                )
                .buildOrThrow()
        val starlarkInfo: StarlarkInfo = makeStruct(fields)

        val javaInfo: JavaInfo? = JavaInfo.Companion.wrap(starlarkInfo)

        Truth.assertThat(javaInfo).isNotNull()
        Truth.assertThat(javaInfo.javaModuleFlagsInfo).isNotNull()
        assertThat(javaInfo.javaModuleFlagsInfo.addExports.toList())
            .containsExactly("export1", "export2")
        assertThat(javaInfo.javaModuleFlagsInfo.addOpens.toList())
            .containsExactly("open1", "open2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun translateStarlarkJavaInfo_pluginInfo() {
        val fields: com.google.common.collect.ImmutableMap<String?, Any?> =
            builderWithMandataryFields
                .put(
                    "plugins",
                    JavaPluginData.create(
                        NestedSetBuilder.create(Order.STABLE_ORDER, "c1", "c2", "c3"),
                        NestedSetBuilder.create(Order.STABLE_ORDER, createArtifact("f1")),
                        NestedSetBuilder.emptySet(Order.STABLE_ORDER)
                    )
                )
                .buildKeepingLast()
        val starlarkInfo: StarlarkInfo = makeStruct(fields)

        val javaInfo: JavaInfo? = JavaInfo.Companion.wrap(starlarkInfo)

        Truth.assertThat(javaInfo).isNotNull()
        Truth.assertThat(javaInfo.plugins()).isNotNull()
        assertThat(javaInfo.plugins().processorClasses().toList()).containsExactly("c1", "c2", "c3")
        assertThat(prettyArtifactNames(javaInfo.plugins().processorClasspath())).containsExactly("f1")
    }

    @Throws(IOException::class)
    private fun createArtifact(path: String?): Artifact {
        val execRoot: Path? = scratch.dir("/")
        val root: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "fake-root")
        return ActionsTestUtil.createArtifact(root, path)
    }

    companion object {
        private val builderWithMandataryFields: com.google.common.collect.ImmutableMap.Builder<String?, Any?>
            get() {
                val emptyDepset: Depset? = Depset.of(Artifact::class.java, NestedSetBuilder.create(Order.STABLE_ORDER))
                return com.google.common.collect.ImmutableMap.builder<String?, Any?>()
                    .put("transitive_native_libraries", emptyDepset)
                    .put("compile_jars", emptyDepset)
                    .put("full_compile_jars", emptyDepset)
                    .put("transitive_compile_time_jars", emptyDepset)
                    .put("transitive_runtime_jars", emptyDepset)
                    .put("_transitive_full_compile_time_jars", emptyDepset)
                    .put("_compile_time_java_dependencies", emptyDepset)
                    .put("header_compilation_direct_deps", emptyDepset)
                    .put("plugins", JavaPluginData.empty())
                    .put("api_generating_plugins", JavaPluginData.empty())
                    .put("java_outputs", StarlarkList.empty<Any?>())
                    .put("transitive_source_jars", emptyDepset)
                    .put("source_jars", StarlarkList.empty<Any?>())
                    .put("runtime_output_jars", StarlarkList.empty<Any?>())
            }

        private fun <T> makeDepset(clazz: java.lang.Class<T?>?, vararg elems: T?): Depset {
            return Depset.of(clazz, NestedSetBuilder.create(Order.STABLE_ORDER, elems))
        }

        private fun makeStruct(struct: MutableMap<String?, Any?>?): StarlarkInfo {
            return StructProvider.STRUCT.create(struct, "")
        }
    }
}
