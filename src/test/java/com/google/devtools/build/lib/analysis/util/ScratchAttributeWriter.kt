// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.analysis.ConfiguredTarget
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.*

/**
 * A writer for a scratch build target and associated source files. Can be parameterized with a rule
 * type for which to write a mock target.
 * 
 * 
 * For example, the snippet:
 * 
 * <pre>`new ScratchAttributeWriter(testCase, "cc_library", "//x:x")     .setList("srcs", "a.cc", "b.cc")     .setList("hdrs", "hdr.h")     .write(); `</pre>
 * 
 * 
 * Would create the BUILD file "x/BUILD" with contents:
 * 
 * <pre>`cc_library(     name = 'x',     srcs = ['a.cc', 'b.cc'],     hdrs = ['hdr.h'], ) `</pre>
 */
class ScratchAttributeWriter private constructor(
    testCase: BuildViewTestCase?,
    buildFilePreamble: String?,
    ruleName: String?,
    packageName: String?,
    targetName: String?
) {
    private abstract class ScratchAttribute<T> {
        protected var attributeName: String? = null
        protected var attributeValue: T? = null

        abstract fun appendLine(builder: StringBuilder?): StringBuilder?
    }

    /** A plain string attribute.  */
    private class StringAttribute(attributeName: String?, attributeValue: String?) : ScratchAttribute<String?>() {
        init {
            this.attributeName = attributeName
            this.attributeValue = attributeValue
        }

        override fun appendLine(builder: StringBuilder): StringBuilder {
            return builder.append(String.format("%s=%s,", attributeName, attributeValue))
        }
    }

    /** An integer attribute, such as "alwayslink"  */
    private class IntegerAttribute(attributeName: String?, attributeValue: Int?) : ScratchAttribute<Int?>() {
        init {
            this.attributeName = attributeName
            this.attributeValue = attributeValue
        }

        override fun appendLine(builder: StringBuilder): StringBuilder {
            return builder.append(String.format("%s=%d,", attributeName, attributeValue))
        }
    }

    /** A list attribute, such as "srcs"  */
    private class StringListAttribute(attributeName: String?, attributeValue: Iterable<String?>?) :
        ScratchAttribute<Iterable<String?>?>() {
        init {
            this.attributeName = attributeName
            this.attributeValue = attributeValue
        }

        override fun appendLine(builder: StringBuilder): StringBuilder {
            builder.append(String.format("%s=[", attributeName))
            for (value in attributeValue!!) {
                builder.append(String.format("'%s',", value))
            }
            builder.append("],")
            return builder
        }
    }

    /** The name of the package.  */
    private val packageName: String

    /** The name of the target.  */
    private val targetName: String

    /** The test case for which to write this target.  */
    private val testCase: BuildViewTestCase

    /** The name of the rule for this target  */
    private val ruleName: String

    /** An ordered list of the attributes to be written for this scratch target  */
    var buildString: StringBuilder

    /**
     * Creates a ScratchAttributeWriter for a given test case, package name, and target name. The
     * provided rule name will determine the type of the target written.
     */
    init {
        this.testCase = Preconditions.checkNotNull<BuildViewTestCase>(testCase)
        this.ruleName = Preconditions.checkNotNull<String>(ruleName)
        this.packageName = Preconditions.checkNotNull<String>(packageName)
        this.targetName = Preconditions.checkNotNull<String>(targetName)
        this.buildString =
            StringBuilder()
                .append(buildFilePreamble)
                .append("\n")
                .append(String.format("%s(", this.ruleName))
                .append(String.format("name='%s',", this.targetName))
    }

    /**
     * Writes this scratch target to this ScratchAttributeWriter's Scratch instance, and returns the
     * target in the given configuration.
     */
    @Throws(Exception::class)
    fun write(config: BuildConfigurationValue?): ConfiguredTarget? {
        val scratch: Scratch = testCase.getScratch()

        buildString.append(")")

        scratch.file(String.format("%s/BUILD", packageName), buildString.toString())
        return testCase.getConfiguredTarget(String.format("//%s:%s", packageName, targetName), config)
    }

    /**
     * Writes this scratch target to this ScratchAttributeWriter's Scratch instance, and returns the
     * target in the target configuration.
     */
    @Throws(Exception::class)
    fun write(): ConfiguredTarget? {
        return write(testCase.getTargetConfiguration())
    }

    @Throws(IOException::class)
    private fun createSource(source: String?) {
        testCase.getScratch().file(String.format("%s/%s", packageName, source))
    }

    /** Sets a string attribute (like ios_application.app_icon) for this target.  */
    @CanIgnoreReturnValue
    fun set(name: String?, value: String?): ScratchAttributeWriter {
        StringAttribute(name, value).appendLine(this.buildString)
        return this
    }

    /** Sets a list attribute (like cc_library.srcs) for this target.  */
    @CanIgnoreReturnValue
    fun setList(name: String?, value: Iterable<String?>?): ScratchAttributeWriter {
        StringListAttribute(name, value).appendLine(this.buildString)
        return this
    }

    /** Sets a list attribute (like cc_library.srcs) for this target  */
    fun setList(name: String?, vararg value: String?): ScratchAttributeWriter {
        return setList(name, Arrays.asList<String?>(*value))
    }

    /**
     * Sets a list attribute (link cc_library.srcs) for this target. For each string in 'value',
     * writes an empty file to this writer's package with that name.
     * 
     * 
     * Usually, an analysis-time should not require that referenced files actually be written, in
     * which case ScratchAttributeWriter#set should be used instead.
     */
    @Throws(IOException::class)
    fun setAndCreateFiles(name: String?, value: Iterable<String?>): ScratchAttributeWriter {
        for (source in value) {
            createSource(source)
        }
        return setList(name, value)
    }

    /**
     * Sets a list attribute (link cc_library.srcs) for this target. For each string in 'value',
     * writes an empty file to this writer's package with that name.
     * 
     * 
     * Usually, an analysis-time should not require that referenced files actually be written, in
     * which case ScratchAttributeWriter#set should be used instead.
     */
    @Throws(IOException::class)
    fun setAndCreateFiles(name: String?, vararg value: String?): ScratchAttributeWriter {
        return setAndCreateFiles(name, Arrays.asList<String?>(*value))
    }

    companion object {
        /**
         * Creates a ScratchAttributeWriter for a given test case and label. The provided rule name will
         * determine the type of the target written.
         */
        fun fromLabel(
            testCase: BuildViewTestCase?, ruleName: String?, label: Label
        ): ScratchAttributeWriter {
            return ScratchAttributeWriter(
                testCase, "", ruleName, label.getPackageName(), label.name
            )
        }

        /**
         * Creates a ScratchAttributeWriter for a given test case and label string. The provided rule name
         * will determine the type of the target written.
         */
        fun fromLabelString(
            testCase: BuildViewTestCase?, buildFilePreamble: String?, ruleName: String?, labelString: String?
        ): ScratchAttributeWriter {
            val label: Label = Label.parseCanonicalUnchecked(labelString)
            return ScratchAttributeWriter(
                testCase, buildFilePreamble, ruleName, label.getPackageName(), label.name
            )
        }

        /**
         * Creates a ScratchAttributeWriter for a given test case and label string. The provided rule name
         * will determine the type of the target written.
         */
        fun fromLabelString(
            testCase: BuildViewTestCase?, ruleName: String?, labelString: String?
        ): ScratchAttributeWriter {
            return fromLabel(testCase, ruleName, Label.parseCanonicalUnchecked(labelString))
        }
    }
}
