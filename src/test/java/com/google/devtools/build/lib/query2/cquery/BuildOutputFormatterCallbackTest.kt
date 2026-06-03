// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests cquery's BUILD output format.  */
class BuildOutputFormatterCallbackTest : ConfiguredTargetQueryTest() {
    private var options: CqueryOptions? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private val events: MutableList<com.google.devtools.build.lib.events.Event?> =
        java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()

    @Before
    @Throws(java.lang.Exception::class)
    fun setUpCqueryOptions() {
        this.options = com.google.devtools.common.options.Options.getDefaults<O>(CqueryOptions::class.java)
        options.setIncludeToolDeps(false)
        options.setIncludeImplicitDeps(false)
        options.setIncludeNoDepDeps(false)
        // TODO(bazel-team): reduce the confusion about these two seemingly similar settings.
        // options.aspectDeps impacts how proto and similar output formatters output aspect results.
        // Setting.INCLUDE_ASPECTS impacts whether or not aspect dependencies are included when
        // following target deps. See CommonQueryOptions for further flag details.
        options.setAspectDeps(com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver.Mode.OFF)
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS)
        this.reporter = com.google.devtools.build.lib.events.Reporter(
            EventBusEventHandler.createWithNewEventBus(),
            com.google.devtools.build.lib.events.EventHandler { e: com.google.devtools.build.lib.events.Event? ->
                events.add(
                    e
                )
            })
        helper.useRuleClassProvider(
            setRuleClassProviders(MockRule { simpleRule() }).build()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getOutput(queryExpression: String?): MutableList<String?> {
        val expression: QueryExpression =
            com.google.devtools.build.lib.query2.engine.QueryParser.parse(queryExpression, getDefaultFunctions())
        val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
        expression.collectTargetPatterns(targetPatternSet)
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val env: PostAnalysisQueryEnvironment<CqueryNode?> =
            (helper as ConfiguredTargetQueryHelper).getPostAnalysisQueryEnvironment(targetPatternSet)

        val output: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val callback: BuildOutputFormatterCallback =
            BuildOutputFormatterCallback(
                reporter,
                options,
                PrintStream(output),
                getHelper().getSkyframeExecutor(),
                env.getAccessor(),
                LabelPrinter.legacy()
            )
        env.evaluateQuery(expression, callback)
        return java.util.Arrays.asList<String?>(
            *output.toString(java.nio.charset.StandardCharsets.UTF_8).split("\n".toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectInAttribute() {
        writeFile(
            "test/BUILD",
            """
        my_rule(
          name = 'my_rule',
          deps = select({
            ':garfield': ['lasagna.java', 'naps.java'],
            '//conditions:default': ['mondays.java']
          })
        )
        config_setting(
          name = 'garfield',
          values = {'foo': 'cat'}
        )
        
        """.trimIndent()
        )

        getHelper().useConfiguration("--foo=cat")
        Truth.assertThat(getOutput("//test:my_rule"))
            .containsExactly(
                "# /workspace/test/BUILD:1:8",
                "my_rule(",
                "  name = \"my_rule\",",
                "  deps = [\"//test:lasagna.java\", \"//test:naps.java\"],",
                ")",
                "# Rule my_rule instantiated at (most recent call last):",
                "#   /workspace/test/BUILD:1:8 in <toplevel>"
            )
            .inOrder()

        getHelper().useConfiguration("--foo=hound")
        Truth.assertThat(getOutput("//test:my_rule"))
            .containsExactly(
                "# /workspace/test/BUILD:1:8",
                "my_rule(",
                "  name = \"my_rule\",",
                "  deps = [\"//test:mondays.java\"],",
                ")",
                "# Rule my_rule instantiated at (most recent call last):",
                "#   /workspace/test/BUILD:1:8 in <toplevel>"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun alias() {
        writeFile(
            "test/BUILD",
            """
        my_rule(
          name = 'my_rule',
          deps = select({
            ':garfield': ['lasagna.java', 'naps.java'],
            '//conditions:default': ['mondays.java']
          })
        )
        config_setting(
          name = 'garfield',
          values = {'foo': 'cat'}
        )
        alias(
          name = 'my_alias',
          actual = ':my_rule'
        )
        # Rule my_alias instantiated at (most recent call last):
        #   /workspace/test/BUILD:12:6 in <toplevel>
        
        """.trimIndent()
        )

        Truth.assertThat(getOutput("//test:my_alias"))
            .containsExactly(
                "# /workspace/test/BUILD:12:6",
                "alias(",
                "  name = \"my_alias\",",
                "  actual = \"//test:my_rule\",",
                ")",
                "# Rule my_alias instantiated at (most recent call last):",
                "#   /workspace/test/BUILD:12:6 in <toplevel>"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithSelect() {
        writeFile(
            "test/BUILD",
            """
        my_rule(
          name = 'my_first_rule',
          deps = ['penne.java'],
        )
        my_rule(
          name = 'my_second_rule',
          deps = ['linguini.java'],
        )
        config_setting(
          name = 'garfield',
          values = {'foo': 'cat'}
        )
        alias(
          name = 'my_alias',
          actual = select({
            ':garfield': ':my_first_rule',
            '//conditions:default': ':my_second_rule'
          })
        )
        
        """.trimIndent()
        )

        getHelper().useConfiguration("--foo=cat")
        Truth.assertThat(getOutput("//test:my_alias"))
            .containsExactly(
                "# /workspace/test/BUILD:13:6",
                "alias(",
                "  name = \"my_alias\",",
                "  actual = \"//test:my_first_rule\",",
                ")",
                "# Rule my_alias instantiated at (most recent call last):",
                "#   /workspace/test/BUILD:13:6 in <toplevel>"
            )
            .inOrder()

        getHelper().useConfiguration("--foo=hound")
        Truth.assertThat(getOutput("//test:my_alias"))
            .containsExactly(
                "# /workspace/test/BUILD:13:6",
                "alias(",
                "  name = \"my_alias\",",
                "  actual = \"//test:my_second_rule\",",
                ")",
                "# Rule my_alias instantiated at (most recent call last):",
                "#   /workspace/test/BUILD:13:6 in <toplevel>"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sourceFile() {
        writeFile(
            "test/BUILD",
            """
        my_rule(
          name = 'my_rule',
          deps = select({
            ':garfield': ['lasagna.java', 'naps.java'],
            '//conditions:default': ['mondays.java']
          })
        )
        config_setting(
          name = 'garfield',
          values = {'foo': 'cat'}
        )
        
        """.trimIndent()
        )

        Truth.assertThat(getOutput("//test:lasagna.java")).containsExactly("")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFile() {
        writeFile(
            "test/BUILD",
            """
        my_rule(
          name = 'my_rule',
          deps = select({
            ':garfield': ['lasagna.java', 'naps.java'],
            '//conditions:default': ['mondays.java']
          }),
          out = 'output.txt'
        )
        config_setting(
          name = 'garfield',
          values = {'foo': 'cat'}
        )
        
        """.trimIndent()
        )

        getHelper().useConfiguration("--foo=cat")
        Truth.assertThat(getOutput("//test:output.txt"))
            .containsExactly(
                "# /workspace/test/BUILD:1:8",
                "my_rule(",
                "  name = \"my_rule\",",
                "  deps = [\"//test:lasagna.java\", \"//test:naps.java\"],",
                "  out = \"//test:output.txt\",",
                ")",
                "# Rule my_rule instantiated at (most recent call last):",
                "#   /workspace/test/BUILD:1:8 in <toplevel>"
            )
            .inOrder()

        getHelper().useConfiguration("--foo=hound")
        Truth.assertThat(getOutput("//test:output.txt"))
            .containsExactly(
                "# /workspace/test/BUILD:1:8",
                "my_rule(",
                "  name = \"my_rule\",",
                "  deps = [\"//test:mondays.java\"],",
                "  out = \"//test:output.txt\",",
                ")",
                "# Rule my_rule instantiated at (most recent call last):",
                "#   /workspace/test/BUILD:1:8 in <toplevel>"
            )
            .inOrder()
    }

    companion object {
        private fun simpleRule(): com.google.devtools.build.lib.analysis.util.MockRule.State {
            return MockRule.define(
                "my_rule",
                { builder, env ->
                    builder
                        .add(attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                        .add(attr("out", OUTPUT))
                })
        }
    }
}
