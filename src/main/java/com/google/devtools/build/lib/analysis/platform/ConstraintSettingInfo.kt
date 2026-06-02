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

import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo
import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.NativeInfo
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintSettingInfoApi
import com.google.devtools.build.lib.util.Fingerprint

/** Provider for a platform constraint setting that is available to be fulfilled.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ConstraintSettingInfo private constructor(
    label: com.google.devtools.build.lib.cmdline.Label,
    defaultConstraintValueLabel: com.google.devtools.build.lib.cmdline.Label?
) : NativeInfo(), ConstraintSettingInfoApi {
    private val label: com.google.devtools.build.lib.cmdline.Label
    private val defaultConstraintValueLabel: com.google.devtools.build.lib.cmdline.Label?

    init {
        this.label = label
        this.defaultConstraintValueLabel = defaultConstraintValueLabel
    }

    val provider: BuiltinProvider<ConstraintSettingInfo?>
        get() = PROVIDER

    override fun label(): com.google.devtools.build.lib.cmdline.Label {
        return label
    }

    override fun hasDefaultConstraintValue(): Boolean {
        return defaultConstraintValueLabel != null
    }

    override fun defaultConstraintValue(): ConstraintValueInfo? {
        if (!hasDefaultConstraintValue()) {
            return null
        }
        return ConstraintValueInfo.Companion.create(this, defaultConstraintValueLabel)
    }

    /** Add this constraint setting to the given fingerprint.  */
    fun addTo(fp: Fingerprint) {
        fp.addString(label.getCanonicalForm())
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ConstraintSettingInfo) {
            return false
        }

        return com.google.common.base.Objects.equal(label, other.label)
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(label)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("ConstraintSettingInfo(").str(label, semantics)
        if (defaultConstraintValueLabel != null) {
            printer.append(", default_constraint_value=").str(defaultConstraintValueLabel, semantics)
        }
        printer.append(")")
    }

    companion object {
        /** Name used in Starlark for accessing this provider.  */
        const val STARLARK_NAME: String = "ConstraintSettingInfo"

        /** Provider singleton constant.  */
        @kotlin.jvm.JvmField
        val PROVIDER: BuiltinProvider<ConstraintSettingInfo?> = object : BuiltinProvider<ConstraintSettingInfo?>(
            STARLARK_NAME, ConstraintSettingInfo::class.java
        ) {}

        /** Returns a new [ConstraintSettingInfo] with the given data.  */
        /** Returns a new [ConstraintSettingInfo] with the given data.  */
        @kotlin.jvm.JvmOverloads
        fun create(
            constraintSetting: com.google.devtools.build.lib.cmdline.Label,
            defaultConstraintValue: com.google.devtools.build.lib.cmdline.Label? = null
        ): ConstraintSettingInfo {
            return ConstraintSettingInfo(constraintSetting, defaultConstraintValue)
        }
    }
}
