// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Knows how to print labels consistently in various formats.  */
interface LabelPrinter {
    /** Returns a string representation of the given label.  */
    fun toString(label: Label?): String?

    fun toString(packageIdentifier: PackageIdentifier?): String?

    companion object {
        /**
         * Creates a [LabelPrinter] that prints labels in the same way as the Starlark `str` method.
         * This behavior is useful when matching labels against Starlark values, in particular in tools.
         * 
         * 
         * Do not use this method directly, call [ ][com.google.devtools.build.lib.query2.common.CommonQueryOptions.getLabelPrinter] instead.
         */
        fun starlark(starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?): LabelPrinter {
            return object : LabelPrinter {
                override fun toString(label: Label): String {
                    val printer: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer()
                    label.str(printer, starlarkSemantics)
                    return printer.toString()
                }

                override fun toString(packageIdentifier: PackageIdentifier?): String {
                    // PackageIdentifier is not a StarlarkValue and thus doesn't have a str method. Since it is
                    // only used in the context of --output=package, we reuse Label#str by stripping a
                    // placeholder name.
                    val label: String = toString(Label.createUnvalidated(packageIdentifier, "unused"))
                    return label.substring(0, label.length() - ":unused".length())
                }
            }
        }

        /**
         * Creates a [LabelPrinter] that prints labels in a form meant for consumption by humans. It
         * the main repository has visibility into the label's repository, the apparent repository name is
         * used instead of the canonical repository name.
         * 
         * 
         * Do not use this method directly, call [ ][com.google.devtools.build.lib.query2.common.CommonQueryOptions.getLabelPrinter] instead.
         */
        fun displayForm(mainRepoMapping: RepositoryMapping?): LabelPrinter {
            return object : LabelPrinter {
                override fun toString(label: Label): String {
                    return label.getDisplayForm(mainRepoMapping)
                }

                override fun toString(packageIdentifier: PackageIdentifier): String {
                    return packageIdentifier.getDisplayForm(mainRepoMapping)
                }
            }
        }

        /**
         * Creates a [LabelPrinter] that prints labels via [Label.toString]. This should
         * only be used for backwards compatibility in cases where exact label forms matter, such as for
         * genquery or in digests, or call sites outside of the query commands.
         */
        @kotlin.jvm.JvmStatic
        fun legacy(): LabelPrinter {
            return LEGACY
        }

        @kotlin.jvm.JvmField
        val LEGACY: LabelPrinter = object : LabelPrinter {
            override fun toString(label: Label): String {
                return label.toString()
            }

            override fun toString(packageIdentifier: PackageIdentifier): String {
                return packageIdentifier.toString()
            }
        }
    }
}
