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
package com.google.devtools.build.lib.bazel.rules.java

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.packages.Rule
import com.google.devtools.build.lib.util.OS
import java.util.*

/** Semantics for Bazel Java rules  */
class BazelJavaSemantics private constructor() : JavaSemantics {
    override fun getDefaultJavaResourcePath(path: PathFragment): PathFragment? {
        // Look for src/.../resources to match Maven repository structure.
        val segments: MutableList<String?> = path.splitToListOfSegments()
        for (i in 0..<segments.size() - 2) {
            if (segments.get(i) == "src" && segments.get(i + 2) == "resources") {
                return path.subFragment(i + 3)
            }
        }
        val javaPath: PathFragment? = JavaUtil.getJavaPath(path)
        return if (javaPath == null) path else javaPath
    }

    override fun utf8Environment(executionPlatform: PlatformInfo?): ImmutableMap<String?, String?>? {
        return if (getOsFromConstraintsOrHost(executionPlatform) === OS.DARWIN)
            MACOS_UTF8_ENVIRONMENT
        else
            DEFAULT_UTF8_ENVIRONMENT
    }

    override fun turbineParallelism(): Boolean {
        // Disable parallelism
        // See also https://github.com/bazelbuild/bazel/issues/29350
        return false
    }

    override fun getFixDepsTool(rule: Rule?, javaConfiguration: JavaConfiguration?): Optional<String?> {
        return Optional.empty<String?>()
    }

    companion object {
        /**
         * `C.UTF-8` is now the universally accepted standard UTF-8 locale, to the point where some
         * minimal Linux distributions no longer ship with `en_US.UTF-8`. macOS doesn't have it
         * though.
         */
        private val DEFAULT_UTF8_ENVIRONMENT: ImmutableMap<String?, String?> =
            ImmutableMap.of<String?, String?>("LC_CTYPE", "C.UTF-8")

        /** macOS doesn't have `C.UTF-8`, so we use `en_US.UTF-8` instead.  */
        private val MACOS_UTF8_ENVIRONMENT: ImmutableMap<String?, String?> =
            ImmutableMap.of<String?, String?>("LC_CTYPE", "en_US.UTF-8")

        @kotlin.jvm.JvmField
        @SerializationConstant
        val INSTANCE: BazelJavaSemantics = BazelJavaSemantics()

        val javaToolchainType: String =
            Label.Companion.parseCanonicalUnchecked("@bazel_tools//tools/jdk:toolchain_type").toString()
            get() = Companion.field
        val javaRuntimeToolchainType: Label? =
            Label.Companion.parseCanonicalUnchecked("@bazel_tools//tools/jdk:runtime_toolchain_type")
            get() = Companion.field
    }
}
