// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** [AspectClass] for aspects defined in Starlark.  */
@Immutable
class StarlarkAspectClass(extensionKey: BzlLoadValue.Key, exportedName: String) : AspectClass {
    private val extensionKey: BzlLoadValue.Key
    @kotlin.jvm.JvmField
    private val exportedName: String
    private val name: String

    init {
        this.extensionKey = extensionKey
        this.exportedName = exportedName
        this.name = extensionKey.getLabel().toString() + "%" + exportedName
    }

    fun getExtensionKey(): BzlLoadValue.Key {
        return extensionKey
    }

    fun getExtensionLabel(): Label? {
        return extensionKey.getLabel()
    }

    fun getExportedName(): String {
        return exportedName
    }

    override fun getName(): String {
        return name
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }

        if (o !is StarlarkAspectClass) {
            return false
        }

        val that = o
        return extensionKey == that.extensionKey && exportedName == that.exportedName
    }

    override fun hashCode(): Int {
        return HashCodes.hashObjects(extensionKey, exportedName)
    }

    override fun toString(): String {
        return getName()
    }

    /**
     * An exception indicating that there was a problem creating a [StarlarkAspectClass] aspect.
     */
    class AspectClassCreationException(message: String?) : java.lang.Exception(message)
    companion object {
        @kotlin.jvm.JvmStatic
        @Throws(AspectClassCreationException::class)
        fun getAspectClassFromName(aspect: String): StarlarkAspectClass {
            val delimiterPosition: Int = aspect.indexOf('%'.code)
            if (delimiterPosition >= 0) {
                val bzlFileLoadLikeString: String = aspect.substring(0, delimiterPosition)
                if (!bzlFileLoadLikeString.startsWith("//") && !bzlFileLoadLikeString.startsWith("@")) {
                    throw AspectClassCreationException(
                        ("--exec_aspects must be specified with absolute labels, e.g."
                                + " //foo/bar:baz.bzl%my_aspect, @repo//foo/bar:baz%my_aspect, or"
                                + " /foo/bar:baz.bzl%my_aspect. Found: "
                                + aspect)
                    )
                } else if (!bzlFileLoadLikeString.endsWith(".bzl")) {
                    throw AspectClassCreationException(
                        "--exec_aspects files must end with .bzl. Found: " + aspect
                    )
                } else {
                    var starlarkFileLabel: Label? = null
                    try {
                        starlarkFileLabel = Label.parseCanonical(bzlFileLoadLikeString)
                        val starlarkFunctionName: String = aspect.substring(delimiterPosition + 1)
                        return StarlarkAspectClass(
                            BzlLoadValue.keyForBuild(starlarkFileLabel), starlarkFunctionName
                        )
                    } catch (e: LabelSyntaxException) {
                        throw AspectClassCreationException(
                            java.lang.String.format("Invalid aspect '%s': %s", aspect, e.getMessage())
                        )
                    }
                }
            } else {
                throw AspectClassCreationException(
                    ("--exec_aspects must include the aspect name, preceded by '%', e.g."
                            + " //foo/bar:baz.bzl%my_aspect, @repo//foo/bar:baz%my_aspect, or"
                            + " /foo/bar:baz.bzl%my_aspect. Found: "
                            + aspect)
                )
            }
        }
    }
}
