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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** A generated file that is the output of a rule.  */
abstract class OutputFile private constructor(
    label: Label,
    generatingRule: com.google.devtools.build.lib.packages.Rule,
    outputKey: String?
) : FileTarget(generatingRule.getPackageoid(), label) {
    private val generatingRule: com.google.devtools.build.lib.packages.Rule
    private val outputKey: String?

    init {
        this.generatingRule = generatingRule
        this.outputKey = outputKey
    }

    override fun getRawVisibility(): RuleVisibility? {
        return generatingRule.getRawVisibility()
    }

    override fun getDefaultVisibility(): RuleVisibility? {
        if (generatingRule.containsErrors()) {
            // If the generating rule is in error, the default visibility might not be resolvable.
            return RuleVisibility.Companion.PRIVATE
        }
        return generatingRule.getDefaultVisibility()
    }

    override fun getVisibility(): RuleVisibility? {
        if (generatingRule.containsErrors()) {
            // If the generating rule is in error, the visibility might not be resolvable.
            return RuleVisibility.Companion.PRIVATE
        }
        return generatingRule.getVisibility()
    }

    override fun getVisibilityDependencyLabels(): Iterable<Label?>? {
        if (generatingRule.containsErrors()) {
            // If the generating rule is in error, the visibility deps might not be resolvable.
            return com.google.common.collect.ImmutableList.of<Label?>()
        }
        return generatingRule.getVisibilityDependencyLabels()
    }

    override fun getVisibilityDeclaredLabels(): MutableList<Label?>? {
        return generatingRule.getVisibilityDeclaredLabels()
    }

    override fun isConfigurable(): Boolean {
        return true
    }

    /** Returns the rule which generates this output file.  */
    fun getGeneratingRule(): com.google.devtools.build.lib.packages.Rule {
        return generatingRule
    }

    override fun getPackageoid(): Packageoid? {
        return generatingRule.getPackageoid()
    }

    override fun getPackageMetadata(): com.google.devtools.build.lib.packages.Package.Metadata? {
        return generatingRule.getPackageMetadata()
    }

    override fun getPackageDeclarations(): Declarations? {
        return generatingRule.getPackageDeclarations()
    }

    /**
     * A kind of output file.
     * 
     * 
     * The FILESET kind is only supported for a non-open-sourced `fileset` rule.
     */
    enum class Kind {
        FILE,
        FILESET
    }

    /** Returns the kind of this output file.  */
    fun getKind(): Kind? {
        return generatingRule.getRuleClassObject().getOutputFileKind()
    }

    override fun getTargetKind(): String {
        return targetKind()
    }

    override fun getAssociatedRule(): com.google.devtools.build.lib.packages.Rule {
        return generatingRule
    }

    override fun getLocation(): net.starlark.java.syntax.Location? {
        return generatingRule.getLocation()
    }

    override fun isOutputFile(): Boolean {
        return true
    }

    override fun getGeneratingRuleLabel(): Label? {
        return generatingRule.getLabel()
    }

    override fun getDeprecationWarning(): String? {
        return generatingRule.getDeprecationWarning()
    }

    override fun isTestOnly(): Boolean {
        return generatingRule.isTestOnly()
    }

    override fun satisfies(required: RequiredProviders): Boolean {
        return generatingRule.satisfies(required)
    }

    override fun getTestTimeout(): TestTimeout? {
        return TestTimeout.Companion.getTestTimeout(generatingRule)
    }

    override fun getAdvertisedProviders(): AdvertisedProviderSet? {
        return generatingRule.getAdvertisedProviders()
    }

    /**
     * Returns this output file's output key.
     * 
     * 
     * An output key is an identifier used to access the output in `ctx.outputs`, or the
     * empty string in the case of an output that's not exposed there. For explicit outputs, the
     * output key is the name of the attribute under which that output appears. For Starlark-defined
     * implicit outputs, the output key is determined by the dict returned from the Starlark function.
     * Native-defined implicit outputs are not named in this manner, and so are invisible to `ctx.outputs` and use the empty string key. (It'd be pathological for the empty string to be
     * used as a key in the other two cases, but this class makes no attempt to prohibit that.)
     */
    fun getOutputKey(): String? {
        return outputKey
    }

    abstract fun isImplicit(): Boolean

    override fun reduceForSerialization(): TargetData {
        return OutputFileData(getLocation(), getLabel(), generatingRule.reduceForSerialization())
    }

    private class Implicit(
        label: Label,
        generatingRule: com.google.devtools.build.lib.packages.Rule,
        outputKey: String?
    ) : OutputFile(label, generatingRule, outputKey) {
        override fun isImplicit(): Boolean {
            return true
        }
    }

    private class Explicit(
        label: Label,
        generatingRule: com.google.devtools.build.lib.packages.Rule,
        attrName: String?
    ) : OutputFile(label, generatingRule, attrName) {
        override fun isImplicit(): Boolean {
            return false
        }
    }

    private class OutputFileData(
        location: net.starlark.java.syntax.Location?,
        label: Label?,
        generatingRuleData: TargetData
    ) : TargetData {
        private val location: net.starlark.java.syntax.Location?
        private val label: Label?

        // TODO(b/297857068): ensure this is not duplicated on deserialization.
        private val generatingRuleData: TargetData

        init {
            this.location = location
            this.label = label
            this.generatingRuleData = generatingRuleData
        }

        override fun getTargetKind(): String {
            return targetKind()
        }

        override fun getLocation(): net.starlark.java.syntax.Location? {
            return location
        }

        override fun getLabel(): Label? {
            return label
        }

        override fun isFile(): Boolean {
            return true
        }

        override fun isOutputFile(): Boolean {
            return true
        }

        override fun getGeneratingRuleLabel(): Label? {
            return generatingRuleData.getLabel()
        }

        override fun getDeprecationWarning(): String? {
            return generatingRuleData.getDeprecationWarning()
        }

        override fun isTestOnly(): Boolean {
            return generatingRuleData.isTestOnly()
        }

        override fun satisfies(required: RequiredProviders?): Boolean {
            return generatingRuleData.satisfies(required)
        }

        override fun getTestTimeout(): TestTimeout? {
            return generatingRuleData.getTestTimeout()
        }

        override fun getAdvertisedProviders(): AdvertisedProviderSet? {
            return generatingRuleData.getAdvertisedProviders()
        }
    }

    companion object {
        /**
         * Constructs an implicit output file with the given label, which must be in the generating rule's
         * package.
         * 
         * @param outputKey either the map key returned by [     ][ImplicitOutputsFunction.StarlarkImplicitOutputsFunction.calculateOutputs] or the empty
         * string for natively defined implicit outputs
         */
        fun createImplicit(
            label: Label,
            generatingRule: com.google.devtools.build.lib.packages.Rule,
            outputKey: String?
        ): OutputFile {
            return com.google.devtools.build.lib.packages.OutputFile.Implicit(label, generatingRule, outputKey)
        }

        /**
         * Constructs an explicit output file with the given label, which must be in the generating rule's
         * package.
         * 
         * @param attrName the output attribute's name; used as the [output key][.getOutputKey]
         */
        fun createExplicit(
            label: Label,
            generatingRule: com.google.devtools.build.lib.packages.Rule,
            attrName: String?
        ): OutputFile {
            return com.google.devtools.build.lib.packages.OutputFile.Explicit(label, generatingRule, attrName)
        }

        /** Returns the target kind for all output files.  */
        fun targetKind(): String {
            return "generated file"
        }
    }
}
