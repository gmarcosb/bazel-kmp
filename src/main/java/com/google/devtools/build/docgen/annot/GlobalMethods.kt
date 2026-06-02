// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.docgen.annot

import com.google.common.base.Ascii

/**
 * An annotation applied to a class that indicates to docgen that the class's [ ]-annotated methods should be included in docgen's output
 * as standalone global functions.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GlobalMethods(val environment: Array<Environment>) {
    /** The environment in which the global methods in the annotated class are available.  */
    enum class Environment(title: String, description: String) {
        ALL(
            "All Bazel files",
            "Methods available in all Bazel files, including .bzl files, BUILD, MODULE.bazel,"
                    + " VENDOR.bazel, and WORKSPACE."
        ),
        BZL(".bzl files", "Global methods available in all .bzl files."),
        BUILD(
            "BUILD files",
            ("Methods available in BUILD files. See also the Build"
                    + " Encyclopedia for extra <a href=\"\${link functions}\">functions</a> and build rules,"
                    + " which can also be used in BUILD files.")
        ),
        MODULE("MODULE.bazel files", "Methods available in MODULE.bazel files."),
        REPO("REPO.bazel files", "Methods available in REPO.bazel files."),
        VENDOR("VENDOR.bazel files", "Methods available in VENDOR.bazel files.");

        @kotlin.jvm.JvmField
        private val title: String?
        private val description: String?

        init {
            this.title = title
            this.description = description
        }

        fun getTitle(): String? {
            return title
        }

        fun getDescription(): String? {
            return description
        }

        fun getPath(): String {
            return Ascii.toLowerCase(name)
        }
    }
}
