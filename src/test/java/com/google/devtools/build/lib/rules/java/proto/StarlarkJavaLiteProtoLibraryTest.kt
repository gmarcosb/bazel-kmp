// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java.proto

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.eventbus.EventBus
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import org.junit.Test

/** Tests for the Starlark version of java_lite_proto_library rule.  */
@RunWith(JUnit4::class)
class StarlarkJavaLiteProtoLibraryTest : BuildViewTestCase() {
    private var actionsTestUtil: ActionsTestUtil? = null

    @Before
    @Throws(Exception::class)
    fun setUpMocks() {
        setBuildLanguageOptions("--incompatible_enable_proto_toolchain_resolution=false")
        useConfiguration(
            "--proto_compiler=//proto:compiler",
            "--proto_toolchain_for_javalite=//tools/proto/toolchains:javalite"
        )
        MockProtoSupport.setup(mockToolsConfig)

        scratch.file(
            "proto/BUILD",
            """
        licenses(["notice"])

        exports_files(["compiler"])
        
        """.trimIndent()
        )

        mockToolchains()
        invalidatePackages()

        actionsTestUtil = actionsTestUtil()
    }

    @Throws(IOException::class)
    private fun mockToolchains() {
        mockRuntimes()

        scratch.appendFile(
            "tools/proto/toolchains/BUILD",
            """
load('@com_google_protobuf//bazel/toolchains:proto_lang_toolchain.bzl', 'proto_lang_toolchain')
package(default_visibility = ["//visibility:public"])

proto_lang_toolchain(
    name = "javalite",
    command_line = "--java_out=lite,immutable:${'$'}(OUT)",
    progress_message = "Generating JavaLite proto_library %{label}",
    runtime = "//protobuf:javalite_runtime",
)

""".trimIndent()
        )
    }

    @Throws(IOException::class)
    private fun mockRuntimes() {
        mockToolsConfig.overwrite(
            "protobuf/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        package(default_visibility = ["//visibility:public"])

        java_library(
            name = "javalite_runtime",
            srcs = ["javalite_runtime.java"],
        )
        
        """.trimIndent()
        )
    }

    /** Tests that java_binaries which depend on proto_libraries depend on the right set of files.  */
    @Test
    @Throws(Exception::class)
    fun testBinaryDeps() {
        scratch.file(
            "x/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        java_lite_proto_library(
            name = "lite_pb2",
            deps = [":foo"],
        )

        proto_library(
            name = "foo",
            srcs = [
                "bar.proto",
                "foo.proto",
            ],
            deps = [":baz"],
        )

        proto_library(
            name = "baz",
            srcs = ["baz.proto"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget = getConfiguredTarget("//x:lite_pb2")
        val filesToBuild: NestedSet<Artifact?> = getFilesToBuild(target)
        val deps: Iterable<String?>? = prettyArtifactNames(actionsTestUtil.artifactClosureOf(filesToBuild))

        // Should depend on compiler and Java proto1 API.
        Truth.assertThat(deps).contains("proto/compiler")

        // Also should not depend on RPC APIs.
        Truth.assertThat(deps).doesNotContain("apps/xplat/rpc/codegen/protoc-gen-rpc")

        // Should depend on Java outputs.
        Truth.assertThat(deps).contains("x/foo-lite-src.jar")
        Truth.assertThat(deps).contains("x/baz-lite-src.jar")

        // Should depend on Java libraries.
        Truth.assertThat(deps).contains("x/libfoo-lite.jar")
        Truth.assertThat(deps).contains("x/libbaz-lite.jar")
        Truth.assertThat(deps).contains("protobuf/libjavalite_runtime-hjar.jar")
    }

    /** Tests that we pass the correct arguments to the protocol compiler.  */
    @Test
    @Throws(Exception::class)
    fun testJavaProto2CompilerArgs() {
        scratch.file(
            "x/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        java_lite_proto_library(
            name = "lite_pb2",
            deps = [":protolib"],
        )

        proto_library(
            name = "protolib",
            srcs = ["file.proto"],
        )
        
        """.trimIndent()
        )

        val genfilesDir: String? = targetConfig.getGenfilesFragment(RepositoryName.MAIN).getPathString()

        val args: MutableList<String?>? =
            getGeneratingSpawnAction(getConfiguredTarget("//x:lite_pb2"), "x/protolib-lite-src.jar")
                .getRemainingArguments()

        Truth.assertThat(args)
            .containsAtLeast(
                "--java_out=lite,immutable:" + genfilesDir + "/x/protolib-lite-src.jar",
                "-I.",
                "x/file.proto"
            )
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testProtoLibraryBuildsCompiledJar() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "java",
                "lite_pb2",
                "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
                "load('@com_google_protobuf//bazel:java_lite_proto_library.bzl',"
                        + " 'java_lite_proto_library')",
                "java_lite_proto_library(name = 'lite_pb2', deps = [':compiled'])",
                "proto_library(name = 'compiled',",
                "              srcs = [ 'ok.proto' ])"
            )

