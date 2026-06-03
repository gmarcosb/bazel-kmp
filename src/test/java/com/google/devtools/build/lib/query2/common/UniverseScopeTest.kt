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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.vfs.PathFragment

/** Tests for [UniverseScope].  */
@RunWith(JUnit4::class)
class UniverseScopeTest {
    @org.junit.Test
    @Throws(com.google.devtools.build.lib.query2.engine.QuerySyntaxException::class)
    fun testInferFromQueryExpression() {
        val underTest: UniverseScope = UniverseScope.INFER_FROM_QUERY_EXPRESSION

        val cases: com.google.common.collect.ImmutableMap<QueryExpression?, com.google.common.collect.ImmutableList<String?>?> =
            com.google.common.collect.ImmutableMap.builder<QueryExpression?, com.google.common.collect.ImmutableList<String?>?>()
                .put(parse("//a/..."), com.google.common.collect.ImmutableList.of<String?>("//a/..."))
                .put(parse("//a:a"), com.google.common.collect.ImmutableList.of<String?>("//a:a"))
                .put(
                    parse("set(//a:a //b/...)"),
                    com.google.common.collect.ImmutableList.of<String?>("//a:a", "//b/...")
                )
                .put(parse("//a:a + //b/..."), com.google.common.collect.ImmutableList.of<String?>("//a:a", "//b/..."))
                .put(
                    parse("let x = a/... in \$x + a/... + b/..."),
                    com.google.common.collect.ImmutableList.of<String?>("a/...", "b/...")
                )
                .put(
                    parse("rdeps(a:a, b:b) - rdeps(c:c, d:d)"),
                    com.google.common.collect.ImmutableList.of<String?>("a:a", "b:b", "c:c", "d:d")
                )
                .build()
        cases.forEach { (expr: QueryExpression?, expectedInferredTargetPatterns: com.google.common.collect.ImmutableList<String?>?) ->
            assertThat(underTest.getUniverseKey(expr, PathFragment.EMPTY_FRAGMENT).getPatterns())
                .isEqualTo(expectedInferredTargetPatterns)
        }
    }

    companion object {
        @Throws(com.google.devtools.build.lib.query2.engine.QuerySyntaxException::class)
        private fun parse(exprString: String?): QueryExpression {
            return com.google.devtools.build.lib.query2.engine.QueryParser.parse(
                exprString,
                com.google.common.collect.ImmutableMap.of<K?, V?>("rdeps", RdepsFunction())
            )
        }
    }
}
