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
package com.google.devtools.build.docgen

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.StarlarkDocumentationOptions
import com.google.devtools.build.docgen.StarlarkDocumentationProcessor
import com.google.devtools.common.options.HelpVerbosity
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build

/** The main class for the Starlark documentation generator.  */
object StarlarkDocumentationGenerator {
    private fun printUsage(parser: com.google.devtools.common.options.OptionsParser) {
        java.lang.System.err.println(
            """
Usage: skydoc_bin output_dir --link_map_path=link_map.json [other options]

Generates Starlark API documentation, including Starlark and BUILD language
built-in functions and data types, providers, configuration fragments, etc.

""".trimIndent()
        )
        java.lang.System.err.println(
            parser.describeOptionsWithDeprecatedCategories(mutableMapOf<String?, String?>(), HelpVerbosity.LONG)
        )
    }

    private fun fail(e: Throwable, printStackTrace: Boolean) {
        java.lang.System.err.println("ERROR: " + e.message)
        if (printStackTrace) {
            e.printStackTrace()
        }
        java.lang.Runtime.getRuntime().exit(1)
    }

    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        val parser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder()
                .optionsClasses(StarlarkDocumentationOptions::class.java)
                .allowResidue(true)
                .build()
        parser.parseAndExitUponError(args)
        val options: StarlarkDocumentationOptions? =
            parser.getOptions<StarlarkDocumentationOptions?>(StarlarkDocumentationOptions::class.java)

        if (options.getHelp()) {
            printUsage(parser)
            java.lang.Runtime.getRuntime().exit(0)
        }

        if (parser.getResidue().size != 1 || options.getLinkMapPath().isEmpty()) {
            printUsage(parser)
            java.lang.Runtime.getRuntime().exit(1)
        }
        val outputDir: String? = parser.getResidue().getFirst()

        println("Generating Starlark documentation...")
        try {
            StarlarkDocumentationProcessor.generateDocumentation(outputDir, options)
        } catch (e: Throwable) {
            fail(e, true)
        }
        println("Finished.")
    }
}
