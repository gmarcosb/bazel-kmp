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
import com.google.devtools.build.docgen.BuildEncyclopediaDocException
import com.google.devtools.build.docgen.BuildEncyclopediaOptions
import com.google.devtools.build.docgen.BuildEncyclopediaProcessor
import com.google.devtools.build.docgen.DocLinkMap
import com.google.devtools.build.docgen.MultiPageBuildEncyclopediaProcessor
import com.google.devtools.build.docgen.RuleLinkExpander
import com.google.devtools.build.docgen.SinglePageBuildEncyclopediaProcessor
import com.google.devtools.build.docgen.SourceUrlMapper
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import com.google.devtools.common.options.HelpVerbosity
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build

/**
 * The main class for the docgen project. The class checks the input arguments
 * and uses the BuildEncyclopediaProcessor for the actual documentation generation.
 */
object BuildEncyclopediaGenerator {
    private fun printUsage(parser: com.google.devtools.common.options.OptionsParser) {
        java.lang.System.err.println(
            ("Usage: docgen_bin -m link_map_file -p rule_class_provider\n"
                    + "    [-r input_root] (-i input_dir)+ (--be_stardoc_proto binproto)+\n"
                    + "    [-o outputdir] [-b denylist] [-1 | -t] [-h]\n\n"
                    + "Generates the Build Encyclopedia from embedded native rule documentation.\n"
                    + "The link map file (-m), rule class provider (-p), and at least one input_dir\n"
                    + "(-i) or binproto (--be_stardoc_proto) must be specified.\n"
                    + "Single page (-1) and table-of-contents creation (-t) are mutually exclusive.\n")
        )
        java.lang.System.err.println(
            parser.describeOptionsWithDeprecatedCategories(
                mutableMapOf<String?, String?>(), HelpVerbosity.LONG
            )
        )
    }

    private fun fail(e: Throwable, printStackTrace: Boolean) {
        java.lang.System.err.println("ERROR: " + e.message)
        if (printStackTrace) {
            e.printStackTrace()
        }
        java.lang.Runtime.getRuntime().exit(1)
    }

    @Throws(
        java.lang.ClassNotFoundException::class,
        java.lang.NoSuchMethodException::class,
        java.lang.reflect.InvocationTargetException::class,
        java.lang.IllegalAccessException::class
    )
    private fun createRuleClassProvider(classProvider: String?): ConfiguredRuleClassProvider? {
        val providerClass: java.lang.Class<*> = java.lang.Class.forName(classProvider)
        val createMethod: java.lang.reflect.Method = providerClass.getMethod("create")
        return createMethod.invoke(null) as ConfiguredRuleClassProvider?
    }

    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        val parser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder()
                .optionsClasses(BuildEncyclopediaOptions::class.java)
                .allowResidue(false)
                .build()
        parser.parseAndExitUponError(args)
        val options: BuildEncyclopediaOptions? =
            parser.getOptions<BuildEncyclopediaOptions?>(BuildEncyclopediaOptions::class.java)

        if (options.getHelp()) {
            printUsage(parser)
            java.lang.Runtime.getRuntime().exit(0)
        }

        if (options.getLinkMapPath().isEmpty()
            || (options.getInputJavaDirs().isEmpty()
                    && options.getBuildEncyclopediaStardocProtos().isEmpty())
            || options.getProvider().isEmpty()
            || (options.getSinglePage() && options.getCreateToc())
        ) {
            printUsage(parser)
            java.lang.Runtime.getRuntime().exit(1)
        }

        try {
            val linkMap: DocLinkMap = DocLinkMap.Companion.createFromFile(options.getLinkMapPath())
            val linkExpander: RuleLinkExpander = RuleLinkExpander(options.getSinglePage(), linkMap)
            val urlMapper: SourceUrlMapper = SourceUrlMapper(linkMap, options.getInputRoot())

            var processor: BuildEncyclopediaProcessor? = null
            if (options.getSinglePage()) {
                processor =
                    SinglePageBuildEncyclopediaProcessor(
                        linkExpander, urlMapper, createRuleClassProvider(options.getProvider())
                    )
            } else {
                processor =
                    MultiPageBuildEncyclopediaProcessor(
                        linkExpander,
                        urlMapper,
                        createRuleClassProvider(options.getProvider()),
                        options.getCreateToc()
                    )
            }
            processor.generateDocumentation(
                options.getInputJavaDirs(),
                options.getBuildEncyclopediaStardocProtos(),
                options.getOutputDir(),
                options.getDenylist()
            )
        } catch (e: BuildEncyclopediaDocException) {
            fail(e, false)
        } catch (e: Throwable) {
            fail(e, true)
        }
    }
}
