// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.*

/**
 * Provides a simple API for creating custom rule classes for tests.
 * 
 * 
 * Use this whenever you want to test language-agnostic Bazel functionality, i.e. behavior that
 * isn't specific to individual rule implementations. If you find yourself searching through rule
 * implementations trying to find one that matches whatever you're trying to test, you probably
 * want this instead.
 * 
 * 
 * This prevents the anti-pattern of tests with commingled dependencies. For example, when a test
 * uses `cc_library` to test generic logic that `cc_library` happens to
 * provide, the test can break if the `cc_library` implementation changes. This means C++
 * rule developers have to understand the test to change C++ logic: a dependency that helps no one.
 * 
 * 
 * Even if C++ logic doesn't change, `cc_library` may not make it clear what's being
 * tested (e.g. "why is the "malloc" attribute used here?"). Using a mock rule class offers the
 * ability to write a clearer, more focused, easier to understand test (e.g.
 * `mock_rule(name = "foo", attr_that_tests_this_specific_test_logic = ":bar")).`
 *
 *Usage for a custom rule type that just needs to exist (no special attributes or behavior
 * needed):
 * 
 * <pre>
 * MockRule fooRule = () -> MockRule.define("foo_rule");
</pre> * 
 * 
 * 
 * Usage for custom attributes:
 * 
 * <pre>
 * MockRule fooRule = () -> MockRule.define("foo_rule", attr("some_attr", Type.STRING));
</pre> * 
 * 
 * 
 * Usage for arbitrary customization:
 * 
 * <pre>
 * MockRule fooRule = () -> MockRule.define(
 * "foo_rule",
 * (builder, env) ->
 * builder
 * .removeAttribute("tags")
 * .requiresConfigurationFragments(FooConfiguration.class);
 * );
</pre> * 
 * 
 * Custom [RuleDefinition] ancestors and [RuleConfiguredTargetFactory] implementations
 * can also be specified:
 * 
 * <pre>
 * MockRule customAncestor = () -> MockRule.ancestor(BaseRule.class).define(...);
 * MockRule customImpl = () -> MockRule.factory(FooRuleFactory.class).define(...);
 * MockRule customEverything = () ->
 * MockRule.ancestor(BaseRule.class).factory(FooRuleFactory.class).define(...);
</pre> * 
 * 
 * When unspecified, [State.DEFAULT_ANCESTOR] and [State.DEFAULT_FACTORY] apply.
 * 
 * 
 * We use lambdas for custom rule classes because [ConfiguredRuleClassProvider] indexes
 * rule class definitions by their Java class names. So each definition has to have its own
 * unique Java class.
 * 
 * 
 * Both of the following forms are valid:
 * 
 * <pre>MockRule fooRule = () -> MockRule.define("foo_rule");</pre>
 * <pre>RuleDefinition fooRule = (MockRule) () -> MockRule.define("foo_rule");</pre>
 * 
 * 
 * Use discretion in choosing your preferred form. The first is more compact. The second makes
 * it clearer that `fooRule` is a proper rule class definition.
 */
