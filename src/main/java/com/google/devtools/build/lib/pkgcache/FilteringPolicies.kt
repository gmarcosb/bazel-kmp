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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.packages.TargetUtils
import com.google.devtools.build.lib.pkgcache.FilteringPolicy
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * Utility class for predefined filtering policies.
 */
object FilteringPolicies {
    @kotlin.jvm.JvmField
    val NO_FILTER: FilteringPolicy = NoFilter()
    @kotlin.jvm.JvmField
    val FILTER_MANUAL: FilteringPolicy = FilterManual()
    @kotlin.jvm.JvmField
    val FILTER_TESTS: FilteringPolicy = FilterTests()
    @kotlin.jvm.JvmField
    val RULES_ONLY: FilteringPolicy = RulesOnly()

    /** Returns the result of applying y, if target passes x.  */
    fun and(x: FilteringPolicy, y: FilteringPolicy): FilteringPolicy? {
        if (x == NO_FILTER) {
            return y
        }
        if (y == NO_FILTER) {
            return x
        }
        return AndFilteringPolicy(x, y)
    }

    @kotlin.jvm.JvmStatic
    fun ruleTypeExplicit(ruleName: String?): FilteringPolicy {
        return RuleTypeFilter.Companion.create(ruleName,  /*keepExplicit=*/true)
    }

    /** Base class for singleton filtering policies.  */
    private abstract class AbstractFilteringPolicy : FilteringPolicy {
        private val hashCode: Int = getClass().getSimpleName().hashCode()

        override fun hashCode(): Int {
            return hashCode
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null) {
                return false
            }
            if (obj === this) {
                return true
            }
            return getClass() == obj.getClass()
        }
    }

    private class NoFilter : AbstractFilteringPolicy() {
        override fun shouldRetain(target: com.google.devtools.build.lib.packages.Target?, explicit: Boolean): Boolean {
            return true
        }

        override fun toString(): String {
            return "[]"
        }
    }

    private class FilterManual : AbstractFilteringPolicy() {
        override fun shouldRetain(target: com.google.devtools.build.lib.packages.Target?, explicit: Boolean): Boolean {
            return explicit || !TargetUtils.hasManualTag(target)
        }
    }

    private class FilterTests : AbstractFilteringPolicy() {
        override fun shouldRetain(target: com.google.devtools.build.lib.packages.Target?, explicit: Boolean): Boolean {
            return TargetUtils.isTestOrTestSuiteRule(target)
                    && FILTER_MANUAL.shouldRetain(target, explicit)
        }
    }

    private class RulesOnly : AbstractFilteringPolicy() {
        override fun shouldRetain(target: com.google.devtools.build.lib.packages.Target?, explicit: Boolean): Boolean {
            return target is com.google.devtools.build.lib.packages.Rule
        }
    }

    /** FilteringPolicy that only matches a specific rule name.  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    internal data class RuleTypeFilter(ruleName: String?, keepExplicit: Boolean) : FilteringPolicy {
        override fun shouldRetain(target: com.google.devtools.build.lib.packages.Target, explicit: Boolean): Boolean {
            if (explicit && this.keepExplicit) {
                return true
            }

            val rule: com.google.devtools.build.lib.packages.Rule? = target.getAssociatedRule()
            if (rule != null && rule.getRuleClass() == this.ruleName) {
                return true
            }

            return false
        }

        val ruleName: String?
        val keepExplicit: Boolean

        init {
            this.keepExplicit = keepExplicit
            this.ruleName = ruleName
            java.util.Objects.requireNonNull<String?>(ruleName, "ruleName")
        }

        companion object {
            private fun create(ruleName: String?, keepExplicit: Boolean): RuleTypeFilter {
                return RuleTypeFilter(ruleName, keepExplicit)
            }
        }
    }

    /** FilteringPolicy for combining FilteringPolicies.  */
    class AndFilteringPolicy private constructor(firstPolicy: FilteringPolicy?, secondPolicy: FilteringPolicy?) :
        FilteringPolicy {
        private val firstPolicy: FilteringPolicy
        private val secondPolicy: FilteringPolicy

        init {
            this.firstPolicy = com.google.common.base.Preconditions.checkNotNull<FilteringPolicy>(firstPolicy)
            this.secondPolicy = com.google.common.base.Preconditions.checkNotNull<FilteringPolicy>(secondPolicy)
        }

        override fun shouldRetain(target: com.google.devtools.build.lib.packages.Target?, explicit: Boolean): Boolean {
            return firstPolicy.shouldRetain(target, explicit)
                    && secondPolicy.shouldRetain(target, explicit)
        }

        fun getFirstPolicy(): FilteringPolicy {
            return firstPolicy
        }

        fun getSecondPolicy(): FilteringPolicy {
            return secondPolicy
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(firstPolicy, secondPolicy)
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is AndFilteringPolicy) {
                return false
            }
            return obj.firstPolicy == firstPolicy && obj.secondPolicy == secondPolicy
        }

        override fun toString(): String {
            return java.lang.String.format("and_filter(%s, %s)", firstPolicy, secondPolicy)
        }
    }
}
