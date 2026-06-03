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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.events.EventBusEventHandler

/** Test for [QueryCommand].  */
@RunWith(JUnit4::class)
class QueryCommandTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val mockQueryEnvironment: AbstractBlazeQueryEnvironment<Target?>? = null

    private var underTest: QueryCommand? = null

    @Before
    fun setUp() {
        this.underTest = QueryCommand()
        Mockito.`when`<T?>(mockQueryEnvironment.getFunctions())
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>())
    }

    @org.junit.Test
    fun testQuerySyntaxErrorResultsInCommandLineExitStatusWithDetails() {
        val storedEventHandler: StoredEventHandler = StoredEventHandler()

        val result: Either<BlazeCommandResult?, QueryEvalResult?> =
            underTest.doQuery(
                "terrible syntax",
                mockCommandEnvironment(
                    com.google.devtools.build.lib.events.Reporter(
                        EventBusEventHandler.createWithNewEventBus(),
                        storedEventHandler
                    )
                ),
                com.google.devtools.common.options.Options.getDefaults<O?>(QueryOptions::class.java),  /* streamResults= */
                false,
                < T > mock < T ? > (com.google.devtools.build.lib.query2.query.output.OutputFormatter::class.java),
        mockQueryEnvironment,
        <T > mock<T?>(QueryRuntimeHelper::class.java))

        val detailedExitCode: java.util.Optional<DetailedExitCode?> =
            result.map(
                { r -> java.util.Optional.of<T?>(r.getDetailedExitCode()) },
                { r -> java.util.Optional.empty<T?>() })
        Truth.assertWithMessage("Expected to contain BlazeCommandResult, got: %s", result)
            .that(detailedExitCode.isPresent())
            .isTrue()

        assertThat(detailedExitCode.get().getExitCode()).isEqualTo(ExitCode.COMMAND_LINE_ERROR)
        assertThat(detailedExitCode.get().getFailureDetail().getQuery().getCode())
            .isEqualTo(Code.SYNTAX_ERROR)

        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getEvents().get(0).getMessage())
            .startsWith("Error while parsing 'terrible syntax'")
    }

    companion object {
        private fun mockCommandEnvironment(reporter: com.google.devtools.build.lib.events.Reporter?): CommandEnvironment {
            val result: CommandEnvironment = Mockito.mock<CommandEnvironment>(CommandEnvironment::class.java)
            Mockito.`when`<T?>(result.getReporter()).thenReturn(reporter)
            return result
        }
    }
}
