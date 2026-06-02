// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.collect.nestedset.Depset
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions
import com.google.devtools.build.zip.ZipFileEntry.getName

/**
 * A Depset is a Starlark value that wraps a [NestedSet].
 * 
 * 
 * A NestedSet has a type parameter that describes, at compile time, the elements of the set. By
 * contrast, a Depset has a value, [.getElementType], that describes the elements during
 * execution. This type symbol permits the element type of a Depset value to be queried, after the
 * type parameter has been erased, without visiting each element of the often-vast data structure.
 * 
 * 
 * For depsets constructed by Starlark code, the element type of a non-empty `Depset` is
 * determined by its first element. All elements must have the same type. An empty depset has type
 * `ElementType.EMPTY`, and may be combined with any other depset.
 * 
 * 
 * Every call to `depset` returns a distinct instance equal to no other.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "depset", category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN, doc = """
<p>A specialized data structure that supports efficient merge operations and has a defined traversal
order. Commonly used for accumulating data from transitive dependencies in rules and aspects. For
more information see <a href="/extending/depsets">here</a>.

<p>The elements of a depset must be hashable and all of the same type (as defined by the built-in
<a href="../globals/all#type"><code>type(x)</code></a> function), but depsets are not simply hash
sets and do not support fast membership tests. If you need a general set datatype, use the core
<a href="../core/set">Starlark set</a> type (available since Bazel 8.1); if your .bzl file needs to
be compatible with older Bazel releases, you can simulate a set by using a dictionary where all keys
map to <code>True</code>.

<p>When tested for truth (that is, when used in a Boolean context such as <code>if d:</code> where
<code>d</code> is a depset), a depset is True if and only if it is non-empty; this check is an O(1)
operation.

<p>Depsets are immutable. They should be created using their
<a href=${'"'}../globals/bzl.html#depset">constructor function</a> and merged or augmented with other
depsets via the <code>transitive</code> argument.

<p>The <code>order</code> parameter determines the kind of traversal that is done to convert the
depset to an iterable. There are four possible values:

<ul>
  <li>
    <code>"default"</code> (formerly <code>"stable"</code>): Order is unspecified (but
    deterministic).
  </li>
  <li>
    <code>"postorder"</code> (formerly <code>"compile"</code>): A left-to-right post-ordering.
    Precisely, this recursively traverses all children leftmost-first, then the direct elements
    leftmost-first.
  </li>
  <li>
    <code>"preorder"</code> (formerly <code>"naive_link"</code>): A left-to-right pre-ordering.
    Precisely, this traverses the direct elements leftmost-first, then recursively traverses the
    children leftmost-first.
  </li>
  <li>
    <code>"topological"</code> (formerly <code>"link"</code>): A topological ordering from the root
    down to the leaves. There is no left-to-right guarantee.
  </li>
</ul>

<p>Two depsets may only be merged if either both depsets have the same order, or one of them has
<code>"default"</code> order. In the latter case the resulting depset's order will be the same as
the other's order.

<p>Depsets may contain duplicate values but these will be suppressed when iterating (using
<a href="#to_list"><code>to_list()</code></a>). Duplicates may interfere with the ordering
semantics.

""".trimIndent()
)
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class Depset internal constructor(elemClass: java.lang.Class<*>?, set: NestedSet<*>) :
    net.starlark.java.eval.StarlarkValue, net.starlark.java.eval.Debug.ValueWithDebugAttributes {
    // The value of elemClass is set to getTypeClass(actualElemClass)
    // null is used for empty Depset-s
    private val elemClass: java.lang.Class<*>?
    private val set: NestedSet<*>

    init {
        this.elemClass = elemClass
        this.set = set
    }

    /**
     * Returns the embedded [NestedSet], first asserting that its elements are instances of the
     * given class, which must be a valid Starlark type (or Object.class). Only the top-level class is
     * verified.
     * 
     * 
     * If you do not specifically need the `NestedSet` and you are going to flatten it
     * anyway, prefer [.toList] to make your intent clear.
     * 
     * @param type a [Class] representing the expected type of the elements
     * @return the `NestedSet`, with the appropriate generic type
     * @throws TypeException if the type does not accurately describe all elements
     */
    @Throws(TypeException::class)
    fun <T> getSet(type: java.lang.Class<T?>): NestedSet<T?> {
        val elemType = this.elementType
        if (!set.isEmpty() && !elemType.canBeCastTo(type)) {
            throw TypeException(
                java.lang.String.format(
                    "got a depset of '%s', expected a depset of '%s'",
                    elemType, net.starlark.java.eval.Starlark.classType(type)
                )
            )
        }
        val res: NestedSet<T?> = set as NestedSet<T?>
        return res
    }

    /**
     * Returns the embedded [NestedSet] without asserting the type of its elements---and thus
     * cannot fail. To validate the type of elements in the set, call [.getSet] instead.
     */
    fun getSet(): NestedSet<*> {
        return set
    }

    /**
     * Returns an ImmutableList containing the set elements, asserting that each element is an
     * instance of class `type`. Requires traversing the entire graph of the underlying
     * NestedSet.
     * 
     * @param type a [Class] representing the expected type of the elements, which must be a
     * valid Starlark type (or Object.class)
     * @throws TypeException if the type does not accurately describe all elements
     */
    @Throws(TypeException::class)
    fun <T> toList(type: java.lang.Class<T?>): com.google.common.collect.ImmutableList<T?>? {
        return getSet<T?>(type).toList()
    }

    /**
     * Returns an ImmutableList containing the set elements. Requires traversing the entire graph of
     * the underlying NestedSet.
     */
    fun toList(): com.google.common.collect.ImmutableList<*>? {
        return set.toList()
    }

    val isEmpty: Boolean
        get() = set.isEmpty()

    override fun truth(): Boolean {
        return !set.isEmpty()
    }

    val elementType: ElementType
        get() {
            if (elemClass == null) {
                return com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.EMPTY
            }
            return com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.of(elemClass)
        }

    val elementClass: java.lang.Class<*>?
        get() = elemClass

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
    }

    val order: com.google.devtools.build.lib.collect.nestedset.Order
        get() = set.getOrder()

    val isImmutable: Boolean
        get() = true

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("depset(")
        printer.printList(set.toList(), "[", ", ", "]", semantics)
        val order: com.google.devtools.build.lib.collect.nestedset.Order = this.order
        if (order != com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER) {
            printer.append(", order = ")
            printer.repr(order.getStarlarkName(), semantics)
        }
        printer.append(")")
    }

    val debugAttributes: com.google.common.collect.ImmutableList<net.starlark.java.eval.Debug.DebugAttribute?>
        get() = com.google.common.collect.ImmutableList.of<net.starlark.java.eval.Debug.DebugAttribute?>(
            net.starlark.java.eval.Debug.DebugAttribute("order", this.order.getStarlarkName()),
            net.starlark.java.eval.Debug.DebugAttribute("directs", set.getLeaves()),
            net.starlark.java.eval.Debug.DebugAttribute("transitives", set.getNonLeaves())
        )

    @net.starlark.java.annot.StarlarkMethod(
        name = "to_list", doc = ("Returns a list of the elements, without duplicates, in the depset's traversal order. "
                + "Note that order is unspecified (but deterministic) for elements that were added "
                + "more than once to the depset. Order is also unspecified for <code>\"default\""
                + "</code>-ordered depsets, and for elements of child depsets whose order differs "
                + "from that of the parent depset. The list is a copy; modifying it has no effect "
                + "on the depset and vice versa."), useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun toListForStarlark(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<Any?>? {
        return net.starlark.java.eval.StarlarkList.copyOf<Any?>(thread.mutability(), this.toList())
    }

    /** An exception thrown when validation fails on the type of elements of a nested set.  */
    class TypeException internal constructor(message: String?) : java.lang.Exception(message)

    /**
     * A ElementType represents the type of elements in a Depset.
     * 
     * 
     * Call [.of] to obtain the ElementType for a Java class. The class must be a legal
     * Starlark value class, such as String, Boolean, or a subclass of StarlarkValue.
     * 
     * 
     * An element type represents only the top-most type identifier of an element value. That is,
     * an element type may represent "list" but not "list of string".
     */
    // TODO(adonovan): consider deleting this class entirely and using Class directly.
    // Depset.getElementType would need to document "null means empty",
    // but almost every caller just wants to stringify it.
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    class ElementType private constructor(cls: java.lang.Class<*>?) {
        private val cls: java.lang.Class<*>? // null => empty depset

        init {
            this.cls = cls
        }

        override fun toString(): String {
            return if (cls == null) "empty" else net.starlark.java.eval.Starlark.classType(cls)
        }

        // Called by precondition check of Depset.getSet conversion.
        //
        // Fails if cls is neither Object.class nor a valid Starlark value class.
        // One might expect that if a ElementType canBeCastTo Integer, then it can
        // also be cast to Number, but this is not the case: getTypeClass throws IAE if
        // passed a supertype of a Starlark class that is not itself a valid Starlark
        // value class. As a special case, Object.class is permitted,
        // and represents "any value".
        //
        // This leads one to wonder why canBeCastTo calls getTypeClass at all.
        // The answer is that it is yet another hack to support starlarkbuildapi.
        // For example, (FileApi).canBeCastTo(Artifact.class) reports true,
        // because a Depset whose elements are nominally of type FileApi is assumed
        // to actually contain only elements of class Artifact. If there were
        // a second implementation of FileAPI, the operation would be unsafe.
        //
        // TODO(adonovan): once starlarkbuildapi has been deleted, eliminate the
        // getTypeClass calls here and in ElementType.of, and remove the special
        // case for Object.class since isAssignableFrom will allow any supertype
        // of the element type, whether or not it is a Starlark value class.
        private fun canBeCastTo(cls: java.lang.Class<*>): Boolean {
            return this.cls == null || cls == Any::class.java // historical exception
                    || com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.getTypeClass(cls)
                .isAssignableFrom(this.cls)
        }

        override fun hashCode(): Int {
            return if (cls == null) 0 else cls.hashCode()
        }

        override fun equals(that: Any?): Boolean {
            return that is ElementType && this.cls == that.cls
        }

        companion object {
            /** The element type of the empty depset.  */
            @kotlin.jvm.JvmField
            val EMPTY: ElementType = com.google.devtools.build.lib.collect.nestedset.Depset.ElementType(null)

            /** The element type of a depset of strings.  */
            @kotlin.jvm.JvmField
            val STRING: ElementType = com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.of(
                String::class.java
            )

            /**
             * Returns the type symbol for a depset whose elements are instances of `cls`.
             * 
             * @throws IllegalArgumentException if `cls` is not a legal Starlark value class.
             */
            fun of(cls: java.lang.Class<*>): ElementType {
                return com.google.devtools.build.lib.collect.nestedset.Depset.ElementType(
                    com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.getTypeClass(
                        cls
                    )
                )
            }

            // If cls is a valid Starlark type, returns the canonical Java class for that
            // Starlark type (which may be an ancestor); otherwise throws IllegalArgumentException.
            //
            // If cls is String or Boolean, cls is returned. Otherwise, the
            // @StarlarkBuiltin-annotated ancestor of cls is returned if it exists (it may
            // be cls itself), or cls is returned if there is no such ancestor.
            //
            // TODO(adonovan): consider publishing something like this as Starlark.typeClass.
            private fun getTypeClass(cls: java.lang.Class<*>): java.lang.Class<*> {
                if (cls == String::class.java || cls == Boolean::class.java) {
                    return cls // fast path for common case
                }
                if (cls == net.starlark.java.eval.StarlarkInt::class.java) {
                    // StarlarkInt doesn't currently have a StarlarkBuiltin annotation
                    // because stardoc can't handle a type and a function with the same name.
                    return cls
                }
                val superclass: java.lang.Class<*>? =
                    net.starlark.java.annot.StarlarkAnnotations.getParentWithStarlarkBuiltin(cls)
                if (superclass != null) {
                    return superclass
                }
                require(net.starlark.java.eval.StarlarkValue::class.java.isAssignableFrom(cls)) {
                    ("invalid Depset element type: "
                            + cls.getName()
                            + " is not a subclass of StarlarkValue")
                }
                return cls
            }
        }
    }

    // Delegate equality to the underlying NestedSet. Otherwise, it's possible to create multiple
    // Depset instances wrapping the same NestedSet that aren't equal to each other.
    override fun hashCode(): Int {
        return set.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is Depset && set == other.set
    }

    /** The user-facing API to the `depset` callable.  */
    @com.google.devtools.build.docgen.annot.GlobalMethods(environment = [com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BUILD, com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BZL])
    class DepsetLibrary private constructor() {
        @net.starlark.java.annot.StarlarkMethod(
            name = "depset",
            doc = ("Creates a <a href=\"../builtins/depset.html\">depset</a>. The <code>direct</code>"
                    + " parameter is a list of direct elements of the depset, and"
                    + " <code>transitive</code> parameter is a list of depsets whose elements become"
                    + " indirect elements of the created depset. The order in which elements are"
                    + " returned when the depset is converted to a list is specified by the"
                    + " <code>order</code> parameter. See the <a"
                    + " href=\"https://bazel.build/extending/depsets\">Depsets overview</a> for more"
                    + " information.\n" //
                    + "<p>All elements (direct and indirect) of a depset must be of the same type, as"
                    + " obtained by the expression <a"
                    + " href=\"../globals/all#type\"><code>type(x)</code></a>.\n" //
                    + "<p>Because a hash-based set is used to eliminate duplicates during iteration,"
                    + " all elements of a depset should be hashable. However, this invariant is not"
                    + " currently checked consistently in all constructors. Use the"
                    + " --incompatible_always_check_depset_elements flag to enable consistent"
                    + " checking; this will be the default behavior in future releases;  see <a"
                    + " href='https://github.com/bazelbuild/bazel/issues/10313'>Issue 10313</a>.\n" //
                    + "<p>In addition, elements must currently be immutable, though this restriction"
                    + " will be relaxed in future.\n" //
                    + "<p> The order of the created depset should be <i>compatible</i> with the order"
                    + " of its <code>transitive</code> depsets. <code>\"default\"</code> order is"
                    + " compatible with any other order, all other orders are only compatible with"
                    + " themselves."),
            parameters = [net.starlark.java.annot.Param(
                name = "direct",
                defaultValue = "None",
                named = true,
                allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.Sequence::class), net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.NoneType::class
                )],
                doc = "A list of <i>direct</i> elements of a depset. "
            ), net.starlark.java.annot.Param(
                name = "order", defaultValue = "\"default\"", doc = "The traversal strategy for the new depset. See "
                        + "<a href=\"../builtins/depset.html\">here</a> for the possible values.", named = true
            ), net.starlark.java.annot.Param(
                name = "transitive",
                named = true,
                positional = false,
                allowedTypes = [net.starlark.java.annot.ParamType(
                    type = net.starlark.java.eval.Sequence::class,
                    generic1 = Depset::class
                ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)],
                doc = "A list of depsets whose elements will become indirect elements of the depset.",
                defaultValue = "None"
            )],
            useStarlarkThread = true
        )
        @Throws(net.starlark.java.eval.EvalException::class)
        fun depset(
            direct: Any?, orderString: String?, transitive: Any?, thread: net.starlark.java.eval.StarlarkThread
        ): Depset {
            return depset(orderString, direct, transitive, thread.getSemantics())
        }

        companion object {
            val INSTANCE: DepsetLibrary = DepsetLibrary()
        }
    }

    companion object {
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkElement(x: Any?, strict: Boolean) {
            // Historically the requirement for a depset element was isImmutable(x).
            // However, this check is neither necessary not sufficient.
            // It is unnecessary because elements need only be hashable,
            // as with dicts, whose keys may be mutable so long as mutations
            // don't affect the hash code. (Elements of a NestedSet must be
            // hashable because a hash-based set is used to de-duplicate
            // elements during iteration.)
            // And it is insufficient because some values are immutable
            // but not Starlark-hashable, such as frozen lists.
            // NestedSet calls its hashCode method regardless.
            //
            // TODO(adonovan): use this check instead:
            //   EvalUtils.checkHashable(x);
            // and delete the StarlarkValue.isImmutable and Starlark.isImmutable.
            // Unfortunately this is a breaking change because some users
            // construct depsets whose elements contain lists of strings,
            // which are Starlark-unhashable even if frozen.
            // TODO(adonovan): also remove StarlarkList.hashCode.
            if (strict && !net.starlark.java.eval.Starlark.isImmutable(x)) {
                // TODO(adonovan): improve this error message to include type(x).
                throw net.starlark.java.eval.Starlark.errorf("depset elements must not be mutable values")
            }

            // Even the looser regime forbids the top-level class to be list or dict.
            if (x is net.starlark.java.eval.StarlarkList<*> || x is net.starlark.java.eval.Dict<*, *>) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "depsets cannot contain items of type '%s'",
                    net.starlark.java.eval.Starlark.type(x)
                )
            }
        }

        /** Returns a Depset that wraps the specified NestedSet.  */ // TODO(adonovan): enforce that we never construct a Depset with a StarlarkType
        // that represents a non-Starlark type (e.g. NestedSet<PathFragment>).
        // One way to do that is to disallow constructing StarlarkTypes for classes
        // that would fail Starlark.valid; however remains the problem that
        // Object.class means "any Starlark value" but in fact allows any Java value.
        fun <T> of(elemClass: java.lang.Class<T?>?, set: NestedSet<T?>): Depset? {
            com.google.common.base.Preconditions.checkNotNull<java.lang.Class<T?>?>(
                elemClass,
                "elemClass cannot be null"
            )
            if (set.isEmpty()) {
                return set.getOrder().emptyDepset()
            }
            return Depset(
                com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.getTypeClass(
                    elemClass
                ), set
            )
        }

        /**
         * Checks that an element with `newElemType` is permitted in a set of `existingElemType`.
         * 
         * 
         * `existingElemType` may be null, corresponding to a set that does not yet have any
         * elements.
         * 
         * 
         * Both Class-es should be returned by getTypeClass(cls).
         * 
         * @return the (non-null) element type for a new set that adds the element
         * @throws EvalException if the new type is not permitted
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkType(
            existingElemType: java.lang.Class<*>?,
            newElemType: java.lang.Class<*>
        ): java.lang.Class<*> {
            // An initially empty depset (existingElemType == null) takes its type from the first element
            // added.
            // Otherwise, the types of the item and depset must match exactly.
            com.google.common.base.Preconditions.checkNotNull(newElemType)
            if (existingElemType == null || existingElemType == newElemType) {
                return newElemType
            }
            throw net.starlark.java.eval.Starlark.errorf(
                "cannot add an item of type '%s' to a depset of '%s'",
                com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.of(newElemType),
                com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.of(existingElemType)
            )
        }

        /**
         * Casts a non-null Starlark value `x` to a `Depset` and returns its underlying `NestedSet<T>` (where `type` reifies `T`).
         * 
         * 
         * It may be assumed that all elements of the depset are of type `T`, but no actual
         * iteration takes place.
         * 
         * 
         * If `x` is not a depset or does not have the right element type, this throws an `EvalException` whose message includes `what`, ideally a string literal, as a description
         * of the role of `x`.
         * 
         * @throws IllegalArgumentException if `type` is not a valid Starlark type (or Object.class)
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun <T> cast(x: Any, type: java.lang.Class<T?>, what: String?): NestedSet<T?> {
            if (x !is Depset) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "for %s, got %s, want a depset of %s",
                    what,
                    net.starlark.java.eval.Starlark.type(x),
                    net.starlark.java.eval.Starlark.classType(type)
                )
            }
            try {
                return x.getSet<T?>(type)
            } catch (ex: TypeException) {
                throw net.starlark.java.eval.Starlark.errorf("for '%s', %s", what, ex.getMessage())
            }
        }

        /** Like [.cast], but if x is None, returns an empty stable-order NestedSet.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun <T> noneableCast(x: Any, type: java.lang.Class<T?>, what: String?): NestedSet<T?>? {
            if (x === net.starlark.java.eval.Starlark.NONE) {
                return NestedSetBuilder.Companion.emptySet<T?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER)
            }
            return cast<T?>(x, type, what)
        }

        /** Create a Depset from the given direct and transitive components.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromDirectAndTransitive(
            order: com.google.devtools.build.lib.collect.nestedset.Order,
            direct: MutableList<Any>,
            transitive: MutableList<Depset>,
            strict: Boolean
        ): Depset {
            val builder: NestedSetBuilder<Any?> = NestedSetBuilder.Companion.newBuilder<Any?>(order)
            var type: java.lang.Class<*>? = null

            // Check direct elements' type is equal to elements already added.
            for (x in direct) {
                // Historically, checkElement was called only by some depset constructors,
                // but not this one, depset(direct=[x]).
                // This was a regrettable oversight that allowed users to put mutable values
                // such as lists into depsets, doubly so because we have just forced our
                // users to migrate away from the legacy constructor which applied the check.
                //
                // We are currently discovering and fixing existing violations, for example
                // marking the relevant Starlark types as immutable where appropriate
                // (e.g. ConfiguredTarget), but violations are numerous so we must
                // suppress the checkElement call below and reintroduce it as a breaking change.
                // See b/144992997 or github.com/bazelbuild/bazel/issues/10289.
                checkElement(x,  /*strict=*/strict)

                val xt: java.lang.Class<*> =
                    com.google.devtools.build.lib.collect.nestedset.Depset.ElementType.Companion.getTypeClass(x.getClass())
                type = checkType(type, xt)
            }
            builder.addAll(direct)

            // Add transitive sets, checking that type is equal to elements already added.
            for (x in transitive) {
                if (!x.isEmpty) {
                    type = checkType(type, x.elemClass)
                    if (!order.isCompatible(x.order)) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Order '%s' is incompatible with order '%s'",
                            order.getStarlarkName(), x.order.getStarlarkName()
                        )
                    }
                    builder.addTransitive(x.getSet())
                }
            }

            if (builder.isEmpty()) {
                return builder.getOrder().emptyDepset()
            }
            val set: NestedSet<Any?> = builder.build()
            // If the nested set was optimized to one of the transitive elements, reuse the corresponding
            // depset.
            for (x in transitive) {
                if (x.getSet() == set) {
                    return x
                }
            }

            return Depset(type, set)
        }

        /** Implementation of the `depset()` callable.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun depset(
            orderString: String?, direct: Any?, transitive: Any?, semantics: net.starlark.java.eval.StarlarkSemantics
        ): Depset {
            val order: com.google.devtools.build.lib.collect.nestedset.Order
            try {
                order = com.google.devtools.build.lib.collect.nestedset.Order.Companion.parse(orderString)
            } catch (ex: java.lang.IllegalArgumentException) {
                throw net.starlark.java.eval.EvalException(ex)
            }

            val result =
                fromDirectAndTransitive(
                    order,
                    net.starlark.java.eval.Sequence.noneableCast<Any?>(direct, Any::class.java, "direct"),
                    net.starlark.java.eval.Sequence.noneableCast<Depset?>(transitive, Depset::class.java, "transitive"),
                    semantics.getBool(BuildLanguageOptions.INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS)
                )

            // check depth limit
            val depth: Int = result.getSet().getApproxDepth()
            val limit: Int = semantics.get<Int?>(BuildLanguageOptions.NESTED_SET_DEPTH_LIMIT)
            if (depth > limit) {
                throw net.starlark.java.eval.Starlark.errorf("depset depth %d exceeds limit (%d)", depth, limit)
            }

            return result
        }
    }
}
