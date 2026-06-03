// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.cmdline.TargetPattern
import org.junit.Test

/** Unit test for [TotalWeightQueryExpressionVisitor].  */
@RunWith(JUnit4::class)
class TotalWeightQueryExpressionVisitorTest {
    private val underTest = TotalWeightQueryExpressionVisitor()

    @Test
    @Throws(Exception::class)
    fun basicBodyCount() {
        Truth.assertThat(calculateQueryWeight("\$x - attr('tags', 'foo', \$x)")).isEqualTo(11)
    }

    @Test
    @Throws(Exception::class)
    fun nestedQueryFunctions() {
        Truth.assertThat(calculateQueryWeight("kind('pat', allrdeps(//a + //b + //c + //d + //e + //f))"))
            .isEqualTo(21)
    }

    @Test
    @Throws(Exception::class)
    fun multipleLetStatements_sameVariableRebound() {
        Truth.assertThat(
            calculateQueryWeight("let x = (let x = //... in \$x - deps(\$x)) in \$x - allrdeps(\$x)")
        )
            .isEqualTo(15)
    }

    @Test
    @Throws(Exception::class)
    fun multipleLetStatements_sameVariableReboundAlsoInVariableExpr() {
        Truth.assertThat(calculateQueryWeight("let x = \$x in \$x - allrdeps(\$x)")).isEqualTo(7)
    }

    @Test
    @Throws(Exception::class)
    fun multipleLetStatements_differentVariable() {
        Truth.assertThat(
            calculateQueryWeight("let x = (let y = //... in \$x - deps(\$y)) in \$x - allrdeps(\$y)")
        )
            .isEqualTo(15)
    }

    @Test
    @Throws(Exception::class)
    fun variableInsideSetExpression() {
        Truth.assertThat(calculateQueryWeight("\$x + set(\$x)")).isEqualTo(4)
    }

    @Throws(Exception::class)
    private fun calculateQueryWeight(expr: String?): Long {
        makeEnv().use { env ->
            return QueryExpression.parse(expr, env)!!.accept(underTest)!!
        }
    }

    companion object {
        private fun makeEnv(): SkyQueryEnvironment {
            // Creates a bare-minimum SkyQueryEnvironment usable for parsing a query expression to weigh it.
            return SkyQueryEnvironment( /* keepGoing= */
                false,  /* loadingPhaseThreads= */
                1,  /* trackIncrementalState= */
                true,  /* eventHandler= */
                NullEventHandler.INSTANCE,  /* settings= */
                ImmutableSet.of<E?>(),  /* extraFunctions= */
                ImmutableList.of<E?>(),
                TargetPattern.mainRepoParser(PathFragment.EMPTY_FRAGMENT),
                PathFragment.EMPTY_FRAGMENT,  /* graphFactory= */
                object : WalkableGraphFactory() {
                    @Throws(InterruptedException::class)
                    public override fun prepareAndGet(
                        roots: MutableSet<SkyKey?>?, evaluationContext: EvaluationContext?
                    ): EvaluationResult<SkyValue?>? {
                        return null
                    }
                },
                UniverseScope.fromUniverseScopeList(ImmutableList.of<E?>("//...")),  /* pkgPath= */
                PathPackageLocator(null, ImmutableList.of<E?>(), ImmutableList.of<E?>()),
                LabelPrinter.legacy()
            )
        }
    }
}
