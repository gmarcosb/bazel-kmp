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
package net.starlark.java.eval

import java.util.AbstractSet
import java.util.Collections
import java.util.LinkedHashSet

/** A finite, mutable set of Starlark values.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "set", category = "core", doc = """
The built-in set type. A set is a mutable collection of unique values &ndash; the set's
<em>elements</em>. The <a href="../globals/all#type">type name</a> of a set is <code>"set"</code>.

<p>Sets provide constant-time operations to insert, remove, or check for the presence of a value.
Sets are implemented using a hash table, and therefore, just like keys of a
<a href="../dict">dictionary</a>, elements of a set must be hashable. A value may be used as an
element of a set if and only if it may be used as a key of a dictionary.

<p>Sets may be constructed using the <a href="../globals/all#set"><code>set()</code></a> built-in
function, which returns a new set containing the unique elements of its optional argument, which
must be an iterable. Calling <code>set()</code> without an argument constructs an empty set. Sets
have no literal syntax.

<p>The <code>in</code> and <code>not in</code> operations check whether a value is (or is not) in a
set:

<pre class=language-python>
s = set(["a", "b", "c"])
"a" in s  # True
"z" in s  # False
</pre>

<p>A set is iterable, and thus may be used as the operand of a <code>for</code> loop, a list
comprehension, and the various built-in functions that operate on iterables. Its length can be
retrieved using the <a href="../globals/all#len"><code>len()</code></a> built-in function, and the
order of iteration is the order in which elements were first added to the set:

<pre class=language-python>
s = set(["z", "y", "z", "y"])
len(s)       # prints 2
s.add("x")
len(s)       # prints 3
for e in s:
    print e  # prints "z", "y", "x"
</pre>

<p>A set used in Boolean context is true if and only if it is non-empty.

<pre class=language-python>
s = set()
"non-empty" if s else "empty"  # "empty"
t = set(["x", "y"])
"non-empty" if t else "empty"  # "non-empty"
</pre>

<p>Sets may be compared for equality or inequality using <code>==</code> and <code>!=</code>. A set
<code>s</code> is equal to <code>t</code> if and only if <code>t</code> is a set containing the same
elements; iteration order is not significant. In particular, a set is <em>not</em> equal to the list
of its elements. Sets are not ordered with respect to other sets, and an attempt to compare two sets
using <code>&lt;</code>, <code>&lt;=</code>, <code>&gt;</code>, <code>&gt;=</code>, or to sort a
sequence of sets, will fail.

<pre class=language-python>
set() == set()              # True
set() != []                 # True
set([1, 2]) == set([2, 1])  # True
set([1, 2]) != [1, 2]       # True
</pre>

<p>The <code>|</code> operation on two sets returns the union of the two sets: a set containing the
elements found in either one or both of the original sets.

<pre class=language-python>
set([1, 2]) | set([3, 2])  # set([1, 2, 3])
</pre>

<p>The <code>&amp;</code> operation on two sets returns the intersection of the two sets: a set
containing only the elements found in both of the original sets.

<pre class=language-python>
set([1, 2]) &amp; set([2, 3])  # set([2])
set([1, 2]) &amp; set([3, 4])  # set()
</pre>

<p>The <code>-</code> operation on two sets returns the difference of the two sets: a set containing
the elements found in the left-hand side set but not the right-hand side set.

<pre class=language-python>
set([1, 2]) - set([2, 3])  # set([1])
set([1, 2]) - set([3, 4])  # set([1, 2])
</pre>

<p>The <code>^</code> operation on two sets returns the symmetric difference of the two sets: a set
containing the elements found in exactly one of the two original sets, but not in both.

<pre class=language-python>
set([1, 2]) ^ set([2, 3])  # set([1, 3])
set([1, 2]) ^ set([3, 4])  # set([1, 2, 3, 4])
</pre>

<p>In each of the above operations, the elements of the resulting set retain their order from the
two operand sets, with all elements that were drawn from the left-hand side ordered before any
element that was only present in the right-hand side.

<p>The corresponding augmented assignments, <code>|=</code>, <code>&amp;=</code>, <code>-=</code>,
and <code>^=</code>, modify the left-hand set in place.

<pre class=language-python>
s = set([1, 2])
s |= set([2, 3, 4])     # s now equals set([1, 2, 3, 4])
s &amp;= set([0, 1, 2, 3])  # s now equals set([1, 2, 3])
s -= set([0, 1])        # s now equals set([2, 3])
s ^= set([3, 4])        # s now equals set([2, 4])
</pre>

<p>Like all mutable values in Starlark, a set can be frozen, and once frozen, all subsequent
operations that attempt to update it will fail.

""".trimIndent()
)
class StarlarkSet<E> : AbstractSet<E?>, net.starlark.java.eval.Mutability.Freezable,
    net.starlark.java.eval.StarlarkMembershipTestable, net.starlark.java.eval.StarlarkIterable<E?>,
    net.starlark.java.eval.Compactable {
    // Either LinkedHashSet<E> or ImmutableSet<E>.
    private var contents: MutableSet<E?>

    // Number of active iterators (unused once frozen).
    @Transient
    private var iteratorCount = 0 // transient for serialization by Bazel

    /** Final except for [.unsafeShallowFreeze]; must not be modified any other way.  */
    private var mutability: net.starlark.java.eval.Mutability

    private constructor(mutability: net.starlark.java.eval.Mutability, contents: LinkedHashSet<E?>) {
        com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.Mutability?>(mutability)
        com.google.common.base.Preconditions.checkArgument(mutability != net.starlark.java.eval.Mutability.Companion.IMMUTABLE)
        this.mutability = mutability
        this.contents = contents
    }

    private constructor(contents: com.google.common.collect.ImmutableSet<E?>) {
        // An immutable set might as well store its contents as an ImmutableSet, since ImmutableSet
        // both is more memory-efficient than LinkedHashSet and also it has the requisite deterministic
        // iteration order.
        this.mutability = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
        this.contents = contents
    }

    override fun truth(): Boolean {
        return !isEmpty()
    }

    override fun isImmutable(): Boolean {
        return mutability().isFrozen()
    }

    override fun updateIteratorCount(delta: Int): Boolean {
        if (mutability().isFrozen()) {
            return false
        }
        if (delta > 0) {
            iteratorCount++
        } else if (delta < 0) {
            iteratorCount--
        }
        return iteratorCount > 0
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkHashable() {
        // Even a frozen set is unhashable.
        throw net.starlark.java.eval.Starlark.Companion.errorf("unhashable type: 'set'")
    }

    override fun hashCode(): Int {
        return contents.hashCode()
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        if (isEmpty()) {
            printer.append("set()")
        } else {
            printer.printList(this, "set([", ", ", "])", semantics)
        }
    }

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.Companion.repr(
            this,
            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
        )
    }

    override fun equals(o: Any?): Boolean {
        return contents == o
    }

    override fun iterator(): MutableIterator<E?>? {
        if (contents is com.google.common.collect.ImmutableSet) {
            return contents.iterator()
        } else {
            // Prohibit mutation through Iterator.remove().
            return Collections.unmodifiableSet<E?>(contents).iterator()
        }
    }

    override fun size(): Int {
        return contents.size()
    }

    override fun isEmpty(): Boolean {
        return contents.isEmpty()
    }

    override fun toArray(): Array<Any?> {
        return contents.toArray()
    }

    override fun <T> toArray(a: Array<T?>?): Array<T?> {
        return contents.toArray<T?>(a)
    }

    override fun contains(o: Any?): Boolean {
        return contents.contains(o)
    }

    override fun containsAll(c: MutableCollection<*>?): Boolean {
        return contents.containsAll(c)
    }

    override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, element: Any?): Boolean {
        return contents.contains(element)
    }

    override fun mutability(): net.starlark.java.eval.Mutability {
        return mutability
    }

    override fun unsafeShallowFreeze() {
        net.starlark.java.eval.Mutability.Freezable.Companion.checkUnsafeShallowFreezePrecondition(this)
        this.mutability = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "issubset",
        doc = """
Returns true of this set is a subset of another.

<p>Note that a set is always considered to be a subset of itself.

<p>For example,
<pre class=language-python>
set([1, 2]).issubset([1, 2, 3])  # True
set([1, 2]).issubset([1, 2])     # True
set([1, 2]).issubset([2, 3])     # False
</pre>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(name = "other", doc = "A collection of hashable elements.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isSubset(other: Any): Boolean {
        return net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(other, "issubset argument")
            .containsAll(this.contents)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "issuperset",
        doc = """
Returns true of this set is a superset of another.

<p>Note that a set is always considered to be a superset of itself.

<p>For example,
<pre class=language-python>
set([1, 2, 3]).issuperset([1, 2])     # True
set([1, 2, 3]).issuperset([1, 2, 3])  # True
set([1, 2, 3]).issuperset([2, 3, 4])  # False
</pre>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(name = "other", doc = "A collection of hashable elements.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isSuperset(other: Any): Boolean {
        return contents.containsAll(
            net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
                other,
                "issuperset argument"
            )
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "isdisjoint",
        doc = """
Returns true if this set has no elements in common with another.

<p>For example,
<pre class=language-python>
set([1, 2]).isdisjoint([3, 4])  # True
set().isdisjoint(set())         # True
set([1, 2]).isdisjoint([2, 3])  # False
</pre>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(name = "other", doc = "A collection of hashable elements.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isDisjoint(other: Any): Boolean {
        return Collections.disjoint(
            this.contents,
            net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(other, "isdisjoint argument")
        )
    }

    /**
     * Intended for use from Starlark; if used from Java, the caller should ensure that the elements
     * to be added are instances of `E`.
     */
    @net.starlark.java.annot.StarlarkMethod(
        name = "update",
        doc = """
Adds the elements found in others to this set.

<p>For example,
<pre class=language-python>
s = set()
s.update([1, 2])          # None; s is set([1, 2])
s.update([2, 3], [3, 4])  # None; s is set([1, 2, 3, 4])
</pre>

<p>If <code>s</code> and <code>t</code> are sets, <code>s.update(t)</code> is equivalent to
<code>s |= t</code>; however, note that the <code>|=</code> augmented assignment requires both sides
to be sets, while the <code>update</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>update</code> without any arguments; this leaves the set
unchanged.

""".trimIndent(),
        extraPositionals = net.starlark.java.annot.Param(name = "others", doc = "Collections of hashable elements.")
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun update(others: net.starlark.java.eval.Tuple) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        for (other in others) {
            val otherCollection =
                net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
                    other,
                    "update argument"
                ) as MutableCollection<out E?>
            contents.addAll(otherCollection)
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "add", doc = """
Adds an element to the set.

<p>It is permissible to <code>add</code> a value already present in the set; this leaves the set
unchanged.

<p>If you need to add multiple elements to a set, see <a href="#update"><code>update</code></a> or
the <code>|=</code> augmented assignment operation.

""".trimIndent(), parameters = [net.starlark.java.annot.Param(name = "element", doc = "Element to add.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun addElement(element: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        net.starlark.java.eval.Starlark.Companion.checkHashable(element)
        contents.add(element)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "remove",
        doc = """
Removes an element, which must be present in the set, from the set.

<p><code>remove</code> fails if the element was not present in the set. If you don't want to fail on
an attempt to remove a non-present element, use <a href="#discard"><code>discard</code></a> instead.
If you need to remove multiple elements from a set, see
<a href="#difference_update"><code>difference_update</code></a> or the <code>-=</code> augmented
assignment operation.

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "element",
            doc = "Element to remove. Must be an element of the set (and hashable)."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun removeElement(element: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        net.starlark.java.eval.Starlark.Companion.checkHashable(element)
        if (!contents.remove(element)) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "element %s not found in set",
                net.starlark.java.eval.Starlark.Companion.repr(
                    element,
                    net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                )
            )
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "discard",
        doc = """
Removes an element from the set if it is present.

<p>It is permissible to <code>discard</code> a value not present in the set; this leaves the set
unchanged. If you want to fail on an attempt to remove a non-present element, use
<a href="#remove"><code>remove</code></a> instead. If you need to remove multiple elements from a
set, see <a href="#difference_update"><code>difference_update</code></a> or the <code>-=</code>
augmented assignment operation.

<p>For example,
<pre class=language-python>
s = set(["x", "y"])
s.discard("y")  # None; s == set(["x"])
s.discard("y")  # None; s == set(["x"])
</pre>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(name = "element", doc = "Element to discard. Must be hashable.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun discard(element: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        net.starlark.java.eval.Starlark.Companion.checkHashable(element)
        contents.remove(element)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "pop", doc = """
Removes and returns the first element of the set (in iteration order, which is the order in which
elements were first added to the set).

<p>Fails if the set is empty.

<p>For example,
<pre class=language-python>
s = set([3, 1, 2])
s.pop()  # 3; s == set([1, 2])
s.pop()  # 1; s == set([2])
s.pop()  # 2; s == set()
s.pop()  # error: empty set
</pre>

""".trimIndent()
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun pop(): E? {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        if (isEmpty()) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("set is empty")
        }
        val element = contents.iterator().next()
        contents.remove(element)
        return element
    }

    @net.starlark.java.annot.StarlarkMethod(name = "clear", doc = "Removes all the elements of the set.")
    @Throws(net.starlark.java.eval.EvalException::class)
    fun clearElements() {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        contents.clear()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "union",
        doc = """
Returns a new mutable set containing the union of this set with others.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.union(t)</code> is equivalent to
<code>s | t</code>; however, note that the <code>|</code> operation requires both sides to be sets,
while the <code>union</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>union</code> without any arguments; this returns a copy of the
set.

<p>For example,
<pre class=language-python>
set([1, 2]).union([2, 3])                    # set([1, 2, 3])
set([1, 2]).union([2, 3], {3: "a", 4: "b"})  # set([1, 2, 3, 4])
</pre>

""".trimIndent(),
        extraPositionals = net.starlark.java.annot.Param(name = "others", doc = "Collections of hashable elements."),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun union(others: net.starlark.java.eval.Tuple, thread: net.starlark.java.eval.StarlarkThread): StarlarkSet<*>? {
        val newContents: LinkedHashSet<Any?> = LinkedHashSet<Any?>(contents)
        for (other in others) {
            newContents.addAll(
                net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
                    other,
                    "union argument"
                )
            )
        }
        return net.starlark.java.eval.StarlarkSet.Companion.wrapOrImmutableCopy<Any?>(thread.mutability(), newContents)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "intersection",
        doc = """
Returns a new mutable set containing the intersection of this set with others.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.intersection(t)</code> is equivalent to
<code>s &amp; t</code>; however, note that the <code>&amp;</code> operation requires both sides to
be sets, while the <code>intersection</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>intersection</code> without any arguments; this returns a copy of
the set.

<p>For example,
<pre class=language-python>
set([1, 2]).intersection([2, 3])             # set([2])
set([1, 2, 3]).intersection([0, 1], [1, 2])  # set([1])
</pre>

""".trimIndent(),
        extraPositionals = net.starlark.java.annot.Param(name = "others", doc = "Collections of hashable elements."),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun intersection(
        others: net.starlark.java.eval.Tuple,
        thread: net.starlark.java.eval.StarlarkThread
    ): StarlarkSet<*>? {
        val newContents: LinkedHashSet<Any?> = LinkedHashSet<Any?>(contents)
        for (other in others) {
            newContents.retainAll(
                net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
                    other,
                    "intersection argument"
                )
            )
        }
        return net.starlark.java.eval.StarlarkSet.Companion.wrapOrImmutableCopy<Any?>(thread.mutability(), newContents)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "intersection_update",
        doc = """
Removes any elements not found in all others from this set.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.intersection_update(t)</code> is
equivalent to <code>s &amp;= t</code>; however, note that the <code>&amp;=</code> augmented
assignment requires both sides to be sets, while the <code>intersection_update</code> method also
accepts sequences and dicts.

<p>It is permissible to call <code>intersection_update</code> without any arguments; this leaves the
set unchanged.

<p>For example,
<pre class=language-python>
s = set([1, 2, 3, 4])
s.intersection_update([0, 1, 2])       # None; s is set([1, 2])
s.intersection_update([0, 1], [1, 2])  # None; s is set([1])
</pre>

""".trimIndent(),
        extraPositionals = net.starlark.java.annot.Param(name = "others", doc = "Collections of hashable elements.")
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun intersectionUpdate(others: net.starlark.java.eval.Tuple) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        for (other in others) {
            contents.retainAll(
                net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
                    other,
                    "intersection_update argument"
                )
            )
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "difference",
        doc = """
Returns a new mutable set containing the difference of this set with others.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.difference(t)</code> is equivalent to
<code>s - t</code>; however, note that the <code>-</code> operation requires both sides to be sets,
while the <code>difference</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>difference</code> without any arguments; this returns a copy of
the set.

<p>For example,
<pre class=language-python>
set([1, 2, 3]).difference([2])             # set([1, 3])
set([1, 2, 3]).difference([0, 1], [3, 4])  # set([2])
</pre>

""".trimIndent(),
        extraPositionals = net.starlark.java.annot.Param(name = "others", doc = "Collections of hashable elements."),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun difference(
        others: net.starlark.java.eval.Tuple,
        thread: net.starlark.java.eval.StarlarkThread
    ): StarlarkSet<*>? {
        val newContents: LinkedHashSet<Any?> = LinkedHashSet<Any?>(contents)
        for (other in others) {
            newContents.removeAll(
                net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
                    other,
                    "difference argument"
                )
            )
        }
        return net.starlark.java.eval.StarlarkSet.Companion.wrapOrImmutableCopy<Any?>(thread.mutability(), newContents)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "difference_update",
        doc = """
Removes any elements found in any others from this set.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.difference_update(t)</code> is equivalent
to <code>s -= t</code>; however, note that the <code>-=</code> augmented assignment requires both
sides to be sets, while the <code>difference_update</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>difference_update</code> without any arguments; this leaves the
set unchanged.

<p>For example,
<pre class=language-python>
s = set([1, 2, 3, 4])
s.difference_update([2])             # None; s is set([1, 3, 4])
s.difference_update([0, 1], [4, 5])  # None; s is set([3])
</pre>

""".trimIndent(),
        extraPositionals = net.starlark.java.annot.Param(name = "others", doc = "Collections of hashable elements.")
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun differenceUpdate(others: net.starlark.java.eval.Tuple) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        for (other in others) {
            contents.removeAll(
                net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
                    other,
                    "intersection_update argument"
                )
            )
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "symmetric_difference",
        doc = """
Returns a new mutable set containing the symmetric difference of this set with another collection of
hashable elements.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.symmetric_difference(t)</code> is
equivalent to <code>s ^ t</code>; however, note that the <code>^</code> operation requires both
sides to be sets, while the <code>symmetric_difference</code> method also accepts a sequence or a
dict.

<p>For example,
<pre class=language-python>
set([1, 2]).symmetric_difference([2, 3])  # set([1, 3])
</pre>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(name = "other", doc = "A collection of hashable elements.")],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun symmetricDifference(other: Any, thread: net.starlark.java.eval.StarlarkThread): StarlarkSet<*>? {
        val newContents: LinkedHashSet<Any?> = LinkedHashSet<Any?>(contents)
        for (element in net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
            other,
            "symmetric_difference argument"
        )) {
            if (contents.contains(element)) {
                newContents.remove(element)
            } else {
                newContents.add(element)
            }
        }
        return net.starlark.java.eval.StarlarkSet.Companion.wrapOrImmutableCopy<Any?>(thread.mutability(), newContents)
    }

    /**
     * Intended for use from Starlark; if used from Java, the caller should ensure that the elements
     * to be added are instances of `E`.
     */
    @net.starlark.java.annot.StarlarkMethod(
        name = "symmetric_difference_update",
        doc = """
Returns a new mutable set containing the symmetric difference of this set with another collection of
hashable elements.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.symmetric_difference_update(t)</code> is
equivalent to `s ^= t<code>; however, note that the </code>^=` augmented assignment requires both
sides to be sets, while the <code>symmetric_difference_update</code> method also accepts a sequence
or a dict.

<p>For example,
<pre class=language-python>
s = set([1, 2])
s.symmetric_difference_update([2, 3])  # None; s == set([1, 3])
</pre>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(name = "other", doc = "A collection of hashable elements.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun symmetricDifferenceUpdate(other: Any) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        val originalContents: com.google.common.collect.ImmutableSet<E?> =
            com.google.common.collect.ImmutableSet.copyOf<E?>(contents)
        for (element in net.starlark.java.eval.StarlarkSet.Companion.toHashableCollection(
            other,
            "symmetric_difference_update argument"
        )) {
            if (originalContents.contains(element)) {
                contents.remove(element)
            } else {
                val castElement = element as E?
                contents.add(castElement)
            }
        }
    }

    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType {
        // TODO(ilist@): store the type for non-homogeneous sets
        if (isEmpty()) {
            return if (mutability().isFrozen()) net.starlark.java.syntax.Types.set(net.starlark.java.syntax.Types.NEVER) else net.starlark.java.syntax.Types.set(
                net.starlark.java.syntax.Types.ANY
            )
        }
        return net.starlark.java.syntax.Types.set(
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

    // Prohibit Java Set mutators.

    @Deprecated("use {@link #addElement} instead.")
    override fun add(e: E?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("use {@link #update} instead.")
    override fun addAll(c: MutableCollection<out E?>?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("use {@link #clearElements} instead.")
    override fun clear() {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("use {@link #removeElement} instead.")
    override fun remove(o: Any?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("use {@link #differenceUpdate} instead.")
    override fun removeAll(c: MutableCollection<*>?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("use {@link #intersectionUpdate} instead.")
    override fun retainAll(c: MutableCollection<*>?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    override fun unsafeOptimizeMemoryLayout(): net.starlark.java.eval.StarlarkValue {
        com.google.common.base.Preconditions.checkState(mutability.isFrozen())
        this.contents = com.google.common.collect.ImmutableSet.copyOf<E?>(contents)
        return this
    }

    companion object {
        fun getAssociatedTypeConstructor(): net.starlark.java.syntax.TypeConstructor {
            return net.starlark.java.syntax.Types.SET_CONSTRUCTOR
        }

        private val EMPTY: StarlarkSet<*> =
            net.starlark.java.eval.StarlarkSet<Any?>(com.google.common.collect.ImmutableSet.of<Any?>())

        /** Returns an immutable empty set.  */ // Safe because the empty singleton is immutable.
        fun <E> empty(): StarlarkSet<E?>? {
            return net.starlark.java.eval.StarlarkSet.Companion.EMPTY as StarlarkSet<E?>?
        }

        /** Returns a new empty set with the specified mutability.  */
        fun <E> of(mu: net.starlark.java.eval.Mutability?): StarlarkSet<E?>? {
            var mu: net.starlark.java.eval.Mutability? = mu
            if (mu == null) {
                mu = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
            }
            if (mu == net.starlark.java.eval.Mutability.Companion.IMMUTABLE) {
                return net.starlark.java.eval.StarlarkSet.Companion.empty<E?>()
            } else {
                return net.starlark.java.eval.StarlarkSet<E?>(
                    mu,
                    com.google.common.collect.Sets.newLinkedHashSetWithExpectedSize<E?>(1)
                )
            }
        }

        /**
         * Returns a set with the specified mutability containing the entries of `elements`. Tries
         * to elide copying if `elements` is immutable.
         * 
         * @param elements a collection of elements, which must be Starlark-hashable (note that this
         * method assumes but does not verify their hashability), to add to the new set.
         */
        fun <E> copyOf(
            mu: net.starlark.java.eval.Mutability?, elements: MutableCollection<out E?>
        ): StarlarkSet<E?>? {
            var mu: net.starlark.java.eval.Mutability? = mu
            if (elements.isEmpty()) {
                return net.starlark.java.eval.StarlarkSet.Companion.of<E?>(mu)
            }

            if (mu == null) {
                mu = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
            }

            if (mu == net.starlark.java.eval.Mutability.Companion.IMMUTABLE) {
                if (elements is com.google.common.collect.ImmutableSet) {
                    elements.forEach { x: T? -> net.starlark.java.eval.Starlark.Companion.checkValid(x) }
                    val immutableSet: com.google.common.collect.ImmutableSet<E?> =
                        elements as com.google.common.collect.ImmutableSet<E?>
                    return net.starlark.java.eval.StarlarkSet<E?>(immutableSet)
                }

                if (elements is StarlarkSet<*> && (elements as StarlarkSet<*>).isImmutable()) {
                    val starlarkSet = elements as StarlarkSet<E?>
                    return starlarkSet
                }

                val immutableSetBuilder: com.google.common.collect.ImmutableSet.Builder<E?> =
                    com.google.common.collect.ImmutableSet.builderWithExpectedSize<E?>(elements.size())
                elements.forEach { e: E? ->
                    immutableSetBuilder.add(
                        net.starlark.java.eval.Starlark.Companion.checkValid(
                            e
                        )
                    )
                }
                return net.starlark.java.eval.StarlarkSet<E?>(immutableSetBuilder.build())
            } else {
                val linkedHashSet: LinkedHashSet<E?> =
                    com.google.common.collect.Sets.newLinkedHashSetWithExpectedSize<E?>(elements.size())
                elements.forEach { e: E? -> linkedHashSet.add(net.starlark.java.eval.Starlark.Companion.checkValid(e)) }
                return net.starlark.java.eval.StarlarkSet<E?>(mu, linkedHashSet)
            }
        }

        private fun <E> wrapOrImmutableCopy(
            mu: net.starlark.java.eval.Mutability?,
            elements: LinkedHashSet<E?>
        ): StarlarkSet<E?>? {
            com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.Mutability?>(mu)
            if (mu == net.starlark.java.eval.Mutability.Companion.IMMUTABLE) {
                return if (elements.isEmpty()) net.starlark.java.eval.StarlarkSet.Companion.empty<E?>() else net.starlark.java.eval.StarlarkSet<E?>(
                    com.google.common.collect.ImmutableSet.copyOf<E?>(elements)
                )
            } else {
                return net.starlark.java.eval.StarlarkSet<E?>(mu, elements)
            }
        }

        /**
         * A variant of [.copyOf] intended to be used from Starlark. Unlike [.copyOf], this
         * method does verify that the elements being added to the set are Starlark-hashable.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun checkedCopyOf(mu: net.starlark.java.eval.Mutability?, elements: Iterable<*>): StarlarkSet<Any?>? {
            val collection: MutableCollection<*>?
            if (elements is MutableCollection<*>) {
                collection = elements
            } else {
                collection = com.google.common.collect.ImmutableList.copyOf(elements)
            }
            for (element in collection!!) {
                net.starlark.java.eval.Starlark.Companion.checkHashable(element)
            }
            return net.starlark.java.eval.StarlarkSet.Companion.copyOf<Any?>(mu, collection)
        }

        /**
         * Returns an immutable set containing the entries of `elements`. Tries to elide copying if
         * `elements` is already immutable.
         * 
         * @param elements a collection of elements, which must be Starlark-hashable (note that this
         * method assumes but does not verify their hashability), to add to the new set.
         */
        fun <E> immutableCopyOf(elements: MutableCollection<out E?>): StarlarkSet<E?>? {
            return net.starlark.java.eval.StarlarkSet.Companion.copyOf<E?>(null, elements)
        }

        /**
         * Verifies that `other` is either a [Collection] of Starlark-hashable elements or a
         * [Map] with Starlark-hashable keys.
         * 
         * 
         * Note that in the Starlark language spec, this notion is referred to as a "collection", but
         * Java [Map]s aren't Java [Collection]s.
         * 
         * @return `other` if it is a [Collection], or the key set of `other` if it is a
         * [Map].
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun toHashableCollection(other: Any, what: String?): MutableCollection<*> {
            if (other is MutableCollection<*>) {
                // Assume that elements of a StarlarkSet have already been checked to be hashable.
                if (other !is StarlarkSet<*>) {
                    for (element in other) {
                        net.starlark.java.eval.Starlark.Companion.checkHashable(element)
                    }
                }
                return other
            } else if (other is MutableMap<*, *>) {
                val keySet: MutableSet<*> = other.keySet()
                // Assume that keys of a Dict have already been checked to be hashable.
                if (other !is net.starlark.java.eval.Dict<*, *>) {
                    for (element in keySet) {
                        net.starlark.java.eval.Starlark.Companion.checkHashable(element)
                    }
                }
                return keySet
            }
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "for %s got value of type '%s', want a collection of hashable elements",
                what, net.starlark.java.eval.Starlark.Companion.type(other)
            )
        }
    }
}
