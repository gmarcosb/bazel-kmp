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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.docgen.starlark.AnnotStarlarkMethodDoc
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark

/**
 * A class representing a Java method callable from Starlark which constructs a type of Starlark
 * object. Such a method is annotated with [StarlarkMethod.selfCall] or [ ], and has special handling.
 */
class AnnotStarlarkConstructorMethodDoc(
    val name: String?,
    javaMethod: java.lang.reflect.Method?,
    annotation: StarlarkMethod?,
    expander: StarlarkDocExpander?
) : AnnotStarlarkMethodDoc(javaMethod, annotation, expander) {
    val isConstructor: Boolean
        get() = true

    val rawDocumentation: String?
        get() = annotation.doc

    val signature: String?
        get() = getSignature(this.name)

    val returnType: String?
        get() = Starlark.classType(javaMethod.getReturnType())

    override fun toString(): String {
        return String.format(
            "AnnotStarlarkConstructorMethodDoc{fullyQualifiedName=%s method=%s callable=%s}",
            this.name, javaMethod, formatCallable()
        )
    }

    private fun formatCallable(): String? {
        return String.format(
            "StarlarkMethod{name=%s selfCall=%s structField=%s doc=%s}",
            annotation.name, annotation.selfCall, annotation.structField, annotation.doc
        )
    }
}
