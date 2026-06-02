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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.cmdline.TargetPattern

/**
 * A [QueryExpressionMapper] that transforms each occurrence of an expression of the form
 * &#39;rdeps(filterfunc(&lt;pat&gt;, &lt;universeScope&gt;), &lt;T&gt;, 1)&#39; to &#39;filterfunc(&lt;pat&gt;, rdeps(&lt;universeScope&gt;, &lt;T&gt;, 1))&#39;.
 * 
 * 
 * By factoring the filterfunc out of the rdeps universe, we prepare the query for transformation
 * by the [RdepsToAllRdepsQueryExpressionMapper].
 * 
 * 
 * *Note:* we require a max depth of 1 because the transformation is only sound with a
 * depth of 1. Consider a query of depth 2.
 * 
 * 
 *  1. &#39;rdeps(kind(^foo$, //...), //base:b, 2)&#39; yields these two disjoint sets:
 * 
 *  1. targets of kind 'foo' that depend directly on //base:b
 *  1. targets of kind 'foo' that do not depend directly on //base:b, and depend on a target
 * of kind 'foo' that itself depends directly on //base:b
 * 
 *  1. &#39;kind(^foo$, rdeps(//..., //base:b, 2))&#39; yields these two disjoint sets:
 * 
 *  1. targets of kind 'foo' that depend directly on //base:b
 *  1. targets of kind 'foo' that do not depend directly on //base:b, and depend on a target
 * of *any* kind that itself depends directly on //base:b
 * 
 * 
 * 
 * With a depth of 1, the rdeps operator in both queries would return only the first set: proof the
 * transformation is sound. With a depth of 2, we see the rdeps operator with a filtered universe
 * returns a strictly smaller set.
 */
