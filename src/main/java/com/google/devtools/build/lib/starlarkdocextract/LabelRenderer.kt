// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.cmdline.Label

/**
 * A wrapper around a Starlark value printer which prints the Starlark representation of a value
 * with any embedded [Label] values rendered in a form suitable for API documentation.
 * 
 * 
 * Labels are rendered via [Label.getShorthandDisplayForm] with a provided repository
 * mapping, further adding an optional explicit repo name to labels in the main repo, and allowing
 * the `Label` constructor to be either included or omitted (rendering label objects as string
 * values).
 */
class LabelRenderer(repositoryMapping: RepositoryMapping?, mainRepoName: java.util.Optional<String?>) {
    private val repositoryMapping: RepositoryMapping?
    private val mainRepoName: java.util.Optional<String?>

    init {
        this.repositoryMapping = repositoryMapping
        this.mainRepoName = mainRepoName
    }

    /**
     * Renders a label as an unquoted string via [Label.getShorthandDisplayForm], further adding
     * an explicit repo component for labels in the main repo if `mainRepoName` was provided.
     */
    fun render(label: Label): String? {
        return render(label,  /* shorthand= */true)
    }

    // This method could be public, but there are currently no users outside this class who would want
    // to call it with shorthand = false.
    private fun render(label: Label, shorthand: Boolean): String? {
        val labelString: String =
            if (shorthand)
                label.getShorthandDisplayForm(repositoryMapping)
            else
                label.getDisplayForm(repositoryMapping)
        if (mainRepoName.isEmpty() || labelString.startsWith("@")) {
            return labelString
        } else {
            // label.getShorthandDisplayForm omits the repo name part for labels in the main repo
            // regardless of what repositoryMapping says. Therefore, if we want to rename the main repo
            // in labels in emitted docs, we have to do so manually.
            if (shorthand && labelString == "//:" + mainRepoName.get()) {
                // Special case: the shorthand form of "@foo//:foo" is "@foo".
                return "@" + mainRepoName.get()
            }
            return String.format("@%s%s", mainRepoName.get(), labelString)
        }
    }

    /**
     * Renders the `repr()` of a Starlark value as a string, with any embedded label values
     * first converted to string values via [.render].
     */
    fun reprWithoutLabelConstructor(o: Any?): String {
        return object : net.starlark.java.eval.Printer() {
            override fun repr(
                o: Any?,
                semantics: net.starlark.java.eval.StarlarkSemantics?
            ): net.starlark.java.eval.Printer? {
                if (o is Label) {
                    return repr(render(o), semantics)
                } else {
                    return super.repr(o, semantics)
                }
            }
        }.repr(o, net.starlark.java.eval.StarlarkSemantics.DEFAULT).toString()
    }

    /**
     * Renders the `repr()` of a Starlark value as a string, with the argument to the `Label` constructor for any embedded label values produced via [Label.getDisplayForm],
     * further adding an explicit repo component for labels in the main repo if `mainRepoName`
     * was provided.
     * 
     * 
     * Invariant: if the label's repo is mapped by `repositoryMapping`, or if `repositoryMapping` allows fallback, then `this.repr(label)` equals `Starlark.repr(Label.parseCanonicalUnchecked(this.render(label)))`.
     */
    fun repr(o: Any?): String {
        return object : net.starlark.java.eval.Printer() {
            override fun repr(
                o: Any?,
                semantics: net.starlark.java.eval.StarlarkSemantics?
            ): net.starlark.java.eval.Printer? {
                if (o is Label) {
                    return append("Label(") // For consistency with Starlark.repr(label), we use label.getDisplayForm() instead of
                        // the shorthand form.
                        .repr(render(o as Label?,  /* shorthand= */false), semantics)
                        .append(")")
                } else {
                    return super.repr(o, semantics)
                }
            }
        }.repr(o, net.starlark.java.eval.StarlarkSemantics.DEFAULT).toString()
    }

    companion object {
        /** A LabelRenderer which always uses [Label.getShorthandDisplayForm] for rendering.  */
        @kotlin.jvm.JvmField
        val DEFAULT: LabelRenderer = LabelRenderer(RepositoryMapping.EMPTY, java.util.Optional.empty<String?>())
    }
}
