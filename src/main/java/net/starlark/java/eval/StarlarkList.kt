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
package net.starlark.java.eval

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.AbstractCollection

/**
 * A StarlarkList is a mutable finite sequence of values.
 * 
 * 
 * Starlark operations on lists, including element update and the `append`, `insert`,
 * and `extend` methods, may insert arbitrary Starlark values as list elements, regardless of
 * the type argument used to reference to the list from Java code. Therefore, as long as a list is
 * mutable, Java code should refer to it only through a type such as `StarlarkList<Object>` or
 * `StarlarkList<?>` to avoid undermining the type-safety of the Java application. Once the
 * list becomes frozen, it is safe to [.cast] it to a more specific type that accurately
 * reflects its elements, such as `StarlarkList<String>`.
 * 
 * 
 * The following List methods, by inheriting their implementations from AbstractList, are
 * effectively disabled. Use the corresponding methods with "element" in their name; they may report
 * mutation failure by throwing a checked exception.
 * 
 * <pre>
 * boolean add(E)                    -- use addElement
 * boolean remove(Object)            -- use removeElement
 * boolean addAll(Collection)        -- use addElements
 * boolean addAll(int, Collection)
 * boolean removeAll(Collection)     -- use removeElements
 * boolean retainAll(Collection)
 * void clear()                      -- use clearElements
 * E set(int, E)                     -- use setElementAt
 * void add(int, E)                  -- use addElementAt
 * E remove(int)                     -- use removeElementAt
</pre> * 
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "list", category = "core", doc = ("The built-in list type. Example list expressions:<br>"
            + "<pre class=language-python>x = [1, 2, 3]</pre>"
            + "Accessing elements is possible using indexing (starts from <code>0</code>):<br>"
            + "<pre class=language-python>e = x[1]   # e == 2</pre>"
            + "Lists support the <code>+</code> operator to concatenate two lists. Example:<br>"
            + "<pre class=language-python>x = [1, 2] + [3, 4]   # x == [1, 2, 3, 4]\n"
            + "x = [\"a\", \"b\"]\n"
            + "x += [\"c\"]            # x == [\"a\", \"b\", \"c\"]</pre>"
            + "Similar to strings, lists support slice operations:"
            + "<pre class=language-python>['a', 'b', 'c', 'd'][1:3]   # ['b', 'c']\n"
            + "['a', 'b', 'c', 'd'][::2]  # ['a', 'c']\n"
            + "['a', 'b', 'c', 'd'][3:0:-1]  # ['d', 'c', 'b']</pre>"
            + "Lists are mutable, as in Python.")
)
abstract class StarlarkList<E>  // Prohibit instantiation outside of package.
internal constructor() : AbstractCollection<E?>(), net.starlark.java.eval.Sequence<E?>,
    net.starlark.java.eval.StarlarkValue, net.starlark.java.eval.Mutability.Freezable, Comparable<StarlarkList<*>?> {
    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        // TODO(ilist@): store the type for non-homogeneous lists
        // Current implementation traverses the list and computes union of all elements - same as most
        // of the native calls. This is correct, but could be expensive. Proposed optimization is
        // to store and update list's type when elements are added to it.
        if (isEmpty()) {
            return if (mutability().isFrozen()) net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.NEVER) else net.starlark.java.syntax.Types.list(
                net.starlark.java.syntax.Types.ANY
            )
        }
        return net.starlark.java.syntax.Types.list(
            net.starlark.java.syntax.Types.union(
                stream().map<net.starlark.java.syntax.StarlarkType?>(java.util.function.Function { e: E? ->
                    net.starlark.java.eval.Starlark.Companion.getStarlarkType(
                        e,
                        semantics
                    )
                })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<net.starlark.java.syntax.StarlarkType?>())
            )
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkHashable() {
        // Even a frozen list is unhashable.
        throw net.starlark.java.eval.Starlark.Companion.errorf("unhashable type: 'list'")
    }

    /** An associated convenience type for LazyImmutableStarlarkLists  */
    interface SerializableListSupplier<T> : java.util.function.Supplier<com.google.common.collect.ImmutableList<T?>?>,
        java.io.Serializable

    abstract fun elems(): Array<Any?>

    @javax.annotation.Nonnull
    override fun iterator(): MutableIterator<E?> {
        return net.starlark.java.eval.StarlarkList.Itr()
    }

    override fun compareTo(that: StarlarkList<*>): Int {
        return net.starlark.java.eval.Sequence.Companion.compare(this, that)
    }

    override fun equals(that: Any?): Boolean {
        // This slightly violates the java.util.List equivalence contract
        // because it considers the class, not just the elements.
        // This is needed because in Starlark lists are never equal to tuples, however in Java they both
        // implement List interface.
        return this === that
                || (that is StarlarkList<*> && net.starlark.java.eval.Sequence.Companion.sameElems(this, that))
    }

    override fun hashCode(): Int {
        // Hash the elements elems[0:size].
        var result = 1
        val size: Int = size()
        val elems = elems()
        for (i in 0..<size) {
            result = 31 * result + elems[i]!!.hashCode()
        }
        return 6047 + 4673 * result
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.printList(this, "[", ", ", "]", semantics)
    }

    // TODO(adonovan): StarlarkValue has 3 String methods yet still we need this fourth. Why?
    override fun toString(): String {
        return net.starlark.java.eval.Starlark.Companion.repr(
            this,
            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
        )
    }

    /** Returns a new StarlarkList containing n consecutive repeats of this tuple.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun repeat(
        n: net.starlark.java.eval.StarlarkInt,
        mutability: net.starlark.java.eval.Mutability?
    ): StarlarkList<E?>? {
        if (n.signum() <= 0) {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<E?>(
                mutability,
                net.starlark.java.eval.StarlarkList.Companion.EMPTY_ARRAY
            )
        }

        val ni: Int = n.toInt("repeat")
        val size: Int = size()
        val sz = ni.toLong() * size
        if (sz > net.starlark.java.eval.StarlarkList.Companion.MAX_ALLOC) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("excessive repeat (%d * %d elements)", size, ni)
        }
        val res = arrayOfNulls<Any>(sz.toInt())
        for (i in 0..<ni) {
            java.lang.System.arraycopy(elems(), 0, res, i * size, size)
        }
        return net.starlark.java.eval.StarlarkList.Companion.wrap<E?>(mutability, res)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getSlice(mu: net.starlark.java.eval.Mutability?, start: Int, stop: Int, step: Int): StarlarkList<E?>? {
        val indices: net.starlark.java.eval.RangeList = net.starlark.java.eval.RangeList(start, stop, step)
        val n: Int = indices.size()
        val res = arrayOfNulls<Any>(n)
        if (step == 1) { // common case
            java.lang.System.arraycopy(elems(), indices.at(0), res, 0, n)
        } else {
            val elems = elems()
            for (i in 0..<n) {
                res[i] = elems[indices.at(i)]
            }
        }
        return net.starlark.java.eval.StarlarkList.Companion.wrap<E?>(mu, res)
    }

    /**
     * Appends an element to the end of the list, after validating that mutation is allowed.
     * 
     * @param element the element to add
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun addElement(element: E?)

    /**
     * Inserts an element at a given position to the list.
     * 
     * @param index the new element's index
     * @param element the element to add
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun addElementAt(index: Int, element: E?)

    /**
     * Appends all the elements to the end of the list.
     * 
     * @param elements the elements to add
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun addElements(elements: Iterable<out E?>?)

    /**
     * Removes the element at a given index. The index must already have been validated to be in
     * range.
     * 
     * @param index the index of the element to remove
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun removeElementAt(index: Int)

    /**
     * Sets the position at the given index to contain the given value. Precondition: `0 <= index < size()`.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun setElementAt(index: Int, value: E?)

    @net.starlark.java.annot.StarlarkMethod(
        name = "remove",
        doc = ("Removes the first item from the list whose value is x. "
                + "It is an error if there is no such item."),
        parameters = [net.starlark.java.annot.Param(name = "x", doc = "The object to remove.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun removeElement(x: E?) {
        val size: Int = size()
        val elems = elems()
        for (i in 0..<size) {
            if (elems[i] == x) {
                removeElementAt(i)
                return
            }
        }
        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "item %s not found in list",
            net.starlark.java.eval.Starlark.Companion.repr(
                x,
                net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
            )
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "append",
        doc = "Adds an item to the end of the list.",
        parameters = [net.starlark.java.annot.Param(name = "item", doc = "Item to add at the end.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun append(item: E?) {
        addElement(item)
    }

    @net.starlark.java.annot.StarlarkMethod(name = "clear", doc = "Removes all the elements of the list.")
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun clearElements()

    @net.starlark.java.annot.StarlarkMethod(
        name = "insert", doc = "Inserts an item at a given position.", parameters = [net.starlark.java.annot.Param(
            name = "index", doc = ("The index the item will be at after insertion. If the index is out of range, it's"
                    + " transformed into an effective index in the range from 0 to the list's"
                    + " previous length, inclusive, in the same manner as for the start index of a"
                    + " slice operator.")
        ), net.starlark.java.annot.Param(name = "item", doc = "The item.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun insert(index: net.starlark.java.eval.StarlarkInt, item: E?) {
        addElementAt(net.starlark.java.syntax.SyntaxUtils.toSliceBound(index.toInt("index"), size()), item) // unchecked
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "extend",
        doc = "Adds all items to the end of the list.",
        parameters = [net.starlark.java.annot.Param(name = "items", doc = "Items to add at the end.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun extend(items: net.starlark.java.eval.StarlarkIterable<out E?>?) {
        addElements(items)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "index",
        doc = ("Returns the index in the list of the first item whose value is x. It is an error if"
                + " there is no such item. If <code>start</code> and <code>end</code> are given,"
                + " they restrict the range searched in the same manner as slicing."),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            doc = "The object to search."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class)],
            defaultValue = "unbound",
            doc = "The start index of the list portion to inspect."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class)],
            defaultValue = "unbound",
            doc = "The end index of the list portion to inspect."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun index(x: E?, start: Any?, end: Any?): Int {
        val size: Int = size()
        val elems = elems()
        var i =
            if (start === net.starlark.java.eval.Starlark.Companion.UNBOUND)
                0
            else
                net.starlark.java.syntax.SyntaxUtils.toSliceBound(
                    net.starlark.java.eval.Starlark.Companion.toInt(
                        start,
                        "start"
                    ), size
                )
        val j =
            if (end === net.starlark.java.eval.Starlark.Companion.UNBOUND) size else net.starlark.java.syntax.SyntaxUtils.toSliceBound(
                net.starlark.java.eval.Starlark.Companion.toInt(end, "end"),
                size
            )
        while (i < j) {
            if (elems[i] == x) {
                return i
            }
            i++
        }
        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "item %s not found in list",
            net.starlark.java.eval.Starlark.Companion.repr(
                x,
                net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
            )
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "pop",
        doc = ("Removes the item at the given position in the list, and returns it. "
                + "If no <code>index</code> is specified, "
                + "it removes and returns the last item in the list."),
        parameters = [net.starlark.java.annot.Param(name = "i", defaultValue = "-1", doc = "The index of the item.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun pop(arg: net.starlark.java.eval.StarlarkInt): E? {
        val size: Int = size()
        val elems// safe by specification
                = elems() as Array<E?>
        val index: Int = net.starlark.java.eval.EvalUtils.getSequenceIndex(arg.toInt("i"), size)
        val result = elems[index]
        removeElementAt(index)
        return result
    }

    /**
     * Mutates this list in-place to reduce memory usage, and returns an optimized list (which might
     * be the same as this instance).
     * 
     * 
     * This operation is not protected by the mutability mechanism. It is the caller's
     * responsibility to ensure this list is not concurrently accessed during this method's execution.
     * 
     * 
     * The mutated list and the returned list are both equivalent to the original list.
     * 
     * 
     * The mutability must be frozen prior to calling this method.
     */
    open fun unsafeOptimizeMemoryLayout(): StarlarkList<E?>? {
        return this
    }

    private inner class Itr : MutableIterator<E?> {
        private var cursor = 0

        override fun hasNext(): Boolean {
            return cursor != size()
        }

        override fun next(): E? {
            try {
                val i = cursor
                val next: E? = get(i)
                cursor = i + 1
                return next
            } catch (e: java.lang.IndexOutOfBoundsException) {
                throw java.util.NoSuchElementException(e.getMessage())
            }
        }
    }

    // the following List methods are deliberately left unsupported for now, but could be implemented
    // if the need ever arises
    @javax.annotation.Nonnull
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E?>? {
        throw java.lang.UnsupportedOperationException()
    }

    @javax.annotation.Nonnull
    override fun listIterator(): MutableListIterator<E?>? {
        throw java.lang.UnsupportedOperationException()
    }

    @javax.annotation.Nonnull
    override fun listIterator(index: Int): MutableListIterator<E?>? {
        throw java.lang.UnsupportedOperationException()
    }

    override fun lastIndexOf(o: Any?): Int {
        throw java.lang.UnsupportedOperationException()
    }

    override fun indexOf(o: Any?): Int {
        throw java.lang.UnsupportedOperationException()
    }

    override fun set(index: Int, element: E?): E? {
        throw java.lang.UnsupportedOperationException()
    }

    override fun add(index: Int, element: E?) {
        throw java.lang.UnsupportedOperationException()
    }

    override fun remove(index: Int): E? {
        throw java.lang.UnsupportedOperationException()
    }

    override fun addAll(index: Int, @javax.annotation.Nonnull c: MutableCollection<out E?>): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    companion object {
        fun getAssociatedTypeConstructor(): net.starlark.java.syntax.TypeConstructor {
            return net.starlark.java.syntax.Types.LIST_CONSTRUCTOR
        }

        // It's always possible to overeat in small bites but we'll
        // try to stop someone swallowing the world in one gulp.
        val MAX_ALLOC: Int = 1 shl 30

        val EMPTY_ARRAY: Array<Any?> = arrayOf<Any?>()

        /**
         * Takes ownership of the supplied array of class Object[].class, and returns a new StarlarkList
         * instance that initially wraps the array. The caller must not subsequently modify the array, but
         * the StarlarkList instance may do so.
         */
        fun <T> wrap(mutability: net.starlark.java.eval.Mutability?, elems: Array<Any?>): StarlarkList<T?>? {
            if (mutability == null || mutability.isFrozen()) {
                return when (elems.size) {
                    0 -> net.starlark.java.eval.StarlarkList.Companion.empty<T?>()
                    1 -> net.starlark.java.eval.ImmutableSingletonStarlarkList<T?>(elems[0])
                    else -> net.starlark.java.eval.RegularImmutableStarlarkList<T?>(elems)
                }
            }
            return net.starlark.java.eval.MutableStarlarkList<T?>(mutability, elems)
        }

        /** Returns an empty frozen list of the desired type.  */
        @kotlin.jvm.JvmStatic
        fun <T> empty(): StarlarkList<T?> {
            return net.starlark.java.eval.RegularImmutableStarlarkList.Companion.EMPTY as StarlarkList<T?>
        }

        /** Returns a new, empty list with the specified Mutability.  */
        fun <T> newList(mutability: net.starlark.java.eval.Mutability?): StarlarkList<T?>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<T?>(
                mutability,
                net.starlark.java.eval.StarlarkList.Companion.EMPTY_ARRAY
            )
        }

        /**
         * Returns a `StarlarkList` whose items are given by an iterable and which has the given
         * [Mutability]. If `mutability` is null, the list is immutable.
         */
        fun <T> copyOf(
            mutability: net.starlark.java.eval.Mutability?, elems: Iterable<out T?>
        ): StarlarkList<T?>? {
            if (mutability == null && elems is StarlarkList<*>
                && (elems as StarlarkList<*>).isImmutable()
            ) {
                val list = elems as StarlarkList<T?> // safe
                return list
            }

            val array: Array<Any?> = com.google.common.collect.Iterables.toArray<Any?>(elems, Any::class.java)
            net.starlark.java.eval.StarlarkList.Companion.checkElemsValid(array)
            return net.starlark.java.eval.StarlarkList.Companion.wrap<T?>(mutability, array)
        }

        private fun checkElemsValid(elems: Array<Any?>) {
            for (elem in elems) {
                net.starlark.java.eval.Starlark.Companion.checkValid<Any?>(elem)
            }
        }

        /**
         * Returns an immutable list with the given elements. Equivalent to `copyOf(null, elems)`.
         */
        fun <T> immutableCopyOf(elems: Iterable<out T?>): StarlarkList<T?>? {
            return net.starlark.java.eval.StarlarkList.Companion.copyOf<T?>(null, elems)
        }

        /**
         * Creates an immutable [StarlarkList] with lazily supplied elements.
         * 
         * 
         * The given supplier is not invoked until the list is accessed and is invoked at most once.
         * This can be used to create a [StarlarkList] while deferring an expensive computation
         * until the list is actually accessed.
         */
        fun <T> lazyImmutable(supplier: SerializableListSupplier<T?>?): StarlarkList<T?> {
            return net.starlark.java.eval.LazyImmutableStarlarkList<T?>(supplier)
        }

        /**
         * Returns a `StarlarkList` with the given items and the [Mutability]. If `mutability` is null, the list is immutable.
         */
        fun <T> of(mutability: net.starlark.java.eval.Mutability?, vararg elems: T?): StarlarkList<T?>? {
            if (elems.size == 0) {
                return net.starlark.java.eval.StarlarkList.Companion.newList<T?>(mutability)
            }

            net.starlark.java.eval.StarlarkList.Companion.checkElemsValid(elems)
            return net.starlark.java.eval.StarlarkList.Companion.wrap<T?>(
                mutability,
                java.util.Arrays.copyOf<Any?, T?>(elems, elems.size, Array<Any>::class.java)
            )
        }

        /** Returns an immutable `StarlarkList` with the given items.  */
        fun <T> immutableOf(vararg elems: T?): StarlarkList<T?>? {
            net.starlark.java.eval.StarlarkList.Companion.checkElemsValid(elems)
            return net.starlark.java.eval.StarlarkList.Companion.wrap<T?>(
                null,
                java.util.Arrays.copyOf<Any?, T?>(elems, elems.size, Array<Any>::class.java)
            )
        }

        /**
         * Returns a new `StarlarkList` that is the concatenation of two `StarlarkList`s. The
         * new list will have the given [Mutability].
         * 
         * @throws EvalException if the resulting list would be too large
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun <T> concat(
            x: StarlarkList<out T?>, y: StarlarkList<out T?>, mutability: net.starlark.java.eval.Mutability?
        ): StarlarkList<T?>? {
            val xsize: Int = x.size()
            val ysize: Int = y.size()
            val res = arrayOfNulls<Any>(
                net.starlark.java.eval.StarlarkList.Companion.addSizesAndFailIfExcessive(
                    xsize,
                    ysize
                )
            )
            java.lang.System.arraycopy(x.elems(), 0, res, 0, xsize)
            java.lang.System.arraycopy(y.elems(), 0, res, xsize, ysize)
            return net.starlark.java.eval.StarlarkList.Companion.wrap<T?>(mutability, res)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        protected fun addSizesAndFailIfExcessive(xsize: Int, ysize: Int): Int {
            val sum = xsize + ysize
            if (sum < 0 || sum > net.starlark.java.eval.StarlarkList.Companion.MAX_ALLOC) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "excessive capacity requested (%d + %d elements)",
                    xsize,
                    ysize
                )
            }
            return sum
        }
    }
}
