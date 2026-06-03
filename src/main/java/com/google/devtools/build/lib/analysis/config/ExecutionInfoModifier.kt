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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.util.StringEncoding.internalToUnicode

/**
 * Represents a list of regexes over mnemonics and changes to add or remove keys, parsed from a
 * `--modify_execution_info` option.
 */
@AutoValue
abstract class ExecutionInfoModifier {
    abstract fun option(): String?

    abstract fun expressions(): com.google.common.collect.ImmutableList<Expression>?

    @AutoValue
    internal abstract class Expression {
        // Patterns do not have a useful equals(), so compare by the regex and memoize the derived
        // Pattern.
        @Memoized
        fun pattern(): java.util.regex.Pattern {
            return java.util.regex.Pattern.compile(regex())
        }

        abstract fun regex(): String?

        abstract fun remove(): Boolean

        abstract fun key(): String?
    }

    /** Constructs an instance of ExecutionInfoModifier by parsing an option string.  */
    class Converter

        : Contextless<ExecutionInfoModifier?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): ExecutionInfoModifier {
            if (com.google.common.base.Strings.isNullOrEmpty(input)) {
                return EMPTY
            }

            val expressionBuilder: com.google.common.collect.ImmutableList.Builder<Expression?> =
                com.google.common.collect.ImmutableList.builder<Expression?>()
            for (spec in com.google.common.base.Splitter.on(",").split(input)) {
                val specMatcher: java.util.regex.Matcher = MODIFIER_PATTERN.matcher(spec)
                if (!specMatcher.matches()) {
                    throw OptionsParsingException(
                        java.lang.String.format("malformed expression '%s'", spec), input
                    )
                }
                expressionBuilder.add(
                    AutoValue_ExecutionInfoModifier_Expression( // Convert to get a useful exception if it's not a valid pattern, but use the regex
                        // (see comment in Expression)
                        RegexPatternConverter()
                            .convert(specMatcher.group("pattern"),  /* conversionContext= */null)
                            .regexPattern()
                            .pattern(),
                        specMatcher.group("sign") == "-",
                        specMatcher.group("key")
                    )
                )
            }
            return create(input, expressionBuilder.build())
        }

        public override fun getTypeDescription(): String {
            return "regex=[+-]key,regex=[+-]key,..."
        }
    }

    /**
     * Determines whether the given `mnemonic` (e.g. "CppCompile") matches any of the patterns.
     */
    fun matches(mnemonic: String?): Boolean {
        return expressions().stream()
            .anyMatch(java.util.function.Predicate { expr: Expression? ->
                expr!!.pattern().matcher(internalToUnicode(mnemonic)).matches()
            })
    }

    /** Modifies the given map of `executionInfo` to add or remove the keys for this option.  */
    fun apply(mnemonic: String?, executionInfo: MutableMap<String?, String?>) {
        for (expr in expressions()) {
            if (expr.pattern().matcher(internalToUnicode(mnemonic)).matches()) {
                if (expr.remove()) {
                    executionInfo.remove(expr.key())
                } else {
                    executionInfo.put(expr.key(), "")
                }
            }
        }
    }

    companion object {
        private val MODIFIER_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("^(?<pattern>.+)=(?<sign>[+-])(?<key>.+)$")

        private val EMPTY = create("", com.google.common.collect.ImmutableList.of<Expression?>())

        private fun create(
            input: String?,
            expressions: com.google.common.collect.ImmutableList<Expression?>?
        ): ExecutionInfoModifier {
            return AutoValue_ExecutionInfoModifier(input, expressions)
        }

        /** Checks whether the `executionInfoList` matches the `mnemonic`.  */
        fun matches(
            executionInfoList: MutableList<ExecutionInfoModifier?>, isAdditive: Boolean, mnemonic: String?
        ): Boolean {
            if (executionInfoList.isEmpty()) {
                return false
            }

            if (isAdditive) {
                return executionInfoList.stream()
                    .anyMatch(java.util.function.Predicate { eim: ExecutionInfoModifier? -> eim!!.matches(mnemonic) })
            } else {
                return executionInfoList.getLast().matches(mnemonic)
            }
        }

        /** Applies `executionInfoList` to the given `executionInfo`.  */
        fun apply(
            executionInfoList: MutableList<ExecutionInfoModifier?>,
            isAdditive: Boolean,
            mnemonic: String?,
            executionInfo: MutableMap<String?, String?>
        ) {
            if (executionInfoList.isEmpty()) {
                return
            }

            if (isAdditive) {
                executionInfoList.forEach(java.util.function.Consumer { eim: ExecutionInfoModifier? ->
                    eim!!.apply(
                        mnemonic,
                        executionInfo
                    )
                })
            } else {
                executionInfoList.getLast().apply(mnemonic, executionInfo)
            }
        }
    }
}
