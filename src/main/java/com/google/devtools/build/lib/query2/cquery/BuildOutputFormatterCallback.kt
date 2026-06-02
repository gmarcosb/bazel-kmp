// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget

/** Cquery implementation of BUILD-style output.  */
internal class BuildOutputFormatterCallback(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions?,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor?,
    accessor: TargetAccessor<CqueryNode?>?,
    labelPrinter: LabelPrinter?
) : CqueryThreadsafeCallback(eventHandler, options, out, skyframeExecutor, accessor,  /* uniquifyResults= */false) {
    private val labelPrinter: LabelPrinter?

    init {
        this.labelPrinter = labelPrinter
    }

    val name: String
        get() = "build"

    /** [AttributeReader] implementation that returns the exact value an attribute takes.  */
    private class CqueryAttributeReader(attributeMap: ConfiguredAttributeMapper) :
        com.google.devtools.build.lib.query2.query.output.BuildOutputFormatter.AttributeReader {
        private val attributeMap: ConfiguredAttributeMapper

        init {
            this.attributeMap = attributeMap
        }

        /**
         * Cquery knows which select path is taken so it knows the exact value the attribute takes. Note
         * that null values are also possible - these are represented as an empty value list.
         */
        override fun getPossibleValues(rule: Rule?, attr: Attribute): Iterable<Any?> {
            val actualValue: Any? = attributeMap.get(attr.name, attr.getType())
            return if (actualValue == null) com.google.common.collect.ImmutableList.of<Any?>() else com.google.common.collect.ImmutableList.of<Any?>(
                actualValue
            )
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getAttributeMap(kct: CqueryNode): ConfiguredAttributeMapper? {
        val associatedRule: Rule? = accessor.getTarget(kct).getAssociatedRule()
        if (associatedRule == null) {
            return null
        } else if (kct is OutputFileConfiguredTarget) {
            return ConfiguredAttributeMapper.of(
                associatedRule,
                accessor.getGeneratingConfiguredTarget(kct).getConfigConditions(),
                kct.getConfigurationKey().getOptionsChecksum(),  /* alwaysSucceed= */
                false
            )
        } else {
            return ConfiguredAttributeMapper.of(
                associatedRule,
                kct.getConfigConditions(),
                kct.getConfigurationKey().getOptionsChecksum(),  /* alwaysSucceed= */
                false
            )
        }
    }

    @Throws(java.lang.InterruptedException::class, IOException::class)
    override fun processOutput(partialResult: Iterable<CqueryNode>) {
        val outputter: TargetOutputter =
            TargetOutputter(
                printStream,  // This tells TargetOutputter which attributes to print as selects without resolving
                // those selects. For cquery we never have to do this since we can always resolve
                // selects. Going forward we could expand this to show both the complete select
                // and which path is chosen, which people may find even more informative.
                java.util.function.BiPredicate { rule: Rule?, attr: Attribute? -> false },
                "\n",
                labelPrinter
            )
        for (configuredTarget in partialResult) {
            val target: Target = accessor.getTarget(configuredTarget)
            outputter.output(target, CqueryAttributeReader(getAttributeMap(configuredTarget)))
        }
    }
}
