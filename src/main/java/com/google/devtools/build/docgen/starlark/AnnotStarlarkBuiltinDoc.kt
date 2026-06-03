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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import com.google.devtools.build.docgen.starlark.StarlarkDocPage
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.syntax.Identifier.getName

/** A documentation page for a Starlark builtin type implemented in Java.  */
class AnnotStarlarkBuiltinDoc(
    starlarkBuiltin: StarlarkBuiltin,
    classObject: java.lang.Class<*>,
    expander: StarlarkDocExpander?
) : StarlarkDocPage(expander) {
    private val starlarkBuiltin: StarlarkBuiltin
    private val classObject: java.lang.Class<*>

    init {
        this.starlarkBuiltin = starlarkBuiltin
        this.classObject = classObject
    }

    val name: String?
        get() = starlarkBuiltin.name

    val rawDocumentation: String?
        get() = starlarkBuiltin.doc

    val title: String?
        get() = starlarkBuiltin.name

    fun getClassObject(): java.lang.Class<*> {
        return classObject
    }

    val sourceFile: String?
        get() {
            val parts: Array<String?> = classObject.getName().split("\\$".toRegex()).toTypedArray()
            return String.format(
                "%s/%s.java",
                SOURCE_ROOT,
                parts[0].replace('.', '/')
            )
        }

    companion object {
        private const val SOURCE_ROOT = "src/main/java"
    }
}
