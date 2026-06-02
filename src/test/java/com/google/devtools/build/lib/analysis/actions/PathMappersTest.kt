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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.Artifact

/** Tests for [PathMappers].  */
@RunWith(JUnit4::class)
class PathMappersTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        useConfiguration("--experimental_output_paths=strip")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaLibraryWithJavacopts() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        genrule(
            name = 'gen_b',
            outs = ['B.java'],
            cmd = '<some command>',
        )
        genrule(
            name = 'gen_c',
            outs = ['C.java'],
            cmd = '<some command>',
        )
        java_library(
            name = 'a',
            javacopts = [
                '-XepOpt:foo:bar=${'$'}(location B.java)',
                '-XepOpt:baz=${'$'}(location C.java),${'$'}(location B.java)',
            ],
            srcs = [
                'A.java',
                'B.java',
                'C.java',
            ],
        )
        
        """.trimIndent()
        )

        val configuredTarget: ConfiguredTarget? = getConfiguredTarget("//java/com/google/test:a")
        val compiledArtifact: Artifact? =
            JavaInfo.getProvider<T?>(JavaCompilationArgsProvider::class.java, configuredTarget)
                .directCompileTimeJars()
                .toList()
                .get(0)
        val action: SpawnAction? = getGeneratingAction(compiledArtifact) as SpawnAction?
        val spawn: Spawn =
            action.getSpawn(
                ActionExecutionContextBuilder()
                    .setMetadataProvider(com.google.devtools.build.lib.exec.util.FakeActionInputFileCache())
                    .build()
            )

        assertThat(spawn.getPathMapper().isNoop()).isFalse()
        val outDir = analysisMock.getProductName() + "-out"
        assertThat(
            spawn.getArguments().stream()
                .filter({ arg -> arg.contains("java/com/google/test/") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactly(
                "java/com/google/test/A.java",
                java.lang.String.format("%s/cfg/bin/java/com/google/test/B.java", outDir),
                java.lang.String.format("%s/cfg/bin/java/com/google/test/C.java", outDir),
                java.lang.String.format("%s/cfg/bin/java/com/google/test/liba-hjar.jar", outDir),
                java.lang.String.format("%s/cfg/bin/java/com/google/test/liba-hjar.jdeps", outDir),
                java.lang.String.format("%s/cfg/bin/java/com/google/test/liba-tjar.jar", outDir),
                java.lang.String.format("-XepOpt:foo:bar=%s/cfg/bin/java/com/google/test/B.java", outDir),
                java.lang.String.format(
                    "-XepOpt:baz=%s/cfg/bin/java/com/google/test/C.java,%s/cfg/bin/java/com/google/test/B.java",
                    outDir, outDir
                )
            )
    }

    @Throws(IOException::class)
    private fun addStarlarkRule(executionRequirements: Dict<String?, String?>?) {
        scratch.file("defs/BUILD")
        scratch.file(
            "defs/defs.bzl",
            "def _map_each(file):",
            "    return '{}:{}:{}:{}'.format(file.short_path, file.path, file.root.path, file.dirname)",
            "def _my_rule_impl(ctx):",
            "    args = ctx.actions.args()",
            "    args.add(ctx.outputs.out)",
            "    args.add_all(",
            "        depset(ctx.files.srcs),",
            "        before_each = '-source',",
            "        format_each = '<%s>',",
            "        map_each = _map_each,",
            "    )",
            "    ctx.actions.run(",
            "        outputs = [ctx.outputs.out],",
            "        inputs = ctx.files.srcs,",
            "        executable = ctx.executable._tool,",
            "        arguments = [args],",
            "        mnemonic = 'MyRuleAction',",
            java.lang.String.format(
                "        execution_requirements = %s,",
                Starlark.repr(executionRequirements, StarlarkSemantics.DEFAULT)
            ),
            "    )",
            "    return [DefaultInfo(files = depset([ctx.outputs.out]))]",
            "my_rule = rule(",
            "    implementation = _my_rule_impl,",
            "    attrs = {",
            "        'srcs': attr.label_list(allow_files = True),",
            "        'out': attr.output(mandatory = True),",
            "        '_tool': attr.label(",
            "            default = '//tool',",
            "            executable = True,",
            "            cfg = 'exec',",
            "        ),",
            "    },",
            ")"
        )
        scratch.file(
            "pkg/BUILD",
            """
        load('//defs:defs.bzl', 'my_rule')
        genrule(
            name = 'gen_src',
            outs = ['gen_src.txt'],
            cmd = '<some command>',
        )
        my_rule(
            name = 'my_rule',
            out = 'out.bin',
            srcs = [
                ':gen_src',
                'source.txt',
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "tool/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = 'tool',
            srcs = ['tool.sh'],
            visibility = ['//visibility:public'],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRule_optedInViaExecutionRequirements() {
        addStarlarkRule(
            Dict.builder<String?, String?>().put("supports-path-mapping", "1").buildImmutable()
        )

        val configuredTarget: ConfiguredTarget? = getConfiguredTarget("//pkg:my_rule")
        val outputArtifact: Artifact? =
            configuredTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList().get(0)
        val action: SpawnAction? = getGeneratingAction(outputArtifact) as SpawnAction?
        val spawn: Spawn =
            action.getSpawn(
                ActionExecutionContextBuilder()
                    .setMetadataProvider(com.google.devtools.build.lib.exec.util.FakeActionInputFileCache())
                    .build()
            )

        assertThat(spawn.getPathMapper().isNoop()).isFalse()
        val outDir = analysisMock.getProductName() + "-out"
        assertThat(spawn.getArguments().stream().collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()))
            .containsExactly(
                java.lang.String.format("%s/cfg/bin/tool/tool", outDir),
                java.lang.String.format("%s/cfg/bin/pkg/out.bin", outDir),
                "-source",
                java.lang.String.format(
                    "<pkg/gen_src.txt:%1\$s/cfg/bin/pkg/gen_src.txt:%1\$s/cfg/bin:%1\$s/cfg/bin/pkg>",
                    outDir
                ),
                "-source",
                "<pkg/source.txt:pkg/source.txt::pkg>"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRule_optedInViaModifyExecutionInfo() {
        useConfiguration(
            "--experimental_output_paths=strip",
            "--modify_execution_info=MyRuleAction=+supports-path-mapping"
        )
        addStarlarkRule(Dict.empty<String?, String?>())

        val configuredTarget: ConfiguredTarget? = getConfiguredTarget("//pkg:my_rule")
        val outputArtifact: Artifact? =
            configuredTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList().get(0)
        val action: SpawnAction? = getGeneratingAction(outputArtifact) as SpawnAction?
        val spawn: Spawn =
            action.getSpawn(
                ActionExecutionContextBuilder()
                    .setMetadataProvider(com.google.devtools.build.lib.exec.util.FakeActionInputFileCache())
                    .build()
            )

        assertThat(spawn.getPathMapper().isNoop()).isFalse()
        val outDir = analysisMock.getProductName() + "-out"
        assertThat(spawn.getArguments().stream().collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()))
            .containsExactly(
                java.lang.String.format("%s/cfg/bin/tool/tool", outDir),
                java.lang.String.format("%s/cfg/bin/pkg/out.bin", outDir),
                "-source",
                java.lang.String.format(
                    "<pkg/gen_src.txt:%1\$s/cfg/bin/pkg/gen_src.txt:%1\$s/cfg/bin:%1\$s/cfg/bin/pkg>",
                    outDir
                ),
                "-source",
                "<pkg/source.txt:pkg/source.txt::pkg>"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRule_stringExecutablePath() {
        scratch.file("defs/BUILD")
        scratch.file(
            "defs/defs.bzl",
            """
        def my_rule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.run(
                executable = ctx.executable.tool.path,
                arguments = [ctx.actions.args().add(out)],
                outputs = [out],
                tools = [ctx.executable.tool],
                execution_requirements = {"supports-path-mapping": "1"},
            )
            return DefaultInfo(files = depset([out]))
        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "tool": attr.label(
                    default = "//foo:script",
                    cfg = "exec",
                    executable = True,
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = 'script',
            srcs = ['script.sh'],
            visibility = ['//visibility:public'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            """
        load("//defs:defs.bzl", "my_rule")
        my_rule(name = "my_rule")
        
        """.trimIndent()
        )

        val configuredTarget: ConfiguredTarget? = getConfiguredTarget("//:my_rule")
        val outputArtifact: Artifact? =
            configuredTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList().get(0)
        val action: SpawnAction? = getGeneratingAction(outputArtifact) as SpawnAction?
        val spawn: Spawn =
            action.getSpawn(
                ActionExecutionContextBuilder()
                    .setMetadataProvider(com.google.devtools.build.lib.exec.util.FakeActionInputFileCache())
                    .build()
            )

        assertThat(spawn.getPathMapper().isNoop()).isFalse()
        val outDir = analysisMock.getProductName() + "-out"
        assertThat(spawn.getArguments())
            .containsExactly(
                "%s/cfg/bin/foo/script".formatted(outDir), "%s/cfg/bin/my_rule".formatted(outDir)
            )
            .inOrder()
    }

    @org.junit.Test
    fun forActionKey() {
        val pathMapper: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            PathMapper.forActionKey(CoreOptions.OutputPathsMode.STRIP)
        assertThat(pathMapper.isNoop()).isFalse()
        assertThat(pathMapper.map(PathFragment.create("pkg/file")))
            .isEqualTo(PathFragment.create("pkg/file"))
        assertThat(pathMapper.map(PathFragment.create("bazel-out/k8-fastbuild-ST-12345/bin/pkg/file")))
            .isEqualTo(PathFragment.create("bazel-out/pm-k8-fastbuild-ST-12345/bin/pkg/file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRule_archivedTreePaths() {
        val outDir = analysisMock.getProductName() + "-out"
        scratch.file("defs/BUILD")
        scratch.file(
            "defs/defs.bzl",
            """
        def my_rule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name)
            args = ctx.actions.args()
            args.add(out)
            args.add("--input")
            args.add("%1${'$'}s/k8-fastbuild/bin/pkg/standard.js")
            args.add("--input")
            args.add("%1${'$'}s/:archived_tree_artifacts/k8-fastbuild/bin/pkg/tree.zip")
            ctx.actions.run(
                executable = ctx.executable.tool.path,
                arguments = [args],
                outputs = [out],
                tools = [ctx.executable.tool],
                mnemonic = "Android",  # Using a supported mnemonic to enable path-stripping.
                execution_requirements = {"supports-path-mapping": "1"},
            )
            return DefaultInfo(files = depset([out]))
        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "tool": attr.label(
                    default = "//foo:script",
                    cfg = "exec",
                    executable = True,
                ),
            },
        )
        
        """
                .trimIndent()
                .formatted(outDir)
        )
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = 'script',
            srcs = ['script.sh'],
            visibility = ['//visibility:public'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            """
        load("//defs:defs.bzl", "my_rule")
        my_rule(name = "my_rule")
        
        """.trimIndent()
        )

        val configuredTarget: ConfiguredTarget? = getConfiguredTarget("//:my_rule")
        val outputArtifact: Artifact? =
            configuredTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList().get(0)
        val action: SpawnAction? = getGeneratingAction(outputArtifact) as SpawnAction?
        val spawn: Spawn =
            action.getSpawn(
                ActionExecutionContextBuilder()
                    .setMetadataProvider(com.google.devtools.build.lib.exec.util.FakeActionInputFileCache())
                    .build()
            )

        assertThat(spawn.getPathMapper().isNoop()).isFalse()

        assertThat(spawn.getArguments())
            .containsExactly(
                "%s/cfg/bin/foo/script".formatted(outDir),
                "%s/cfg/bin/my_rule".formatted(outDir),
                "--input",
                "%s/cfg/bin/pkg/standard.js".formatted(outDir),
                "--input",
                "%s/:archived_tree_artifacts/cfg/bin/pkg/tree.zip".formatted(outDir)
            )
            .inOrder()
    }
}
