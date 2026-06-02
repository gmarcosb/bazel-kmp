// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider

/** Provider for a platform constraint value that fulfills a [ConstraintSettingInfo].  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ConstraintValueInfo private constructor(
    constraint: ConstraintSettingInfo,
    label: com.google.devtools.build.lib.cmdline.Label
) : NativeInfo(), ConstraintValueInfoApi {
    private val constraint: ConstraintSettingInfo
    private val label: com.google.devtools.build.lib.cmdline.Label

    init {
        this.constraint = constraint
        this.label = label
    }

    val provider: BuiltinProvider<ConstraintValueInfo?>
        get() = PROVIDER

    override fun constraint(): ConstraintSettingInfo {
        return constraint
    }

    override fun label(): com.google.devtools.build.lib.cmdline.Label {
        return label
    }

    /**
     * Returns a [ConfigMatchingProvider] that matches if the owning target's platform includes
     * this constraint.
     * 
     * 
     * The [com.google.devtools.build.lib.rules.platform.ConstraintValue] rule can't directly
     * return a [ConfigMatchingProvider] because, as part of a platform's definition, it doesn't
     * have access to the platform during its analysis.
     * 
     * 
     * Instead, a target with a `select()` on a [ ] passes its platform info to this
     * method.
     */
    fun configMatchingProvider(platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo): ConfigMatchingProvider {
        val platformValue: ConstraintValueInfo? = platformInfo.constraints().get(this.constraint())
        return ConfigMatchingProvider.create(
            label,
            com.google.common.collect.ImmutableMultimap.of<K?, V?>(),
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            com.google.common.collect.ImmutableSet.of<E?>(),
            computeMatchResult(platformValue)
        )
    }

    private fun computeMatchResult(platformValue: ConstraintValueInfo?): MatchResult? {
        return if (this == platformValue)
            MatchResult.MATCH
        else
            NoMatch(
                MatchResult.NoMatch.Diff.what(constraint().label())
                    .want(label().getName())
                    .got(if (platformValue != null) platformValue.label().getName() else "<unset>")
                    .build()
            )
    }

    override fun repr(printer: net.starlark.java.eval.Printer?, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        net.starlark.java.eval.Printer.format(
            printer, semantics, "ConstraintValueInfo(setting=%s, %s)", constraint.label(), label
        )
    }

    /** Add this constraint value to the given fingerprint.  */
    fun addTo(fp: Fingerprint) {
        this.constraint.addTo(fp)
        fp.addString(label.getCanonicalForm())
    }

    override fun equals(o: Any?): Boolean {
        if (o !is ConstraintValueInfo) {
            return false
        }
        return constraint == o.constraint && label == o.label
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(constraint, label)
    }

    companion object {
        /** Name used in Starlark for accessing this provider.  */
        const val STARLARK_NAME: String = "ConstraintValueInfo"

        /** Provider singleton constant.  */
        @kotlin.jvm.JvmField
        val PROVIDER: BuiltinProvider<ConstraintValueInfo?> = object : BuiltinProvider<ConstraintValueInfo?>(
            STARLARK_NAME, ConstraintValueInfo::class.java
        ) {}

        /** Returns a new [ConstraintValueInfo] with the given data.  */
        fun create(
            constraint: ConstraintSettingInfo,
            value: com.google.devtools.build.lib.cmdline.Label
        ): ConstraintValueInfo {
            return ConstraintValueInfo(constraint, value)
        }
    }
}
