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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment

/** Tests [QueryCommandUtils].  */
@RunWith(JUnit4::class)
class QueryCommandUtilsTest {
    private var functions: com.google.common.collect.ImmutableMap<String?, QueryFunction?>? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setFunctions() {
        val builder: com.google.common.collect.ImmutableMap.Builder<String?, QueryFunction?> =
            com.google.common.collect.ImmutableMap.builder<String?, QueryFunction?>()

        for (queryFunction in ActionGraphQueryEnvironment.FUNCTIONS) {
            builder.put(queryFunction.name, queryFunction)
        }

        for (queryFunction in ActionGraphQueryEnvironment.AQUERY_FUNCTIONS) {
            builder.put(queryFunction.name, queryFunction)
        }

        functions = builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAqueryCommandGetTopLevelTargets_skyframeState_targetLabelSpecified() {
        val query = "//some_target"
        val expr: QueryExpression? = com.google.devtools.build.lib.query2.engine.QueryParser.parse(query, functions)
        val exception: com.google.devtools.build.lib.query2.engine.QueryException =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable {
                    QueryCommandUtils.getTopLevelTargets( /* universeScope= */
                        com.google.common.collect.ImmutableList.of<E?>(),
                        expr,  /* queryCurrentSkyframeState= */
                        true
                    )
                })
        Truth.assertThat(exception).hasMessageThat().contains("Error while parsing '" + query)
        Truth.assertThat(exception)
            .hasMessageThat()
            .contains("with --skyframe_state is currently not supported")
        assertThat(exception.getFailureDetail().getActionQuery().getCode())
            .isEqualTo(ActionQuery.Code.TOP_LEVEL_TARGETS_WITH_SKYFRAME_STATE_NOT_SUPPORTED)
    }
}
