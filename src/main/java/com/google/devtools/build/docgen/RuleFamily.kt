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
import com.google.devtools.build.docgen.DocgenConsts
import com.google.devtools.build.docgen.RuleDocumentation
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get

/**
 * Helper class for representing a rule family in the rule summary table template.
 * 
 * 
 * The rules are separated into categories by rule class: binary, library, test, flag, and other.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class RuleFamily internal constructor(
    ruleTypeMap: com.google.common.collect.ListMultimap<DocgenConsts.RuleType?, RuleDocumentation?>,
    name: String,
    summary: String?
) {
    val summary: String?
    val name: String?
    val id: String?

    private val binaryRules: com.google.common.collect.ImmutableList<RuleDocumentation?>
    private val libraryRules: com.google.common.collect.ImmutableList<RuleDocumentation?>
    private val testRules: com.google.common.collect.ImmutableList<RuleDocumentation?>
    private val otherRules: com.google.common.collect.ImmutableList<RuleDocumentation?>

    private val rules: com.google.common.collect.ImmutableList<RuleDocumentation?>
    private val flags: com.google.common.collect.ImmutableList<RuleDocumentation?>

    init {
        this.name = name
        this.id = normalize(name)
        this.summary = summary
        this.binaryRules =
            com.google.common.collect.ImmutableList.copyOf<RuleDocumentation?>(ruleTypeMap.get(com.google.devtools.build.docgen.DocgenConsts.RuleType.BINARY))
        this.libraryRules =
            com.google.common.collect.ImmutableList.copyOf<RuleDocumentation?>(ruleTypeMap.get(com.google.devtools.build.docgen.DocgenConsts.RuleType.LIBRARY))
        this.testRules =
            com.google.common.collect.ImmutableList.copyOf<RuleDocumentation?>(ruleTypeMap.get(com.google.devtools.build.docgen.DocgenConsts.RuleType.TEST))
        this.otherRules =
            com.google.common.collect.ImmutableList.copyOf<RuleDocumentation?>(ruleTypeMap.get(com.google.devtools.build.docgen.DocgenConsts.RuleType.OTHER))

        rules =
            com.google.common.collect.ImmutableList.builder<RuleDocumentation?>()
                .addAll(binaryRules)
                .addAll(libraryRules)
                .addAll(testRules)
                .addAll(otherRules)
                .build()
        this.flags =
            com.google.common.collect.ImmutableList.copyOf<RuleDocumentation?>(ruleTypeMap.get(com.google.devtools.build.docgen.DocgenConsts.RuleType.FLAG))
    }

    fun size(): Int {
        return rules.size
    }

    fun getBinaryRules(): MutableList<RuleDocumentation?> {
        return binaryRules
    }

    fun getLibraryRules(): MutableList<RuleDocumentation?> {
        return libraryRules
    }

    fun getTestRules(): MutableList<RuleDocumentation?> {
        return testRules
    }

    fun getOtherRules(): MutableList<RuleDocumentation?> {
        return otherRules
    }

    fun getRules(): MutableList<RuleDocumentation?> {
        return rules
    }

    fun getFlags(): MutableList<RuleDocumentation?> {
        return flags
    }

    companion object {
        private val FAMILY_NAME_ESCAPER: com.google.common.escape.Escaper =
            com.google.common.escape.CharEscaperBuilder()
                .addEscape('+', "p")
                .addEscapes(charArrayOf('[', ']', '(', ')'), "")
                .addEscapes(charArrayOf(' ', '/'), "-")
                .toEscaper()

        /*
   * Returns a "normalized" version of the input string. Used to convert rule family names into
   * strings that are more friendly as file names. For example, "C / C++" is converted to
   * "c-cpp".
   */
        fun normalize(s: String): String? {
            return FAMILY_NAME_ESCAPER.escape(s.lowercase()).replace("[-]+".toRegex(), "-")
        }
    }
}
