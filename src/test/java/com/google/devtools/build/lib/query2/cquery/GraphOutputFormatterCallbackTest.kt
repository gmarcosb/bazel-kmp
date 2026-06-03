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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.events.EventBusEventHandler

/** Tests cquery's [=graph][--output] format.  */
class GraphOutputFormatterCallbackTest : ConfiguredTargetQueryTest() {
    private var options: CqueryOptions? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private val events: MutableList<com.google.devtools.build.lib.events.Event?> =
        java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()

    @Before
    @Throws(java.lang.Exception::class)
    fun defineSimpleRule() {
        writeFile(
            "defs/defs.bzl",
            """
        def _impl(ctx):
            pass

        simple_rule = rule(
            implementation = _impl,
            attrs = {
                "deps": attr.label_list(allow_files = True),
                "tool_deps": attr.label_list(cfg = "exec"),
            },
        )
        
        """.trimIndent()
        )
        writeFile("defs/BUILD")
    }

    @Before
    fun setUpCqueryOptions() {
        this.options = com.google.devtools.common.options.Options.getDefaults<O>(CqueryOptions::class.java)
        options.setGraphNodeStringLimit(512)
        options.setGraphFactored(false)
        options.setIncludeToolDeps(false)
        options.setIncludeImplicitDeps(false)
        options.setIncludeNoDepDeps(false)
        this.reporter = com.google.devtools.build.lib.events.Reporter(
            EventBusEventHandler.createWithNewEventBus(),
            com.google.devtools.build.lib.events.EventHandler { e: com.google.devtools.build.lib.events.Event? ->
                events.add(
                    e
                )
            })
    }