class FilteredDirectRdepsInUniverseExpressionMapper @com.google.common.annotations.VisibleForTesting constructor(
    targetPatternParser: TargetPattern.Parser,
    absoluteUniverseScopePattern: TargetPattern?
) : QueryExpressionMapper<java.lang.Void?>() {
    private val targetPatternParser: TargetPattern.Parser
    private val absoluteUniverseScopePattern: TargetPattern?

    init {
        this.targetPatternParser = targetPatternParser
        this.absoluteUniverseScopePattern = absoluteUniverseScopePattern
    }

    override fun visit(functionExpression: FunctionExpression, context: java.lang.Void?): QueryExpression? {
        if (functionExpression.getFunction().getName() == RdepsFunction().getName()) {
            val args: MutableList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument> =
                functionExpression.getArgs()
            // This transformation only applies to the 3-arg form of rdeps, with depth == 1.
            if (args.size == 3 && args.get(2).getInteger() == 1) {
                val rdepsUniverseArgument: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument =
                    args.get(0)
                val rdepsUniverseExpression: QueryExpression = rdepsUniverseArgument.getExpression()
                val result: ExtractFilteringFunctionsResult =
                    rdepsUniverseExpression.accept<ExtractFilteringFunctionsResult>(
                        ExtractFilteringFunctionsFromUniverseVisitor()
                    )
                // If we get back a non-empty result, then there are filtering functions that can be safely
                // factored out.
                if (result.universeArgument != null) {
                    var curFunction: QueryExpression =
                        makeUnfilteredRdepsWithDepthOne( /*rdepsUniverseArgument=*/
                            result.universeArgument,  /*sourceArgument=*/
                            args.get(1)
                        )
                    for (filterExpr in result.filteringExpressions) {
                        curFunction = wrapExprWithFilter(curFunction, filterExpr)
                    }
                    return curFunction
                }
            }
        }
        return super.visit(functionExpression, context)
    }

    private fun makeUnfilteredRdepsWithDepthOne(
        rdepsUniverseArgument: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?,
        sourceArgument: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?
    ): FunctionExpression {
        return FunctionExpression(
            RdepsFunction(),
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?>(
                rdepsUniverseArgument,
                sourceArgument,
                com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument.Companion.of(1)
            )
        )
    }

    private class ExtractFilteringFunctionsResult(
        filteringExpressions: com.google.common.collect.ImmutableList<FunctionExpression>,
        universeArgument: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?
    ) {
        private val filteringExpressions: com.google.common.collect.ImmutableList<FunctionExpression>
        private val universeArgument: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?

        init {
            this.filteringExpressions = filteringExpressions
            this.universeArgument = universeArgument
        }

        companion object {
            private val EMPTY =
                ExtractFilteringFunctionsResult(com.google.common.collect.ImmutableList.of<FunctionExpression?>(), null)
        }
    }

    /**
     * Internal visitor applied to the universe argument of all QueryExpressions of the form rdeps(u, x, 1).
     */
    private inner class ExtractFilteringFunctionsFromUniverseVisitor

        : QueryExpressionVisitor<ExtractFilteringFunctionsResult?, java.lang.Void?> {
        override fun visit(targetLiteral: TargetLiteral?, context: java.lang.Void?): ExtractFilteringFunctionsResult {
            return ExtractFilteringFunctionsResult.Companion.EMPTY
        }

        override fun visit(
            binaryOperatorExpression: com.google.devtools.build.lib.query2.engine.BinaryOperatorExpression?,
            context: java.lang.Void?
        ): ExtractFilteringFunctionsResult {
            return ExtractFilteringFunctionsResult.Companion.EMPTY
        }

        override fun visit(
            functionExpression: FunctionExpression, context: java.lang.Void?
        ): ExtractFilteringFunctionsResult {
            val filteringFunction: FilteringQueryFunction? =
                functionExpression.getFunction().asFilteringFunction()
            if (filteringFunction == null) {
                return ExtractFilteringFunctionsResult.Companion.EMPTY
            }
            val filteredArgument: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument =
                functionExpression.getArgs().get(filteringFunction.getExpressionToFilterIndex())
            com.google.common.base.Preconditions.checkArgument(filteredArgument.getType() == com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType.EXPRESSION)
            val filteredExpression: QueryExpression = filteredArgument.getExpression()

            if (filteredExpression is TargetLiteral) {
                val eligibility: Eligibility? =
                    RdepsToAllRdepsQueryExpressionMapper.Companion.determineEligibility(
                        targetPatternParser,
                        absoluteUniverseScopePattern,
                        (filteredExpression as TargetLiteral).getPattern()
                    )
                if (eligibility == Eligibility.ELIGIBLE_AS_IS
                    || eligibility == Eligibility.ELIGIBLE_WITH_FILTERING
                ) {
                    return ExtractFilteringFunctionsResult(
                        com.google.common.collect.ImmutableList.of<FunctionExpression?>(functionExpression),
                        filteredArgument
                    )
                }
            } else if (filteredExpression is FunctionExpression) {
                val recursiveResult: ExtractFilteringFunctionsResult =
                    filteredExpression.accept<ExtractFilteringFunctionsResult>(this)
                if (recursiveResult.universeArgument != null) {
                    return ExtractFilteringFunctionsResult( /*filteringExpressions=*/
                        com.google.common.collect.ImmutableList.builder<FunctionExpression?>()
                            .addAll(recursiveResult.filteringExpressions)
                            .add(functionExpression)
                            .build(),
                        recursiveResult.universeArgument
                    )
                }
            }
            return ExtractFilteringFunctionsResult.Companion.EMPTY
        }

        override fun visit(letExpression: LetExpression?, context: java.lang.Void?): ExtractFilteringFunctionsResult {
            return ExtractFilteringFunctionsResult.Companion.EMPTY
        }

        override fun visit(setExpression: SetExpression?, context: java.lang.Void?): ExtractFilteringFunctionsResult {
            return ExtractFilteringFunctionsResult.Companion.EMPTY
        }
    }

    companion object {
        private fun wrapExprWithFilter(
            curFunction: QueryExpression?, filterExpr: FunctionExpression
        ): QueryExpression {
            val filterExprArgs: MutableList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument> =
                filterExpr.getArgs()
            val filteringFunction: FilteringQueryFunction? = filterExpr.getFunction().asFilteringFunction()
            val rewrittenArgs: MutableList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?> =
                java.util.ArrayList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?>()
            for (i in filterExprArgs.indices) {
                if (i == filteringFunction.getExpressionToFilterIndex()) {
                    rewrittenArgs.add(
                        com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument.Companion.of(
                            curFunction
                        )
                    )
                } else {
                    rewrittenArgs.add(filterExprArgs.get(i))
                }
            }
            return FunctionExpression(filteringFunction, rewrittenArgs)
        }
    }
}
