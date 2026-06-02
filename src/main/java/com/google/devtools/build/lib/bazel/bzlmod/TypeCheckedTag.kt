// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code

/**
 * A [Tag] whose attribute values have been type-checked against the attribute schema define
 * in the [TagClass].
 */
@net.starlark.java.annot.StarlarkBuiltin(name = "bazel_module_tag", documented = false)
class TypeCheckedTag private constructor(
    tagClass: TagClass,
    attrValues: com.google.common.collect.ImmutableList<Any?>,
    devDependency: Boolean,
    moduleIndex: Int,
    tagIndex: Int,
    location: net.starlark.java.syntax.Location?,
    tagClassName: String?
) : net.starlark.java.eval.Structure {
    /**
     * An opaque object that can be used to sort tags in the order they are defined across all tag
     * classes within a module file and across modules in BFS order.
     */
    @net.starlark.java.annot.StarlarkBuiltin(name = "sort_key", documented = false)
    @kotlin.jvm.JvmRecord
    internal data class SortKey(val moduleIndex: Int, val tagIndex: Int) : net.starlark.java.eval.StarlarkValue,
        Comparable<SortKey?> {
        override fun isImmutable(): Boolean {
            return true
        }

        override fun compareTo(other: SortKey?): Int {
            return com.google.devtools.build.lib.bazel.bzlmod.TypeCheckedTag.SortKey.Companion.COMPARATOR.compare(
                this,
                other
            )
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<sort_key>")
        }

        override fun debugPrint(
            printer: net.starlark.java.eval.Printer,
            thread: net.starlark.java.eval.StarlarkThread?
        ) {
            printer.append("<sort_key module=%d tag=%d>".formatted(moduleIndex, tagIndex))
        }

        companion object {
            private val COMPARATOR: java.util.Comparator<SortKey?> =
                java.util.Comparator.comparingInt<SortKey?>(com.google.devtools.build.lib.bazel.bzlmod.TypeCheckedTag.SortKey::moduleIndex)
                    .thenComparingInt(com.google.devtools.build.lib.bazel.bzlmod.TypeCheckedTag.SortKey::tagIndex)
        }
    }

    private val tagClass: TagClass
    private val attrValues: com.google.common.collect.ImmutableList<Any?>
    @kotlin.jvm.JvmField
    private val devDependency: Boolean
    private val sortKey: SortKey

    // The properties below are only used for error reporting.
    private val location: net.starlark.java.syntax.Location?
    private val tagClassName: String?

    init {
        this.tagClass = tagClass
        this.attrValues = attrValues
        this.devDependency = devDependency
        this.sortKey = com.google.devtools.build.lib.bazel.bzlmod.TypeCheckedTag.SortKey(moduleIndex, tagIndex)
        this.location = location
        this.tagClassName = tagClassName
    }

    /**
     * Whether the tag was specified on an extension proxy created with `dev_dependency=True
    ` * .
     */
    fun isDevDependency(): Boolean {
        return devDependency
    }

    override fun isImmutable(): Boolean {
        return true
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getValue(name: String?): Any? {
        val attrIndex: Int? = tagClass.attributeIndices.get(name)
        if (attrIndex == null) {
            return null
        }
        return attrValues.get(attrIndex)
    }

    override fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?> {
        return tagClass.attributeIndices.keySet()
    }

    fun getSortKey(): Any {
        return sortKey
    }

    override fun getErrorMessageForUnknownField(field: String?): String? {
        return "unknown attribute " + field + net.starlark.java.spelling.SpellChecker.didYouMean(field, getFieldNames())
    }

    override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: net.starlark.java.eval.StarlarkThread?) {
        printer.append(java.lang.String.format("'%s' tag at %s", tagClassName, location))
    }

    companion object {
        /** Creates a [TypeCheckedTag].  */
        @Throws(ExternalDepsException::class)
        fun create(
            tagClass: TagClass,
            tag: com.google.devtools.build.lib.bazel.bzlmod.Tag,
            labelConverter: LabelConverter?,
            moduleDisplayString: String?,
            moduleIndex: Int,
            tagIndex: Int
        ): TypeCheckedTag {
            val attrValues: com.google.common.collect.ImmutableList<Any?> =
                AttributeUtils.typeCheckAttrValues(
                    tagClass.attributes,
                    tagClass.attributeIndices,
                    tag.getAttributeValues().attributes(),
                    labelConverter,
                    Code.BAD_MODULE,
                    com.google.common.collect.ImmutableList.of<net.starlark.java.eval.StarlarkThread.CallStackEntry?>(
                        net.starlark.java.eval.StarlarkThread.callStackEntry("<toplevel>", tag.getLocation())
                    ),
                    "'%s' tag".formatted(tag.getTagName()),
                    "to the %s".formatted(moduleDisplayString)
                )
            return TypeCheckedTag(
                tagClass,
                attrValues,
                tag.isDevDependency(),
                moduleIndex,
                tagIndex,
                tag.getLocation(),
                tag.getTagName()
            )
        }
    }
}
