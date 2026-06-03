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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for the Starlark-accessible actions provider on rule configured targets.  */
@RunWith(JUnit4::class)
class StarlarkActionProviderTest : AnalysisTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectGetsActionProviderForNativeRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        foo = provider()

        def _impl(target, ctx):
            return [foo(actions = target.actions)]

        MyAspect = aspect(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "xxx",
            outs = ["mygen.out"],
            cmd = 'echo "hello" > ${'$'}@',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val fooKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo")

        val fooProvider: StructImpl = configuredAspect.get(fooKey) as StructImpl
        assertThat(fooProvider.getValue("actions")).isNotNull()
        val actions: net.starlark.java.eval.Sequence<ActionAnalysisMetadata>? =
            fooProvider.getValue("actions") as net.starlark.java.eval.Sequence<ActionAnalysisMetadata>?
        Truth.assertThat(actions).isNotEmpty()

        val action: ActionAnalysisMetadata = actions.get(0)
        assertThat(action.getMnemonic()).isEqualTo("Genrule")
        assertThat(action).isInstanceOf(AbstractAction::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectGetsActionProviderForStarlarkRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        foo = provider()

        def _impl(target, ctx):
            mnemonics = [a.mnemonic for a in target.actions]
            envs = [a.env for a in target.actions]
            execution_info = [a.execution_info for a in target.actions]
            inputs = [a.inputs.to_list() for a in target.actions]
            outputs = [a.outputs.to_list() for a in target.actions]
            argv = [a.argv for a in target.actions]
            return [foo(
                actions = target.actions,
                mnemonics = mnemonics,
                envs = envs,
                execution_info = execution_info,
                inputs = inputs,
                outputs = outputs,
                argv = argv,
            )]

        MyAspect = aspect(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            """
        def impl(ctx):
            output_file0 = ctx.actions.declare_file("myfile0")
            output_file1 = ctx.actions.declare_file("myfile1")
            executable = ctx.actions.declare_file("executable")
            ctx.actions.run(
                outputs = [output_file0],
                executable = executable,
                toolchain = None,
                mnemonic = "MyAction0",
                env = {"foo": "bar", "pet": "puppy"},
            )
            ctx.actions.run_shell(
                outputs = [executable, output_file1],
                command = "fakecmd",
                mnemonic = "MyAction1",
                env = {"pet": "bunny"},
            )
            return None

        my_rule = rule(impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rule.bzl", "my_rule")

        my_rule(
            name = "xxx",
        )
        
        """.trimIndent()
        )

        useConfiguration("--experimental_google_legacy_api")
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val fooKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo")
        val fooProvider: StructImpl = configuredAspect.get(fooKey) as StructImpl
        assertThat(fooProvider.getValue("actions")).isNotNull()

        val actions: net.starlark.java.eval.Sequence<ActionAnalysisMetadata>? =
            fooProvider.getValue("actions") as net.starlark.java.eval.Sequence<ActionAnalysisMetadata>?
        Truth.assertThat(actions).hasSize(2)

        val mnemonics: net.starlark.java.eval.Sequence<String>? =
            fooProvider.getValue("mnemonics") as net.starlark.java.eval.Sequence<String>?
        Truth.assertThat(mnemonics).containsExactly("MyAction0", "MyAction1")

        val envs: net.starlark.java.eval.Sequence<Dict<String?, String?>>? =
            fooProvider.getValue("envs") as net.starlark.java.eval.Sequence<Dict<String?, String?>>?
        Truth.assertThat(envs)
            .containsExactly(
                Dict.builder<Any?, Any?>().put("foo", "bar").put("pet", "puppy").buildImmutable(),
                Dict.builder<Any?, Any?>().put("pet", "bunny").buildImmutable()
            )

        val executionInfo: net.starlark.java.eval.Sequence<Dict<String?, String?>>? =
            fooProvider.getValue("execution_info") as net.starlark.java.eval.Sequence<Dict<String?, String?>>?
        Truth.assertThat(executionInfo).isNotNull()

        val inputs: net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<Artifact?>?> =
            fooProvider.getValue("inputs") as net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<Artifact?>?>
        Truth.assertThat(flattenArtifactNames(inputs)).containsExactly("executable")

        val outputs: net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<Artifact?>?> =
            fooProvider.getValue("outputs") as net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<Artifact?>?>
        Truth.assertThat(flattenArtifactNames(outputs)).containsExactly("myfile0", "executable", "myfile1")

        val argv: net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<String?>?> =
            fooProvider.getValue("argv") as net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<String?>?>
        Truth.assertThat(argv.get(0)).hasSize(1)
        Truth.assertThat(argv.get(0).get(0)).endsWith("executable")
        Truth.assertThat(argv.get(1)).contains("fakecmd")
    }

    companion object {
        private fun flattenArtifactNames(artifactLists: net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<Artifact?>?>): MutableList<String> {
            return artifactLists.stream()
                .flatMap<Artifact?> { artifacts: net.starlark.java.eval.Sequence<Artifact?>? -> artifacts.stream() }
                .map<Any?> { artifact: Artifact? -> artifact.getFilename() }
                .collect(Collectors.toList())
        }
    }
}