        val compiledJar: Artifact? =
            ActionsTestUtil.getFirstArtifactEndingWith(
                getFilesToBuild(target), "/libcompiled-lite.jar"
            )
        assertThat(compiledJar).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testCommandLineContainsTargetLabel() {
        scratch.file(
            "java/lib/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        java_lite_proto_library(
            name = "lite_pb2",
            deps = [":proto"],
        )

        proto_library(
            name = "proto",
            srcs = ["dummy.proto"],
        )
        
        """.trimIndent()
        )

        val javacAction: JavaCompileAction =
            getGeneratingAction(
                getConfiguredTarget("//java/lib:lite_pb2"), "java/lib/libproto-lite.jar"
            ) as JavaCompileAction

        val commandLine: MutableList<String?> =
            ImmutableList.copyOf<String?>(JavaCompileActionTestHelper.getJavacArguments(javacAction) as Iterable<String?>?)
        MoreAsserts.assertContainsSublist<String?>(commandLine, "--target_label", "//java/lib:proto")
    }

    @Test
    @Throws(Exception::class)
    fun testEmptySrcsForJavaApi() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "notbad",
                "lite_pb2",
                "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
                "load('@com_google_protobuf//bazel:java_lite_proto_library.bzl',"
                        + " 'java_lite_proto_library')",
                "java_lite_proto_library(name = 'lite_pb2', deps = [':null_lib'])",
                "proto_library(name = 'null_lib')"
            )
        val compilationArgsProvider: JavaCompilationArgsProvider? =
            JavaInfo.Companion.getProvider<T?>(JavaCompilationArgsProvider::class.java, target)
        Truth.assertThat(compilationArgsProvider).isNotNull()
        assertThat(compilationArgsProvider.directCompileTimeJars).isNotNull()
        val sourceJarsProvider: JavaSourceJarsProvider? =
            JavaInfo.Companion.getProvider<T?>(JavaSourceJarsProvider::class.java, target)
        Truth.assertThat(sourceJarsProvider).isNotNull()
        Truth.assertThat(sourceJarsProvider.sourceJars).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testSameVersionCompilerArguments() {
        scratch.file(
            "cross/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        java_lite_proto_library(
            name = "lite_pb2",
            deps = ["bravo"],
        )

        proto_library(
            name = "bravo",
            srcs = ["bravo.proto"],
            deps = [":alpha"],
        )

        proto_library(name = "alpha")
        
        """.trimIndent()
        )

        val genfilesDir: String? = targetConfig.getGenfilesFragment(RepositoryName.MAIN).getPathString()

        val litepb2: ConfiguredTarget = getConfiguredTarget("//cross:lite_pb2")

        val args: MutableList<String?>? =
            getGeneratingSpawnAction(litepb2, "cross/bravo-lite-src.jar").getRemainingArguments()
        Truth.assertThat(args)
            .containsAtLeast(
                "--java_out=lite,immutable:" + genfilesDir + "/cross/bravo-lite-src.jar",
                "-I.",
                "cross/bravo.proto"
            )
            .inOrder()

