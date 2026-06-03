// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.OutputGroupInfo

/** Tests cquery's [=files][--output] format.  */
class FilesOutputFormatterCallbackTest : ConfiguredTargetQueryTest() {
    private val options: CqueryOptions? =
        com.google.devtools.common.options.Options.getDefaults<O?>(CqueryOptions::class.java)
    private val reporter: com.google.devtools.build.lib.events.Reporter =
        com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())

    @Before
    @Throws(java.lang.Exception::class)
    fun defineSimpleRule() {
        writeFile(
            "defs/rules.bzl",
            """
        AspectInfo = provider()
        def _r_impl(ctx):
            default_file = ctx.actions.declare_file(ctx.attr.name + '_default_file')
            output_group_only = ctx.actions.declare_file(ctx.attr.name + '_output_group_only')
            runfile = ctx.actions.declare_file(ctx.attr.name + '_runfile')
            executable_only = ctx.actions.declare_file(ctx.attr.name + '_executable')
            files = [default_file, output_group_only, runfile, executable_only]
            ctx.actions.run_shell(
                outputs = files,
                command = '\
                '.join(['touch %s' % file.path for file in files]),
            )
            return [
                DefaultInfo(
                    executable = executable_only,
                    files = depset(
                        direct = [
                            default_file,
                            ctx.file._implicit_source_dep,
                            ctx.file.explicit_source_dep,
                        ],
                        transitive = [info[DefaultInfo].files for info in ctx.attr.deps]
                    ),
                    runfiles = ctx.runfiles([runfile]),
                ),
                OutputGroupInfo(
                    foobar = [output_group_only, ctx.file.explicit_source_dep],
                ),
            ]
        r = rule(
            implementation = _r_impl,
            executable = True,
            attrs = {
                'deps': attr.label_list(),
                '_implicit_source_dep': attr.label(default = 'rules.bzl', allow_single_file = True),
                'explicit_source_dep': attr.label(allow_single_file = True),
            },
        )
        def _a_impl(target, ctx):
            custom_output_group = ctx.actions.declare_file(target.label.name + '_custom_aspect_a_file')
            shared_output_group = ctx.actions.declare_file(target.label.name + '_shared_aspect_a_file')
            ctx.actions.run_shell(
                outputs = [custom_output_group, shared_output_group],
                command = "touch %s && touch %s" % (custom_output_group.path, shared_output_group.path),
            )
            return [
                OutputGroupInfo(
                    aspect_files = depset([custom_output_group]),
                    foobar = depset([shared_output_group]),
                ),
                # Collides with aspect b.
                AspectInfo(),
            ]
        a = aspect(implementation = _a_impl)
        def _b_impl(target, ctx):
            custom_output_group = ctx.actions.declare_file(target.label.name + '_custom_aspect_b_file')
            shared_output_group = ctx.actions.declare_file(target.label.name + '_shared_aspect_b_file')
            ctx.actions.run_shell(
                outputs = [custom_output_group, shared_output_group],
                command = "touch %s && touch %s" % (custom_output_group.path, shared_output_group.path),
            )
            return [
                OutputGroupInfo(
                    aspect_files = depset([custom_output_group]),
                    foobar = depset([shared_output_group]),
                ),
                # Collides with aspect a.
                AspectInfo(),
            ]
        b = aspect(implementation = _b_impl)
        
        """.trimIndent()
        )
        writeFile("defs/BUILD", "exports_files(['rules.bzl'])")
        writeFile(
            "pkg/BUILD",
            """
        load("//defs:rules.bzl", "r")

        r(
            name = "main",
            explicit_source_dep = "BUILD",
        )

        r(
            name = "other",
            explicit_source_dep = "BUILD",
            deps = [":main"],
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getOutput(
        queryExpression: String?, outputGroups: MutableList<String?>?, vararg aspects: String?
    ): com.google.common.collect.ImmutableList<String?> {
        val expression: QueryExpression =
            com.google.devtools.build.lib.query2.engine.QueryParser.parse(queryExpression, getDefaultFunctions())
        val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
        expression.collectTargetPatterns(targetPatternSet)
        val env: PostAnalysisQueryEnvironment<CqueryNode?> =
            (helper as ConfiguredTargetQueryHelper)
                .getPostAnalysisQueryEnvironment(targetPatternSet, java.util.Arrays.asList<String?>(*aspects))

        val output: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val callback: FilesOutputFormatterCallback =
            FilesOutputFormatterCallback(
                reporter,
                options,
                PrintStream(output),
                getHelper().getSkyframeExecutor(),
                env.getAccessor(),  // Based on BuildRequest#getTopLevelArtifactContext.
                TopLevelArtifactContext(
                    false,
                    false,
                    OutputGroupInfo.determineOutputGroups(outputGroups, ValidationMode.OFF, false)
                )
            )
        env.evaluateQuery(expression, callback)
        return java.util.regex.Pattern.compile("\n")
            .splitAsStream(output.toString(java.nio.charset.StandardCharsets.UTF_8))
            .filter { line: String? -> !line.isEmpty() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicQuery_defaultOutputGroup() {
        val output: MutableList<String?> = getOutput("//pkg:all", com.google.common.collect.ImmutableList.of<String?>())
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).containsExactly("pkg/BUILD", "defs/rules.bzl")
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true), "pkg/main_default_file", "pkg/other_default_file"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicQuery_defaultAndCustomOutputGroup() {
        val output: MutableList<String?> =
            getOutput("//pkg:main", com.google.common.collect.ImmutableList.of<String?>("+foobar"))
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).containsExactly("pkg/BUILD", "defs/rules.bzl")
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true), "pkg/main_default_file", "pkg/main_output_group_only"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicQuery_customOutputGroupOnly() {
        val output: MutableList<String?> =
            getOutput("//pkg:other", com.google.common.collect.ImmutableList.of<String?>("foobar"))
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).containsExactly("pkg/BUILD")
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true), "pkg/other_output_group_only"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withAspect_customOutputGroupOnly() {
        helper.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        val output: MutableList<String?> =
            getOutput(
                "//pkg:other",
                com.google.common.collect.ImmutableList.of<String?>("aspect_files"),
                "//defs:rules.bzl%a"
            )
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).isEmpty()
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true), "pkg/other_custom_aspect_a_file"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withAspect_sharedOutputGroupOnly() {
        helper.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        val output: MutableList<String?> =
            getOutput(
                "//pkg:other",
                com.google.common.collect.ImmutableList.of<String?>("foobar"),
                "//defs:rules.bzl%a"
            )
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).containsExactly("pkg/BUILD")
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true),
            "pkg/other_output_group_only",
            "pkg/other_shared_aspect_a_file"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withAspects_customOutputGroupOnly() {
        helper.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        val output: MutableList<String?> =
            getOutput(
                "//pkg:other",
                com.google.common.collect.ImmutableList.of<String?>("aspect_files"),
                "//defs:rules.bzl%a",
                "//defs:rules.bzl%b"
            )
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).isEmpty()
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true),
            "pkg/other_custom_aspect_b_file",
            "pkg/other_custom_aspect_a_file"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withAspects_sharedOutputGroupOnly() {
        helper.setQuerySettings(QueryEnvironment.Setting.INCLUDE_ASPECTS)
        val output: MutableList<String?> =
            getOutput(
                "//pkg:other",
                com.google.common.collect.ImmutableList.of<String?>("foobar"),
                "//defs:rules.bzl%a",
                "//defs:rules.bzl%b"
            )
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).containsExactly("pkg/BUILD")
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true),
            "pkg/other_output_group_only",
            "pkg/other_shared_aspect_b_file",
            "pkg/other_shared_aspect_a_file"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withAspects_noIncludeAspects() {
        // Explicitly omit INCLUDE_ASPECTS.
        helper.setQuerySettings()
        val output: MutableList<String?> =
            getOutput(
                "//pkg:other",
                com.google.common.collect.ImmutableList.of<String?>("foobar"),
                "//defs:rules.bzl%a",
                "//defs:rules.bzl%b"
            )
        val sourceAndGeneratedFiles: MutableMap<Boolean?, MutableList<String?>?> =
            output.stream()
                .collect(Collectors.partitioningBy(java.util.function.Predicate { path: String? -> path.matches("^[^/]*-out/.*".toRegex()) }))
        Truth.assertThat(sourceAndGeneratedFiles.get(false)).containsExactly("pkg/BUILD")
        Companion.assertContainsExactlyWithBinDirPrefix(
            sourceAndGeneratedFiles.get(true), "pkg/other_output_group_only"
        )
    }

    companion object {
        private fun assertContainsExactlyWithBinDirPrefix(
            output: MutableList<String>?, vararg binDirRelativePaths: String?
        ) {
            if (binDirRelativePaths.size == 0) {
                Truth.assertThat(output).isEmpty()
                return
            }

            // Extract the configuration-dependent bin dir from the first output.
            Truth.assertThat(output).isNotEmpty()
            val firstPath = output!!.get(0)
            val binDir: String = firstPath.substring(0, firstPath.indexOf("bin/") + "bin/".length)

            Truth.assertThat(output)
                .containsExactly(
                    *java.util.Arrays.stream<String?>(binDirRelativePaths)
                        .map<String?> { binDirRelativePath: String? -> binDir + binDirRelativePath }
                        .toArray())
        }
    }
}
