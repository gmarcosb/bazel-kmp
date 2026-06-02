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
package com.google.devtools.common.options

import com.google.auto.value.AutoValue

/**
 * Option class wrapping a [class][Pattern]. We wrap the [Pattern] class instance since
 * it uses reference equality, which breaks the assumption of [Converter] that `converter.convert(sameString).equals(converter.convert(sameString)`.
 * 
 * 
 * Please note that the equality implementation is based solely on the input regex, therefore
 * patterns expressing the same intent with different regular expressions (e.g. `"a"` and
 * `"[a]"` will not be treated as equal.
 */
@AutoValue
abstract class RegexPatternOption {
    /**
     * The original regex pattern.
     * 
     * 
     * Note: Strings passed to the [Pattern] and [java.util.regex.Matcher] API have to
     * be converted to "Unicode" form first (see [ ][com.google.devtools.build.lib.util.StringEncoding.internalToUnicode].
     */
    abstract fun regexPattern(): java.util.regex.Pattern?

    /**
     * A potentially optimized [Predicate] that matches the entire input string against the
     * regex pattern.
     */
    abstract fun matcher(): java.util.function.Predicate<String?>?

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is RegexPatternOption) {
            return false
        }

        val otherOption = other
        return otherOption.regexPattern().pattern() == regexPattern().pattern()
    }

    override fun hashCode(): Int {
        return regexPattern().pattern().hashCode()
    }

    companion object {
        fun create(regexPattern: java.util.regex.Pattern?): RegexPatternOption {
            return AutoValue_RegexPatternOption(
                com.google.common.base.Preconditions.checkNotNull<T?>(regexPattern),
                com.google.devtools.build.lib.util.regex.RegexUtil.asOptimizedMatchingPredicate(regexPattern)
            )
        }
    }
}