        val directJars: MutableList<String?>? =
            prettyArtifactNames(
                JavaInfo.Companion.getProvider<T?>(JavaCompilationArgsProvider::class.java, litepb2).runtimeJars()
            )
        Truth.assertThat(directJars)
            .containsExactly("cross/libbravo-lite.jar", "protobuf/libjavalite_runtime.jar")
    }

    @Test
    @Ignore // TODO(elenairina): Enable this test when proguard specs are supported in the Starlark version of
    // java_lite_proto_library OR delete this if Proguard support will be removed from Java rules.
    @Throws(Exception::class)
    fun testExportsProguardSpecsForSupportLibraries() {
        scratch.overwriteFile(
            "protobuf/BUILD",
            "package(default_visibility=['//visibility:public'])",
            "java_library(name = 'javalite_runtime', srcs = ['javalite_runtime.java'], "
                    + "proguard_specs = ['javalite_runtime.pro'])"
        )

        scratch.file(
            "x/BUILD",
            """
        java_lite_proto_library(
            name = "lite_pb2",
            deps = [":foo"],
        )

        proto_library(
            name = "foo",
            deps = [":bar"],
        )

        proto_library(name = "bar")
        
        """.trimIndent()
        )
        val key: StarlarkProvider.Key =
            Key(
                keyForBuild(
                    Label.parseCanonicalUnchecked(
                        "@rules_java//java/common:proguard_spec_info.bzl"
                    )
                ),
                "ProguardSpecInfo"
            )
        val proguardSpecInfo: StarlarkInfo = getConfiguredTarget("//x:lite_pb2").get(key) as StarlarkInfo
        val providedSpecs: NestedSet<Artifact?>? =
            proguardSpecInfo.getValue("specs", Depset::class.java).getSet(Artifact::class.java)

        assertThat(ActionsTestUtil.baseArtifactNames(providedSpecs))
            .containsExactly("javalite_runtime.pro_valid")
    }

    @Test
    @Throws(Exception::class)
    fun testExperimentalProtoExtraActions() {
        scratch.file(
            "x/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        java_lite_proto_library(
            name = "lite_pb2",
            deps = [":foo"],
        )

        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "xa/BUILD",
            """
        extra_action(
            name = "xa",
            cmd = "echo ${'$'}(EXTRA_ACTION_FILE)",
        )

        action_listener(
            name = "al",
            extra_actions = [":xa"],
            mnemonics = ["Javac"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--experimental_action_listener=//xa:al",
            "--proto_compiler=//proto:compiler",
            "--proto_toolchain_for_javalite=//tools/proto/toolchains:javalite"
        )
        val ct: ConfiguredTarget = getConfiguredTarget("//x:lite_pb2")
        val artifacts: NestedSet<DerivedArtifact?> =
            ct.getProvider(ExtraActionArtifactsProvider::class.java).getTransitiveExtraActionArtifacts()

        val extraActionOwnerLabels: Iterable<String?> =
            Iterables.transform<F?, T?>(
                artifacts.toList(),
                Function { artifact: F? -> if (artifact == null) null else artifact.getOwnerLabel().toString() })

        Truth.assertThat(extraActionOwnerLabels).contains("//x:foo")
    }

    /**
     * Verify that a java_lite_proto_library exposes Starlark providers for the Java code it
     * generates.
     */
    @Test
    @Throws(Exception::class)
    fun testJavaProtosExposeStarlarkProviders() {
        scratch.file(
            "proto/extensions.bzl",
            """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        def _impl(ctx):
            print(ctx.attr.dep[JavaInfo])

        custom_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "protolib/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        load("//proto:extensions.bzl", "custom_rule")

        proto_library(
            name = "proto",
            srcs = ["file.proto"],
        )

        java_lite_proto_library(
            name = "lite_pb2",
            deps = [":proto"],
        )

        custom_rule(
            name = "custom",
            dep = ":lite_pb2",
        )
        
        """.trimIndent()
        )
        update(
            ImmutableList.of<String?>("//protolib:custom"),  /* keepGoing= */
            false,  /* loadingPhaseThreads= */
            1,  /* doAnalysis= */
            true,
            EventBus()
        )
        // Implicitly check that `update()` above didn't throw an exception. This implicitly checks that
        // ctx.attr.dep.java.{transitive_compile_time_jars, outputs}, above, is defined.
    }

    @Test
    @Throws(Exception::class)
    fun testProtoLibraryInterop() {
        scratch.file(
            "protolib/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        proto_library(
            name = "proto",
            srcs = ["file.proto"],
        )

        java_lite_proto_library(
            name = "lite_pb2",
            deps = [":proto"],
        )
        
        """.trimIndent()
        )
        update(
            ImmutableList.of<String?>("//protolib:lite_pb2"),  /* keepGoing= */
            false,  /* loadingPhaseThreads= */
            1,  /* doAnalysis= */
            true,
            EventBus()
        )
    }

    /**
     * Tests that a java_proto_library only provides direct jars corresponding on the proto_library
     * rules it directly depends on, excluding anything that the proto_library rules depends on
     * themselves. This does not concern strict-deps in the compilation of the generated Java code
     * itself, only compilation of regular code in java_library/java_binary and similar rules.
     * 
     * 
     * Here, a java_lite_proto_library dependes on an alias proto. We make sure that the system
     * behaves as if we depend directly on the aliased proto_library.
     */
    @Test
    @Throws(Exception::class)
    fun jplCorrectlyDefinesDirectJars_strictDepsEnabled_aliasProto() {
        scratch.file(
            "x/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        load('@com_google_protobuf//bazel:java_lite_proto_library.bzl', 'java_lite_proto_library')
        java_lite_proto_library(
            name = "foo_java_proto_lite",
            deps = [":foo_proto"],
        )

        proto_library(
            name = "foo_proto",
            deps = [":bar_proto"],
        )

        proto_library(
            name = "bar_proto",
            srcs = ["bar.proto"],
        )
        
        """.trimIndent()
        )

        val compilationArgsProvider: JavaCompilationArgsProvider? =
            JavaInfo.Companion.getProvider<T?>(
                JavaCompilationArgsProvider::class.java, getConfiguredTarget("//x:foo_java_proto_lite")
            )

        val directJars: Iterable<String?>? =
            prettyArtifactNames(compilationArgsProvider.directCompileTimeJars)

        Truth.assertThat(directJars).containsExactly("x/libbar_proto-lite-hjar.jar")
    }

    /**
     * Tests that when strict-deps is disabled, java_lite_proto_library provides (in its "direct"
     * jars) all transitive classes, not only direct ones. This does not concern strict-deps in the
     * compilation of the generated Java code itself, only compilation of regular code in
     * java_library/java_binary and similar rules.
     */
    @Test
    @Ignore("TODO(b/216484418): Systematize this test with its new version.")
    @Throws(Exception::class)
    fun jplCorrectlyDefinesDirectJars_strictDepsDisabled() {
        scratch.file(
            "x/BUILD",
            """
        java_lite_proto_library(
            name = "foo_lite_pb",
            deps = [":foo"],
        )

        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
            deps = [":bar"],
        )

        java_lite_proto_library(
            name = "bar_lite_pb",
            deps = [":bar"],
        )

        proto_library(
            name = "bar",
            srcs = ["bar.proto"],
            deps = [":baz"],
        )

        proto_library(
            name = "baz",
            srcs = ["baz.proto"],
        )
        
        """.trimIndent()
        )

        run {
            val action: JavaCompileAction =
                getGeneratingAction(getConfiguredTarget("//x:foo_lite_pb"), "x/libfoo-lite.jar") as JavaCompileAction
            assertThat(
                prettyArtifactNames(
                    getInputs(
                        action,
                        JavaCompileActionTestHelper.getDirectJars(action)
                    )
                )
            ).isEmpty()
        }

        run {
            val action: JavaCompileAction =
                getGeneratingAction(getConfiguredTarget("//x:bar_lite_pb"), "x/libbar-lite.jar") as JavaCompileAction
            assertThat(
                prettyArtifactNames(
                    getInputs(
                        action,
                        JavaCompileActionTestHelper.getDirectJars(action)
                    )
                )
            ).isEmpty()
        }
    }

    /** Tests that java_lite_proto_library's aspect exposes a Starlark provider named 'proto_java'.  */
    @Test
    @Ignore // TODO(elenairina): Enable this test when proto_java is returned from the aspect in Starlark
    // version of java_lite_proto_library.
    @Throws(Exception::class)
    fun testJavaLiteProtoLibraryAspectProviders() {
        scratch.file(
            "x/aspect.bzl",
            """
        MyInfo = provider()

        def _foo_aspect_impl(target, ctx):
            proto_found = hasattr(target, "proto_java")
            if hasattr(ctx.rule.attr, "deps"):
                for dep in ctx.rule.attr.deps:
                    proto_found = proto_found or dep.proto_found
            return MyInfo(proto_found = proto_found)

        foo_aspect = aspect(_foo_aspect_impl, attr_aspects = ["deps"])

        def _foo_rule_impl(ctx):
            return MyInfo(result = ctx.attr.dep.proto_found)

        foo_rule = rule(_foo_rule_impl, attrs = {"dep": attr.label(aspects = [foo_aspect])})
        
        """.trimIndent()
        )
        scratch.file(
            "x/BUILD",
            """
        load(":aspect.bzl", "foo_rule")

        java_lite_proto_library(
            name = "foo_java_proto",
            deps = ["foo_proto"],
        )

        proto_library(
            name = "foo_proto",
            srcs = ["foo.proto"],
            java_lib = ":lib",
        )

        foo_rule(
            name = "foo_rule",
            dep = "foo_java_proto",
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo_rule")
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//x:aspect.bzl")), "MyInfo")
        val myInfo: StructImpl = target.get(key) as StructImpl
        val result = myInfo.getValue("result") as Boolean?

        // "yes" means that "proto_java" was found on the proto_library + java_proto_library aspect.
        Truth.assertThat(result).isTrue()
    }
}
