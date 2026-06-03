// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/**
 * Tests [BlazeCommand].
 */
@RunWith(JUnit4::class)
class AbstractCommandTest {
    @OptionsClass
    abstract class FooOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val foo: Int
    }

    @OptionsClass
    abstract class BarOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "bar",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "42"
        )
        abstract val foo: Int

        @get:com.google.devtools.common.options.Option(
            name = "baz",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "oops"
        )
        abstract val baz: String?
    }

    private open class ConcreteCommand : BlazeCommand {
        public override fun exec(env: CommandEnvironment?, options: OptionsParsingResult?): BlazeCommandResult? {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun editOptions(optionsParser: OptionsParser?) {}
    }

    @Command(
        name = "test_name",
        help = "Usage: some funny usage for %{command} ...;\n\n%{options}; end",
        options = [com.google.devtools.build.lib.runtime.AbstractCommandTest.FooOptions::class, BarOptions::class],
        shortDescription = "a short description",
        allowResidue = false
    )
    private class TestCommand : ConcreteCommand()

    @org.junit.Test
    fun testGetNameYieldsAnnotatedName() {
        assertThat(TestCommand().javaClass.getAnnotation<A?>(Command::class.java).name())
            .isEqualTo("test_name")
    }

    @org.junit.Test
    fun testGetOptionsYieldsAnnotatedOptions() {
        val ruleClassProvider: ConfiguredRuleClassProvider? = Builder()
            .setToolsRepository(TestConstants.TOOLS_REPOSITORY)
            .build()

        assertThat(
            BlazeCommandUtils.getOptions(
                TestCommand::class.java,
                com.google.common.collect.ImmutableList.of<E?>(),
                ruleClassProvider
            )
        )
            .containsExactlyElementsIn(
                optionClassesWithDefault(
                    com.google.devtools.build.lib.runtime.AbstractCommandTest.FooOptions::class.java,
                    BarOptions::class.java
                )
            )
    }

    /***************************************************************************
     * The tests below test how a command interacts with the dispatcher except *
     * for execution, which is tested in [BlazeCommandDispatcherTest].   *
     */
    @Command(
        name = "a",
        options = [com.google.devtools.build.lib.runtime.AbstractCommandTest.FooOptions::class],
        shortDescription = "",
        help = ""
    )
    private class CommandA : ConcreteCommand()

    @Command(
        name = "b",
        options = [BarOptions::class],
        inheritsOptionsFrom = [CommandA::class],
        shortDescription = "",
        help = ""
    )
    private class CommandB : ConcreteCommand()

    @org.junit.Test
    fun testOptionsAreInherited() {
        val ruleClassProvider: ConfiguredRuleClassProvider? = Builder()
            .setToolsRepository(TestConstants.TOOLS_REPOSITORY)
            .build()
        assertThat(
            BlazeCommandUtils.getOptions(
                CommandA::class.java,
                com.google.common.collect.ImmutableList.of<E?>(),
                ruleClassProvider
            )
        )
            .containsExactlyElementsIn(optionClassesWithDefault(com.google.devtools.build.lib.runtime.AbstractCommandTest.FooOptions::class.java))
        assertThat(
            BlazeCommandUtils.getOptions(
                CommandB::class.java,
                com.google.common.collect.ImmutableList.of<E?>(),
                ruleClassProvider
            )
        )
            .containsExactlyElementsIn(
                optionClassesWithDefault(
                    com.google.devtools.build.lib.runtime.AbstractCommandTest.FooOptions::class.java,
                    BarOptions::class.java
                )
            )
    }

    private fun optionClassesWithDefault(vararg optionClasses: java.lang.Class<*>?): MutableCollection<java.lang.Class<*>?> {
        val result: MutableList<java.lang.Class<*>?> = java.util.ArrayList<java.lang.Class<*>?>()
        Collections.addAll<java.lang.Class<*>?>(result, *optionClasses)
        result.add(UiOptions::class.java)
        result.add(CommonCommandOptions::class.java)
        result.add(KeepStateAfterBuildOption::class.java)
        result.add(ClientOptions::class.java)
        result.add(BuildLanguageOptions::class.java)
        return result
    }
}
