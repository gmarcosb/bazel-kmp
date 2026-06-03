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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.RuleClass

/**
 * This class is a common ancestor for every rule object.
 * 
 * 
 * Implementors are also required to have the [Metadata] annotation
 * set.
 */
interface RuleDefinition {
    /**
     * This method should return a RuleClass object that represents the rule. The usual pattern is
     * that various setter methods are called on the builder object passed in as the argument, then
     * the object that is built by the builder is returned.
     * 
     * @param builder A [com.google.devtools.build.lib.packages.RuleClass.Builder] object
     * already preloaded with the attributes of the ancestors specified in the [     ] annotation.
     * @param environment The services Blaze provides to rule definitions.
     * 
     * @return the [RuleClass] representing the rule.
     */
    fun build(builder: RuleClass.Builder?, environment: RuleDefinitionEnvironment?): RuleClass?

    /**
     * Returns metadata for this rule.
     */
    fun getMetadata(): Metadata?

    /**
     * Value class that contains the name, type, ancestors of a rule, as well as a reference to the
     * configured target factory.
     * 
     * @param name The name of the rule, as it appears in the BUILD file. If it starts with '$', the
     * rule will be hidden from users and will only be usable from inside Blaze.
     * @param type The type of the rule. It can be an abstract rule, a normal rule or a test rule. If
     * the rule type is abstract, the configured class must not be set.
     * @param factoryClass The [RuleConfiguredTargetFactory] class that implements this rule. If
     * the rule is abstract, this must not be set.
     * @param ancestors The list of other rule classes this rule inherits from.
     */
    class Metadata(
        name: String?,
        type: RuleClassType?,
        factoryClass: java.lang.Class<out RuleConfiguredTargetFactory?>?,
        ancestors: MutableList<java.lang.Class<out RuleDefinition?>?>?
    ) {
        /** Builder class for the Metadata class.  */
        @AutoBuilder
        abstract class Builder {
            abstract fun name(s: String?): Builder?
            abstract fun type(type: RuleClassType?): Builder?
            abstract fun factoryClass(factory: java.lang.Class<out RuleConfiguredTargetFactory?>?): Builder?
            abstract fun ancestors(ancestors: MutableList<java.lang.Class<out RuleDefinition?>?>?): Builder?

            @java.lang.SafeVarargs
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun ancestors(vararg ancstrs: java.lang.Class<out RuleDefinition?>?): Builder? {
                return ancestors(java.util.Arrays.asList<java.lang.Class<out RuleDefinition?>?>(*ancstrs))
            }

            abstract fun build(): Metadata?
        }

        val name: String?
        val type: RuleClassType?
        val factoryClass: java.lang.Class<out RuleConfiguredTargetFactory?>?
        val ancestors: MutableList<java.lang.Class<out RuleDefinition?>?>?

        init {
            this.ancestors = ancestors
            this.factoryClass = factoryClass
            this.type = type
            this.name = name
            java.util.Objects.requireNonNull<String?>(name, "name")
            java.util.Objects.requireNonNull<Any?>(type, "type")
            java.util.Objects.requireNonNull(factoryClass, "factoryClass")
            java.util.Objects.requireNonNull<MutableList<java.lang.Class<out RuleDefinition?>?>?>(
                ancestors,
                "ancestors"
            )
        }

        companion object {
            fun builder(): Builder {
                return AutoBuilder_RuleDefinition_Metadata_Builder()
                    .type(RuleClassType.NORMAL)
                    .factoryClass(RuleConfiguredTargetFactory::class.java)
                    .ancestors(Collections.emptyList<java.lang.Class<out RuleDefinition?>?>())
            }

            fun empty(): Metadata? {
                return com.google.devtools.build.lib.analysis.RuleDefinition.Metadata.Companion.builder().build()
            }
        }
    }
}
