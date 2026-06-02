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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.cmdline.Label

/**
 * Common functionality for tests for the constraint enforcement system.
 */
abstract class AbstractConstraintsTest : BuildViewTestCase() {
    /**
     * Creates an environment group on the scratch filesystem consisting of the specified
     * environments and specified defaults, set via a builder-style interface. The package name
     * is the same as the group name.
     */
    protected inner class EnvironmentGroupMaker(name: String) {
        private val name: String
        private var environments: MutableSet<String?>? = null
        private var defaults: MutableSet<String?>? = null
        private val fulfillsMap: com.google.common.collect.Multimap<String?, String?> =
            com.google.common.collect.HashMultimap.create<String?, String?>()

        init {
            this.name = name
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setEnvironments(vararg environments: String?): EnvironmentGroupMaker {
            this.environments = com.google.common.collect.ImmutableSet.copyOf<String?>(environments)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDefaults(vararg environments: String?): EnvironmentGroupMaker {
            this.defaults = com.google.common.collect.ImmutableSet.copyOf<String?>(environments)
            return this
        }

        /** Declares that env1 fulfills env2.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFulfills(env1: String?, env2: String?): EnvironmentGroupMaker {
            fulfillsMap.put(env1, env2)
            return this
        }

        @Throws(java.lang.Exception::class)
        fun make() {
            val builder: java.lang.StringBuilder = java.lang.StringBuilder()
            for (env in environments!!) {
                builder.append("environment(name = '" + env + "',\n")
                    .append(getAttrDef("fulfills", *fulfillsMap.get(env).toArray<String?>(arrayOfNulls<String>(0))))
                    .append(")\n")
            }
            val envGroupName = if (name.contains("/")) name.substring(name.lastIndexOf("/") + 1) else name
            builder.append("environment_group(\n")
                .append("    name = '" + envGroupName + "',\n")
                .append(getAttrDef("environments", *environments.toArray<String?>(arrayOfNulls<String>(0))) + ",\n")
                .append(getAttrDef("defaults", *defaults.toArray<String?>(arrayOfNulls<String>(0))) + ",\n")
                .append(")")
            scratch.file("" + name + "/BUILD", builder.toString())
        }
    }

    /**
     * Returns the environments supported by a rule.
     */
    @Throws(java.lang.Exception::class)
    protected fun supportedEnvironments(ruleName: String?, ruleDef: String?): MutableCollection<Label?> {
        return RuleContextConstraintSemantics()
            .getSupportedEnvironments(
                getRuleContext(scratchConfiguredTarget("hello", ruleName, ruleDef))
            )
            .getEnvironments()
    }

    companion object {
        /**
         * Returns a rule definition of the given name, type and custom attribute settings.
         */
        protected fun getRuleDef(ruleType: String?, ruleName: String?, vararg customAttributes: String?): String {
            var ruleDef = ruleType + "(name = '" + ruleName + "',"
            for (customAttribute in customAttributes) {
                ruleDef += "    " + customAttribute + ","
            }
            ruleDef += ")"
            return ruleDef
        }

        /**
         * Given the inputs, returns the string "attrName = [':label1', ':label2', etc.]"
         */
        protected fun getAttrDef(attrName: String?, vararg labels: String?): String {
            var attrDef = "    " + attrName + " = ["
            for (label in labels) {
                attrDef += "'" + label + "', "
            }
            attrDef += "]"
            return attrDef
        }

        /**
         * The core constraint semantics check that if rule A depends on rule B, B must support all of A's
         * environments. To model this in the tests below, we construct two rules: a "depending" rule
         * (i.e. A) that depends on a "dependency" rule (i.e. B). Each test can construct its own instance
         * of these rules with its own environments specifications by calling this method and [ ][.getDependencyRule] with appropriate environment settings passed in as custom attributes.
         * 
         * 
         * This method constructs and returns the depending rule (i.e. A).
         */
        protected fun getDependingRule(vararg customAttributes: String?): String {
            val attrsAsList: MutableList<String?> =
                com.google.common.collect.Lists.newArrayList<String?>(*customAttributes)
            attrsAsList.add(getAttrDef("srcs", "dep"))
            return getRuleDef("filegroup", "main", *attrsAsList.toArray<String?>(arrayOfNulls<String>(0)))
        }

        /**
         * Returns the rule that [.getDependingRule] depends on. This rule must support every
         * environment supported by the other one for their constraint relationship to be considered
         * valid.
         */
        protected fun getDependencyRule(vararg customAttributes: String?): String {
            return getRuleDef("filegroup", "dep", *customAttributes)
        }

        /**
         * Returns the attribute definition that constrains a rule to the given environments. Inputs
         * are expected to be package-relative labels (e.g. `"foo_env"`).
         */
        protected fun constrainedTo(vararg environments: String?): String {
            return getAttrDef("restricted_to", *environments)
        }

        /**
         * Returns the attribute definition that designates a rule compatible with the given environments.
         */
        protected fun compatibleWith(vararg environments: String?): String {
            return getAttrDef("compatible_with", *environments)
        }
    }
}