    @Throws(java.lang.Exception::class)
    private fun getOutput(queryExpression: String?): com.google.common.collect.ImmutableList<String> {
        val expression: QueryExpression =
            com.google.devtools.build.lib.query2.engine.QueryParser.parse(queryExpression, getDefaultFunctions())
        val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
        expression.collectTargetPatterns(targetPatternSet)
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val env: PostAnalysisQueryEnvironment<CqueryNode?> =
            (helper as ConfiguredTargetQueryHelper).getPostAnalysisQueryEnvironment(targetPatternSet)

        val output: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val callback: GraphOutputFormatterCallback =
            GraphOutputFormatterCallback(
                reporter,
                options,
                PrintStream(output),
                getHelper().getSkyframeExecutor(),
                env.getAccessor(),
                { ct -> env.getFwdDeps(com.google.common.collect.ImmutableList.of<E?>(ct)) },
                LabelPrinter.legacy()
            )
        env.evaluateQuery(expression, callback)
        return com.google.common.collect.ImmutableList.copyOf<String?>(
            output.toString().split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicGraph() {
        writeFile(
            "test/BUILD",
            """
        load("//defs:defs.bzl", "simple_rule")

        simple_rule(
            name = "a",
            deps = [
                ":b",
                ":c",
            ],
        )

        simple_rule(
            name = "b",
            deps = [":d"],
        )

        simple_rule(name = "c")

        simple_rule(name = "d")
        
        """.trimIndent()
        )
        val output: MutableList<String> = getOutput("deps(//test:a)")
        val firstNode = output.get(2)
        val configHash: String = firstNode.substring(firstNode.indexOf("(") + 1, firstNode.length - 2)
        Truth.assertThat(getOutput("deps(//test:a)"))
            .isEqualTo(
                withConfigHash(
                    configHash,
                    "digraph mygraph {",
                    "  node [shape=box];",
                    "  \"//test:a (%s)\"",
                    "  \"//test:a (%s)\" -> \"//test:b (%s)\"",
                    "  \"//test:a (%s)\" -> \"//test:c (%s)\"",
                    "  \"//test:c (%s)\"",
                    "  \"//test:b (%s)\"",
                    "  \"//test:b (%s)\" -> \"//test:d (%s)\"",
                    "  \"//test:d (%s)\"",
                    "}"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun factorEquivalentNodes() {
        options.setGraphFactored(true)
        writeFile(
            "test/BUILD",
            """
        load("//defs:defs.bzl", "simple_rule")

        simple_rule(
            name = "a",
            deps = [
                ":b",
                ":c",
            ],
        )

        simple_rule(
            name = "b",
            deps = [":d"],
        )

        simple_rule(
            name = "c",
            deps = [":d"],
        )

        simple_rule(name = "d")
        
        """.trimIndent()
        )
        val output: MutableList<String> = getOutput("deps(//test:a)")
        val firstNode = output.get(2)
        val configHash: String = firstNode.substring(firstNode.indexOf("(") + 1, firstNode.length - 2)
        Truth.assertThat(getOutput("deps(//test:a)"))
            .isEqualTo(
                withConfigHash(
                    configHash,
                    "digraph mygraph {",
                    "  node [shape=box];",
                    "  \"//test:a (%s)\"",
                    "  \"//test:a (%s)\" -> \"//test:b (%s)\\n//test:c (%s)\"",
                    "  \"//test:b (%s)\\n//test:c (%s)\"",
                    "  \"//test:b (%s)\\n//test:c (%s)\" -> \"//test:d (%s)\"",
                    "  \"//test:d (%s)\"",
                    "}"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullAndToolDeps() {
        writeFile(
            "test/BUILD",
            """
        load("//defs:defs.bzl", "simple_rule")

        simple_rule(
            name = "a",
            tool_deps = [":tool_dep"],
            deps = [
                ":b",
                ":file.src",
            ],
        )

        simple_rule(name = "b")

        simple_rule(name = "tool_dep")
        
        """.trimIndent()
        )
        writeFile("test/file.src")
        val output: com.google.common.collect.ImmutableList<String> =
            getOutput("deps(//test:a)" + getDependencyCorrection())
        val firstNode: String = output.get(2)
        val configHash: String = firstNode.substring(firstNode.indexOf("(") + 1, firstNode.length - 2)
        val toolNode: String = output.get(6)
        val execConfigHash: String = toolNode.substring(toolNode.indexOf("(") + 1, toolNode.length - 2)
        Truth.assertThat(output)
            .isEqualTo(
                withConfigHash(
                    configHash,
                    "digraph mygraph {",
                    "  node [shape=box];",
                    "  \"//test:a (%s)\"",
                    "  \"//test:a (%s)\" -> \"//test:b (%s)\"",
                    "  \"//test:a (%s)\" -> \"//test:file.src (null)\"",
                    "  \"//test:a (%s)\" -> \"//test:tool_dep (" + execConfigHash + ")\"",
                    "  \"//test:tool_dep (" + execConfigHash + ")\"",
                    "  \"//test:file.src (null)\"",
                    "  \"//test:b (%s)\"",
                    "}"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectsResolvedAndRemoved() {
        writeFile(
            "test/BUILD",
            """
        load("//defs:defs.bzl", "simple_rule")

        config_setting(
            name = "use_a",
            define_values = {"a": "1"},
        )

        simple_rule(
            name = "a",
            deps = select({
                ":use_a": [":dep_with_a"],
                "//conditions:default": [":default_dep"],
            }),
        )

        simple_rule(name = "dep_with_a")

        simple_rule(name = "default_dep")
        
        """.trimIndent()
        )
        getHelper().useConfiguration("--define", "a=1")
        val output: MutableList<String> = getOutput("deps(//test:a)")
        val firstNode = output.get(2)
        val configHash: String = firstNode.substring(firstNode.indexOf("(") + 1, firstNode.length - 2)
        Truth.assertThat(getOutput("deps(//test:a)"))
            .isEqualTo(
                withConfigHash(
                    configHash,
                    "digraph mygraph {",
                    "  node [shape=box];",
                    "  \"//test:a (%s)\"",
                    "  \"//test:a (%s)\" -> \"//test:dep_with_a (%s)\"",
                    "  \"//test:a (%s)\" -> \"//test:use_a (%s)\"",
                    "  \"//test:use_a (%s)\"",
                    "  \"//test:dep_with_a (%s)\"",
                    "}"
                )
            )
    }

    companion object {
        /** Convenience method for easily injecting a config hash into an expected output sequence.  */
        private fun withConfigHash(configHash: String, vararg pattern: String?): MutableList<String?> {
            return java.util.Arrays.stream<String?>(pattern)
                .map<String?> { entry: String? -> entry.replace("%s", configHash) }
                .collect(Collectors.toList())
        }
    }
}
