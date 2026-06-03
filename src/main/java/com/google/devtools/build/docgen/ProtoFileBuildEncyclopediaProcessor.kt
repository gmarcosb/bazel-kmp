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
package com.google.devtools.build.docgen

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.BuildDocCollector
import com.google.devtools.build.docgen.BuildEncyclopediaDocException
import com.google.devtools.build.docgen.BuildEncyclopediaProcessor
import com.google.devtools.build.docgen.BuildEncyclopediaProcessor.RuleFamilies
import com.google.devtools.build.docgen.RuleDocumentation
import com.google.devtools.build.docgen.RuleLinkExpander
import com.google.devtools.build.docgen.SourceUrlMapper
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import java.io.IOException

/** Assembles a list of native rules that can be exported to a builtin.proto file.  */
class ProtoFileBuildEncyclopediaProcessor(
    linkExpander: RuleLinkExpander?,
    urlMapper: SourceUrlMapper?,
    ruleClassProvider: ConfiguredRuleClassProvider?
) : BuildEncyclopediaProcessor(linkExpander, urlMapper, ruleClassProvider) {
    private var nativeRules: com.google.common.collect.ImmutableList<RuleDocumentation?>? = null

    /*
   * Collects and processes all rule and attribute documentation in inputJavaDirs and generates a
   * list of RuleDocumentation objects.
   */
    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    override fun generateDocumentation(
        inputJavaDirs: MutableList<String?>,
        buildEncyclopediaStardocProtos: MutableList<String?>?,
        outputFile: String?,
        denyList: String?
    ) {
        val collector: BuildDocCollector = BuildDocCollector(linkExpander, urlMapper, ruleClassProvider)
        val ruleDocEntries: MutableMap<String?, RuleDocumentation?> =
            collector.collect(inputJavaDirs, buildEncyclopediaStardocProtos, denyList)
        val ruleFamilies: RuleFamilies = assembleRuleFamilies(ruleDocEntries.values)
        val ruleDocsBuilder: com.google.common.collect.ImmutableList.Builder<RuleDocumentation?> =
            com.google.common.collect.ImmutableList.Builder<RuleDocumentation?>()

        for (entry in ruleFamilies.all) {
            for (doc in entry.getRules()) {
                ruleDocsBuilder.add(doc)
            }
        }
        nativeRules = ruleDocsBuilder.build()
    }

    fun getNativeRules(): com.google.common.collect.ImmutableList<RuleDocumentation?>? {
        return nativeRules
    }
}
