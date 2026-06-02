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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoValue
import com.ryanharter.auto.value.gson.GenerateTypeAdapter

/**
 * This object holds the evaluation factors for module extensions in the lockfile, such as the
 * operating system and architecture it depends on. If an extension has no dependencies in this
 * regard, the object remains empty
 */
@AutoValue
@GenerateTypeAdapter
abstract class ModuleExtensionEvalFactors : Comparable<ModuleExtensionEvalFactors?> {
    /** Returns the OS this extension is evaluated on, or empty if it doesn't depend on the os  */
    abstract fun getOs(): String?

    /**
     * Returns the architecture this extension is evaluated on, or empty if it doesn't depend on the
     * architecture
     */
    abstract fun getArch(): String?

    fun isEmpty(): Boolean {
        return getOs().isEmpty() && getArch().isEmpty()
    }

    fun hasSameDependenciesAs(other: ModuleExtensionEvalFactors): Boolean {
        return getOs().isEmpty() == other.getOs().isEmpty()
                && getArch().isEmpty() == other.getArch().isEmpty()
    }

    override fun toString(): String {
        if (isEmpty()) {
            return GENERAL_EXTENSION
        }

        val parts: MutableList<String?> = java.util.ArrayList<String?>()
        if (!getOs().isEmpty()) {
            parts.add(OS_KEY + getOs())
        }
        if (!getArch().isEmpty()) {
            parts.add(ARCH_KEY + getArch())
        }
        return java.lang.String.join(",", parts)
    }

    override fun compareTo(o: ModuleExtensionEvalFactors): Int {
        return toString().compareTo(o.toString())
    }

    companion object {
        private const val OS_KEY = "os:"
        private const val ARCH_KEY = "arch:"

        // This is used when the module extension doesn't depend on os or arch, to indicate that
        // its value is "general" and can be used with any platform
        private const val GENERAL_EXTENSION = "general"

        @kotlin.jvm.JvmStatic
        fun create(os: String?, arch: String?): ModuleExtensionEvalFactors {
            return AutoValue_ModuleExtensionEvalFactors(os, arch)
        }

        fun parse(s: String): ModuleExtensionEvalFactors {
            if (s == GENERAL_EXTENSION) {
                return create("", "")
            }

            var os = ""
            var arch = ""
            val extParts: MutableList<String?> = com.google.common.base.Splitter.on(',').splitToList(s)
            for (part in extParts) {
                if (part.startsWith(OS_KEY)) {
                    os = part.substring(OS_KEY.length())
                } else if (part.startsWith(ARCH_KEY)) {
                    arch = part.substring(ARCH_KEY.length())
                }
            }
            return create(os, arch)
        }
    }
}
