// Copyright 2018 The Bazel Authors. All rights reserved.
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

/**
 * Starlark String module.
 * 
 * 
 * This module has special treatment in Starlark, as its methods represent methods present for
 * any 'string' objects in the language.
 * 
 * 
 * Methods of this class annotated with [StarlarkMethod] must have a positional-only
 * 'String self' parameter as the first parameter of the method.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "string", category = "core", doc = ("A language built-in type to support strings. "
            + "Examples of string literals:<br>"
            + "<pre class=\"language-python\">a = 'abc\\ndef'\n"
            + "b = \"ab'cd\"\n"
            + "c = \"\"\"multiline string\"\"\"\n"
            + "\n"
            + "# Strings support slicing (negative index starts from the end):\n"
            + "x = \"hello\"[2:4]  # \"ll\"\n"
            + "y = \"hello\"[1:-1]  # \"ell\"\n"
            + "z = \"hello\"[:4]  # \"hell\"\n"
            + "# Slice steps can be used, too:\n"
            + "s = \"hello\"[::2] # \"hlo\"\n"
            + "t = \"hello\"[3:0:-1] # \"lle\"\n</pre>"
            + "Strings are not directly iterable, use the <code>.elems()</code> "
            + "method to iterate over their characters. Examples:<br>"
            + "<pre class=\"language-python\">\"bc\" in \"abcd\"   # evaluates to True\n"
            + "x = [c for c in \"abc\".elems()]  # x == [\"a\", \"b\", \"c\"]</pre>\n"
            + "Implicit concatenation of strings is not allowed; use the <code>+</code> "
            + "operator instead. Comparison operators perform a lexicographical comparison; "
            + "use <code>==</code> to test for equality.")
)
internal class StringModule private constructor() : net.starlark.java.eval.StarlarkValue {
    /**
     * @throws UnsupportedOperationException always; this method is not expected to be called, and
     * exists to ensure that a ClassStarlarkType doesn't get auto-generated for StringModule.
     */
    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        throw java.lang.UnsupportedOperationException(
            "StringModule.INSTANCE should not be directly exposed to Starlark code"
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "join",
        doc = ("Returns a string in which the string elements of the argument have been "
                + "joined by this string as a separator. Example:<br>"
                + "<pre class=\"language-python\">\"|\".join([\"a\", \"b\", \"c\"]) == \"a|b|c\""
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "self"), net.starlark.java.annot.Param(
            name = "elements",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.StarlarkIterable::class,
                generic1 = String::class
            )],
            doc = "The objects to join."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun join(
        self: String,
        elements: net.starlark.java.eval.StarlarkIterable<*>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): String {
        val items: Iterable<*> = net.starlark.java.eval.Starlark.Companion.toIterable(elements)
        var i = 0
        for (item in items) {
            if (item !is String) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "expected string for sequence element %d, got '%s' of type %s",
                    i,
                    net.starlark.java.eval.Starlark.Companion.str(item, thread.getSemantics()),
                    net.starlark.java.eval.Starlark.Companion.type(item)
                )
            }
            i++
        }
        return com.google.common.base.Joiner.on(self).join(items)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "lower",
        doc = "Returns the lower case version of this string.",
        parameters = [net.starlark.java.annot.Param(name = "self")]
    )
    fun lower(self: String): String {
        return com.google.common.base.Ascii.toLowerCase(self)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "upper",
        doc = "Returns the upper case version of this string.",
        parameters = [net.starlark.java.annot.Param(name = "self")]
    )
    fun upper(self: String): String {
        return com.google.common.base.Ascii.toUpperCase(self)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "lstrip",
        doc = ("Returns a copy of the string where leading characters that appear in "
                + "<code>chars</code> are removed. Note that <code>chars</code> "
                + "is not a prefix: all combinations of its value are removed:"
                + "<pre class=\"language-python\">"
                + "\"abcba\".lstrip(\"ba\") == \"cba\""
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "self"), net.starlark.java.annot.Param(
            name = "chars",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            doc = "The characters to remove, or all whitespace if None.",
            defaultValue = "None"
        )],
        useStarlarkThread = true
    )
    fun lstrip(self: String, charsOrNone: Any?, starlarkThread: net.starlark.java.eval.StarlarkThread): String {
        return lstripSemantics(self, charsOrNone, starlarkThread.getSemantics())
    }

    fun lstripSemantics(
        self: String, charsOrNone: Any?, starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
    ): String {
        return net.starlark.java.eval.StringModule.Companion.stringLStrip(
            self,
            net.starlark.java.eval.StringModule.Companion.matcher(charsOrNone, starlarkSemantics)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "rstrip",
        doc = ("Returns a copy of the string where trailing characters that appear in "
                + "<code>chars</code> are removed. Note that <code>chars</code> "
                + "is not a suffix: all combinations of its value are removed:"
                + "<pre class=\"language-python\">"
                + "\"abcbaa\".rstrip(\"ab\") == \"abc\""
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "chars",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            doc = "The characters to remove, or all whitespace if None.",
            defaultValue = "None"
        )],
        useStarlarkThread = true
    )
    fun rstrip(self: String, charsOrNone: Any?, starlarkThread: net.starlark.java.eval.StarlarkThread): String {
        return rstripSemantics(self, charsOrNone, starlarkThread.getSemantics())
    }

    fun rstripSemantics(
        self: String, charsOrNone: Any?, starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
    ): String {
        return net.starlark.java.eval.StringModule.Companion.stringRStrip(
            self,
            net.starlark.java.eval.StringModule.Companion.matcher(charsOrNone, starlarkSemantics)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "strip",
        doc = ("Returns a copy of the string where leading or trailing characters that appear in "
                + "<code>chars</code> are removed. Note that <code>chars</code> "
                + "is neither a prefix nor a suffix: all combinations of its value "
                + "are removed:"
                + "<pre class=\"language-python\">"
                + "\"aabcbcbaa\".strip(\"ab\") == \"cbc\""
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "chars",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            doc = "The characters to remove, or all whitespace if None.",
            defaultValue = "None"
        )],
        useStarlarkThread = true
    )
    fun strip(self: String, charsOrNone: Any?, starlarkThread: net.starlark.java.eval.StarlarkThread): String {
        return stripSemantics(self, charsOrNone, starlarkThread.getSemantics())
    }

    fun stripSemantics(
        self: String, charsOrNone: Any?, starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
    ): String {
        return net.starlark.java.eval.StringModule.Companion.stringStrip(
            self,
            net.starlark.java.eval.StringModule.Companion.matcher(charsOrNone, starlarkSemantics)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "replace",
        doc = ("Returns a copy of the string in which the occurrences "
                + "of <code>old</code> have been replaced with <code>new</code>, optionally "
                + "restricting the number of replacements to <code>count</code>."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "old",
            doc = "The string to be replaced."
        ), net.starlark.java.annot.Param(
            name = "new",
            doc = "The string to replace with."
        ), net.starlark.java.annot.Param(
            name = "count",
            defaultValue = "-1",
            doc = "The maximum number of replacements. If omitted, or if the value is negative, "
                    + "there is no limit."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun replace(
        self: String,
        oldString: String,
        newString: String?,
        countI: net.starlark.java.eval.StarlarkInt,
        thread: net.starlark.java.eval.StarlarkThread?
    ): String {
        var count: Int = countI.toInt("count")
        if (count < 0) {
            count = java.lang.Integer.MAX_VALUE
        }

        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        var start = 0
        for (i in 0..<count) {
            if (oldString.isEmpty()) {
                sb.append(newString)
                if (start < self.length()) {
                    sb.append(self.charAt(start++))
                } else {
                    break
                }
            } else {
                val end: Int = self.indexOf(oldString, start)
                if (end < 0) {
                    break
                }
                sb.append(self, start, end).append(newString)
                start = end + oldString.length()
            }
        }
        sb.append(self, start, self.length())
        return sb.toString()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "split",
        doc = ("Returns a list of all the words in the string, using <code>sep</code> as the "
                + "separator, optionally limiting the number of splits to <code>maxsplit</code>."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sep",
            doc = "The string to split on.",
            named = true
        ), net.starlark.java.annot.Param(
            name = "maxsplit",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class)],
            defaultValue = "unbound",
            doc = "The maximum number of splits.",
            named = true
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun split(
        self: String, sep: String, maxSplitO: Any?, thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<String?> {
        if (sep.isEmpty()) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("Empty separator")
        }
        var maxSplit: Int = java.lang.Integer.MAX_VALUE
        if (maxSplitO !== net.starlark.java.eval.Starlark.Companion.UNBOUND) {
            maxSplit = net.starlark.java.eval.Starlark.Companion.toInt(maxSplitO, "maxsplit")
        }
        val res: net.starlark.java.eval.StarlarkList<String?> =
            net.starlark.java.eval.StarlarkList.Companion.newList<String?>(thread.mutability())
        var start = 0
        while (true) {
            val end: Int = self.indexOf(sep, start)
            if (end < 0 || maxSplit-- == 0) {
                res.addElement(self.substring(start))
                break
            }
            res.addElement(self.substring(start, end))
            start = end + sep.length()
        }
        return res
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "rsplit",
        doc = ("Returns a list of all the words in the string, using <code>sep</code> as the "
                + "separator, optionally limiting the number of splits to <code>maxsplit</code>. "
                + "Except for splitting from the right, this method behaves like split()."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sep",
            doc = "The string to split on.",
            named = true
        ), net.starlark.java.annot.Param(
            name = "maxsplit",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class)],
            defaultValue = "unbound",
            doc = "The maximum number of splits.",
            named = true
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun rsplit(
        self: String, sep: String, maxSplitO: Any?, thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.StarlarkList<String?>? {
        if (sep.isEmpty()) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("Empty separator")
        }
        var maxSplit: Int = java.lang.Integer.MAX_VALUE
        if (maxSplitO !== net.starlark.java.eval.Starlark.Companion.UNBOUND) {
            maxSplit = net.starlark.java.eval.Starlark.Companion.toInt(maxSplitO, "maxsplit")
        }
        val res: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        var end: Int = self.length()
        while (true) {
            val start: Int = self.lastIndexOf(sep, end - 1)
            if (start < 0 || maxSplit-- == 0) {
                res.add(self.substring(0, end))
                break
            }
            res.add(self.substring(start + sep.length(), end))
            end = start
        }
        Collections.reverse(res)
        return net.starlark.java.eval.StarlarkList.Companion.copyOf<String?>(thread.mutability(), res)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "partition",
        doc = ("Splits the input string at the first occurrence of the separator <code>sep</code> and"
                + " returns the resulting partition as a three-element tuple of the form (before,"
                + " separator, after). If the input string does not contain the separator, partition"
                + " returns (self, '', '')."),
        parameters = [net.starlark.java.annot.Param(name = "self"), net.starlark.java.annot.Param(
            name = "sep",
            doc = "The string to split on."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun partition(self: String, sep: String): net.starlark.java.eval.Tuple? {
        return net.starlark.java.eval.StringModule.Companion.partitionCommon(self, sep,  /*first=*/true)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "rpartition",
        doc = ("Splits the input string at the last occurrence of the separator <code>sep</code> and"
                + " returns the resulting partition as a three-element tuple of the form (before,"
                + " separator, after). If the input string does not contain the separator,"
                + " rpartition returns ('', '', self)."),
        parameters = [net.starlark.java.annot.Param(name = "self"), net.starlark.java.annot.Param(
            name = "sep",
            doc = "The string to split on."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun rpartition(self: String, sep: String): net.starlark.java.eval.Tuple? {
        return net.starlark.java.eval.StringModule.Companion.partitionCommon(self, sep,  /*first=*/false)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "capitalize",
        doc = ("Returns a copy of the string with its first character (if any) capitalized and the rest "
                + "lowercased. This method does not support non-ascii characters. "),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun capitalize(self: String): String {
        if (self.isEmpty()) {
            return self
        }
        // TODO(adonovan): fix: support non-ASCII characters. Requires that Bazel stop abusing Latin1.
        return java.lang.Character.toUpperCase(self.charAt(0)).toString() + com.google.common.base.Ascii.toLowerCase(
            self.substring(1)
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "title",
        doc = ("Converts the input string into title case, i.e. every word starts with an "
                + "uppercase letter while the remaining letters are lowercase. In this "
                + "context, a word means strictly a sequence of letters. This method does "
                + "not support supplementary Unicode characters."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun title(self: String): String {
        val data: CharArray = self.toCharArray()
        var previousWasLetter = false

        for (pos in data.indices) {
            val current = data[pos]
            val currentIsLetter: Boolean = java.lang.Character.isLetter(current)

            if (currentIsLetter) {
                if (previousWasLetter && java.lang.Character.isUpperCase(current)) {
                    data[pos] = java.lang.Character.toLowerCase(current)
                } else if (!previousWasLetter && java.lang.Character.isLowerCase(current)) {
                    data[pos] = java.lang.Character.toUpperCase(current)
                }
            }
            previousWasLetter = currentIsLetter
        }

        return String(data)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "rfind",
        doc = ("Returns the last index where <code>sub</code> is found, or -1 if no such index exists, "
                + "optionally restricting to <code>[start:end]</code>, "
                + "<code>start</code> being inclusive and <code>end</code> being exclusive."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sub",
            doc = "The substring to find."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "0",
            doc = "Restrict to search from this position."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "optional position before which to restrict to search."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun rfind(self: String, sub: String, start: Any?, end: Any?): Int {
        return net.starlark.java.eval.StringModule.Companion.stringFind(false, self, sub, start, end)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "find",
        doc = ("Returns the first index where <code>sub</code> is found, or -1 if no such index exists, "
                + "optionally restricting to <code>[start:end]</code>, "
                + "<code>start</code> being inclusive and <code>end</code> being exclusive."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sub",
            doc = "The substring to find."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "0",
            doc = "Restrict to search from this position."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "optional position before which to restrict to search."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun find(self: String, sub: String, start: Any?, end: Any?): Int {
        return net.starlark.java.eval.StringModule.Companion.stringFind(true, self, sub, start, end)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "rindex",
        doc = ("Returns the last index where <code>sub</code> is found, or raises an error if no such "
                + "index exists, optionally restricting to <code>[start:end]</code>, "
                + "<code>start</code> being inclusive and <code>end</code> being exclusive."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sub",
            doc = "The substring to find."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "0",
            doc = "Restrict to search from this position."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "optional position before which to restrict to search."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun rindex(self: String, sub: String, start: Any?, end: Any?): Int {
        val res: Int = net.starlark.java.eval.StringModule.Companion.stringFind(false, self, sub, start, end)
        if (res < 0) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("substring not found")
        }
        return res
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "index",
        doc = ("Returns the first index where <code>sub</code> is found, or raises an error if no such "
                + " index exists, optionally restricting to <code>[start:end]</code>"
                + "<code>start</code> being inclusive and <code>end</code> being exclusive."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sub",
            doc = "The substring to find."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "0",
            doc = "Restrict to search from this position."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "optional position before which to restrict to search."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun index(self: String, sub: String, start: Any?, end: Any?): Int {
        val res: Int = net.starlark.java.eval.StringModule.Companion.stringFind(true, self, sub, start, end)
        if (res < 0) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("substring not found")
        }
        return res
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "splitlines",
        doc = ("Splits the string at line boundaries ('\\n', '\\r\\n', '\\r') "
                + "and returns the result as a new mutable list."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "keepends",
            defaultValue = "False",
            doc = "Whether the line breaks should be included in the resulting list."
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun splitLines(
        self: String?,
        keepEnds: Boolean,
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.Sequence<String?> {
        val result: net.starlark.java.eval.StarlarkList<String?> =
            net.starlark.java.eval.StarlarkList.Companion.newList<String?>(thread.mutability())
        val matcher: java.util.regex.Matcher =
            net.starlark.java.eval.StringModule.Companion.SPLIT_LINES_PATTERN.matcher(self)
        while (matcher.find()) {
            val line: String = matcher.group("line")
            val lineBreak: String = matcher.group("break")
            val trailingBreak: Boolean = lineBreak.isEmpty()
            if (line.isEmpty() && trailingBreak) {
                break
            }
            if (keepEnds && !trailingBreak) {
                result.addElement(line + lineBreak)
            } else {
                result.addElement(line)
            }
        }
        // TODO(adonovan): spec should state that result is mutable,
        // as in Python[23] and go.starlark.net.
        return result
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "isalpha",
        doc = ("Returns True if all characters in the string are alphabetic ([a-zA-Z]) and there is "
                + "at least one character."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isAlpha(self: String): Boolean {
        return net.starlark.java.eval.StringModule.Companion.matches(
            self,
            net.starlark.java.eval.StringModule.Companion.ALPHA,
            false
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "isalnum",
        doc = ("Returns True if all characters in the string are alphanumeric ([a-zA-Z0-9]) and there "
                + "is at least one character."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isAlnum(self: String): Boolean {
        return net.starlark.java.eval.StringModule.Companion.matches(
            self,
            net.starlark.java.eval.StringModule.Companion.ALNUM,
            false
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "isdigit",
        doc = ("Returns True if all characters in the string are digits ([0-9]) and there is "
                + "at least one character."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isDigit(self: String): Boolean {
        return net.starlark.java.eval.StringModule.Companion.matches(
            self,
            net.starlark.java.eval.StringModule.Companion.DIGIT,
            false
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "isspace",
        doc = ("Returns True if all characters are white space characters and the string "
                + "contains at least one character."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isSpace(self: String): Boolean {
        return net.starlark.java.eval.StringModule.Companion.matches(
            self,
            net.starlark.java.eval.StringModule.Companion.SPACE,
            false
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "islower",
        doc = ("Returns True if all cased characters in the string are lowercase and there is "
                + "at least one character."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isLower(self: String): Boolean {
        // Python also accepts non-cased characters, so we cannot use LOWER.
        return net.starlark.java.eval.StringModule.Companion.matches(
            self,
            net.starlark.java.eval.StringModule.Companion.UPPER.negate(),
            true
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "isupper",
        doc = ("Returns True if all cased characters in the string are uppercase and there is "
                + "at least one character."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isUpper(self: String): Boolean {
        // Python also accepts non-cased characters, so we cannot use UPPER.
        return net.starlark.java.eval.StringModule.Companion.matches(
            self,
            net.starlark.java.eval.StringModule.Companion.LOWER.negate(),
            true
        )
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "istitle",
        doc = ("Returns True if the string is in title case and it contains at least one character. "
                + "This means that every uppercase character must follow an uncased one (e.g. "
                + "whitespace) and every lowercase character must follow a cased one (e.g. "
                + "uppercase or lowercase)."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun isTitle(self: String): Boolean {
        if (self.isEmpty()) {
            return false
        }
        // From the Python documentation: "uppercase characters may only follow uncased characters
        // and lowercase characters only cased ones".
        val data: CharArray = self.toCharArray()
        var matcher: com.google.common.base.CharMatcher? = com.google.common.base.CharMatcher.any()
        var leftMostCased = ' '
        for (pos in data.indices.reversed()) {
            val current = data[pos]
            // 1. Check condition that was determined by the right neighbor.
            if (!matcher.matches(current)) {
                return false
            }
            // 2. Determine condition for the left neighbor.
            if (net.starlark.java.eval.StringModule.Companion.LOWER.matches(current)) {
                matcher = net.starlark.java.eval.StringModule.Companion.CASED
            } else if (net.starlark.java.eval.StringModule.Companion.UPPER.matches(current)) {
                matcher = net.starlark.java.eval.StringModule.Companion.CASED.negate()
            } else {
                matcher = com.google.common.base.CharMatcher.any()
            }
            // 3. Store character if it is cased.
            if (net.starlark.java.eval.StringModule.Companion.CASED.matches(current)) {
                leftMostCased = current
            }
        }
        // The leftmost cased letter must be uppercase. If leftMostCased is not a cased letter here,
        // then the string doesn't have any cased letter, so UPPER.test will return false.
        return net.starlark.java.eval.StringModule.Companion.UPPER.matches(leftMostCased)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "count",
        doc = ("Returns the number of (non-overlapping) occurrences of substring <code>sub</code> in "
                + "string, optionally restricting to <code>[start:end]</code>, <code>start</code> "
                + "being inclusive and <code>end</code> being exclusive."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sub",
            doc = "The substring to count."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "0",
            doc = "Restrict to search from this position."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "optional position before which to restrict to search."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun count(self: String, sub: String, start: Any?, end: Any?): Int {
        val indices: Long = net.starlark.java.eval.StringModule.Companion.substringIndices(self, start, end)
        if (sub.isEmpty()) {
            return net.starlark.java.eval.StringModule.Companion.hi(indices) - net.starlark.java.eval.StringModule.Companion.lo(
                indices
            ) + 1 // str.length() + 1
        }
        // The allocation could be avoided by starting at lo(indices) and checking
        // for index <= hi(indices) - sub.length() in the loop, but benchmarks show
        // that the allocation can be faster (and it is a no-op in the common case
        // of default values for start and end).
        val str: String = self.substring(
            net.starlark.java.eval.StringModule.Companion.lo(indices),
            net.starlark.java.eval.StringModule.Companion.hi(indices)
        )
        var count = 0
        var index = 0
        while ((str.indexOf(sub, index).also { index = it }) >= 0) {
            count++
            index += sub.length()
        }
        return count
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "elems",
        doc = ("Returns an iterable value containing successive 1-element substrings of the string. "
                + "Equivalent to <code>[s[i] for i in range(len(s))]</code>, except that the "
                + "returned value might not be a list."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")]
    )
    fun elems(self: String): net.starlark.java.eval.Sequence<String?>? {
        // TODO(adonovan): opt: return a new type that is lazily iterable.
        val chars: CharArray = self.toCharArray()
        val strings = arrayOfNulls<Any>(chars.size)
        for (i in chars.indices) {
            strings[i] = net.starlark.java.eval.StringModule.Companion.memoizedCharToString(chars[i])
        }
        return net.starlark.java.eval.StarlarkList.Companion.wrap<String?>(null, strings)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "endswith",
        doc = ("Returns True if the string ends with <code>sub</code>, otherwise False, optionally "
                + "restricting to <code>[start:end]</code>, <code>start</code> being inclusive "
                + "and <code>end</code> being exclusive."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sub",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Tuple::class,
                generic1 = String::class
            )],
            doc = "The suffix (or tuple of alternative suffixes) to match."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "0",
            doc = "Test beginning at this position."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "optional position at which to stop comparing."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun endsWith(self: String, sub: Any?, start: Any?, end: Any?): Boolean {
        val indices: Long = net.starlark.java.eval.StringModule.Companion.substringIndices(self, start, end)
        if (sub is String) {
            return net.starlark.java.eval.StringModule.Companion.substringEndsWith(
                self,
                net.starlark.java.eval.StringModule.Companion.lo(indices),
                net.starlark.java.eval.StringModule.Companion.hi(indices),
                sub
            )
        }
        for (s in net.starlark.java.eval.Sequence.Companion.cast<String>(sub, String::class.java, "sub")) {
            if (net.starlark.java.eval.StringModule.Companion.substringEndsWith(
                    self,
                    net.starlark.java.eval.StringModule.Companion.lo(indices),
                    net.starlark.java.eval.StringModule.Companion.hi(indices),
                    s
                )
            ) {
                return true
            }
        }
        return false
    }

    // In Python, formatting is very complex.
    // We handle here the simplest case which provides most of the value of the function.
    // https://docs.python.org/3/library/string.html#formatstrings
    @net.starlark.java.annot.StarlarkMethod(
        name = "format",
        doc = ("Perform string interpolation. Format strings contain replacement fields surrounded by"
                + " curly braces <code>&#123;&#125;</code>. Anything that is not contained in braces"
                + " is considered literal text, which is copied unchanged to the output.If you need"
                + " to include a brace character in the literal text, it can be escaped by doubling:"
                + " <code>&#123;&#123;</code> and <code>&#125;&#125;</code>A replacement field can be"
                + " either a name, a number, or empty. Values are converted to strings using the <a"
                + " href=\"../globals/all.html#str\">str</a> function.<pre"
                + " class=\"language-python\"># Access in order:\n"
                + "\"&#123;&#125; < &#123;&#125;\".format(4, 5) == \"4 < 5\"\n"
                + "# Access by position:\n"
                + "\"{1}, {0}\".format(2, 1) == \"1, 2\"\n"
                + "# Access by name:\n"
                + "\"x{key}x\".format(key = 2) == \"x2x\"</pre>\n"),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string.")],
        extraPositionals = net.starlark.java.annot.Param(
            name = "args",
            defaultValue = "()",
            doc = "List of arguments."
        ),
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs",
            defaultValue = "{}",
            doc = "Dictionary of arguments."
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun format(
        self: String,
        args: net.starlark.java.eval.Tuple?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>?,
        thread: net.starlark.java.eval.StarlarkThread
    ): String {
        return net.starlark.java.eval.FormatParser().format(self, args, kwargs, thread.getSemantics())
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "startswith",
        doc = ("Returns True if the string starts with <code>sub</code>, otherwise False, optionally "
                + "restricting to <code>[start:end]</code>, <code>start</code> being inclusive and "
                + "<code>end</code> being exclusive."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "sub",
            allowedTypes = [net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Tuple::class,
                generic1 = String::class
            )],
            doc = "The prefix (or tuple of alternative prefixes) to match."
        ), net.starlark.java.annot.Param(
            name = "start",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "0",
            doc = "Test beginning at this position."
        ), net.starlark.java.annot.Param(
            name = "end",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.StarlarkInt::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.NoneType::class
            )],
            defaultValue = "None",
            doc = "Stop comparing at this position."
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun startsWith(self: String, sub: Any?, start: Any?, end: Any?): Boolean {
        val indices: Long = net.starlark.java.eval.StringModule.Companion.substringIndices(self, start, end)
        if (sub is String) {
            return net.starlark.java.eval.StringModule.Companion.substringStartsWith(
                self,
                net.starlark.java.eval.StringModule.Companion.lo(indices),
                net.starlark.java.eval.StringModule.Companion.hi(indices),
                sub
            )
        }
        for (s in net.starlark.java.eval.Sequence.Companion.cast<String>(sub, String::class.java, "sub")) {
            if (net.starlark.java.eval.StringModule.Companion.substringStartsWith(
                    self,
                    net.starlark.java.eval.StringModule.Companion.lo(indices),
                    net.starlark.java.eval.StringModule.Companion.hi(indices),
                    s
                )
            ) {
                return true
            }
        }
        return false
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "removeprefix",
        doc = ("If the string starts with <code>prefix</code>, returns a new string with the prefix "
                + "removed. Otherwise, returns the string."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "prefix",
            doc = "The prefix to remove if present."
        )]
    )
    fun removePrefix(self: String, prefix: String?): String {
        if (self.startsWith(prefix)) {
            return self.substring(prefix.length())
        }
        return self
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "removesuffix",
        doc = ("If the string ends with <code>suffix</code>, returns a new string with the suffix "
                + "removed. Otherwise, returns the string."),
        parameters = [net.starlark.java.annot.Param(name = "self", doc = "This string."), net.starlark.java.annot.Param(
            name = "suffix",
            doc = "The suffix to remove if present."
        )]
    )
    fun removeSuffix(self: String, suffix: String?): String {
        if (self.endsWith(suffix)) {
            return self.substring(0, self.length() - suffix.length())
        }
        return self
    }

    companion object {
        fun getAssociatedTypeConstructor(): net.starlark.java.syntax.TypeConstructor {
            return net.starlark.java.syntax.Types.STR_CONSTRUCTOR
        }

        val INSTANCE: StringModule = net.starlark.java.eval.StringModule()

        // Returns s[start:stop:step], as if by Sequence.getSlice.
        @Throws(net.starlark.java.eval.EvalException::class)
        fun slice(s: String, start: Int, stop: Int, step: Int): String? {
            val indices: net.starlark.java.eval.RangeList = net.starlark.java.eval.RangeList(start, stop, step)
            val n: Int = indices.size()
            if (n == 0) {
                return ""
            } else if (n == 1) {
                return net.starlark.java.eval.StringModule.Companion.memoizedCharToString(s.charAt(indices.at(0)))
            } else if (step == 1) { // common case
                return s.substring(indices.at(0), indices.at(n))
            } else {
                val res = CharArray(n)
                for (i in 0..<n) {
                    res[i] = s.charAt(indices.at(i))
                }
                return String(res)
            }
        }

        // Nearly all chars in Starlark strings are ASCII.
        // This is a cache of single-char strings to avoid allocation in the s[i] operation.
        private val ASCII_CHAR_STRINGS: Array<String?> = net.starlark.java.eval.StringModule.Companion.initCharStrings()

        private fun initCharStrings(): Array<String?> {
            val a = arrayOfNulls<String>(0x80)
            for (i in a.indices) {
                a[i] = java.lang.String.valueOf(i.toChar())
            }
            return a
        }

        /** Semantically equivalent to [String.valueOf] but faster for ASCII strings.  */
        fun memoizedCharToString(c: Char): String? {
            if (c.code < net.starlark.java.eval.StringModule.Companion.ASCII_CHAR_STRINGS.size) {
                return net.starlark.java.eval.StringModule.Companion.ASCII_CHAR_STRINGS[c.code]
            } else {
                return java.lang.String.valueOf(c)
            }
        }

        // Returns the substring denoted by str[start:end], which is never out of bounds.
        // For speed, we don't return str.substring(start, end), as substring allocates a copy.
        // Instead we return the (start, end) indices, packed into the lo/hi arms of a long.
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun substringIndices(str: String, start: Any?, end: Any?): Long {
            // This function duplicates the logic of Starlark.slice for strings.
            val n: Int = str.length()
            var istart = 0
            if (start !== net.starlark.java.eval.Starlark.Companion.NONE) {
                istart = net.starlark.java.syntax.SyntaxUtils.toSliceBound(
                    net.starlark.java.eval.Starlark.Companion.toInt(
                        start,
                        "start"
                    ), n
                )
            }
            var iend = n
            if (end !== net.starlark.java.eval.Starlark.Companion.NONE) {
                iend = net.starlark.java.syntax.SyntaxUtils.toSliceBound(
                    net.starlark.java.eval.Starlark.Companion.toInt(
                        end,
                        "end"
                    ), n
                )
            }
            if (iend < istart) {
                iend = istart // => empty result
            }
            return net.starlark.java.eval.StringModule.Companion.pack(istart, iend) // = str.substring(start, end)
        }

        private fun pack(lo: Int, hi: Int): Long {
            return ((hi.toLong()) shl 32) or (lo.toLong() and 0xffffffffL)
        }

        private fun lo(x: Long): Int {
            return x.toInt()
        }

        private fun hi(x: Long): Int {
            return (x ushr 32).toInt()
        }

        /**
         * For consistency with Python we recognize the same whitespace characters as they do over the
         * range 0x00-0xFF. See https://hg.python.org/cpython/file/3.6/Objects/unicodetype_db.h#l5738 This
         * list is a consequence of Unicode character information.
         * 
         * 
         * Note that this differs from Python 2.7, which uses ctype.h#isspace(), and from
         * java.lang.Character#isWhitespace(), which does not recognize U+00A0.
         */
        // TODO(https://github.com/bazelbuild/starlark/issues/112): use the Unicode definition of
        // whitespace, matching Python 3.
        private val LATIN1_WHITESPACE: com.google.common.base.CharMatcher = com.google.common.base.CharMatcher.anyOf(
            com.google.common.base.Joiner.on("") // to prevent autoformatter from concatenating the strings
                .join(
                    "\u0009", "\n", "\u000B", "\u000C", "\r", "\u001C", "\u001D", "\u001E", "\u001F",
                    " ", "\u0085", "\u00A0"
                )
        )

        // This is used instead of LATIN1_WHITESPACE when strings are represented as raw UTF-8 byte
        // arrays. In that case, we should not strip any bytes that are not ASCII whitespace, but part of
        // a multibyte UTF-8 character.
        private val ASCII_WHITESPACE: com.google.common.base.CharMatcher = com.google.common.base.CharMatcher.anyOf(
            com.google.common.base.Joiner.on("") // to prevent autoformatter from concatenating the strings
                .join(
                    "\u0009", "\n", "\u000B", "\u000C", "\r", "\u001C", "\u001D", "\u001E", "\u001F",
                    " "
                )
        )

        private fun stringLStrip(self: String, matcher: com.google.common.base.CharMatcher): String {
            for (i in 0..<self.length()) {
                if (!matcher.matches(self.charAt(i))) {
                    return self.substring(i)
                }
            }
            return "" // All characters were stripped.
        }

        private fun stringRStrip(self: String, matcher: com.google.common.base.CharMatcher): String {
            for (i in self.length() - 1 downTo 0) {
                if (!matcher.matches(self.charAt(i))) {
                    return self.substring(0, i + 1)
                }
            }
            return "" // All characters were stripped.
        }

        private fun stringStrip(self: String, matcher: com.google.common.base.CharMatcher): String {
            return net.starlark.java.eval.StringModule.Companion.stringLStrip(
                net.starlark.java.eval.StringModule.Companion.stringRStrip(
                    self,
                    matcher
                ), matcher
            )
        }

        private fun matcher(
            charsOrNone: Any?,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
        ): com.google.common.base.CharMatcher? {
            return if (charsOrNone !== net.starlark.java.eval.Starlark.Companion.NONE // When using the latin-1 hack, each utf-8 code unit is stored as a distinct string element.
            // To avoid matching an element that doesn't correspond to a whole code point, we exclude
            // anything that's not in the ASCII range.
            )
                com.google.common.base.CharMatcher.anyOf(charsOrNone as String?)
            else
                (if (starlarkSemantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS))
                    net.starlark.java.eval.StringModule.Companion.ASCII_WHITESPACE
                else
                    net.starlark.java.eval.StringModule.Companion.LATIN1_WHITESPACE)
        }

        // Splits input at the first or last occurrence of the given separator,
        // and returns a triple of substrings (before, separator, after).
        // If the input does not contain the separator,
        // it returns (input, "", "") if first, or ("", "", input), if !first.
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun partitionCommon(input: String, separator: String, first: Boolean): net.starlark.java.eval.Tuple? {
            if (separator.isEmpty()) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("empty separator")
            }

            var a = ""
            var b = ""
            var c = ""

            val pos: Int = if (first) input.indexOf(separator) else input.lastIndexOf(separator)
            if (pos < 0) {
                if (first) {
                    a = input
                } else {
                    c = input
                }
            } else {
                a = input.substring(0, pos)
                b = separator
                c = input.substring(pos + separator.length())
            }

            return net.starlark.java.eval.Tuple.Companion.triple(a, b, c)
        }

        /**
         * Common implementation for find, rfind, index, rindex.
         * 
         * @param forward true if we want to return the last matching index.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun stringFind(forward: Boolean, self: String, sub: String, start: Any?, end: Any?): Int {
            val indices: Long = net.starlark.java.eval.StringModule.Companion.substringIndices(self, start, end)
            val startpos: Int = net.starlark.java.eval.StringModule.Companion.lo(indices)
            val endpos: Int = net.starlark.java.eval.StringModule.Companion.hi(indices)
            if (forward) {
                return self.indexOf(sub, startpos, endpos)
            }
            // String#lastIndexOf can't be used to implement rfind() because it only
            // confines the start position of the substring, not the entire substring.
            val subpos: Int = self.substring(startpos, endpos).lastIndexOf(sub)
            return if (subpos < 0)
                subpos //
            else
                subpos + startpos
        }

        private val SPLIT_LINES_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?<line>[^\\r\\n]*)(?<break>(\\r\\n|\\r|\\n)?)")

        private fun matches(
            str: String, matcher: com.google.common.base.CharMatcher, requiresAtLeastOneCasedLetter: Boolean
        ): Boolean {
            if (str.isEmpty()) {
                return false
            } else if (!requiresAtLeastOneCasedLetter) {
                return matcher.matchesAllOf(str)
            }
            var casedLetters = 0
            for (current in str.toCharArray()) {
                if (!matcher.matches(current)) {
                    return false
                } else if (requiresAtLeastOneCasedLetter && net.starlark.java.eval.StringModule.Companion.CASED.matches(
                        current
                    )
                ) {
                    ++casedLetters
                }
            }
            return casedLetters > 0
        }

        private val DIGIT: com.google.common.base.CharMatcher = com.google.common.base.CharMatcher.javaDigit()
        private val LOWER: com.google.common.base.CharMatcher = com.google.common.base.CharMatcher.inRange('a', 'z')
        private val UPPER: com.google.common.base.CharMatcher = com.google.common.base.CharMatcher.inRange('A', 'Z')
        private val ALPHA: com.google.common.base.CharMatcher =
            net.starlark.java.eval.StringModule.Companion.LOWER.or(net.starlark.java.eval.StringModule.Companion.UPPER)
        private val ALNUM: com.google.common.base.CharMatcher =
            net.starlark.java.eval.StringModule.Companion.ALPHA.or(net.starlark.java.eval.StringModule.Companion.DIGIT)
        private val CASED: com.google.common.base.CharMatcher = net.starlark.java.eval.StringModule.Companion.ALPHA
        private val SPACE: com.google.common.base.CharMatcher = com.google.common.base.CharMatcher.whitespace()

        // Computes str.substring(start, end).endsWith(suffix) without allocation.
        private fun substringEndsWith(str: String, start: Int, end: Int, suffix: String): Boolean {
            val n: Int = suffix.length()
            return start + n <= end && str.regionMatches(end - n, suffix, 0, n)
        }

        // Computes str.substring(start, end).startsWith(prefix) without allocation.
        private fun substringStartsWith(str: String, start: Int, end: Int, prefix: String): Boolean {
            return start + prefix.length() <= end && str.startsWith(prefix, start)
        }
    }
}
