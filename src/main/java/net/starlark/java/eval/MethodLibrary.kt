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

/** The universal predeclared functions of core Starlark.  */
internal class MethodLibrary {
    @net.starlark.java.annot.StarlarkMethod(
        name = "min",
        doc = ("Returns the smallest one of all given arguments. If only one positional argument is"
                + " provided, it must be a non-empty iterable. It is an error if elements are not"
                + " comparable (for example int with string), or if no arguments are given."
                + "<pre class=\"language-python\">\n" //
                + "min(2, 5, 4) == 2\n"
                + "min([5, 6, 3]) == 3\n"
                + "min(\"six\", \"three\", \"four\", key = len) == \"six\"  # the shortest\n"
                + "min([2, -2, -1, 1], key = abs) == -1  # the first encountered with minimal key"
                + " value\n"
                + "</pre>"),
        extraPositionals = net.starlark.java.annot.Param(name = "args", doc = "The elements to be checked."),
        parameters = [net.starlark.java.annot.Param(
            name = "key",
            named = true,
            positional = false,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkCallable::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            doc = "An optional function applied to each element before comparison.",
            defaultValue = "None"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun min(key: Any?, args: net.starlark.java.eval.Sequence<*>, thread: net.starlark.java.eval.StarlarkThread): Any? {
        return net.starlark.java.eval.MethodLibrary.Companion.findExtreme(
            args,
            net.starlark.java.eval.Starlark.Companion.toJavaOptional<net.starlark.java.eval.StarlarkCallable?>(
                key,
                net.starlark.java.eval.StarlarkCallable::class.java
            ),
            net.starlark.java.eval.Starlark.Companion.ORDERING.reverse<Any?>(),
            thread
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "max",
        doc = ("Returns the largest one of all given arguments. If only one positional argument is"
                + " provided, it must be a non-empty iterable.It is an error if elements are not"
                + " comparable (for example int with string), or if no arguments are given."
                + "<pre class=\"language-python\">\n" //
                + "max(2, 5, 4) == 5\n"
                + "max([5, 6, 3]) == 6\n"
                + "max(\"two\", \"three\", \"four\", key = len) ==\"three\"  # the longest\n"
                + "max([1, -1, -2, 2], key = abs) == -2  # the first encountered with maximal key"
                + " value\n"
                + "</pre>"),
        extraPositionals = net.starlark.java.annot.Param(name = "args", doc = "The elements to be checked."),
        parameters = [net.starlark.java.annot.Param(
            name = "key",
            named = true,
            positional = false,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkCallable::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            doc = "An optional function applied to each element before comparison.",
            defaultValue = "None"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun max(key: Any?, args: net.starlark.java.eval.Sequence<*>, thread: net.starlark.java.eval.StarlarkThread): Any? {
        return net.starlark.java.eval.MethodLibrary.Companion.findExtreme(
            args,
            net.starlark.java.eval.Starlark.Companion.toJavaOptional<net.starlark.java.eval.StarlarkCallable?>(
                key,
                net.starlark.java.eval.StarlarkCallable::class.java
            ),
            net.starlark.java.eval.Starlark.Companion.ORDERING,
            thread
        )
    }

    /**
     * Original value decorated with its comparison key; storing the comparison key alongside the
     * value ensures that we call the comparison key computation function only once per original value
     * (which is important in case the function has side effects).
     */
    private class ValueWithComparisonKey(value: Any?, comparisonKey: Any?) {
        private val value: Any?
        private val comparisonKey: Any?

        init {
            this.value = value
            this.comparisonKey = comparisonKey
        }

        fun getValue(): Any? {
            return value
        }

        fun getComparisonKey(): Any? {
            return comparisonKey
        }

        /**
         * An unchecked exception wrapping an exception thrown by [Starlark.positionalOnlyCall].
         */
        private class KeyCallException(cause: java.lang.Exception?) : java.lang.RuntimeException(cause)
        companion object {
            /**
             * @throws KeyCallException wrapping the exception thrown by the underlying [     ][Starlark.positionalOnlyCall] call if it threw.
             */
            fun make(
                value: Any?,
                keyFn: net.starlark.java.eval.StarlarkCallable?,
                thread: net.starlark.java.eval.StarlarkThread
            ): ValueWithComparisonKey {
                try {
                    return net.starlark.java.eval.MethodLibrary.ValueWithComparisonKey(
                        value,
                        net.starlark.java.eval.Starlark.Companion.positionalOnlyCall(thread, keyFn, value)
                    )
                } catch (ex: net.starlark.java.eval.EvalException) {
                    throw net.starlark.java.eval.MethodLibrary.ValueWithComparisonKey.KeyCallException(ex)
                } catch (ex: java.lang.InterruptedException) {
                    throw net.starlark.java.eval.MethodLibrary.ValueWithComparisonKey.KeyCallException(ex)
                }
            }
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "abs",
        doc = ("Returns the absolute value of a number (a non-negative number with the same magnitude)."
                + "<pre class=\"language-python\">abs(-2.3) == 2.3</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.StarlarkFloat::class
            )],
            doc = "A number (int or float)"
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun abs(x: Any): Any {
        if (x is net.starlark.java.eval.StarlarkInt) {
            if (x.signum() < 0) {
                return net.starlark.java.eval.StarlarkInt.Companion.uminus(x)
            }
            return x
        }

        val value: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
        return net.starlark.java.eval.StarlarkFloat.Companion.of(java.lang.Math.abs(value))
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "all",
        doc = ("Returns true if all elements evaluate to True or if the collection is empty. "
                + "Elements are converted to boolean using the <a href=\"#bool\">bool</a> function."
                + "<pre class=\"language-python\">all([\"hello\", 3, True]) == True\n"
                + "all([-1, 0, 1]) == False</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "elements", doc = "A collection of elements.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun all(collection: net.starlark.java.eval.StarlarkIterable<Any?>): Boolean {
        return !net.starlark.java.eval.MethodLibrary.Companion.hasElementWithBooleanValue(collection, false)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "any",
        doc = ("Returns true if at least one element evaluates to True. "
                + "Elements are converted to boolean using the <a href=\"#bool\">bool</a> function."
                + "<pre class=\"language-python\">any([-1, 0, 1]) == True\n"
                + "any([False, 0, \"\"]) == False</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "elements", doc = "A collection of elements.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun any(collection: net.starlark.java.eval.StarlarkIterable<Any?>): Boolean {
        return net.starlark.java.eval.MethodLibrary.Companion.hasElementWithBooleanValue(collection, true)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "sorted",
        doc = ("Returns a new sorted list containing all the elements of the supplied iterable"
                + " sequence. An error may occur if any pair of elements x, y may not be compared"
                + " using x < y. The elements are sorted into ascending order, unless the reverse"
                + " argument is True, in which case the order is descending.\n"
                + " Sorting is stable: elements that compare equal retain their original relative"
                + " order.\n" //
                + "<pre class=\"language-python\">\n" //
                + "sorted([3, 5, 4]) == [3, 4, 5]\n" //
                + "sorted([3, 5, 4], reverse = True) == [5, 4, 3]\n" //
                + "sorted([\"two\", \"three\", \"four\"], key = len) == [\"two\", \"four\","
                + " \"three\"]  # sort by length\n" //
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "iterable",
            doc = "The iterable sequence to sort."
        ), net.starlark.java.annot.Param(
            name = "key",
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkCallable::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            doc = "An optional function applied to each element before comparison.",
            defaultValue = "None"
        ), net.starlark.java.annot.Param(
            name = "reverse",
            doc = "Return results in descending order.",
            named = true,
            defaultValue = "False",
            positional = false
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun sorted(
        iterable: net.starlark.java.eval.StarlarkIterable<*>?,
        key: Any?,
        reverse: Boolean,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<*>? {
        val array: Array<Any?> = net.starlark.java.eval.Starlark.Companion.toArray(iterable)
        val order: java.util.Comparator<Any?> =
            if (reverse) net.starlark.java.eval.Starlark.Companion.ORDERING.reversed() else net.starlark.java.eval.Starlark.Companion.ORDERING

        // no key?
        if (key === net.starlark.java.eval.Starlark.Companion.NONE) {
            try {
                java.util.Arrays.sort<Any?>(array, order)
            } catch (ex: java.lang.ClassCastException) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("%s", ex.getMessage())
            }
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), array)
        }

        // The user provided a key function.
        // We must call it exactly once per element, in order,
        // so use the decorate/sort/undecorate pattern.
        val keyfn: net.starlark.java.eval.StarlarkCallable? = key as net.starlark.java.eval.StarlarkCallable?

        // decorate
        for (i in array.indices) {
            val v = array[i]
            val k: Any? = net.starlark.java.eval.Starlark.Companion.positionalOnlyCall(thread, keyfn, v)
            array[i] = arrayOf<Any?>(k, v)
        }

        class KeyComparator : java.util.Comparator<Any?> {
            var e: net.starlark.java.eval.EvalException? = null

            override fun compare(x: Any, y: Any): Int {
                val xkey = (x as Array<Any?>?)!![0]
                val ykey = (y as Array<Any?>?)!![0]
                try {
                    return order.compare(xkey, ykey)
                } catch (e: java.lang.ClassCastException) {
                    if (this.e == null) {
                        this.e = net.starlark.java.eval.EvalException(e.getMessage())
                    }
                    return 0 // may cause Arrays.sort to fail; see below
                }
            }
        }

        // sort
        val comp = KeyComparator()
        try {
            java.util.Arrays.sort<Any?>(array, comp)
        } catch (unused: java.lang.IllegalArgumentException) {
            // Arrays.sort failed because comp violated the Comparator contract.
            checkNotNull(comp.e) { "sort: element ordering is not self-consistent" }
        }

        // Sort completed, possibly with deferred errors.
        if (comp.e != null) {
            throw comp.e
        }

        // undecorate
        for (i in array.indices) {
            array[i] = (array[i] as Array<Any?>?)!![1]
        }

        return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), array)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "reversed",
        doc = ("Returns a new, unfrozen list that contains the elements of the original iterable"
                + " sequence in reversed order.<pre class=\"language-python\">reversed([3, 5, 4]) =="
                + " [4, 5, 3]</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "sequence",
            doc = "The iterable sequence (e.g. list) to be reversed."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun reversed(
        sequence: net.starlark.java.eval.StarlarkIterable<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<*>? {
        val array: Array<Any?> = net.starlark.java.eval.Starlark.Companion.toArray(sequence)
        net.starlark.java.eval.MethodLibrary.Companion.reverse(array)
        return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), array)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "tuple",
        doc = ("Returns a tuple with the same elements as the given iterable value."
                + "<pre class=\"language-python\">tuple([1, 2]) == (1, 2)\n"
                + "tuple((2, 3, 2)) == (2, 3, 2)\n"
                + "tuple({5: \"a\", 2: \"b\", 4: \"c\"}) == (5, 2, 4)</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "x", defaultValue = "()", doc = "The object to convert.")],
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun tuple(x: net.starlark.java.eval.StarlarkIterable<*>?): net.starlark.java.eval.Tuple? {
        if (x is net.starlark.java.eval.Tuple) {
            return x as net.starlark.java.eval.Tuple
        }
        return net.starlark.java.eval.Tuple.Companion.wrap(net.starlark.java.eval.Starlark.Companion.toArray(x))
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "list",
        doc = ("Returns a new list with the same elements as the given iterable value."
                + "<pre class=\"language-python\">list([1, 2]) == [1, 2]\n"
                + "list((2, 3, 2)) == [2, 3, 2]\n"
                + "list({5: \"a\", 2: \"b\", 4: \"c\"}) == [5, 2, 4]</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "x", defaultValue = "[]", doc = "The object to convert.")],
        useStarlarkThread = true,
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun list(
        x: net.starlark.java.eval.StarlarkIterable<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<*>? {
        return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(
            thread.mutability(),
            net.starlark.java.eval.Starlark.Companion.toArray(x)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "len",
        doc = ("Returns the length of a string, sequence (such as a list or tuple), dict, set, or other"
                + " iterable."),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            doc = "The value whose length to report.",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.StarlarkIterable::class,
                generic1 = Any::class
            ), net.starlark.java.annot.ParamType(type = String::class)]
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun len(x: Any, thread: net.starlark.java.eval.StarlarkThread?): Int {
        val len: Int = net.starlark.java.eval.Starlark.Companion.len(x)
        if (len < 0) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "%s is not iterable",
                net.starlark.java.eval.Starlark.Companion.type(x)
            )
        }
        return len
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "str",
        doc = ("Converts any object to string. This is useful for debugging."
                + "<pre class=\"language-python\">str(\"ab\") == \"ab\"\n"
                + "str(8) == \"8\"</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "x", doc = "The object to convert.")],
        useStarlarkThread = true,
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun str(x: Any?, thread: net.starlark.java.eval.StarlarkThread): String {
        return net.starlark.java.eval.Starlark.Companion.str(x, thread.getSemantics())
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "repr",
        doc = ("Converts any object to a string representation. This is useful for debugging.<br>"
                + "<pre class=\"language-python\">repr(\"ab\") == '\"ab\"'</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "x", doc = "The object to convert.")],
        useStarlarkThread = true
    )
    fun repr(x: Any?, thread: net.starlark.java.eval.StarlarkThread): String {
        return net.starlark.java.eval.Starlark.Companion.repr(x, thread.getSemantics())
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "bool",
        doc = ("Constructor for the bool type. "
                + "It returns <code>False</code> if the object is <code>None</code>, <code>False"
                + "</code>, an empty string (<code>\"\"</code>), the number <code>0</code>, or an "
                + "empty collection (e.g. <code>()</code>, <code>[]</code>). "
                + "Otherwise, it returns <code>True</code>."),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            defaultValue = "False",
            doc = "The variable to convert."
        )],
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun bool(x: Any?): Boolean {
        return net.starlark.java.eval.Starlark.Companion.truth(x)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "float",
        doc = ("Returns x as a float value. " //
                + "<ul><li>If <code>x</code> is already a float, <code>float</code> returns it"
                + " unchanged. " //
                + "<li>If <code>x</code> is a bool, <code>float</code> returns 1.0 for True and 0.0"
                + " for False. " //
                + "<li>If <code>x</code> is an int, <code>float</code> returns the nearest"
                + " finite floating-point value to x, or an error if the magnitude is too large. " //
                + "<li>If <code>x</code> is a string, it must be a valid floating-point literal, or"
                + " be equal (ignoring case) to <code>NaN</code>, <code>Inf</code>, or"
                + " <code>Infinity</code>, optionally preceded by a <code>+</code> or <code>-</code>"
                + " sign. " //
                + "</ul>" //
                + "Any other value causes an error. With no argument, <code>float()</code> returns"
                + " 0.0."),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            doc = "The value to convert.",
            defaultValue = "unbound",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = Boolean::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.StarlarkFloat::class
            )]
        )],
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun floatForStarlark(x: Any): net.starlark.java.eval.StarlarkFloat {
        if (x is String) {
            if (x.isEmpty()) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("empty string")
            }

            val d: Double
            when (com.google.common.base.Ascii.toLowerCase(x.charAt(x.length() - 1))) {
                'n', 'f', 'y' ->           // non-finite
                    if (com.google.common.base.Ascii.equalsIgnoreCase(x, "nan")
                        || com.google.common.base.Ascii.equalsIgnoreCase(x, "+nan")
                        || com.google.common.base.Ascii.equalsIgnoreCase(x, "-nan")
                    ) {
                        d = java.lang.Double.NaN
                    } else if (com.google.common.base.Ascii.equalsIgnoreCase(x, "inf")
                        || com.google.common.base.Ascii.equalsIgnoreCase(x, "+inf")
                        || com.google.common.base.Ascii.equalsIgnoreCase(x, "+infinity")
                    ) {
                        d = java.lang.Double.POSITIVE_INFINITY
                    } else if (com.google.common.base.Ascii.equalsIgnoreCase(
                            x,
                            "-inf"
                        ) || com.google.common.base.Ascii.equalsIgnoreCase(x, "-infinity")
                    ) {
                        d = java.lang.Double.NEGATIVE_INFINITY
                    } else {
                        throw net.starlark.java.eval.Starlark.Companion.errorf("invalid float literal: %s", x)
                    }

                else ->           // finite
                    try {
                        d = java.lang.Double.parseDouble(x)
                        if (!java.lang.Double.isFinite(d)) {
                            // parseDouble accepts signed "NaN" and "Infinity" (case sensitive)
                            // but we already handled those cases, so this indicates
                            // a large number rounded to infinity.
                            throw net.starlark.java.eval.Starlark.Companion.errorf("floating-point number too large")
                        }
                    } catch (unused: java.lang.NumberFormatException) {
                        throw net.starlark.java.eval.Starlark.Companion.errorf("invalid float literal: %s", x)
                    }
            } // switch
            return net.starlark.java.eval.StarlarkFloat.Companion.of(d)
        } else if (x is Boolean) {
            return net.starlark.java.eval.StarlarkFloat.Companion.of((if (x) 1 else 0).toDouble())
        } else if (x is net.starlark.java.eval.StarlarkInt) {
            return net.starlark.java.eval.StarlarkFloat.Companion.of((x as net.starlark.java.eval.StarlarkInt).toFiniteDouble())
        } else if (x is net.starlark.java.eval.StarlarkFloat) {
            return x as net.starlark.java.eval.StarlarkFloat
        } else if (x === net.starlark.java.eval.Starlark.Companion.UNBOUND) {
            return net.starlark.java.eval.StarlarkFloat.Companion.of(0.0)
        } else {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "got %s, want string, int, float, or bool",
                net.starlark.java.eval.Starlark.Companion.type(x)
            )
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "int",
        doc = ("Returns x as an int value."
                + "<ul>"
                + "<li>If <code>x</code> is already an int, <code>int</code> returns it unchanged." //
                + "<li>If <code>x</code> is a bool, <code>int</code> returns 1 for True and 0 for"
                + " False." //
                + "<li>If <code>x</code> is a string, it must have the format "
                + "    <code>&lt;sign&gt;&lt;prefix&gt;&lt;digits&gt;</code>. "
                + "    <code>&lt;sign&gt;</code> is either <code>\"+\"</code>, <code>\"-\"</code>, "
                + "    or empty (interpreted as positive). <code>&lt;digits&gt;</code> are a "
                + "    sequence of digits from 0 up to <code>base</code> - 1, where the letters a-z "
                + "    (or equivalently, A-Z) are used as digits for 10-35. In the case where "
                + "    <code>base</code> is 2/8/16, <code>&lt;prefix&gt;</code> is optional and may "
                + "    be 0b/0o/0x (or equivalently, 0B/0O/0X) respectively; if the "
                + "    <code>base</code> is any other value besides these bases or the special value "
                + "    0, the prefix must be empty. In the case where <code>base</code> is 0, the "
                + "    string is interpreted as an integer literal, in the sense that one of the "
                + "    bases 2/8/10/16 is chosen depending on which prefix if any is used. If "
                + "    <code>base</code> is 0, no prefix is used, and there is more than one digit, "
                + "    the leading digit cannot be 0; this is to avoid confusion between octal and "
                + "    decimal. The magnitude of the number represented by the string must be within "
                + "    the allowed range for the int type." //
                + "<li>If <code>x</code> is a float, <code>int</code> returns the integer value of"
                + "    the float, rounding towards zero. It is an error if x is non-finite (NaN or"
                + "    infinity)."
                + "</ul>" //
                + "This function fails if <code>x</code> is any other type, or if the value is a "
                + "string not satisfying the above format. Unlike Python's <code>int</code> "
                + "function, this function does not allow zero arguments, and does "
                + "not allow extraneous whitespace for string arguments.<p>" //
                + "Examples:<pre class=\"language-python\">int(\"123\") == 123\n"
                + "int(\"-123\") == -123\n"
                + "int(\"+123\") == 123\n"
                + "int(\"FF\", 16) == 255\n"
                + "int(\"0xFF\", 16) == 255\n"
                + "int(\"10\", 0) == 10\n"
                + "int(\"-0x10\", 0) == -16\n"
                + "int(\"-0x10\", 0) == -16\n"
                + "int(\"123.456\") == 123\n"
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            doc = "The string to convert.",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = Boolean::class
            ), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.StarlarkFloat::class
            )]
        ), net.starlark.java.annot.Param(
            name = "base",
            defaultValue = "unbound",
            doc = ("The base used to interpret a string value; defaults to 10. Must be between 2 "
                    + "and 36 (inclusive), or 0 to detect the base as if <code>x</code> were an "
                    + "integer literal. This parameter must not be supplied if the value is not a "
                    + "string."),
            named = true,
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class)]
        )],
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun intForStarlark(x: Any, baseO: Any?): net.starlark.java.eval.StarlarkInt? {
        if (x is String) {
            val base =
                if (baseO === net.starlark.java.eval.Starlark.Companion.UNBOUND) 10 else net.starlark.java.eval.Starlark.Companion.toInt(
                    baseO,
                    "base"
                )
            try {
                return net.starlark.java.eval.StarlarkInt.Companion.parse(x, base)
            } catch (ex: java.lang.NumberFormatException) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("%s", ex.getMessage())
            }
        }

        if (baseO !== net.starlark.java.eval.Starlark.Companion.UNBOUND) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("can't convert non-string with explicit base")
        }
        if (x is Boolean) {
            return net.starlark.java.eval.StarlarkInt.Companion.of(if (x) 1 else 0)
        } else if (x is net.starlark.java.eval.StarlarkInt) {
            return x as net.starlark.java.eval.StarlarkInt
        } else if (x is net.starlark.java.eval.StarlarkFloat) {
            try {
                return net.starlark.java.eval.StarlarkInt.Companion.ofFiniteDouble((x as net.starlark.java.eval.StarlarkFloat).toDouble())
            } catch (unused: java.lang.IllegalArgumentException) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("can't convert float %s to int", x)
            }
        }
        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "got %s, want string, int, float, or bool",
            net.starlark.java.eval.Starlark.Companion.type(x)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "dict",
        doc = ("Creates a <a href=\"../core/dict.html\">dictionary</a> from an optional positional "
                + "argument and an optional set of keyword arguments. In the case where the same key "
                + "is given multiple times, the last value will be used. Entries supplied via "
                + "keyword arguments are considered to come after entries supplied via the "
                + "positional argument."),
        parameters = [net.starlark.java.annot.Param(
            name = "pairs",
            defaultValue = "[]",
            doc = "A dict, or an iterable whose elements are each of length 2 (key, value)."
        )],
        extraKeywords = net.starlark.java.annot.Param(name = "kwargs", doc = "Dictionary of additional entries."),
        useStarlarkThread = true,
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun dict(
        pairs: Any?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.Dict<*, *>? {
        // common case: dict(k=v, ...)
        if (pairs is net.starlark.java.eval.StarlarkList<*> && (pairs as net.starlark.java.eval.StarlarkList<*>).isEmpty()) {
            return kwargs
        }
        val dict: net.starlark.java.eval.Dict<Any?, Any?>? =
            net.starlark.java.eval.Dict.Companion.of<Any?, Any?>(thread.mutability())
        net.starlark.java.eval.Dict.Companion.update("dict", dict, pairs, kwargs)
        return dict
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "set",
        doc = """
Creates a new <a href=${'"'}../core/set.html${'"'}>set</a> containing the unique elements of a given
iterable, preserving iteration order.

<p>If called with no argument, <code>set()</code> returns a new empty set.

<p>For example,
<pre class=language-python>
set()                          # an empty set
set([3, 1, 1, 2])              # set([3, 1, 2]), a set of three elements
set({"k1": "v1", "k2": "v2"})  # set(["k1", "k2"]), a set of two elements
</pre>

""".trimIndent(),
        parameters = [net.starlark.java.annot.Param(
            name = "elements",
            defaultValue = "[]",
            doc = "An iterable of hashable values."
        )],
        useStarlarkThread = true,
        isTypeConstructor = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun set(
        elements: net.starlark.java.eval.StarlarkIterable<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkSet<*>? {
        // Ordinarily we would use StarlarkMethod#enableOnlyWithFlag, but this doesn't work for
        // top-level symbols, so enforce it here instead.
        if (!thread.getSemantics()
                .getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_ENABLE_STARLARK_SET)
        ) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("Use of set() requires --experimental_enable_starlark_set")
        }
        return net.starlark.java.eval.StarlarkSet.Companion.checkedCopyOf(thread.mutability(), elements)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "enumerate",
        doc = ("Returns a list of pairs (two-element tuples), with the index (int) and the item from"
                + " the input sequence.\n<pre class=\"language-python\">"
                + "enumerate([24, 21, 84]) == [(0, 24), (1, 21), (2, 84)]</pre>\n"),
        parameters = [net.starlark.java.annot.Param(
            name = "list",
            doc = "input sequence.",
            named = true
        ), net.starlark.java.annot.Param(name = "start", doc = "start index.", defaultValue = "0", named = true)],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun enumerate(
        input: Any?,
        startI: net.starlark.java.eval.StarlarkInt?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<*>? {
        val start: Int = net.starlark.java.eval.Starlark.Companion.toInt(startI, "start")
        val array: Array<Any?> = net.starlark.java.eval.Starlark.Companion.toArray(input)
        for (i in array.indices) {
            array[i] = net.starlark.java.eval.Tuple.Companion.pair(
                net.starlark.java.eval.StarlarkInt.Companion.of(i + start),
                array[i]
            ) // update in place
        }
        return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(thread.mutability(), array)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "hash",
        doc = ("Return a hash value for a string. This is computed deterministically using the same "
                + "algorithm as Java's <code>String.hashCode()</code>, namely: "
                + "<pre class=\"language-python\">s[0] * (31^(n-1)) + s[1] * (31^(n-2)) + ... + "
                + "s[n-1]</pre> Hashing of values besides strings is not currently supported."),
        parameters = [net.starlark.java.annot.Param(name = "value", doc = "String value to hash.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun hash(value: String): Int {
        return value.hashCode()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "range", doc = ("Creates a list where items go from <code>start</code> to <code>stop</code>, using a "
                + "<code>step</code> increment. If a single argument is provided, items will "
                + "range from 0 to that element."
                + "<pre class=\"language-python\">range(4) == [0, 1, 2, 3]\n"
                + "range(3, 9, 2) == [3, 5, 7]\n"
                + "range(3, 0, -1) == [3, 2, 1]</pre>"), parameters = [net.starlark.java.annot.Param(
            name = "start_or_stop", doc = "Value of the start element if stop is provided, "
                    + "otherwise value of stop and the actual start is 0"
        ), net.starlark.java.annot.Param(
            name = "stop",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class)],
            defaultValue = "unbound",
            doc = "optional index of the first item <i>not</i> to be included in the resulting "
                    + "list; generation of the list stops before <code>stop</code> is reached."
        ), net.starlark.java.annot.Param(
            name = "step",
            defaultValue = "1",
            doc = "The increment (default is 1). It may be negative."
        )], useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun range(
        startOrStop: net.starlark.java.eval.StarlarkInt,
        stopOrUnbound: Any?,
        stepI: net.starlark.java.eval.StarlarkInt,
        thread: net.starlark.java.eval.StarlarkThread?
    ): net.starlark.java.eval.Sequence<net.starlark.java.eval.StarlarkInt?> {
        val start: Int
        val stop: Int
        if (stopOrUnbound === net.starlark.java.eval.Starlark.Companion.UNBOUND) {
            start = 0
            stop = startOrStop.toInt("stop")
        } else {
            start = startOrStop.toInt("start")
            stop = net.starlark.java.eval.Starlark.Companion.toInt(stopOrUnbound, "stop")
        }
        val step: Int = stepI.toInt("step")
        if (step == 0) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("step cannot be 0")
        }
        // TODO(adonovan): support arbitrary integers.
        return net.starlark.java.eval.RangeList(start, stop, step)
    }

    /** Returns true if the object has a field of the given name, otherwise false.  */
    @net.starlark.java.annot.StarlarkMethod(
        name = "hasattr",
        doc = ("Returns True if the object <code>x</code> has an attribute or method of the given "
                + "<code>name</code>, otherwise False. Example:<br>"
                + "<pre class=\"language-python\">hasattr(ctx.attr, \"myattr\")</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            doc = "The object to check."
        ), net.starlark.java.annot.Param(name = "name", doc = "The name of the attribute.")],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun hasattr(obj: Any?, name: String?, thread: net.starlark.java.eval.StarlarkThread): Boolean {
        return net.starlark.java.eval.Starlark.Companion.hasattr(thread, obj, name)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "getattr",
        doc = ("Returns the struct's field of the given name if it exists. If not, it either returns "
                + "<code>default</code> (if specified) or raises an error. "
                + "<code>getattr(x, \"foobar\")</code> is equivalent to <code>x.foobar</code>."
                + "<pre class=\"language-python\">getattr(ctx.attr, \"myattr\")\n"
                + "getattr(ctx.attr, \"myattr\", \"mydefault\")</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            doc = "The struct whose attribute is accessed."
        ), net.starlark.java.annot.Param(
            name = "name",
            doc = "The name of the struct attribute."
        ), net.starlark.java.annot.Param(
            name = "default", defaultValue = "unbound", doc = "The default value to return in case the struct "
                    + "doesn't have an attribute of the given name."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun getattr(obj: Any?, name: String?, defaultValue: Any?, thread: net.starlark.java.eval.StarlarkThread): Any? {
        return net.starlark.java.eval.Starlark.Companion.getattr(
            thread,
            obj,
            name,
            if (defaultValue === net.starlark.java.eval.Starlark.Companion.UNBOUND) null else defaultValue
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "dir",
        doc = ("Returns a list of strings: the names of the attributes and "
                + "methods of the parameter object."),
        parameters = [net.starlark.java.annot.Param(name = "x", doc = "The object to check.")],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun dir(
        `object`: Any?,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<String?>? {
        return net.starlark.java.eval.Starlark.Companion.dir(thread, `object`)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "fail", doc = "Causes execution to fail with an error.", parameters = [net.starlark.java.annot.Param(
            name = "msg",
            doc = "Deprecated: use positional arguments instead. "
                    + "This argument acts like an implicit leading positional argument.",
            defaultValue = "None",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "attr",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "Deprecated. Causes an optional prefix containing this string to be added to the"
                    + " error message.",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "sep",
            defaultValue = "\" \"",
            named = true,
            positional = false,
            doc = "The separator string between the objects, default is space (\" \")."
        ), net.starlark.java.annot.Param(
            name = "stack_trace",
            doc = "If False stack trace is elided from failure for friendlier user messages",
            defaultValue = "True",
            positional = false,
            named = true
        )], extraPositionals = net.starlark.java.annot.Param(
            name = "args", doc = ("A list of values, formatted with debugPrint (which is equivalent to str by"
                    + " default) and joined with sep (defaults to \" \"), that appear in the"
                    + " error message.")
        ), useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun fail(
        msg: Any?,
        attr: Any?,
        sep: String?,
        stackTrace: Boolean,
        args: net.starlark.java.eval.Tuple,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val includeStack =
            stackTrace || thread.getSemantics()
                .getBool(net.starlark.java.eval.StarlarkSemantics.Companion.FORCE_STARLARK_STACK_TRACE)
        val printer: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer()
        var needSeparator = false
        if (attr !== net.starlark.java.eval.Starlark.Companion.NONE) {
            printer.append("attribute ").append(attr as String?).append(":")
            needSeparator = true
        }
        // msg acts like a leading element of args.
        if (msg !== net.starlark.java.eval.Starlark.Companion.NONE) {
            if (needSeparator) {
                printer.append(sep)
            }
            printer.debugPrint(msg, thread)
            needSeparator = true
        }
        for (arg in args) {
            if (needSeparator) {
                printer.append(sep)
            }
            printer.debugPrint(arg, thread)
            needSeparator = true
        }
        throw net.starlark.java.eval.EvalException(printer.toString(), null, includeStack)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "print",
        doc = ("Prints <code>args</code> as debug output. It will be prefixed with the string <code>"
                + "\"DEBUG\"</code> and the location (file and line number) of this call. The "
                + "exact way in which the arguments are converted to strings is unspecified and may "
                + "change at any time. In particular, it may be different from (and more detailed "
                + "than) the formatting done by <a href='#str'><code>str()</code></a> and <a "
                + "href='#repr'><code>repr()</code></a>."
                + "<p>Using <code>print</code> in production code is discouraged due to the spam it "
                + "creates for users. For deprecations, prefer a hard error using <a href=\"#fail\">"
                + "<code>fail()</code></a> whenever possible."),
        parameters = [net.starlark.java.annot.Param(
            name = "sep",
            defaultValue = "\" \"",
            named = true,
            positional = false,
            doc = "The separator string between the objects, default is space (\" \")."
        )],
        extraPositionals = net.starlark.java.annot.Param(name = "args", doc = "The objects to print."),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun print(sep: String?, args: net.starlark.java.eval.Sequence<*>, thread: net.starlark.java.eval.StarlarkThread) {
        val p: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer()
        var separator: String? = ""
        for (x in args) {
            p.append(separator)
            p.debugPrint(x, thread)
            separator = sep
        }
        // The PRINT_TEST_MARKER key is used in tests to verify the effects of command-line options.
        // See starlark_flag_test.sh, which runs bazel with --internal_starlark_flag_test_canary.
        if (thread.getSemantics().getBool(net.starlark.java.eval.StarlarkSemantics.Companion.PRINT_TEST_MARKER)) {
            p.append("<== Starlark flag test ==>")
        }

        thread.getPrintHandler().print(thread, p.toString())
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "type",
        doc = ("Returns the type name of its argument. This is useful for debugging and "
                + "type-checking. Examples:"
                + "<pre class=\"language-python\">"
                + "type(2) == \"int\"\n"
                + "type([1]) == \"list\"\n"
                + "type(struct(a = 2)) == \"struct\""
                + "</pre>"
                + "This function might change in the future. To write Python-compatible code and "
                + "be future-proof, use it only to compare return values: "
                + "<pre class=\"language-python\">"
                + "if type(x) == type([]):  # if x is a list"
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "x", doc = "The object to check type of.")]
    )
    fun type(`object`: Any): String? {
        // There is no 'type' type in Starlark, so we return a string with the type name.
        return net.starlark.java.eval.Starlark.Companion.type(`object`)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "zip",
        doc = ("Returns a <code>list</code> of <code>tuple</code>s, where the i-th tuple contains "
                + "the i-th element from each of the argument sequences or iterables. The list has "
                + "the size of the shortest input. With a single iterable argument, it returns a "
                + "list of 1-tuples. With no arguments, it returns an empty list. Examples:"
                + "<pre class=\"language-python\">"
                + "zip()  # == []\n"
                + "zip([1, 2])  # == [(1,), (2,)]\n"
                + "zip([1, 2], [3, 4])  # == [(1, 3), (2, 4)]\n"
                + "zip([1, 2], [3, 4, 5])  # == [(1, 3), (2, 4)]</pre>"),
        extraPositionals = net.starlark.java.annot.Param(name = "args", doc = "lists to zip."),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun zip(
        args: net.starlark.java.eval.Sequence<*>,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<*> {
        val result: net.starlark.java.eval.StarlarkList<net.starlark.java.eval.Tuple?> =
            net.starlark.java.eval.StarlarkList.Companion.newList<net.starlark.java.eval.Tuple?>(thread.mutability())
        val ncols: Int = args.size()
        if (ncols > 0) {
            val iterators = arrayOfNulls<MutableIterator<*>>(ncols)
            for (i in 0..<ncols) {
                iterators[i] = net.starlark.java.eval.Starlark.Companion.toIterable(args.get(i)).iterator()
            }
            rows@ while (true) {
                val elem = arrayOfNulls<Any>(ncols)
                for (i in 0..<ncols) {
                    val it: MutableIterator<*> = iterators[i]!!
                    if (!it.hasNext()) {
                        break@rows
                    }
                    elem[i] = it.next()
                }
                result.addElement(net.starlark.java.eval.Tuple.Companion.wrap(elem))
            }
        }
        return result
    }

    /** Starlark bool type.  */
    @net.starlark.java.annot.StarlarkBuiltin(
        name = "bool", category = "core", doc = ("A type to represent booleans. There are only two possible values: "
                + "True and False. Any value can be converted to a boolean using the "
                + "<a href=\"../globals/all.html#bool\">bool</a> function.")
    )
    internal class BoolModule : net.starlark.java.eval.StarlarkValue // (documentation only)
    companion object {
        /** Returns the maximum element from this list, as determined by maxOrdering.  */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        private fun findExtreme(
            args: net.starlark.java.eval.Sequence<*>,
            keyFn: java.util.Optional<net.starlark.java.eval.StarlarkCallable?>,
            maxOrdering: com.google.common.collect.Ordering<Any?>,
            thread: net.starlark.java.eval.StarlarkThread
        ): Any? {
            // Args can either be a list of items to compare, or a singleton list whose element is an
            // iterable of items to compare. In either case, there must be at least one item to compare.
            val items: Iterable<*> =
                if (args.size() == 1) net.starlark.java.eval.Starlark.Companion.toIterable(args.get(0)) else args
            try {
                if (keyFn.isPresent()) {
                    try {
                        return com.google.common.collect.Streams.stream(items)
                            .map<ValueWithComparisonKey?> { value: Any? ->
                                net.starlark.java.eval.MethodLibrary.ValueWithComparisonKey.Companion.make(
                                    value,
                                    keyFn.get(),
                                    thread
                                )
                            }
                            .max(
                                java.util.Comparator.comparing<ValueWithComparisonKey?, Any?>(
                                    java.util.function.Function { obj: ValueWithComparisonKey? -> obj!!.getComparisonKey() },
                                    maxOrdering
                                )
                            )
                            .get()
                            .getValue()
                    } catch (ex: ValueWithComparisonKey.KeyCallException) {
                        com.google.common.base.Throwables.throwIfInstanceOf<net.starlark.java.eval.EvalException?>(
                            ex.getCause(),
                            net.starlark.java.eval.EvalException::class.java
                        )
                        com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                            ex.getCause(),
                            java.lang.InterruptedException::class.java
                        )
                        throw java.lang.AssertionError("Got invalid ValueWithComparisonKey.KeyCallException", ex)
                    }
                } else {
                    return maxOrdering.max(items)
                }
            } catch (ex: java.lang.ClassCastException) {
                throw net.starlark.java.eval.EvalException(ex.getMessage()) // e.g. unsupported comparison: int <=> string
            } catch (ex: java.util.NoSuchElementException) {
                throw net.starlark.java.eval.EvalException("expected at least one item", ex)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun hasElementWithBooleanValue(
            seq: net.starlark.java.eval.StarlarkIterable<Any?>,
            value: Boolean
        ): Boolean {
            for (x in seq) {
                if (net.starlark.java.eval.Starlark.Companion.truth(x) == value) {
                    return true
                }
            }
            return false
        }

        private fun reverse(array: Array<Any?>) {
            var i = 0
            var j = array.size - 1
            while (i < j) {
                val tmp = array[i]
                array[i] = array[j]
                array[j] = tmp
                i++
                j--
            }
        }
    }
}
