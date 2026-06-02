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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Default Output callback for cquery. Prints a label and configuration pair per result.  */
class LabelAndConfigurationOutputFormatterCallback internal constructor(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions?,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor?,
    accessor: TargetAccessor<CqueryNode?>?,
    private val showKind: Boolean,
    labelPrinter: LabelPrinter
) : CqueryThreadsafeCallback(eventHandler, options, out, skyframeExecutor, accessor,  /* uniquifyResults= */false) {
    private val labelPrinter: LabelPrinter

    init {
        this.labelPrinter = labelPrinter
    }

    val name: String
        get() = if (this.showKind) "label_kind" else "label"

    override fun processOutput(partialResult: Iterable<CqueryNode>) {
        for (keyedConfiguredTarget in partialResult) {
            var output: java.lang.StringBuilder = java.lang.StringBuilder()
            if (showKind) {
                val actualTarget: Target = accessor.getTarget(keyedConfiguredTarget)
                output = output.append(actualTarget.getTargetKind()).append(" ")
            }
            output =
                output
                    .append(keyedConfiguredTarget.getDescription(labelPrinter))
                    .append(" (")
                    .append(CqueryThreadsafeCallback.Companion.shortId(getConfiguration(keyedConfiguredTarget.getConfigurationKey())))
                    .append(")")

            if (options.getShowRequiredConfigFragments() !== IncludeConfigFragmentsEnum.OFF) {
                output.append(' ').append(requiredFragmentStrings(keyedConfiguredTarget))
            }

            addResult(output.toString())
        }
    }

    companion object {
        private fun requiredFragmentStrings(
            keyedConfiguredTarget: CqueryNode
        ): com.google.common.collect.ImmutableSortedSet<String?> {
            if (keyedConfiguredTarget !is ConfiguredTarget) {
                return com.google.common.collect.ImmutableSortedSet.of<String?>()
            }

            val requiredFragments: RequiredConfigFragmentsProvider? =
                (keyedConfiguredTarget as ConfiguredTarget)
                    .getProvider(RequiredConfigFragmentsProvider::class.java)
            if (requiredFragments == null) {
                return com.google.common.collect.ImmutableSortedSet.of<String?>()
            }

            return com.google.common.collect.ImmutableSortedSet.naturalOrder<String?>()
                .addAll(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        requiredFragments.optionsClasses(),
                        com.google.common.base.Function { clazz: F? ->
                            com.google.devtools.build.lib.util.ClassName.getSimpleNameWithOuter(clazz)
                        })
                )
                .addAll(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        requiredFragments.fragmentClasses(),
                        com.google.common.base.Function { clazz: F? ->
                            com.google.devtools.build.lib.util.ClassName.getSimpleNameWithOuter(clazz)
                        })
                )
                .addAll(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        requiredFragments.defines(),
                        com.google.common.base.Function { define: F? -> "--define:" + define })
                )
                .addAll(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        requiredFragments.starlarkOptions(),
                        Label::toString
                    )
                )
                .build()
        }
    }
}
