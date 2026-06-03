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
package com.google.devtools.build.lib.generatedprojecttest.util

import com.google.common.base.Joiner
import com.google.devtools.build.lib.testutil.BuildRuleBuilder
import com.google.errorprone.annotations.CanIgnoreReturnValue

/**
 * A generator of the contents of a Build File. It contains functions to aid in generating build
 * files for specific purposes.
 */
class BuildFileContentsGenerator : FileContentsGenerator {
    /**
     * Builds a string of the file contents.
     */
    private val contents = StringBuilder()

    /**
     * Boolean to track if default package visibility has been set - it may only happen once.
     */
    private var defaultPackageVisibilityIsSet = false

    /**
     * Index for generated rule names, so they are all unique within a file.
     */
    private var index = 0

    /**
     * @return a new unique rule name
     */
    fun uniqueRuleName(): String {
        index++
        return "rule" + index
    }

    /**
     * Set the default package visibility for this build file. If this function is never called, the
     * default package visibility is ['//visibility:public'].
     */
    @CanIgnoreReturnValue
    fun setDefaultPackageVisibility(vararg visibilityLabelList: String?): FileContentsGenerator {
        check(!defaultPackageVisibilityIsSet) { "setDefaultPackageVisibility was called twice." }
        contents.insert(
            0,
            "package(default_visibility = ['" + Joiner.on("', '").join(visibilityLabelList) + "'])\n"
        )
        defaultPackageVisibilityIsSet = true
        return this
    }

    /**
     * Appends the rule built from the provided BuildRuleBuilder along with the other rules generated
     * in order to this rule to be able to build.
     */
    @CanIgnoreReturnValue
    fun addRule(ruleBuilder: BuildRuleBuilder): FileContentsGenerator {
        contents.append(ruleBuilder.build())
        for (generatedRuleBuilder in ruleBuilder.getRulesToGenerate()) {
            contents.append(generatedRuleBuilder.build())
        }
        return this
    }

    /**
     * Appends a chain of ruleClass rules, each depending on the one before it.
     * 
     * @param ruleClass Name of the rule class to instantiate.
     * @param chainLength Number of rules to create in the chain.
     * @return this
     */
    @CanIgnoreReturnValue
    fun addDependencyChainOfRule(ruleClass: String, chainLength: Int): FileContentsGenerator {
        var chainLength = chainLength
        var previous: BuildRuleBuilder?
        var current = BuildRuleBuilder(ruleClass, uniqueRuleName())
        contents.append(current.build())

        while (chainLength > 1) {
            previous = current
            current = BuildRuleBuilder(ruleClass, uniqueRuleName())
            if (ruleClass == "java_library" || ruleClass == "android_library") {
                current.dependsVia("exports").on(previous)
            } else if (ruleClass == "filegroup") {
                current.dependsVia("srcs").on(previous)
            } else {
                current.dependsVia("deps").on(previous)
            }
            contents.append(current.build())
            chainLength--
        }
        return this
    }

    override fun getContents(): String {
        try {
            setDefaultPackageVisibility("//visibility:public")
        } catch (e: IllegalStateException) {
            // Default Package Visibility already set, do nothing.
        }
        return contents.toString()
    }
}
