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
package com.google.devtools.build.lib.generatedprojecttest.util

import com.google.common.base.Predicate
import com.google.common.base.Predicates
import com.google.common.collect.*
import com.google.devtools.build.lib.packages.Attribute
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList

/**
 * Utility class for providing static predicates for rules, to help filter the rules.
 */
object RuleSetUtils {
    /**
     * Predicate for checking if a rule is hidden.
     */
    val HIDDEN_RULE: Predicate<String?> = object : Predicate<String?> {
        override fun apply(input: String): Boolean {
            try {
                RuleClassType.INVISIBLE.checkName(input)
                return true
            } catch (e: IllegalArgumentException) {
                return input == "testing_dummy_rule"
                        || input == "testing_rule_for_mandatory_providers"
            }
        }
    }

    /** Predicate for checking if a rule has any mandatory attributes, aside from name.  */
    val MANDATORY_ATTRIBUTES: Predicate<RuleClass?> = object : Predicate<RuleClass?> {
        override fun apply(input: RuleClass): Boolean {
            val li: MutableList<Attribute?> = ArrayList<Any?>(input.getAttributeProvider().getAttributes())
            return Iterables.any<Attribute?>(li, Predicate { obj: Attribute? -> RuleSetUtils.mandatoryExcludingName() })
        }
    }

    /**
     * Predicate for checking that the rule can have a deps attribute, and does not have any other
     * mandatory attributes besides deps and name.
     */
    val DEPS_ONLY_ALLOWED: Predicate<RuleClass?> = object : Predicate<RuleClass?> {
        override fun apply(input: RuleClass): Boolean {
            val li: MutableList<Attribute?> = ArrayList<Any?>(input.getAttributeProvider().getAttributes())
            // TODO(bazel-team): after the API migration we shouldn't check srcs separately
            val emptySrcsAllowed =
                !input.getAttributeProvider().hasAttr("srcs", BuildType.LABEL_LIST)
                        || !input.getAttributeProvider().getAttributeByName("srcs").isNonEmpty()
            if (!(emptySrcsAllowed && Iterables.any<Attribute?>(li, DEPS))) {
                return false
            }

            val it: MutableIterator<Attribute?> = li.iterator()
            val mandatoryAttributesBesidesDeps =
                Iterables.any<Attribute?>(
                    Lists.newArrayList<Attribute?>(
                        Iterators.filter<Attribute?>(
                            it,
                            Predicate { obj: Attribute? -> RuleSetUtils.mandatoryExcludingName() })
                    ),
                    Predicates.not<Attribute?>(DEPS)
                )
            return !mandatoryAttributesBesidesDeps
        }
    }

    fun hasAnyAttributes(
        attributes: MutableCollection<Pair<String?, Type<*>?>?>
    ): Predicate<RuleClass?> {
        return HasAttributes(attributes)
    }

    /** Predicate for checking if an attribute (other than name) is mandatory.  */
    private fun mandatoryExcludingName(input: Attribute): Boolean {
        return input.isMandatory() && !input.name.equals("name")
    }

    /**
     * Predicate for checking if an attribute is the "deps" attribute.
     */
    private val DEPS: Predicate<Attribute?> = object : Predicate<Attribute?> {
        override fun apply(input: Attribute): Boolean {
            return input.name.equals("deps")
        }
    }

    /**
     * Predicate for checking if a rule class is not in excluded.
     */
    fun notContainsAnyOf(excluded: ImmutableSet<String?>): Predicate<String?> {
        return Predicates.not<String?>(Predicates.`in`<String?>(excluded))
    }

    /**
     * Predicate for checking if a RuleClass has certain attributes
     */
    class HasAttributes(attributes: MutableCollection<Pair<String?, Type<*>?>?>) : Predicate<RuleClass?> {
        private val attributes: MutableList<Pair<String?, Type<*>?>?>

        init {
            this.attributes = ImmutableList.copyOf<Pair<String?, Type<*>?>?>(attributes)
        }

        override fun apply(ruleClass: RuleClass): Boolean {
            return attributes.stream()
                .anyMatch { pair: Pair<kotlin.String?, Type<*>?>? ->
                    ruleClass.getAttributeProvider().hasAttr(pair.first, pair.second)
                }
        }
    }
}
