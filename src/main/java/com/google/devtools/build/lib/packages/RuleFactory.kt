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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * Static utility class for defining Starlark callables for builtin rules (i.e., [ ] objects for builtin rules' [RuleClass] objects), and instantiating those
 * rules to produce targets (i.e., [Rule] objects).
 */
object RuleFactory {
    /**
     * Creates and returns a rule instance.
     * 
     * 
     * It is the caller's responsibility to add the rule to the package (the caller may choose not
     * to do so if, for example, the rule has errors).
     * 
     * @param callstack the stack of the Starlark thread where the rule is created. In the case of
     * rules instantiated by a symbolic macro, this is the inner macro's stack, and does not
     * include frames for enclosing macros or the BUILD file.
     */
    @Throws(InvalidRuleException::class, java.lang.InterruptedException::class)
    fun createRule(
        targetDefinitionContext: TargetDefinitionContext,
        ruleClass: RuleClass?,
        attributeValues: BuildLangTypedAttributeValuesMap,
        failOnUnknownAttributes: Boolean,
        callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>
    ): com.google.devtools.build.lib.packages.Rule {
        var callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> =
            callstack
        com.google.common.base.Preconditions.checkNotNull<RuleClass?>(ruleClass)
        val ruleClassName: String? = ruleClass.getName()
        val nameObject = attributeValues.getAttributeValue("name")
        if (nameObject == null) {
            throw InvalidRuleException(ruleClassName + " rule has no 'name' attribute")
        } else if (nameObject !is String) {
            throw InvalidRuleException(ruleClassName + " 'name' attribute must be a string")
        }
        val name = nameObject
        val label: Label?
        try {
            // Test that this would form a valid label name -- in particular, this
            // catches cases where Makefile variables $(foo) appear in "name".
            label = targetDefinitionContext.createLabel(name)
        } catch (e: LabelSyntaxException) {
            throw InvalidRuleException("illegal rule name: " + name + ": " + e.getMessage())
        }

        // Add the generator_name attribute.
        val processedAttributes: BuildLangTypedAttributeValuesMap?
        val generatorName: String? = com.google.devtools.build.lib.packages.RuleFactory.getGeneratorName(
            targetDefinitionContext,
            attributeValues,
            callstack
        )
        // Don't bother copying anything if nothing changed.
        if (generatorName != null) {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, Any?>(attributeValues.attributeValues.size() + 1)
            builder.putAll(attributeValues.attributeValues)
            builder.put("generator_name", generatorName)
            processedAttributes = BuildLangTypedAttributeValuesMap(builder.buildKeepingLast())
        } else {
            processedAttributes = attributeValues
        }

        // The raw stack is of the form [<toplevel>@BUILD:1, macro@lib.bzl:1, cc_library@<builtin>].
        // Pop the innermost frame for the rule, since it's obvious.
        callstack = callstack.subList(0, callstack.size() - 1) // pop

        try {
            return ruleClass.createRule<MutableMap.MutableEntry<String?, Any?>?>(
                targetDefinitionContext, label, processedAttributes, failOnUnknownAttributes, callstack
            )
        } catch (e: LabelSyntaxException) {
            throw InvalidRuleException(ruleClass.toString() + " " + e.getMessage())
        } catch (e: CannotPrecomputeDefaultsException) {
            throw InvalidRuleException(ruleClass.toString() + " " + e.getMessage())
        }
    }

    /**
     * Creates a [Rule] instance, adds it to the [Package.Builder] and returns it.
     * 
     * @param pkgBuilder the under-construction [Package.Builder] to which the rule belongs
     * @param ruleClass the [RuleClass] of the rule
     * @param attributeValues a [BuildLangTypedAttributeValuesMap] mapping attribute names to
     * attribute values of build-language type. Each attribute must be defined for this class of
     * rule, and have a build-language-typed value which can be converted to the appropriate
     * native type of the attribute (i.e. via [BuildType.convertFromBuildLangType]). There
     * must be a map entry for each non-optional attribute of this class of rule.
     * @param eventHandler a eventHandler on which errors and warnings are reported during rule
     * creation
     * @param callstack the stack of active calls in the Starlark thread
     * @throws InvalidRuleException if the rule could not be constructed for any reason (e.g. no
     * `name` attribute is defined)
     * @throws NameConflictException if the rule's name or output files conflict with others in this
     * package
     * @throws InterruptedException if interrupted
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(InvalidRuleException::class, NameConflictException::class, java.lang.InterruptedException::class)
    fun createAndAddRule(
        targetDefinitionContext: TargetDefinitionContext,
        ruleClass: RuleClass?,
        attributeValues: BuildLangTypedAttributeValuesMap,
        failOnUnknownAttributes: Boolean,
        callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>
    ): com.google.devtools.build.lib.packages.Rule {
        val rule: com.google.devtools.build.lib.packages.Rule =
            com.google.devtools.build.lib.packages.RuleFactory.createRule(
                targetDefinitionContext,
                ruleClass,
                attributeValues,
                failOnUnknownAttributes,
                callstack
            )
        targetDefinitionContext.addRule(rule)
        return rule
    }

    /**
     * Given the call stack and attribute values of a rule being instantiated, computes and returns
     * the value of the special `generator_name` attribute to be added, or returns null if it
     * shouldn't be added.
     * 
     * 
     * The `generator_name` attribute is set for targets instantiated within a legacy macro
     * (and which are not also within a symbolic macro). Its value is the name argument of the
     * top-level macro on the call stack, if its value can be determined statically (see [ ][PackageFactory.checkBuildSyntax]), or just the name of the target otherwise.
     * 
     * @param callstack the stack of the Starlark thread where the rule is created. In the case of
     * rules instantiated by a symbolic macro, this is the inner macro's stack, and does not
     * include frames for enclosing macros or the BUILD file.
     */
    private fun getGeneratorName(
        targetDefinitionContext: TargetDefinitionContext,
        args: BuildLangTypedAttributeValuesMap,
        callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>
    ): String? {
        val macro: MacroInstance? = targetDefinitionContext.currentMacro()
        // The "generator" of a rule is the function outermost in the BUILD file thread's call stack
        // (regardless of whether or not it was passed a "name" parameter). For rules with generators,
        // the BUILD file thread's call stack must contain at least two entries:
        //   0: the outermost function (BUILD file top level),
        //   1: the function called by it (e.g. a macro in a .bzl file),
        // optionally followed by other Starlark or built-in functions, and finally - if the rule is
        // instantiated in the BUILD file thread - the rule instantiation function.
        if (macro == null
            && (callstack.size() < 2 || !callstack.get(1).location.file().endsWith(".bzl"))
        ) {
            // We are in a BUILD file thread, and the rule is being instantiated directly at the top
            // level of the BUILD file.
            // TODO(bazel-team): Tolerate ".scl" extension in the above if? An .scl file can instantiate a
            // rule if the rule function is passed as an argument.
            return null
        }

        if (args.containsAttributeNamed("generator_name")) {
            // generator_name is explicitly set. Don't override it.
            // TODO(b/274802222): Should this be prohibited?
            return null
        }

        var generatorName: String? =
            if (macro != null)
                macro.getGeneratorName()
            else
                targetDefinitionContext.getGeneratorNameByLocation(callstack.get(0).location)
        if (generatorName == null) {
            // Fall back on target name (meh).
            generatorName = args.getAttributeValue("name") as String?
        }
        return generatorName
    }

    /**
     * Builds a map from rule names to (newly constructed)) Starlark callables that instantiate them.
     */
    fun buildRuleFunctions(
        ruleClassMap: MutableMap<String?, RuleClass>
    ): com.google.common.collect.ImmutableMap<String?, BuiltinRuleFunction?> {
        val result: com.google.common.collect.ImmutableMap.Builder<String?, BuiltinRuleFunction?> =
            com.google.common.collect.ImmutableMap.builder<String?, BuiltinRuleFunction?>()
        for (ruleClassName in ruleClassMap.keySet()) {
            val cl: RuleClass = ruleClassMap.get(ruleClassName)
            if (cl.getRuleClassType() === RuleClassType.NORMAL || cl.getRuleClassType() === RuleClassType.TEST || cl.getRuleClassType() === RuleClassType.BUILD_ONLY) {
                result.put(ruleClassName, BuiltinRuleFunction(cl))
            }
        }
        return result.buildOrThrow()
    }

    /**
     * InvalidRuleException is thrown by [Rule] creation methods if the [Rule] could not
     * be constructed. It contains an error message.
     */
    class InvalidRuleException(message: String?) : java.lang.Exception(message)

    /**
     * A wrapper around an map of named attribute values that specifies whether the map's values are
     * of "build-language" or of "native" types.
     */
    interface AttributeValues<T> {
        /**
         * Returns `true` if all the map's values are "build-language typed", i.e., resulting from
         * the evaluation of an expression in the build language. Returns `false` if all the map's
         * values are "natively typed", i.e. of a type returned by [ ][BuildType.convertFromBuildLangType].
         */
        fun valuesAreBuildLanguageTyped(): Boolean

        fun getAttributeAccessors(): Iterable<T?>?

        fun getName(attributeAccessor: T?): String?

        fun getValue(attributeAccessor: T?): Any?

        fun isExplicitlySpecified(attributeAccessor: T?): Boolean
    }

    /** A [AttributeValues] of explicit "build-language" values.  */
    class BuildLangTypedAttributeValuesMap
        (attributeValues: MutableMap<String?, Any>) : AttributeValues<MutableMap.MutableEntry<String?, Any?>?> {
        private val attributeValues: MutableMap<String?, Any>

        init {
            this.attributeValues = attributeValues
        }

        private fun containsAttributeNamed(attributeName: String?): Boolean {
            return attributeValues.containsKey(attributeName)
        }

        private fun getAttributeValue(attributeName: String?): Any {
            return attributeValues.get(attributeName)!!
        }

        override fun valuesAreBuildLanguageTyped(): Boolean {
            return true
        }

        override fun getAttributeAccessors(): MutableSet<MutableMap.MutableEntry<String?, Any?>?> {
            return attributeValues.entrySet()
        }

        override fun getName(attributeAccessor: MutableMap.MutableEntry<String?, Any?>): String? {
            return attributeAccessor.getKey()
        }

        override fun getValue(attributeAccessor: MutableMap.MutableEntry<String?, Any?>): Any? {
            return attributeAccessor.getValue()
        }

        override fun isExplicitlySpecified(attributeAccessor: MutableMap.MutableEntry<String?, Any?>?): Boolean {
            return true
        }
    }

    /** A callable Starlark value that creates Rules for native RuleClasses.  */ // TODO(adonovan): why is this distinct from RuleClass itself?
    // Make RuleClass implement StarlarkCallable directly.
    private class BuiltinRuleFunction(ruleClass: RuleClass?) : RuleFunction {
        private val ruleClass: RuleClass

        init {
            this.ruleClass = com.google.common.base.Preconditions.checkNotNull<RuleClass>(ruleClass)
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun call(
            thread: net.starlark.java.eval.StarlarkThread,
            args: net.starlark.java.eval.Tuple,
            kwargs: net.starlark.java.eval.Dict<String?, Any>
        ): net.starlark.java.eval.NoneType? {
            if (!args.isEmpty()) {
                throw net.starlark.java.eval.Starlark.errorf("unexpected positional arguments")
            }
            try {
                val targetDefinitionContext: TargetDefinitionContext =
                    when (ruleClass.getRuleClassType()) {
                        RuleClassType.BUILD_ONLY -> com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.fromOrFailAllowBuildOnly(
                            thread, java.lang.String.format("%s rule", ruleClass.getName()), "instantiated"
                        )

                        else -> TargetDefinitionContext.Companion.fromOrFail(thread, "a rule", "instantiated")
                    }
                com.google.devtools.build.lib.packages.RuleFactory.createAndAddRule(
                    targetDefinitionContext,
                    ruleClass,
                    BuildLangTypedAttributeValuesMap(kwargs),
                    thread
                        .getSemantics()
                        .getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_FAIL_ON_UNKNOWN_ATTRIBUTES),
                    thread.getCallStack()
                )
            } catch (e: InvalidRuleException) {
                throw net.starlark.java.eval.EvalException(e)
            } catch (e: NameConflictException) {
                throw net.starlark.java.eval.EvalException(e)
            }
            return net.starlark.java.eval.Starlark.NONE
        }

        override fun getRuleClass(): RuleClass {
            return ruleClass
        }

        override fun getName(): String? {
            return ruleClass.getName()
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<built-in rule " + getName() + ">")
        }

        override fun toString(): String {
            return getName() + "(...)"
        }

        override fun isImmutable(): Boolean {
            return true
        }
    }
}
