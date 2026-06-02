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
 * Represents a use of a symbolic macro in a package.
 * 
 * 
 * There is one `MacroInstance` for each call to a [ ][StarlarkRuleClassFunctions.MacroFunction] that is executed during a package's evaluation. Just as
 * a [MacroClass] is analogous to a [RuleClass], `MacroInstance` is analogous to a
 * [Rule] (i.e. a rule target).
 * 
 * 
 * Macro instance names are not guaranteed to be unique within a package; see [.getId].
 */
class MacroInstance internal constructor(
    packageMetadata: com.google.devtools.build.lib.packages.Package.Metadata,
    packageDeclarations: Declarations?,
    parent: MacroInstance?,
    generatorName: String?,
    buildFileLocation: net.starlark.java.syntax.Location?,
    parentCallStack: CallStack.Node?,
    macroClass: MacroClass,
    label: Label?,
    sameNameDepth: Int
) : RuleOrMacroInstance(label, macroClass.getAttributeProvider().getAttributeCount()) {
    // TODO: #19922 - If we want to save the cost of a field here, we can merge pkg and parent into a
    // single field of type Object, and walk up the parent hierarchy to answer getPackage() queries.
    private val packageMetadata: com.google.devtools.build.lib.packages.Package.Metadata

    // TODO(bazel-team): This is only needed for RuleOrMacroInstance#getPackageDeclarations(), which
    // is used by the attribute mapper logic. That might only be needed for rules rather than macros.
    // Consider removing it and pushing getPackageDeclarations() down to Rule.
    private val packageDeclarations: Declarations?

    // TODO(https://github.com/bazelbuild/bazel/issues/26128): replace with a parent identifier. The
    // existence of a parent pointer prevents change pruning on outer macro instances, forcing an
    // unconditional re-evaluation of all inner macros when an outer macro is invalidated.
    private val parent: MacroInstance?

    // Null if this symbolic macro was instantiated as a result of a legacy macro call without a
    // "name" parameter made at the top level of a BUILD file.
    @kotlin.jvm.JvmField
    private val generatorName: String?

    // TODO(https://github.com/bazelbuild/bazel/issues/26128): move location and Starlark stack to the
    // owning PackagePiece to make MacroInstance more change pruning friendly; we don't want the macro
    // to be invalidated if line numbers in a BUILD file or an ancestor macro's definition .bzl file
    // change.
    private val buildFileLocation: net.starlark.java.syntax.Location?
    private val parentCallStack: CallStack.Node?

    private val macroClass: MacroClass

    private val sameNameDepth: Int

    /**
     * Instantiates the given macro class.
     * 
     * 
     * `sameNameDepth` is the number of macro instances that this one is inside of that share
     * its name. For most instances it is 1, but for the main submacro of a parent macro it is one
     * more than the parent's depth.
     */
    init {
        this.packageMetadata = packageMetadata
        this.packageDeclarations = packageDeclarations
        this.parent = parent
        this.generatorName = generatorName
        this.buildFileLocation = buildFileLocation
        this.parentCallStack = parentCallStack
        this.macroClass = macroClass
        com.google.common.base.Preconditions.checkArgument(sameNameDepth > 0)
        this.sameNameDepth = sameNameDepth
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        // TODO(https://github.com/bazelbuild/bazel/issues/26128): consider comparing digests instead.
        return obj is MacroInstance
                && super.equalsHelper(obj)
                && packageMetadata == obj.packageMetadata
                && packageDeclarations == obj.packageDeclarations
                && parent == obj.parent
                && generatorName == obj.generatorName
                && buildFileLocation == obj.buildFileLocation
                && parentCallStack == obj.parentCallStack
                && macroClass == obj.macroClass
                && sameNameDepth == obj.sameNameDepth
    }

    override fun hashCode(): Int {
        return (super.hashCodeHelper()
                + HashCodes.MULTIPLIER
                * HashCodes.hashObjects(
            packageMetadata,
            packageDeclarations,
            parent,
            generatorName,
            buildFileLocation,
            parentCallStack,
            macroClass,
            sameNameDepth
        ))
    }

    override fun getPackageMetadata(): com.google.devtools.build.lib.packages.Package.Metadata {
        return packageMetadata
    }

    override fun getPackageDeclarations(): Declarations? {
        return packageDeclarations
    }

    /**
     * Returns the macro instance that instantiated this one, or null if this was created directly
     * during BUILD evaluation.
     */
    // TODO(bazel-team): Consider merging into getDeclaringMacro().
    // TODO(https://github.com/bazelbuild/bazel/issues/26128): Avoid new uses of this method; it is
    // hostile to change pruning for lazy macro expansion. Replace with a method that either returns
    // the parent identifier, or takes a context argument that allows retrieving the parent by id.
    fun getParent(): MacroInstance? {
        return parent
    }

    // TODO(https://github.com/bazelbuild/bazel/issues/26128): Avoid new uses of this method; it is
    // hostile to change pruning for lazy macro expansion. Replace with a method that either returns
    // the parent identifier, or takes a context argument that allows retrieving the parent by id.
    override fun getDeclaringMacro(): MacroInstance? {
        return parent
    }

    /**
     * Returns the location in the BUILD file at which this macro was created or its outermost
     * enclosing symbolic or legacy macro was called.
     */
    fun getBuildFileLocation(): net.starlark.java.syntax.Location? {
        return buildFileLocation
    }

    /**
     * Returns the value of the "name" parameter of the top-level call in a BUILD file which resulted
     * in this macro being instantiated.
     * 
     * 
     * This is either the "name" attribute of this macro's outermost symbolic macro ancestor, if it
     * was defined directly at the top level of a BUILD file; or the "name" parameter of the outermost
     * legacy macro wrapping it.
     * 
     * 
     * Null if this symbolic macro was instantiated as a result of a legacy macro call without a
     * "name" parameter made at the top level of a BUILD file.
     */
    fun getGeneratorName(): String? {
        return generatorName
    }

    /**
     * Returns the call stack of the Starlark thread that created this macro instance.
     * 
     * 
     * If this macro was instantiated in a BUILD file thread (as contrasted with a symbolic macro
     * thread), the call stack does not include the frame for the BUILD file top level, since it's
     * redundant with [.getBuildFileLocation].
     */
    fun getParentCallStack(): CallStack.Node? {
        return parentCallStack
    }

    /**
     * Returns the call stack of the Starlark thread that created this macro instance.
     * 
     * 
     * Requires reconstructing the call stack from a compact representation, so should only be
     * called when the full call stack is needed.
     */
    @com.google.common.annotations.VisibleForTesting
    fun reconstructParentCallStack(): com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> {
        val stack: com.google.common.collect.ImmutableList.Builder<net.starlark.java.eval.StarlarkThread.CallStackEntry?> =
            com.google.common.collect.ImmutableList.builder<net.starlark.java.eval.StarlarkThread.CallStackEntry?>()
        if (parent == null) {
            stack.add(
                net.starlark.java.eval.StarlarkThread.callStackEntry(
                    net.starlark.java.eval.StarlarkThread.TOP_LEVEL,
                    buildFileLocation
                )
            )
        }
        var node: CallStack.Node? = parentCallStack
        while (node != null) {
            stack.add(node.toCallStackEntry())
            node = node.next()
        }
        return stack.build()
    }

    /** Returns the [MacroClass] (i.e. schema info) that this instance parameterizes.  */
    fun getMacroClass(): MacroClass {
        return macroClass
    }

    /**
     * The depth of this macro instance in a chain of nested macros having the same name.
     * 
     * 
     * 1 for any macro that is not declared in a macro of the same name.
     * 
     * 
     * Used by [.getId].
     */
    fun getSameNameDepth(): Int {
        return sameNameDepth
    }

    /**
     * Returns the id of this macro instance. The id is the name, concatenated with `":n"` where
     * n is an integer distinguishing this from other macro instances of the same name in the package.
     * 
     * 
     * Within a package, two macro instances are not allowed to share the same name except when one
     * of them is the main submacro of the other. More generally, there may be a contiguous chain of
     * nested main submacros that all share the same name, but these may not share with any other
     * macro outside the chain. We allow this exception so that the build does not break if the rule
     * of a main target is refactored into a macro. The tradeoff of this design is that the name alone
     * is not enough to disambiguate between macros in the chain.
     * 
     * 
     * The number n is simply the depth of the macro in the chain of same-named macros, starting at
     * 1. For example, if we have a chain of macro expansions foo -> foo_bar -> foo_bar -> foo_bar ->
     * foo_bar_baz, then the ids of these macros are respectively "foo:1", "foo_bar:1", "foo_bar:2",
     * "foo_bar:3", "foo_bar_baz:1".
     * 
     * 
     * Note that ids only serve to canonically identify macro instances, and play no role in naming
     * or name conflict detection.
     */
    fun getId(): String {
        return getName() + ":" + sameNameDepth
    }

    override fun getDefaultVisibility(): RuleVisibility {
        return RuleVisibility.Companion.parseUnchecked(
            com.google.common.collect.ImmutableList.of<E?>(
                Label.createUnvalidated(
                    macroClass.getDefiningBzlLabel().getPackageIdentifier(), "__pkg__"
                )
            )
        )
    }

    /**
     * Returns the visibility of this macro instance, analogous to [Target.getActualVisibility].
     * 
     * 
     * This value will be observed as the `visibility` parameter of the implementation
     * function. It is not necessarily the same as the `visibility` value passed in when
     * instantiating the macro, since the latter needs processing to add the call site's location and
     * possibly apply the package's default visibility.
     * 
     * 
     * It can be assumed that the returned list satisfies [RuleVisibility.validate].
     */
    fun getActualVisibility(): com.google.common.collect.ImmutableList<Label?> {
        val visibility: MutableList<Label?> =
            com.google.common.base.Preconditions.checkNotNull<Any?>(getAttr("visibility")) as MutableList<Label?>
        return com.google.common.collect.ImmutableList.copyOf<Label?>(visibility)
    }

    /**
     * Returns the package containing the .bzl file from which this macro instance's macro class was
     * exported.
     * 
     * 
     * This is considered to be the place where the macro's code lives, and is used as the place
     * where a target is instantiated for the purposes of Macro-Aware Visibility.
     */
    fun getDefinitionPackage(): PackageIdentifier {
        return macroClass.getDefiningBzlLabel().getPackageIdentifier()
    }

    /**
     * Visits all labels appearing in non-implicit attributes of [Type.LabelClass.DEPENDENCY]
     * label type, i.e. ignoring nodep labels.
     * 
     * 
     * This is useful for checking whether a given label was passed as an input to this macro by
     * the caller, which in turn is needed in order to decide whether the caller delegated a
     * visibility privilege to us.
     */
    fun visitExplicitAttributeLabels(consumer: java.util.function.Consumer<Label?>) {
        for (attribute in macroClass.getAttributeProvider().getAttributes()) {
            val name: String = attribute.getName()
            val type: com.google.devtools.build.lib.packages.Type<*> = attribute.getType()
            if (name.startsWith("_")) {
                continue
            }
            if (type.getLabelClass() != LabelClass.DEPENDENCY) {
                continue
            }
            val value: Any? = getAttr(name, type)
            visitAttributeLabels(value, type, attribute, consumer)
        }
    }

    // Separate method needed to satisfy type system w.r.t. Type<T>.
    // `value` is either a T or SelectorList<T>.
    private fun <T> visitAttributeLabels(
        value: Any?,
        type: com.google.devtools.build.lib.packages.Type<T?>,
        attribute: com.google.devtools.build.lib.packages.Attribute?,
        consumer: java.util.function.Consumer<Label?>
    ) {
        if (value == null) {
            return
        }

        val visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor =
            com.google.devtools.build.lib.packages.Type.LabelVisitor { label: Label?, unusedAttribute: com.google.devtools.build.lib.packages.Attribute? ->
                if (label != null) {
                    consumer.accept(label)
                }
            }

        if (value is BuildType.SelectorList<*>) {
            val selectorList:  // safe by precondition assumption
                    BuildType.SelectorList<T?> = value as BuildType.SelectorList<T?>
            AggregatingAttributeMapper.Companion.visitLabelsInSelect<T?>(
                selectorList,
                attribute,
                type,
                visitor,  /* rule= */
                null,  // safe because late-bound defaults aren't a thing for macros
                /* includeKeys= */
                false,  /* includeValues= */
                true
            )
        } else {
            val castValue: T? = type.cast(value)
            type.visitLabels(visitor, castValue, attribute)
        }
    }

    override fun getAttributeProvider(): com.google.devtools.build.lib.packages.AttributeProvider? {
        return macroClass.getAttributeProvider()
    }

    override fun reportError(message: String?, eventHandler: EventHandler) {
        eventHandler.handle(Event.error(message))
    }

    override fun isRuleInstance(): Boolean {
        return false
    }

    override fun isRuleCreatedInMacro(): Boolean {
        return false
    }

    /** Returns a human-readable description of the macro suitable for debugging output.  */
    fun getShortDescription(): String? {
        return java.lang.String.format(
            "%smacro %s:%s defined by %s%%%s",
            if (macroClass.isFinalizer()) "finalizer " else "",
            packageMetadata.packageIdentifier.getCanonicalForm(),
            getName(),
            macroClass.getDefiningBzlLabel().getCanonicalForm(),
            macroClass.getName()
        )
    }

    /**
     * Logical tuple of the package and id within the package. Used to label the Starlark evaluation
     * environment.
     */
    @kotlin.jvm.JvmRecord
    internal data class UniqueId(packageId: PackageIdentifier?, id: String?) {
        val packageId: PackageIdentifier?
        val id: String?

        init {
            this.id = id
            this.packageId = packageId
            java.util.Objects.requireNonNull<Any?>(packageId, "packageId")
            java.util.Objects.requireNonNull<String?>(id, "id")
        }

        companion object {
            fun create(packageId: PackageIdentifier?, id: String?): UniqueId {
                return com.google.devtools.build.lib.packages.MacroInstance.UniqueId(packageId, id)
            }
        }
    }
}