interface MockRule : RuleDefinition {
    // MockRule is designed to be easy to use. That doesn't necessarily mean its implementation is
    // easy to understand.
    //
    // If you just want to mock a rule, it's best to rely on the interface javadoc above, rather than
    // trying to parse what's going on below. You really only need to understand the below if you want
    // to customize MockRule itself.
    /** Container for the desired name and custom settings for this rule class.  */
    class State internal constructor(
        ruleClassName: String?, customBehavior: MockRuleCustomBehavior?,
        factory: Class<out RuleConfiguredTargetFactory?>?,
        ancestors: ImmutableList<Class<out RuleDefinition?>?>?,
        type: RuleClassType?
    ) {
        private val name: String
        private val customBehavior: MockRuleCustomBehavior
        private val factory: Class<out RuleConfiguredTargetFactory?>?
        private val ancestors: ImmutableList<Class<out RuleDefinition?>?>?
        private val type: RuleClassType?

        init {
            this.name = Preconditions.checkNotNull<String>(ruleClassName)
            this.customBehavior = Preconditions.checkNotNull<MockRuleCustomBehavior>(customBehavior)
            this.factory = factory
            this.ancestors = ancestors
            this.type = type
        }

        class Builder {
            private var factory: Class<out RuleConfiguredTargetFactory?>? = DEFAULT_FACTORY
            private var ancestors: ImmutableList<Class<out RuleDefinition?>?> = DEFAULT_ANCESTORS
            private var type: RuleClassType? = RuleClassType.NORMAL

            @CanIgnoreReturnValue
            fun factory(factory: Class<out RuleConfiguredTargetFactory?>?): Builder {
                this.factory = factory
                return this
            }

            @CanIgnoreReturnValue
            fun ancestor(vararg ancestor: Class<out RuleDefinition?>?): Builder {
                this.ancestors = ImmutableList.copyOf<Class<out RuleDefinition?>?>(ancestor)
                return this
            }

            @CanIgnoreReturnValue
            fun type(type: RuleClassType?): Builder {
                this.type = type
                return this
            }

            fun define(ruleClassName: String?, vararg attributes: Attribute.Builder<*>?): State {
                return build(
                    ruleClassName,
                    CustomAttributes(Arrays.asList<Attribute.Builder<*>?>(*attributes))
                )
            }

            fun define(ruleClassName: String?, customBehavior: MockRuleCustomBehavior?): State {
                return build(ruleClassName, customBehavior)
            }

            private fun build(ruleClassName: String?, customBehavior: MockRuleCustomBehavior?): State {
                return State(ruleClassName, customBehavior, factory, ancestors, type)
            }
        }

        companion object {
            /** The default [RuleConfiguredTargetFactory] for this rule class.  */
            private val DEFAULT_FACTORY: Class<out RuleConfiguredTargetFactory?> =
                DefaultConfiguredTargetFactory::class.java

            /** The default [RuleDefinition] for this rule class.  */
            private val DEFAULT_ANCESTORS: ImmutableList<Class<out RuleDefinition?>?> =
                ImmutableList.of<Class<out RuleDefinition?>?>()
        }
    }

    /**
     * Returns the basic state that defines this rule class. This is the only interface method
     * implementers must override.
     */
    fun define(): State

    /**
     * Builds out this rule with default attributes Blaze expects of all rules
     * ([MockRuleDefaults.DEFAULT_ATTRIBUTES]) plus the custom attributes defined by this
     * implementation's [State].
     * 
     * 
     * Do not override this method. For extra custom behavior, use
     * [.define]
     */
    public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
        val state = define()
        if (State.Companion.DEFAULT_ANCESTORS == state.ancestors) {
            MockRuleDefaults.DEFAULT_ATTRIBUTES.stream().forEach(builder::add)
        }
        state.customBehavior.customize(builder, environment)
        return builder.build()
    }

    val metadata: RuleDefinition.Metadata
        /**
         * Sets this rule class's metadata with the name defined by [State], configured target
         * factory declared by [State.Builder.factory], and ancestor rule class declared by
         * [State.Builder.ancestor].
         */
        get() {
            val state = define()
            return RuleDefinition.Metadata.builder()
                .name(state.name)
                .type(state.type)
                .factoryClass(state.factory)
                .ancestors(state.ancestors)
                .build()
        }

    companion object {
        /**
         * Sets a custom [RuleConfiguredTargetFactory] for this mock rule.
         * 
         * 
         * If not set, [State.DEFAULT_FACTORY] is used.
         */
        fun factory(factory: Class<out RuleConfiguredTargetFactory?>?): State.Builder {
            return State.Builder().factory(factory)
        }

        /**
         * Sets a custom ancestor [RuleDefinition] for this mock rule.
         * 
         * 
         * If not set, [State.DEFAULT_ANCESTORS] is used.
         */
        fun ancestor(vararg ancestor: Class<out RuleDefinition?>?): State.Builder {
            return State.Builder().ancestor(*ancestor)
        }

        /**
         * Returns a new [State] for this rule class with custom attributes. This is a convenience
         * method for lambda definitions:
         * 
         * <pre>
         * MockRule myRule = () -> MockRule.define("my_rule", attr("myattr", Type.STRING));
        </pre> * 
         */
        fun define(ruleClassName: String?, vararg attributes: Attribute.Builder<*>?): State {
            return State.Builder().define(ruleClassName, *attributes)
        }

        /**
         * Returns a new [State] for this rule class with arbitrary custom behavior. This is a
         * convenience method for lambda definitions:
         * 
         * <pre>
         * MockRule myRule = () -> MockRule.define(
         * "my_rule",
         * (builder, env) -> builder.requiresConfigurationFragments(FooConfiguration.class));
        </pre> * 
         */
        fun define(ruleClassName: String?, customBehavior: MockRuleCustomBehavior?): State {
            return State.Builder().define(ruleClassName, customBehavior)
        }
    }
}
