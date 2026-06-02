// Copyright 2024 The Bazel Authors. All rights reserved.
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
 * Represents a symbolic macro, defined in a .bzl file, that may be instantiated during Package
 * evaluation.
 * 
 * 
 * This is analogous to [RuleClass]. In essence, a `MacroClass` consists of the
 * macro's schema and its implementation function.
 */
// Do not implement equals() or hashCode() for MacroClass unless they guarantee identical behavior
// of executeMacroImplementation() after arbitrary Skyframe invalidations. In particular,
// token-based equality comparison of the implementation StarlarkFunction is not sufficient - we'd
// also need to verify e.g. the digests of the underlying Starlark modules.
class MacroClass private constructor(
    name: String?,
    definingBzlLabel: Label?,
    implementation: net.starlark.java.eval.StarlarkFunction?,
    attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute>,
    isFinalizer: Boolean
) {
    @kotlin.jvm.JvmField
    private val name: String?
    private val definingBzlLabel: Label?
    private val implementation: net.starlark.java.eval.StarlarkFunction?

    // Implicit attributes are stored under their given name ("_foo"), not a mangled name ("$foo").
    @kotlin.jvm.JvmField
    private val isFinalizer: Boolean
    private val attributeProvider: com.google.devtools.build.lib.packages.AttributeProvider

    init {
        this.name = name
        this.definingBzlLabel = definingBzlLabel
        this.implementation = implementation
        this.isFinalizer = isFinalizer
        val attributeIndex: MutableMap<String?, Int?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, Int?>(attributes.size())
        for (i in attributes.indices) {
            val attribute: com.google.devtools.build.lib.packages.Attribute = attributes.get(i)
            attributeIndex.put(attribute.getName(), i)
        }
        this.attributeProvider =
            com.google.devtools.build.lib.packages.AttributeProvider(
                attributes,
                attributeIndex,  /* nonConfigurableAttributes= */
                null,
                name,  /* ignoreLicenses= */
                false
            )
    }

    /** Returns the macro's exported name.  */
    fun getName(): String? {
        return name
    }

    /** Returns the label of the .bzl file where the macro was exported.  */
    fun getDefiningBzlLabel(): Label? {
        return definingBzlLabel
    }

    fun getImplementation(): net.starlark.java.eval.StarlarkFunction? {
        return implementation
    }

    fun getAttributeProvider(): com.google.devtools.build.lib.packages.AttributeProvider {
        return attributeProvider
    }

    /**
     * Returns whether this symbolic macro is a finalizer. All finalizers are run deferred to the end
     * of the BUILD file's evaluation, rather than synchronously with their instantiation.
     */
    fun isFinalizer(): Boolean {
        return isFinalizer
    }

    /** Builder for [MacroClass].  */
    class Builder(implementation: net.starlark.java.eval.StarlarkFunction?) {
        private var name: String? = null
        private var definingBzlLabel: Label? = null
        private val implementation: net.starlark.java.eval.StarlarkFunction?
        private val attributes: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.packages.Attribute?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.packages.Attribute?>()
        private var isFinalizer = false

        init {
            this.implementation = implementation

            addAttribute(RuleClass.Companion.NAME_ATTRIBUTE)
            addAttribute(VISIBILITY_ATTRIBUTE)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setName(name: String?): Builder {
            this.name = name
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDefiningBzlLabel(label: Label?): Builder {
            this.definingBzlLabel = label
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAttribute(attribute: com.google.devtools.build.lib.packages.Attribute): Builder {
            attributes.add(attribute)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIsFinalizer(): Builder {
            this.isFinalizer = true
            return this
        }

        fun build(): MacroClass {
            com.google.common.base.Preconditions.checkNotNull<String?>(name)
            com.google.common.base.Preconditions.checkNotNull<Any?>(definingBzlLabel)
            return MacroClass(
                name,
                definingBzlLabel,
                implementation,
                attributes.build(),  /* isFinalizer= */
                isFinalizer
            )
        }
    }

    /**
     * Constructs and returns a new [MacroInstance] associated with this `MacroClass`.
     * 
     * 
     * See [.instantiateAndAddMacro].
     */
    // TODO(#19922): Consider reporting multiple events instead of failing on the first one. See
    // analogous implementation in RuleClass#populateDefinedRuleAttributeValues.
    @Throws(
        LabelSyntaxException::class,
        net.starlark.java.eval.EvalException::class,
        java.lang.InterruptedException::class,
        CannotPrecomputeDefaultsException::class
    )
    private fun instantiateMacro(
        targetDefinitionContext: TargetDefinitionContext,
        kwargs: MutableMap<String?, Any>,
        parentThreadCallStack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
    ): MacroInstance? {
        // A word on edge cases:
        //   - If an attr is implicit but does not have a default specified, its value is just the
        //     default value for its attr type (e.g. `[]` for `attr.label_list()`).
        //   - If an attr is implicit but also mandatory, it's impossible to instantiate it without
        //     error.
        //   - If an attr is mandatory but also has a default, the default is meaningless.
        // These behaviors align with rule attributes.

        val attrValues: net.starlark.java.eval.Dict.Builder<String?, Any?> =
            net.starlark.java.eval.Dict.builder<String?, Any?>()

        // For each given attr value, validate that the attr exists and can be set.
        for (entry in kwargs.entrySet()) {
            val attrName: String = entry.getKey()
            val value: Any? = entry.getValue()

            // Check for unknown attr.
            if (attributeProvider.getAttributeIndex(attrName) == null) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "no such attribute '%s' in '%s' macro%s",
                    attrName,
                    name,
                    net.starlark.java.spelling.SpellChecker.didYouMean(
                        attrName,
                        attributeProvider.getAttributes().stream()
                            .filter(java.util.function.Predicate { obj: com.google.devtools.build.lib.packages.Attribute? -> obj.isDocumented() })
                            .map<String?>(java.util.function.Function { obj: com.google.devtools.build.lib.packages.Attribute? -> obj.getName() })
                            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                    )
                )
            }

            // Setting an attr to None is the same as omitting it (except that it's still an error to set
            // an unknown attr to None). If the attr is optional, skip adding it to the map now but put it
            // in below when we realize it's missing.
            if (value === net.starlark.java.eval.Starlark.NONE) {
                continue
            }

            // Can't set implicit default.
            // (We don't check Attribute#isImplicit() because that assumes "_" -> "$" prefix mangling.)
            // TODO: #19922 - The lack of "_" -> "$" mangling may impact the future feature of inheriting
            // attributes from rules. We could consider just doing the mangling for macros too so they're
            // consistent.
            if (attrName.startsWith("_")) {
                throw net.starlark.java.eval.Starlark.errorf("cannot set value of implicit attribute '%s'", attrName)
            }

            attrValues.put(attrName, value)
        }

        // Special processing of the "visibility" attribute.
        // TODO(brandjon): When we add introspection of attributes of symbolic macros, we'll want to
        // distinguish between the different types of visibility a la Target#getRawVisibility /
        // #getVisibility / #getActualVisibility.
        val parentMacroFrame: MacroFrame? = targetDefinitionContext.getCurrentMacroFrame()
        val rawVisibility = kwargs.get("visibility")
        val parsedVisibility: RuleVisibility?
        if (rawVisibility == null || rawVisibility == net.starlark.java.eval.Starlark.NONE) {
            // Visibility wasn't explicitly supplied. If we're not in another symbolic macro, use the
            // package's default visibility, otherwise use private visibility.
            if (parentMacroFrame == null) {
                parsedVisibility = targetDefinitionContext.getPartialPackageArgs().defaultVisibility()
            } else {
                parsedVisibility = RuleVisibility.Companion.PRIVATE
            }
        } else {
            val liftedVisibility: MutableList<Label?>? =
                BuildType.copyAndLiftStarlarkValue(
                    name,
                    VISIBILITY_ATTRIBUTE,
                    rawVisibility,
                    targetDefinitionContext.getLabelConverter()
                ) as MutableList<Label?>?
            parsedVisibility = RuleVisibility.Companion.parse(liftedVisibility)
        }
        // Concatenate the visibility (as previously populated) with the instantiation site's location.
        val instantiatingLoc: PackageIdentifier? =
            if (parentMacroFrame == null)
                targetDefinitionContext.getPackageIdentifier()
            else
                parentMacroFrame.macroInstance.getDefinitionPackage()
        val actualVisibility: RuleVisibility = parsedVisibility.concatWithPackage(instantiatingLoc)
        attrValues.put(
            "visibility",
            net.starlark.java.eval.Starlark.fromJava(
                actualVisibility.getDeclaredLabels(),
                net.starlark.java.eval.Mutability.IMMUTABLE
            )
        )

        // Normalize and validate all attr values. (E.g., convert strings to labels, promote
        // configurable attribute values to select()s, fail if bool was passed instead of label, ensure
        // values are immutable.)
        for (attribute in attributeProvider.getAttributes()) {
            if ((attribute.isPublic() && attribute.starlarkDefined())
                || attribute.getName() == "name"
            ) {
                if (kwargs.containsKey(attribute.getName())) {
                    val value: Any = kwargs.get(attribute.getName())!!
                    if (value == net.starlark.java.eval.Starlark.NONE) {
                        // Don't promote None to select({"//conditions:default": None}).
                        continue
                    }
                    val normalizedValue: Any? =  // copyAndLiftStarlarkValue ensures immutability.
                        BuildType.copyAndLiftStarlarkValue(
                            name, attribute, value, targetDefinitionContext.getLabelConverter()
                        )
                    // TODO(#19922): Validate that LABEL_LIST type attributes don't contain duplicates, to
                    // match the behavior of rules. This probably requires factoring out logic from
                    // AggregatingAttributeMapper.
                    attrValues.put(attribute.getName(), normalizedValue)
                }
            }
        }

        // Type and existence enforced by RuleClass.NAME_ATTRIBUTE.
        // Other mandatory attributes are enforced after the macro is created, but we need to check for
        // name now in order to find out the depth.
        if (!kwargs.containsKey("name")) {
            throw net.starlark.java.eval.Starlark.errorf(
                "missing value for mandatory attribute 'name' in '%s' macro",
                name
            )
        }
        val name = kwargs.get("name") as String
        // Determine the id for this macro. If we're in another macro by the same name, increment the
        // number, otherwise use 1 for the number.
        val sameNameDepth: Int =
            if (parentMacroFrame == null || name != parentMacroFrame.macroInstance.getName())
                1
            else
                parentMacroFrame.macroInstance.getSameNameDepth() + 1

        val attributeValues: BuildLangTypedAttributeValuesMap =
            BuildLangTypedAttributeValuesMap(attrValues.buildImmutable())

        val macroInstance: MacroInstance? =
            targetDefinitionContext.createMacro(this, name, sameNameDepth, parentThreadCallStack)
        attributeProvider.populateRuleAttributeValues<MutableMap.MutableEntry<String?, Any?>?>(
            macroInstance,
            targetDefinitionContext,
            attributeValues,  /* failOnUnknownAttributes= */
            true,  /* isStarlark= */
            true
        )
        return macroInstance
    }

    /**
     * Constructs a new [MacroInstance] associated with this `MacroClass`, adds it to the
     * package, and returns it.
     * 
     * @param targetDefinitionContext The builder corresponding to the packageoid in which this
     * instance will live.
     * @param kwargs A map from attribute name to its given Starlark value, such as passed in a BUILD
     * file (i.e., prior to attribute type conversion, `select()` promotion, default value
     * substitution, or even validation that the attribute exists).
     * @param parentThreadCallStack The call stack of the Starlark thread in whose context the macro
     * instance is being constructed. This is *not* the thread that will execute the macro's
     * implementation function.
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun instantiateAndAddMacro(
        targetDefinitionContext: TargetDefinitionContext,
        kwargs: MutableMap<String?, Any>,
        parentThreadCallStack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
    ): MacroInstance? {
        try {
            val macroInstance: MacroInstance? =
                instantiateMacro(targetDefinitionContext, kwargs, parentThreadCallStack)
            targetDefinitionContext.addMacro(macroInstance)
            return macroInstance
        } catch (e: LabelSyntaxException) {
            throw net.starlark.java.eval.EvalException(e)
        } catch (e: NameConflictException) {
            throw net.starlark.java.eval.EvalException(e)
        } catch (e: CannotPrecomputeDefaultsException) {
            throw net.starlark.java.eval.EvalException(e)
        }
    }

    companion object {
        /**
         * Names that users may not pass as keys of the `attrs` dict when calling `macro()`.
         * 
         * 
         * Of these, `name` is special cased as an actual attribute, and the rest do not exist.
         */
        // Keep in sync with `macro()`'s `attrs` user documentation in StarlarkRuleFunctionsApi.
        // But we should avoid adding new entries here, since it's a backwards-incompatible change.
        val RESERVED_MACRO_ATTR_NAMES: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("name", "visibility")

        /**
         * "visibility" attribute present on all symbolic macros.
         * 
         * 
         * This is similar to the visibility attribute for rules, but lacks the exec transitions.
         */
        @kotlin.jvm.JvmField
        val VISIBILITY_ATTRIBUTE: com.google.devtools.build.lib.packages.Attribute =
            com.google.devtools.build.lib.packages.Attribute.Companion.attr<MutableList<Label?>?>(
                "visibility",
                BuildType.NODEP_LABEL_LIST
            )
                .orderIndependent()
                .nonconfigurable("special attribute integrated more deeply into Bazel's core logic")
                .build()

        /**
         * Returns true if the given attribute's default value should be considered `None`.
         * 
         * 
         * This is the case for non-direct defaults and legacy licenses and distribs attributes,
         * because None may (depending on attribute type) violate type checking - and that is ok, since
         * the macro implementation will pass the None to the rule function, which will then set the
         * default as expected.
         */
        private fun shouldForceDefaultToNone(attr: com.google.devtools.build.lib.packages.Attribute): Boolean {
            return attr.hasComputedDefault()
                    || attr.isLateBound()
                    || attr.isMaterializing()
                    || attr.getType() === BuildType.LICENSE
        }

        /**
         * Executes a symbolic macro's implementation function, in a new Starlark thread, mutating the
         * given packageoid under construction.
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun executeMacroImplementation(
            macro: MacroInstance,
            targetDefinitionContext: TargetDefinitionContext,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            // Ensure we're not expanding a (possibly indirect) recursive macro. This is morally analogous
            // to StarlarkThread#isRecursiveCall, except in this context, recursion is through the chain of
            // macro instantiations, which may or may not actually be concurrently executing on the stack
            // depending on whether the evaluation is eager or deferred.
            val recursionMsg = getRecursionErrorMessage(macro)
            if (recursionMsg != null) {
                targetDefinitionContext
                    .getLocalEventHandler()
                    .handle(
                        com.google.devtools.build.lib.packages.Package.Companion.error( /* location= */null,
                            recursionMsg,
                            Code.STARLARK_EVAL_ERROR
                        )
                    )
                targetDefinitionContext.setContainsErrors()
                // Don't try to evaluate this macro again.
                if (targetDefinitionContext is com.google.devtools.build.lib.packages.Package.Builder) {
                    targetDefinitionContext.markMacroComplete(macro)
                }
                return
            }

            net.starlark.java.eval.Mutability.create(
                "macro", targetDefinitionContext.getPackageIdentifier(), macro.getName()
            ).use { mu ->
                val thread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.create(
                        mu,
                        semantics,
                        macro.getShortDescription(),
                        net.starlark.java.eval.SymbolGenerator.create<com.google.devtools.build.lib.packages.MacroInstance.UniqueId?>(
                            com.google.devtools.build.lib.packages.MacroInstance.UniqueId.Companion.create(
                                macro.getPackageMetadata().packageIdentifier, macro.getId()
                            )
                        )
                    )
                thread.setPrintHandler(
                    Event.makeDebugPrintHandler(targetDefinitionContext.getLocalEventHandler())
                )

                // TODO: #19922 - Technically the embedded SymbolGenerator field should use a different key
                // than the one in the main BUILD thread, but that'll be fixed when we change the type to
                // PackagePiece.Builder.
                targetDefinitionContext.storeInThread(thread)

                // TODO: #19922 - If we want to support creating analysis_test rules inside symbolic macros,
                // we'd need to call `thread.setThreadLocal(RuleDefinitionEnvironment.class,
                // ruleClassProvider)`. In that case we'll need to consider how to get access to the
                // ConfiguredRuleClassProvider. For instance, we could put it in the builder.
                val childMacroFrame: MacroFrame = MacroFrame(macro)
                val parentMacroFrame: MacroFrame? = targetDefinitionContext.setCurrentMacroFrame(childMacroFrame)
                // Retrieve the values of the macro's attributes and convert them to Starlark values.
                val kwargs: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                    com.google.common.collect.ImmutableMap.builder<String?, Any?>()
                for (attr in macro.getMacroClass().getAttributeProvider().getAttributes()) {
                    var attrValue: Any? = macro.getAttr(attr.getName(), attr.getType())
                    if (attrValue == null) {
                        attrValue = attr.getDefaultValueUnchecked()
                        if (attrValue == null || shouldForceDefaultToNone(attr)) {
                            attrValue = net.starlark.java.eval.Starlark.NONE
                        }
                    }
                    attrValue = com.google.devtools.build.lib.packages.Attribute.Companion.valueToStarlark(attrValue)
                    if (attr.isConfigurable()
                        && (attrValue !is com.google.devtools.build.lib.packages.SelectorList) && attrValue !== net.starlark.java.eval.Starlark.NONE
                    ) {
                        attrValue =
                            com.google.devtools.build.lib.packages.SelectorList.Companion.wrapSingleValue(attrValue)
                    }
                    kwargs.put(attr.getName(), attrValue)
                }
                try {
                    targetDefinitionContext.updateStartedThreadComputationSteps(thread).use { updater ->
                        val returnValue: Any? =
                            net.starlark.java.eval.Starlark.call(
                                thread,
                                macro.getMacroClass().getImplementation(),  /* args= */
                                com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                                kwargs.buildOrThrow()
                            )
                        if (returnValue !== net.starlark.java.eval.Starlark.NONE) {
                            throw net.starlark.java.eval.Starlark.errorf(
                                "macro '%s' may not return a non-None value (got %s)",
                                macro.getName(), net.starlark.java.eval.Starlark.repr(returnValue, semantics)
                            )
                        }
                    }
                } catch (ex: net.starlark.java.eval.EvalException) { // from either call() or non-None return
                    if (ex.getCallStack().isEmpty()
                        || ex.getCallStack().getFirst().location.file().endsWith(".bzl")
                    ) {
                        // If the call stack starts at a .bzl file (i.e. at the macro definition), prepend the
                        // call stacks of all outer threads to it, so that the user understands how the failing
                        // macro was instantiated.
                        throw net.starlark.java.eval.EvalException(ex.getMessage(), ex.getCause())
                            .withCallStack(
                                com.google.common.collect.ImmutableList.builder<net.starlark.java.eval.StarlarkThread.CallStackEntry?>()
                                    .addAll(macro.reconstructParentCallStack())
                                    .addAll(ex.getCallStack())
                                    .build()
                            )
                    }
                    throw ex
                } finally {
                    // Restore the previously running symbolic macro's state (if any).
                    val top: MacroFrame? = targetDefinitionContext.setCurrentMacroFrame(parentMacroFrame)
                    com.google.common.base.Preconditions.checkState(
                        top === childMacroFrame,
                        "inconsistent macro stack state"
                    )
                    // Mark the macro as having completed, even if it was in error (or interrupted?).
                    if (targetDefinitionContext is com.google.devtools.build.lib.packages.Package.Builder) {
                        targetDefinitionContext.markMacroComplete(macro)
                    }
                }
            }
        }

        /**
         * If the instantiation of `macro` was recursive, i.e. if it was transitively declared by
         * another macro instance having the same macro class, then returns an error string identifying
         * this macro's name and a "traceback" of the instantiating macros. Otherwise, returns null.
         */
        private fun getRecursionErrorMessage(macro: MacroInstance): String? {
            var ancestor: MacroInstance? = macro.getParent()
            var foundRecursion = false
            var onImmediateParent = true
            while (ancestor != null) {
                // TODO: #19922 - We're checking based on object identity here. If we need to worry about
                // macro classes being serialized and deserialized in a context that also does macro
                // evaluation, then we should use the more durable identifier of its definition label + name.
                if (ancestor.getMacroClass() == macro.getMacroClass()) {
                    foundRecursion = true
                    break
                }
                ancestor = ancestor.getParent()
                onImmediateParent = false
            }
            if (!foundRecursion) {
                return null
            }

            val msg: java.lang.StringBuilder = java.lang.StringBuilder()
            msg.append(
                java.lang.String.format(
                    "macro '%s' is %s recursive call of '%s'. Macro instantiation traceback (most"
                            + " recent call last):",
                    macro.getName(), if (onImmediateParent) "a direct" else "an indirect", ancestor.getName()
                )
            )

            // Materialize the stack as an ArrayList, since we want to output it in reverse order (outermost
            // first).
            val allAncestors: java.util.ArrayList<MacroInstance?> = java.util.ArrayList<MacroInstance?>()
            ancestor = macro
            while (ancestor != null) {
                allAncestors.add(ancestor)
                ancestor = ancestor.getParent()
            }
            for (item in com.google.common.collect.Lists.reverse<MacroInstance>(allAncestors)) {
                val pkg: String? = item.getPackageMetadata().packageIdentifier.getCanonicalForm()
                val type =
                    (item.getMacroClass().getDefiningBzlLabel().getCanonicalForm()
                            + "%"
                            + item.getMacroClass().getName())
                msg.append(java.lang.String.format("\n\tPackage %s, macro '%s' of type %s", pkg, item.getName(), type))
            }
            return msg.toString()
        }
    }
}
