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

/**
 * The value returned by a call to `select({...})`, for example:
 * 
 * <pre>
 * rule(
 * name = 'myrule',
 * deps = select({
 * 'a': [':adep'],
 * 'b': [':bdep'],
 * })
</pre> * 
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "selector",
    doc = "A selector between configuration-dependent values.",
    documented = false
)
class SelectorValue internal constructor(
    dictionary: com.google.common.collect.ImmutableMap<*, *>,
    noMatchError: String?
) : net.starlark.java.eval.StarlarkValue, net.starlark.java.eval.HasBinary {
    // TODO(adonovan): combine Selector{List,Value} and BuildType.SelectorList.
    // We don't need three classes for the same concept.
    private val dictionary: com.google.common.collect.ImmutableMap<*, *>
    private val type: java.lang.Class<*>
    private val noMatchError: String?

    init {
        com.google.common.base.Preconditions.checkArgument(!dictionary.isEmpty())
        this.dictionary = dictionary
        // TODO(adonovan): doesn't this assume all the elements have the same type?
        this.type = com.google.common.collect.Iterables.getFirst(dictionary.values(), null).getClass()
        this.noMatchError = noMatchError
    }

    fun getDictionary(): com.google.common.collect.ImmutableMap<*, *> {
        return dictionary
    }

    fun getType(): java.lang.Class<*> {
        return type
    }

    /**
     * Returns a custom error message for this select when no condition matches, or an empty string if
     * no such message is declared.
     */
    fun getNoMatchError(): String? {
        return noMatchError
    }

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun binaryOp(
        op: net.starlark.java.syntax.TokenKind?,
        that: Any?,
        thisLeft: Boolean
    ): com.google.devtools.build.lib.packages.SelectorList? {
        return com.google.devtools.build.lib.packages.SelectorList.Companion.of(this).binaryOp(op, that, thisLeft)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("select(").repr(dictionary, semantics).append(")")
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is SelectorValue) {
            return false
        }
        // TODO(bazel-team): We probably have some inconsistencies here. 1) We're not checking the
        // order of the dictionary, which is relevant to matching semantics. 2) We're checking the
        // type, which depends on the concrete type of the first entry's value, which could be a
        // subtype that is not semantically meaningful to the user. These problems are probably best
        // solved by merging this class into the BuildType-land equivalent, with normalization that
        // removes subtype distinctions by copying into standard attribute types.
        return com.google.common.base.Objects.equal(dictionary, o.dictionary)
                && com.google.common.base.Objects.equal(type, o.type)
                && com.google.common.base.Objects.equal(noMatchError, o.noMatchError)
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(dictionary, type, noMatchError)
    }
}
