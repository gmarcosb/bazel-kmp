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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.lib.cmdline.Label

/** Abstract class for containing documentation for a Starlark syntactic entity.  */
abstract class StarlarkDoc protected constructor(expander: StarlarkDocExpander) {
    protected val expander: StarlarkDocExpander

    init {
        this.expander = expander
    }

    /** Returns a string containing the name of the entity being documented.  */
    abstract val name: String?

    open val documentation: String?
        /**
         * Returns a string containing the formatted HTML documentation of the entity being documented
         * (without any variables).
         */
        get() = expander.expand(this.rawDocumentation)

    /**
     * Returns the HTML documentation of the entity being documented, which potentially contains
     * variables and unresolved links.
     */
    abstract val rawDocumentation: String?

    open val deprecatedStanza: String?
        /**
         * Long-form HTML documentation about deprecation, if the entity being documented is deprecated,
         * or an empty string otherwise; inserted in the output as a separate stanza with a sub-header.
         */
        get() = ""

    open val loadStatement: String?
        /**
         * For Starlark-defined entities, returns a string containing the Starlark load statement to
         * import the entity being documented; or an empty string for Java-defined entities.
         */
        get() = ""

    protected fun getTypeAnchor(returnType: java.lang.Class<*>, generic1: java.lang.Class<*>): String {
        return getTypeAnchor(returnType) + " of " + getTypeAnchor(generic1) + "s"
    }

    protected fun getTypeAnchor(type: java.lang.Class<*>): String? {
        if (type == Boolean::class.java || type == Boolean::class.javaPrimitiveType) {
            return "<a class=\"anchor\" href=\"../core/bool.html\">bool</a>"
        } else if (type == Int::class.javaPrimitiveType || type == Int::class.java) {
            return "<a class=\"anchor\" href=\"../core/int.html\">int</a>"
        } else if (type == String::class.java) {
            return "<a class=\"anchor\" href=\"../core/string.html\">string</a>"
        } else if (MutableMap::class.java.isAssignableFrom(type)) {
            return "<a class=\"anchor\" href=\"../core/dict.html\">dict</a>"
        } else if (type == Tuple::class.java) {
            return "<a class=\"anchor\" href=\"../core/tuple.html\">tuple</a>"
        } else if (type == StarlarkList::class.java || type == com.google.common.collect.ImmutableList::class.java) {
            return "<a class=\"anchor\" href=\"../core/list.html\">list</a>"
        } else if (type == net.starlark.java.eval.Sequence::class.java) {
            return "<a class=\"anchor\" href=\"../core/list.html\">sequence</a>"
        } else if (type == java.lang.Void.TYPE || type == net.starlark.java.eval.NoneType::class.java) {
            return "<code>None</code>"
        } else if (type == NestedSet::class.java) {
            return "<a class=\"anchor\" href=\"../builtins/depset.html\">depset</a>"
        } else if (StarlarkAnnotations.getStarlarkBuiltin(type) != null) {
            val starlarkBuiltin: StarlarkBuiltin? = StarlarkAnnotations.getStarlarkBuiltin(type)
            if (starlarkBuiltin.documented) {
                return String.format(
                    "<a class=\"anchor\" href=\"../%1\$s/%2\$s.html\">%2\$s</a>",
                    com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.Companion.of(
                        starlarkBuiltin
                    ).getPath(), starlarkBuiltin.name
                )
            }
        }
        return Starlark.classType(type)
    }

    companion object {
        /** Transforms a main repo source file label string into a path string.  */
        protected fun getSourceFileFromLabel(labelString: String?): String {
            val label: Label = Label.parseCanonicalUnchecked(labelString)
            checkArgument(
                label.getRepository().isMain(), "Expecting a main repository label, got %s", labelString
            )
            return label.toPathFragment().getPathString()
        }
    }
}
