// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.TargetParsingException

/**
 * A [QueryExpressionMapper] that transforms each occurrence of an expression of the form
 * &#39;rdeps(&lt;universeScope&gt;, &lt;T&gt;)&#39; to &#39;allrdeps(&lt;T&gt;)&#39;. The latter is more
 * efficient.
 */
internal class RdepsToAllRdepsQueryExpressionMapper(
    targetPatternParser: TargetPattern.Parser,
    absoluteUniverseScopePattern: TargetPattern
) : QueryExpressionMapper<java.lang.Void?>() {
    private val targetPatternParser: TargetPattern.Parser
    private val absoluteUniverseScopePattern: TargetPattern

    init {
        this.targetPatternParser = targetPatternParser
        this.absoluteUniverseScopePattern = absoluteUniverseScopePattern
    }

    override fun visit(functionExpression: FunctionExpression, context: java.lang.Void?): QueryExpression? {
        if (functionExpression.getFunction().getName() == RdepsFunction().getName()) {
            val args: MutableList<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?> =
                functionExpression.getArgs()
            val rdepsUniverseExpression: QueryExpression? = args.get(0).getExpression()
            if (rdepsUniverseExpression is TargetLiteral) {
                val eligibility =
                    determineEligibility(
                        targetPatternParser,
                        absoluteUniverseScopePattern,
                        (rdepsUniverseExpression as TargetLiteral).getPattern()
                    )
                when (eligibility) {
                    Eligibility.ELIGIBLE_AS_IS -> {
                        return FunctionExpression(
                            AllRdepsFunction(), args.subList(1, functionExpression.getArgs().size)
                        )
                    }

                    Eligibility.ELIGIBLE_WITH_FILTERING -> {
                        return FunctionExpression(
                            KindFunction(),
                            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument?>(
                                com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument.Companion.of(" rule$"),
                                com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument.Companion.of(
                                    FunctionExpression(
                                        AllRdepsFunction(),
                                        args.subList(1, functionExpression.getArgs().size)
                                    )
                                )
                            )
                        )
                    }

                    else -> {}
                }
            }
        }
        return super.visit(functionExpression, context)
    }

    /**
     * Describes how eligible, if at all, a `rdeps(pattern, E, d)` expression is for being transformed
     * to one that uses `allrdeps`.
     */
    internal enum class Eligibility {
        NOT_ELIGIBLE,

        ELIGIBLE_WITH_FILTERING,

        ELIGIBLE_AS_IS,
    }

    companion object {
        fun determineEligibility(
            targetPatternParser: TargetPattern.Parser,
            absoluteUniverseScopePattern: TargetPattern,
            rdepsUniversePatternString: String?
        ): Eligibility {
            val absoluteRdepsUniverseTargetPattern: TargetPattern
            try {
                absoluteRdepsUniverseTargetPattern =
                    targetPatternParser.parse(targetPatternParser.absolutize(rdepsUniversePatternString))
            } catch (e: TargetParsingException) {
                return Eligibility.NOT_ELIGIBLE
            }

            if (absoluteUniverseScopePattern.type !== absoluteRdepsUniverseTargetPattern.type) {
                return Eligibility.NOT_ELIGIBLE
            }

            when (absoluteUniverseScopePattern.type) {
                PATH_AS_TARGET, SINGLE_TARGET -> {
                    return if (absoluteUniverseScopePattern.originalPattern
                            .equals(absoluteRdepsUniverseTargetPattern.originalPattern)
                    )
                        Eligibility.ELIGIBLE_AS_IS
                    else
                        Eligibility.NOT_ELIGIBLE
                }

                TARGETS_IN_PACKAGE, TARGETS_BELOW_DIRECTORY -> {
                    if (!absoluteUniverseScopePattern
                            .getDirectory()
                            .equals(absoluteRdepsUniverseTargetPattern.getDirectory())
                    ) {
                        return Eligibility.NOT_ELIGIBLE
                    }

                    // Note: If we're here, both patterns are either TARGETS_IN_PACKAGE or
                    // TARGETS_BELOW_DIRECTORY, and are for the same directory.
                    if (absoluteUniverseScopePattern.rulesOnly
                        === absoluteRdepsUniverseTargetPattern.rulesOnly
                    ) {
                        return Eligibility.ELIGIBLE_AS_IS
                    }

                    return if (absoluteUniverseScopePattern.rulesOnly // If the actual universe is narrower, then allrdeps would be unsound because it may
                    // produce narrower results.
                    )
                        Eligibility.NOT_ELIGIBLE // If the actual universe is wider, then allrdeps would produce wider results.
                    // Therefore, we'd want to filter those results.
                    else
                        Eligibility.ELIGIBLE_WITH_FILTERING
                }
            }
            throw java.lang.IllegalStateException(absoluteUniverseScopePattern.type.toString())
        }
    }
}
