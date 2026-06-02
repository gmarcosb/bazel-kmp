// Copyright 2016 The Bazel Authors. All rights reserved.
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
import java.util.Collections
import java.util.LinkedHashMap

/**
 * A Dict is a Starlark dictionary (dict), a mapping from keys to values.
 * 
 * 
 * Dicts are iterable in both Java and Starlark; the iterator yields successive keys.
 * 
 * 
 * Starlark operations on dicts, including element update `dict[k]=v` and the `update` and `setdefault` methods, may insert arbitrary Starlark values as dict keys/values,
 * regardless of the type argument used to reference the dict from Java code. Therefore, as long as
 * a dict is mutable, Java code should refer to it only through a type such as `Dict<Object, Object>` or `Dict<?, ?>` to avoid undermining the type-safety of the Java application. Once
 * the dict becomes frozen, it is safe to [.cast] it to a more specific type that accurately
 * reflects its entries, such as `Dict<String, StarlarkInt>`.
 * 
 * 
 * The following Dict methods, defined by the [Map] interface, are not supported. Use the
 * corresponding methods with "entry" in their name; they may report mutation failure by throwing a
 * checked exception:
 * 
 * <pre>
 * void clear()         -- use clearEntries
 * V put(K, V)          -- use putEntry
 * void putAll(Map)     -- use putEntries
 * V remove(Object key) -- use pop
</pre> * 
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "dict",
    category = "core",
    doc = ("dict is a built-in type representing an associative mapping or <i>dictionary</i>. A"
            + " dictionary supports indexing using <code>d[k]</code> and key membership testing"
            + " using <code>k in d</code>; both operations take constant time. Unfrozen"
            + " dictionaries are mutable, and may be updated by assigning to <code>d[k]</code> or"
            + " by calling certain methods. Dictionaries are iterable; iteration yields the"
            + " sequence of keys in insertion order. Iteration order is unaffected by updating the"
            + " value associated with an existing key, but is affected by removing then reinserting"
            + " a key.\n"
            + "<pre>d = {0: \"x\", 2: \"z\", 1: \"y\"}\n"
            + "[k for k in d]  # [0, 2, 1]\n"
            + "d.pop(2)\n"
            + "d[0], d[2] = \"a\", \"b\"\n"
            + "0 in d, \"a\" in d  # (True, False)\n"
            + "[(k, v) for k, v in d.items()]  # [(0, \"a\"), (1, \"y\"), (2, \"b\")]\n"
            + "</pre>\n"
            + "<p>There are four ways to construct a dictionary:\n"
            + "<ol>\n"
            + "<li>A dictionary expression <code>{k: v, ...}</code> yields a new dictionary with"
            + " the specified key/value entries, inserted in the order they appear in the"
            + " expression. Evaluation fails if any two key expressions yield the same value.\n"
            + "<li>A dictionary comprehension <code>{k: v for vars in seq}</code> yields a new"
            + " dictionary into which each key/value pair is inserted in loop iteration order."
            + " Duplicates are permitted: the first insertion of a given key determines its"
            + " position in the sequence, and the last determines its associated value.\n"
            + "<pre class=\"language-python\">\n"
            + "{k: v for k, v in ((\"a\", 0), (\"b\", 1), (\"a\", 2))}  # {\"a\": 2, \"b\": 1}\n"
            + "{i: 2*i for i in range(3)}  # {0: 0, 1: 2, 2: 4}\n"
            + "</pre>\n"
            + "<li>A call to the built-in <a href=\"../globals/all.html#dict\">dict</a> function"
            + " returns a dictionary containing the specified entries, which are inserted in"
            + " argument order, positional arguments before named. As with comprehensions,"
            + " duplicate keys are permitted.\n"
            + "<li>The union expression <code>x | y</code> yields a new dictionary by combining two"
            + " existing dictionaries. If the two dictionaries have a key <code>k</code> in common,"
            + " the right hand side dictionary's value of the key (in other words,"
            + " <code>y[k]</code>) wins. The <code>|=</code> variant of the union operator modifies"
            + " a dictionary in-place. Example:<br><pre class=language-python>d = {\"foo\":"
            + " \"FOO\", \"bar\": \"BAR\"} | {\"foo\": \"FOO2\", \"baz\": \"BAZ\"}\n"
            + "# d == {\"foo\": \"FOO2\", \"bar\": \"BAR\", \"baz\": \"BAZ\"}\n"
            + "d = {\"a\": 1, \"b\": 2}\n"
            + "d |= {\"b\": 3, \"c\": 4}\n"
            + "# d == {\"a\": 1, \"b\": 3, \"c\": 4}</pre></ol>")
)
abstract class Dict<K, V>
internal constructor() : MutableMap<K?, V?>, net.starlark.java.eval.StarlarkValue,
    net.starlark.java.eval.Mutability.Freezable, net.starlark.java.eval.StarlarkIndexable,
    net.starlark.java.eval.StarlarkIterable<K?> {
    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType {
        // TODO(ilist@): store the type for non-homogeneous dicts
        // Current implementation traverses the dict and computes union of all elements - same as most
        // of the native calls. This is correct, but could be expensive.
        if (isEmpty()) {
            return if (mutability().isFrozen())
                net.starlark.java.syntax.Types.dict(
                    net.starlark.java.syntax.Types.NEVER,
                    net.starlark.java.syntax.Types.NEVER
                )
            else
                net.starlark.java.syntax.Types.dict(
                    net.starlark.java.syntax.Types.ANY,
                    net.starlark.java.syntax.Types.ANY
                )
        }
        return net.starlark.java.syntax.Types.dict(
            net.starlark.java.syntax.Types.union(
                keySet().stream()
                    .map<net.starlark.java.syntax.StarlarkType?>(java.util.function.Function { k: K? ->
                        net.starlark.java.eval.Starlark.Companion.getStarlarkType(
                            k,
                            semantics
                        )
                    })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<net.starlark.java.syntax.StarlarkType?>())
            ),
            net.starlark.java.syntax.Types.union(
                values().stream()
                    .map<net.starlark.java.syntax.StarlarkType?>(java.util.function.Function { v: V? ->
                        net.starlark.java.eval.Starlark.Companion.getStarlarkType(
                            v,
                            semantics
                        )
                    })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<net.starlark.java.syntax.StarlarkType?>())
            )
        )
    }

    override fun isEmpty(): Boolean {
        return size() == 0
    }

    override fun truth(): Boolean {
        return !isEmpty()
    }

    override fun isImmutable(): Boolean {
        return mutability().isFrozen()
    }

    abstract override fun updateIteratorCount(delta: Int): Boolean

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkHashable() {
        // Even a frozen dict is unhashable.
        throw net.starlark.java.eval.Starlark.Companion.errorf("unhashable type: 'dict'")
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "get",
        doc = ("Returns the value for <code>key</code> if <code>key</code> is in the dictionary, "
                + "else <code>default</code>. If <code>default</code> is not given, it defaults to "
                + "<code>None</code>, so that this method never throws an error."),
        parameters = [net.starlark.java.annot.Param(
            name = "key",
            doc = "The key to look for."
        ), net.starlark.java.annot.Param(
            name = "default",
            defaultValue = "None",
            named = true,
            doc = "The default value to use (instead of None) if the key is not found."
        )],
        useStarlarkThread = true
    ) // TODO(adonovan): This method is named get2 as a temporary workaround for a bug in
    // StarlarkAnnotations.getStarlarkMethod. The two 'get' methods cause it to get
    // confused as to which one has the annotation. Fix it and remove "2" suffix.
    @Throws(net.starlark.java.eval.EvalException::class)
    fun get2(key: Any?, defaultValue: Any?, thread: net.starlark.java.eval.StarlarkThread): Any? {
        val v: Any? = get(key)
        if (v != null) {
            return v
        }

        // This statement is executed for its effect, which is to throw "unhashable"
        // if key is unhashable, instead of returning defaultValue.
        // I think this is a bug: the correct behavior is simply 'return defaultValue'.
        // See https://github.com/bazelbuild/starlark/issues/65.
        containsKey(thread.getSemantics(), key)

        return defaultValue
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "pop",
        doc = ("Removes a <code>key</code> from the dict, and returns the associated value. "
                + "If no entry with that key was found, remove nothing and return the specified "
                + "<code>default</code> value; if no default value was specified, fail instead."),
        parameters = [net.starlark.java.annot.Param(
            name = "key",
            doc = "The key."
        ), net.starlark.java.annot.Param(
            name = "default",
            defaultValue = "unbound",
            named = true,
            doc = "a default value if the key is absent."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun pop(key: Any?, defaultValue: Any?, thread: net.starlark.java.eval.StarlarkThread?): Any?

    @net.starlark.java.annot.StarlarkMethod(
        name = "popitem", doc = ("Remove and return the first <code>(key, value)</code> pair from the dictionary. "
                + "<code>popitem</code> is useful to destructively iterate over a dictionary, "
                + "as often used in set algorithms. "
                + "If the dictionary is empty, the <code>popitem</code> call fails.")
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun popitem(): net.starlark.java.eval.Tuple?

    @net.starlark.java.annot.StarlarkMethod(
        name = "setdefault",
        doc = ("If <code>key</code> is in the dictionary, return its value. "
                + "If not, insert key with a value of <code>default</code> "
                + "and return <code>default</code>. "
                + "<code>default</code> defaults to <code>None</code>."),
        parameters = [net.starlark.java.annot.Param(
            name = "key",
            doc = "The key."
        ), net.starlark.java.annot.Param(
            name = "default",
            defaultValue = "None",
            named = true,
            doc = "a default value if the key is absent."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun setdefault(key: K?, defaultValue: V?): V?

    @net.starlark.java.annot.StarlarkMethod(
        name = "update",
        doc = ("Updates the dictionary first with the optional positional argument, <code>pairs</code>, "
                + " then with the optional keyword arguments\n"
                + "If the positional argument is present, it must be a dict, iterable, or None.\n"
                + "If it is a dict, then its key/value pairs are inserted into this dict. "
                + "If it is an iterable, it must provide a sequence of pairs (or other iterables "
                + "of length 2), each of which is treated as a key/value pair to be inserted.\n"
                + "Each keyword argument <code>name=value</code> causes the name/value "
                + "pair to be inserted into this dict."),
        parameters = [net.starlark.java.annot.Param(
            name = "pairs",
            defaultValue = "[]",
            doc = "Either a dictionary or a list of entries. Entries must be tuples or lists with "
                    + "exactly two elements: key, value."
        )],
        extraKeywords = net.starlark.java.annot.Param(name = "kwargs", doc = "Dictionary of additional entries."),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun update(pairs: Any, kwargs: Dict<String?, Any?>?, thread: net.starlark.java.eval.StarlarkThread?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        val dict: Dict<Any?, Any?> = this as Dict<*, *> // see class doc comment
        net.starlark.java.eval.Dict.Companion.update("update", dict, pairs, kwargs)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "values", doc = ("Returns the list of values:"
                + "<pre class=\"language-python\">"
                + "{2: \"a\", 4: \"b\", 1: \"c\"}.values() == [\"a\", \"b\", \"c\"]</pre>\n"), useStarlarkThread = true
    )
    abstract fun values0(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkList<*>?

    @net.starlark.java.annot.StarlarkMethod(
        name = "items", doc = ("Returns the list of key-value tuples:"
                + "<pre class=\"language-python\">"
                + "{2: \"a\", 4: \"b\", 1: \"c\"}.items() == [(2, \"a\"), (4, \"b\"), (1, \"c\")]"
                + "</pre>\n"), useStarlarkThread = true
    )
    abstract fun items(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkList<*>?

    @net.starlark.java.annot.StarlarkMethod(
        name = "keys", doc = ("Returns the list of keys:"
                + "<pre class=\"language-python\">{2: \"a\", 4: \"b\", 1: \"c\"}.keys() == [2, 4, 1]"
                + "</pre>\n"), useStarlarkThread = true
    )
    abstract fun keys(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkList<*>?

    /** A reusable builder for Dicts.  */
    class Builder<K, V> {
        private val items: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>() // [k, v, ... k, v]

        /** Adds an entry (k, v) to the builder, overwriting any previous entry with the same key .  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun put(k: K?, v: V?): Builder<K?, V?> {
            items.add(net.starlark.java.eval.Starlark.Companion.checkValid<K?>(k))
            items.add(net.starlark.java.eval.Starlark.Companion.checkValid<V?>(v))
            return this
        }

        /** Adds all the map's entries to the builder.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putAll(map: MutableMap<out K?, out V?>): Builder<K?, V?> {
            items.ensureCapacity(items.size() + 2 * map.size())
            map.forEach { k: K?, v: V? -> this.put(k, v) }
            return this
        }

        /** Returns a new immutable Dict containing the entries added so far.  */
        fun buildImmutable(): Dict<K?, V?>? {
            return build(null)
        }

        /** Returns a new [ImmutableKeyTrackingDict] containing the entries added so far.  */
        fun buildImmutableWithKeyTracking(): ImmutableKeyTrackingDict<K?, V?> {
            return net.starlark.java.eval.Dict.ImmutableKeyTrackingDict<K?, V?>(buildImmutableMap())
        }

        /**
         * Returns a new Dict containing the entries added so far. The result has the specified
         * mutability; null means immutable.
         */
        fun build(mu: net.starlark.java.eval.Mutability?): Dict<K?, V?>? {
            if (mu == null || mu == net.starlark.java.eval.Mutability.Companion.IMMUTABLE) {
                if (items.isEmpty()) {
                    return net.starlark.java.eval.Dict.Companion.empty<K?, V?>()
                }
                return net.starlark.java.eval.CompactImmutableDict.Companion.copyOf<K?, V?>(buildLinkedHashMap())
            }
            return net.starlark.java.eval.Dict.MutableDict<K?, V?>(mu, buildLinkedHashMap())
        }

        private fun populateMap(n: Int, mapEntryConsumer: java.util.function.BiConsumer<K?, V?>) {
            for (i in 0..<n) {
                val k = items.get(2 * i) as K? // safe
                val v = items.get(2 * i + 1) as V? // safe
                mapEntryConsumer.accept(k, v)
            }
        }

        private fun buildImmutableMap(): com.google.common.collect.ImmutableMap<K?, V?> {
            val n: Int = items.size() / 2
            val immutableMapBuilder: com.google.common.collect.ImmutableMap.Builder<K?, V?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<K?, V?>(n)
            populateMap(n, java.util.function.BiConsumer { key: K?, value: V? -> immutableMapBuilder.put(key, value) })
            // Respect the desired semantics of Builder#put.
            return immutableMapBuilder.buildKeepingLast()
        }

        private fun buildLinkedHashMap(): LinkedHashMap<K?, V?> {
            val n: Int = items.size() / 2
            val map: LinkedHashMap<K?, V?> = com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<K?, V?>(n)
            populateMap(n, java.util.function.BiConsumer { key: K?, value: V? -> map.put(key, value) })
            return map
        }
    }

    override fun unsafeShallowFreeze() {
        net.starlark.java.eval.Mutability.Freezable.Companion.checkUnsafeShallowFreezePrecondition(this)
    }

    /**
     * Puts an entry into a dict, after validating that mutation is allowed.
     * 
     * @param key the key of the added entry
     * @param value the value of the added entry
     * @throws EvalException if the key is invalid or the dict is frozen
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun putEntry(key: K?, value: V?)

    /**
     * Puts all the entries from a given map into the dict, after validating that mutation is allowed.
     * 
     * @param map the map whose entries are added
     * @throws EvalException if some key is invalid or the dict is frozen
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun <K2 : K?, V2 : V?> putEntries(map: MutableMap<K2?, V2?>?)

    /**
     * Clears the dict.
     * 
     * @throws EvalException if the dict is frozen
     */
    @net.starlark.java.annot.StarlarkMethod(name = "clear", doc = "Remove all items from the dictionary.")
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun clearEntries()

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.printList(entrySet(), "{", ", ", "}", semantics)
    }

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.Companion.repr(
            this,
            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Any {
        val v: Any = get(key)!!
        if (v == null) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "key %s not found in dictionary",
                net.starlark.java.eval.Starlark.Companion.repr(key, semantics)
            )
        }
        return v
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Boolean {
        net.starlark.java.eval.Starlark.Companion.checkHashable(key)
        return containsKey(key)
    }

    // disallowed java.util.Map update operations
    // TODO(adonovan): make mutability exception a subclass of (unchecked)
    // UnsupportedOperationException, allowing the primary Dict operations
    // to satisfy the Map operations below in the usual way (like ImmutableMap does).
    @Deprecated("") // use clearEntries
    override fun clear() {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("") // use putEntry
    override fun put(key: K?, value: V?): V? {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("") // use putEntries
    override fun putAll(map: MutableMap<out K?, out V?>?) {
        throw java.lang.UnsupportedOperationException()
    }

    @Deprecated("") // use pop
    override fun remove(key: Any?): V? {
        throw java.lang.UnsupportedOperationException()
    }

    /** Implementation backed by a (non-dict) [Map].  */ // TODO: jhorvitz - This should be private but bazel_bootstrap_distfile_test is not picking up
    //  https://bugs.openjdk.org/browse/JDK-8284011 for some reason.
    internal abstract class MapBackedDict<K, V> private constructor(contents: MutableMap<K?, V?>?) : Dict<K?, V?>() {
        private var contents: MutableMap<K?, V?>

        init {
            this.contents = com.google.common.base.Preconditions.checkNotNull<MutableMap<K?, V?>>(contents)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun pop(key: Any?, defaultValue: Any?, thread: net.starlark.java.eval.StarlarkThread): Any? {
            net.starlark.java.eval.Starlark.Companion.checkMutable(this)
            val value: Any? = contents.remove(key)
            if (value != null) {
                return value
            }

            net.starlark.java.eval.Starlark.Companion.checkHashable(key)

            if (defaultValue !== net.starlark.java.eval.Starlark.Companion.UNBOUND) {
                return defaultValue
            }
            // TODO(adonovan): improve error; this ain't Python.
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "KeyError: %s",
                net.starlark.java.eval.Starlark.Companion.repr(key, thread.getSemantics())
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun popitem(): net.starlark.java.eval.Tuple? {
            if (isEmpty()) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("popitem: empty dictionary")
            }

            net.starlark.java.eval.Starlark.Companion.checkMutable(this)

            val iterator: MutableIterator<MutableMap.MutableEntry<K?, V?>> = contents.entrySet().iterator()
            val entry = iterator.next()
            iterator.remove()
            return net.starlark.java.eval.Tuple.Companion.pair(entry.getKey(), entry.getValue())
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun setdefault(key: K?, defaultValue: V?): V? {
            net.starlark.java.eval.Starlark.Companion.checkMutable(this)
            net.starlark.java.eval.Starlark.Companion.checkHashable(key)

            val prev: V? = contents.putIfAbsent(key, defaultValue) // see class doc comment
            return if (prev != null) prev else defaultValue
        }

        override fun values0(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(
                thread.mutability(),
                contents.values().toArray()
            )
        }

        override fun items(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            val array = arrayOfNulls<Any>(size())
            var i = 0
            for (e in entrySet()) {
                array[i++] = net.starlark.java.eval.Tuple.Companion.pair(e.getKey(), e.getValue())
            }
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), array)
        }

        override fun keys(thread: net.starlark.java.eval.StarlarkThread): net.starlark.java.eval.StarlarkList<*>? {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(
                thread.mutability(),
                contents.keySet().toArray()
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun putEntry(key: K?, value: V?) {
            net.starlark.java.eval.Starlark.Companion.checkMutable(this)
            net.starlark.java.eval.Starlark.Companion.checkHashable(key)
            contents.put(key, value)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun <K2 : K?, V2 : V?> putEntries(map: MutableMap<K2?, V2?>) {
            net.starlark.java.eval.Starlark.Companion.checkMutable(this)
            for (e in map.entrySet()) {
                val k: K2? = e.getKey()
                net.starlark.java.eval.Starlark.Companion.checkHashable(k)
                contents.put(k, e.getValue())
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun clearEntries() {
            net.starlark.java.eval.Starlark.Companion.checkMutable(this)
            contents.clear()
        }

        override fun containsKey(key: Any?): Boolean {
            return contents.containsKey(key)
        }

        override fun containsValue(value: Any?): Boolean {
            return contents.containsValue(value)
        }

        override fun entrySet(): MutableSet<MutableMap.MutableEntry<K?, V?>> {
            return Collections.unmodifiableMap<K?, V?>(contents).entrySet()
        }

        override fun get(key: Any?): V? {
            return contents.get(key)
        }

        override fun keySet(): MutableSet<K?> {
            return Collections.unmodifiableMap<K?, V?>(contents).keySet()
        }

        override fun size(): Int {
            return contents.size()
        }

        override fun values(): MutableCollection<V?> {
            return Collections.unmodifiableMap<K?, V?>(contents).values()
        }

        override fun iterator(): MutableIterator<K?> {
            return keySet().iterator()
        }

        override fun hashCode(): Int {
            return contents.hashCode()
        }

        override fun equals(o: Any?): Boolean {
            return contents == o
        }
    }

    /** A [Dict] that is mutable until its [.mutability] is frozen.  */ // TODO(bazel-team): Memory optimization opportunity: Make it so that a call to
    // `mutability.freeze()` causes `contents` here to become an ImmutableMap. Benchmarks show that
    // for many targets, this can save a small amount of retained heap (up to 1%). But for some
    // targets the bookkeeping required for this causes unacceptably increased temporary heap, and the
    // CPU overhead of the bookkeeping and the CPU cost of the ImmutableMap#copyOf call cause
    // unacceptably increased CPU. In other words, the overall tradeoff is not obviously worth it in
    // all cases. So be careful making this optimization! See comment #12 of b/225469491 for details.
    private class MutableDict<K, V>(mutability: net.starlark.java.eval.Mutability, contents: LinkedHashMap<K?, V?>?) :
        MapBackedDict<K?, V?>(contents), net.starlark.java.eval.Compactable {
        // Number of active iterators (unused once frozen).
        @Transient
        private var iteratorCount = 0 // transient for serialization by Bazel

        /** Final except for [.unsafeShallowFreeze]; must not be modified any other way.  */
        private var mutability: net.starlark.java.eval.Mutability

        init {
            com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.Mutability?>(mutability)
            com.google.common.base.Preconditions.checkArgument(
                mutability != net.starlark.java.eval.Mutability.Companion.IMMUTABLE,
                mutability
            )
            this.mutability = mutability
        }

        override fun mutability(): net.starlark.java.eval.Mutability {
            return mutability
        }

        override fun unsafeShallowFreeze() {
            super.unsafeShallowFreeze()
            this.mutability = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
        }

        override fun updateIteratorCount(delta: Int): Boolean {
            if (mutability.isFrozen()) {
                return false
            }
            if (delta > 0) {
                iteratorCount++
            } else if (delta < 0) {
                iteratorCount--
            }
            return iteratorCount > 0
        }

        override fun unsafeOptimizeMemoryLayout(): net.starlark.java.eval.StarlarkValue {
            com.google.common.base.Preconditions.checkState(mutability.isFrozen())
            // The private field contents can only be accessed if the type is MapBackedDict
            val self: MapBackedDict<K?, V?> = this
            val compact: net.starlark.java.eval.CompactImmutableDict<K?, V?> =
                net.starlark.java.eval.CompactImmutableDict.Companion.copyOf<K?, V?>(self.contents)
            self.contents = compact
            return compact
        }
    }

    /** A deeply immutable [Dict] backed by an [ImmutableMap].  */ // TODO: b/507408768 - Using CompactImmutableDict instead of this leads to more memory use because
    //  callers retain the ImmutableMap. Can callers use CompactImmutableDict instead?
    private open class ImmutableMapBackedDict<K, V>(contents: com.google.common.collect.ImmutableMap<K?, V?>?) :
        MapBackedDict<K?, V?>(contents) {
        override fun mutability(): net.starlark.java.eval.Mutability {
            return net.starlark.java.eval.Mutability.Companion.IMMUTABLE
        }

        override fun updateIteratorCount(delta: Int): Boolean {
            return false
        }
    }

    /**
     * An immutable `Dict` that tracks accessed keys.
     * 
     * 
     * Only keys present in the dict are tracked. Any call to [.keySet] or [.entrySet]
     * conservatively results in all keys being considered as accessed - notably, this happens with
     * iteration, [.repr], and a mutable copy.
     */
    class ImmutableKeyTrackingDict<K, V> private constructor(contents: com.google.common.collect.ImmutableMap<K?, V?>?) :
        ImmutableMapBackedDict<K?, V?>(contents) {
        private val accessedKeys: com.google.common.collect.ImmutableSet.Builder<K?> =
            com.google.common.collect.ImmutableSet.builder<K?>()

        fun getAccessedKeys(): com.google.common.collect.ImmutableSet<K?> {
            return accessedKeys.build()
        }

        // Present keys must be of type K.
        override fun containsKey(key: Any?): Boolean {
            if (super.containsKey(key)) {
                accessedKeys.add(key as K?)
                return true
            }
            return false
        }

        // Present keys must be of type K.
        override fun get(key: Any?): V? {
            val value = super.get(key)
            if (value != null) {
                accessedKeys.add(key as K?)
            }
            return value
        }

        override fun keySet(): MutableSet<K?> {
            val keySet = super.keySet()
            accessedKeys.addAll(keySet)
            return keySet
        }

        override fun entrySet(): MutableSet<MutableMap.MutableEntry<K?, V?>> {
            accessedKeys.addAll(super.keySet())
            return super.entrySet()
        }
    }

    companion object {
        fun getAssociatedTypeConstructor(): net.starlark.java.syntax.TypeConstructor {
            return net.starlark.java.syntax.Types.DICT_CONSTRUCTOR
        }

        /**
         * Takes ownership of the supplied LinkedHashMap and returns a new Dict that wraps it. The caller
         * must not subsequently modify the map, but the Dict may do so.
         */
        fun <K, V> wrap(mu: net.starlark.java.eval.Mutability?, contents: LinkedHashMap<K?, V?>): Dict<K?, V?>? {
            var mu: net.starlark.java.eval.Mutability? = mu
            if (mu == null) {
                mu = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
            }
            if (mu == net.starlark.java.eval.Mutability.Companion.IMMUTABLE && contents.isEmpty()) {
                return net.starlark.java.eval.Dict.Companion.empty<K?, V?>()
            }

            // TODO: b/507408768 - Can we get Mutability.IMMUTABLE here? If so, consider ImmutableDict.
            return net.starlark.java.eval.Dict.MutableDict<K?, V?>(mu, contents)
        }

        // Common implementation of dict(pairs, **kwargs) and dict.update(pairs, **kwargs).
        @Throws(net.starlark.java.eval.EvalException::class)
        fun update(
            funcname: String?, dict: Dict<Any?, Any?>, pairs: Any, kwargs: MutableMap<String?, Any?>?
        ) {
            if (pairs is MutableMap<*, *>) { // common case
                dict.putEntries(pairs)
            } else {
                val iterable: Iterable<*>?
                try {
                    iterable = net.starlark.java.eval.Starlark.Companion.toIterable(pairs)
                } catch (unused: net.starlark.java.eval.EvalException) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "in %s, got %s, want iterable",
                        funcname,
                        net.starlark.java.eval.Starlark.Companion.type(pairs)
                    )
                }
                var pos = 0
                for (item in iterable!!) {
                    val pair: Array<Any?>
                    try {
                        pair = net.starlark.java.eval.Starlark.Companion.toArray(item)
                    } catch (unused: net.starlark.java.eval.EvalException) {
                        throw net.starlark.java.eval.Starlark.Companion.errorf(
                            "in %s, dictionary update sequence element #%d is not iterable (%s)",
                            funcname, pos, net.starlark.java.eval.Starlark.Companion.type(item)
                        )
                    }
                    if (pair.size != 2) {
                        throw net.starlark.java.eval.Starlark.Companion.errorf(
                            "in %s, item #%d has length %d, but exactly two elements are required",
                            funcname, pos, pair.size
                        )
                    }
                    dict.putEntry(pair[0], pair[1])
                    pos++
                }
            }

            dict.putEntries<String?, Any?>(kwargs)
        }

        /** Returns an immutable empty dict.  */
        @kotlin.jvm.JvmStatic
        fun <K, V> empty(): Dict<K?, V?>? {
            return net.starlark.java.eval.CompactImmutableDict.Companion.empty<K?, V?>()
        }

        /** Returns a new empty dict with the specified mutability.  */
        fun <K, V> of(mu: net.starlark.java.eval.Mutability?): Dict<K?, V?>? {
            var mu: net.starlark.java.eval.Mutability? = mu
            if (mu == null) {
                mu = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
            }
            if (mu == net.starlark.java.eval.Mutability.Companion.IMMUTABLE) {
                return net.starlark.java.eval.Dict.Companion.empty<K?, V?>()
            } else {
                return net.starlark.java.eval.Dict.MutableDict<K?, V?>(
                    mu,
                    com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<K?, V?>(1)
                )
            }
        }

        /** Returns a new dict with the specified mutability containing the entries of `m`.  */
        fun <K, V> copyOf(mu: net.starlark.java.eval.Mutability?, m: MutableMap<out K?, out V?>): Dict<K?, V?>? {
            var mu: net.starlark.java.eval.Mutability? = mu
            if (mu == null) {
                mu = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
            }

            if (mu == net.starlark.java.eval.Mutability.Companion.IMMUTABLE) {
                if (m.isEmpty()) {
                    return net.starlark.java.eval.Dict.Companion.empty<K?, V?>()
                }

                if (m is com.google.common.collect.ImmutableMap) {
                    m.forEach { k: K?, v: V? ->
                        net.starlark.java.eval.Starlark.Companion.checkValid(k)
                        net.starlark.java.eval.Starlark.Companion.checkValid(v)
                    }
                    val immutableMap: com.google.common.collect.ImmutableMap<K?, V?> =
                        m as com.google.common.collect.ImmutableMap<K?, V?>
                    return net.starlark.java.eval.Dict.ImmutableMapBackedDict<K?, V?>(immutableMap)
                }

                if (m is Dict<*, *> && (m as Dict<*, *>).isImmutable()) {
                    val dict = m as Dict<K?, V?>
                    return dict
                }

                m.forEach { k: K?, v: V? ->
                    net.starlark.java.eval.Starlark.Companion.checkValid(k)
                    net.starlark.java.eval.Starlark.Companion.checkValid(v)
                }
                return net.starlark.java.eval.CompactImmutableDict.Companion.copyOf<K?, V?>(m)
            } else {
                val linkedHashMap: LinkedHashMap<K?, V?> =
                    com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<K?, V?>(m.size())
                m.forEach { k: K?, v: V? ->
                    linkedHashMap.put(
                        net.starlark.java.eval.Starlark.Companion.checkValid(k),
                        net.starlark.java.eval.Starlark.Companion.checkValid(v)
                    )
                }
                return net.starlark.java.eval.Dict.MutableDict<K?, V?>(mu, linkedHashMap)
            }
        }

        /** Returns an immutable dict containing the entries of `m`.  */
        fun <K, V> immutableCopyOf(m: MutableMap<out K?, out V?>): Dict<K?, V?>? {
            return net.starlark.java.eval.Dict.Companion.copyOf<K?, V?>(null, m)
        }

        /** Returns a new empty Dict.Builder.  */
        @kotlin.jvm.JvmStatic
        fun <K, V> builder(): Builder<K?, V?> {
            return net.starlark.java.eval.Dict.Builder<K?, V?>()
        }

        /**
         * Casts a non-null Starlark value `x` to a `Dict<K, V>` after checking that all keys
         * and values are instances of `keyType` and `valueType`, respectively. On error, it
         * throws an EvalException whose message includes `what`, ideally a string literal, as a
         * description of the role of `x`. If x is null, it returns an immutable empty dict.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun <K, V> cast(
            x: Any?,
            keyType: java.lang.Class<K?>,
            valueType: java.lang.Class<V?>,
            what: String?
        ): Dict<K?, V?> {
            com.google.common.base.Preconditions.checkNotNull<Any?>(x)
            if (x !is Dict<*, *>) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "got %s for '%s', want dict",
                    net.starlark.java.eval.Starlark.Companion.type(x),
                    what
                )
            }

            for (e in (x as MutableMap<*, *>).entrySet()) {
                if (!keyType.isAssignableFrom(e.getKey().getClass())
                    || !valueType.isAssignableFrom(e.getValue().getClass())
                ) {
                    // TODO(adonovan): change message to "found <K2, V2> entry",
                    // without suggesting that the entire dict is <K2, V2>.
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "got dict<%s, %s> for '%s', want dict<%s, %s>",
                        net.starlark.java.eval.Starlark.Companion.type(e.getKey()),
                        net.starlark.java.eval.Starlark.Companion.type(e.getValue()),
                        what,
                        net.starlark.java.eval.Starlark.Companion.classType(keyType),
                        net.starlark.java.eval.Starlark.Companion.classType(valueType)
                    )
                }
            }

            val res// safe
                    = x as Dict<K?, V?>
            return res
        }

        /** Like [.cast], but if x is None, returns an empty Dict.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun <K, V> noneableCast(
            x: Any?, keyType: java.lang.Class<K?>, valueType: java.lang.Class<V?>, what: String?
        ): Dict<K?, V?>? {
            return if (x === net.starlark.java.eval.Starlark.Companion.NONE) net.starlark.java.eval.Dict.Companion.empty<K?, V?>() else net.starlark.java.eval.Dict.Companion.cast<K?, V?>(
                x,
                keyType,
                valueType,
                what
            )
        }
    }
}
