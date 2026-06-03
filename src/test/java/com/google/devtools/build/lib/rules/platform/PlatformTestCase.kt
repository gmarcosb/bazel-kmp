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
package com.google.devtools.build.lib.rules.platform

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Base class for tests that want to use builders to create platforms and constraints.  */
open class PlatformTestCase : BuildViewTestCase() {
    fun constraintBuilder(name: String?): ConstraintBuilder {
        return ConstraintBuilder(name)
    }

    fun platformBuilder(name: String?): PlatformBuilder {
        return PlatformBuilder(name)
    }

    @Throws(java.lang.Exception::class)
    fun fetchConstraintSettingInfo(label: String?): ConstraintSettingInfo? {
        val target: ConfiguredTarget = getConfiguredTarget(label)
        return PlatformProviderUtils.constraintSetting(target)
    }

    @Throws(java.lang.Exception::class)
    fun fetchPlatformInfo(platformLabel: String?): PlatformInfo? {
        val target: ConfiguredTarget = getConfiguredTarget(platformLabel)
        return PlatformProviderUtils.platform(target)
    }

    internal inner class ConstraintBuilder(name: String?) {
        private val label: Label
        private val constraintValues: MutableList<String?> = java.util.ArrayList<String?>()
        private var defaultConstraintValue: String? = null

        init {
            this.label = Label.parseCanonicalUnchecked(name)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun defaultConstraintValue(defaultConstraintValue: String?): ConstraintBuilder {
            this.defaultConstraintValue = defaultConstraintValue
            this.constraintValues.add(defaultConstraintValue)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addConstraintValue(constraintValue: String?): ConstraintBuilder {
            this.constraintValues.add(constraintValue)
            return this
        }

        fun lines(): MutableList<String?> {
            val lines: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()

            // Add the constraint setting.
            lines.add("constraint_setting(name = '" + label.name + "',")
            if (!com.google.common.base.Strings.isNullOrEmpty(defaultConstraintValue)) {
                lines.add("  default_constraint_value = ':" + defaultConstraintValue + "',")
            }
            lines.add(")")

            // Add the constraint values.
            for (constraintValue in constraintValues) {
                lines.add(
                    "constraint_value(",
                    "  name = '" + constraintValue + "',",
                    "  constraint_setting = ':" + label.name + "',",
                    ")"
                )
            }

            return lines.build()
        }

        @Throws(java.lang.Exception::class)
        fun write() {
            val lines = lines()
            val filename: String? = label.getPackageFragment().getRelative("BUILD").getPathString()
            scratch.appendFile(filename, lines.< T > toArray < T ? > (arrayOf<String?>()))
        }
    }

    internal inner class PlatformBuilder(name: String?) {
        private val label: Label
        private val constraintValues: MutableList<String?> = java.util.ArrayList<String?>()
        private var parentLabel: Label? = null
        private var execProperties: com.google.common.collect.ImmutableMap<String?, String?>? = null
        private val flags: MutableList<String?> = java.util.ArrayList<String?>()

        init {
            this.label = Label.parseCanonicalUnchecked(name)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setParent(parentLabel: String?): PlatformBuilder {
            this.parentLabel = Label.parseCanonicalUnchecked(parentLabel)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addConstraint(value: String?): PlatformBuilder {
            this.constraintValues.add(value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecProperties(value: com.google.common.collect.ImmutableMap<String?, String?>?): PlatformBuilder {
            this.execProperties = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFlags(vararg flags: String?): PlatformBuilder {
            this.flags.addAll(com.google.common.collect.ImmutableList.copyOf<String?>(flags))
            return this
        }

        fun lines(): MutableList<String?> {
            val lines: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()

            lines.add("platform(", "  name = '" + label.name + "',")
            if (parentLabel != null) {
                lines.add("  parents = ['" + parentLabel + "'],")
            }
            lines.add("  constraint_values = [")
            for (name in constraintValues) {
                lines.add("    ':" + name + "',")
            }
            lines.add("  ],")
            if (execProperties != null && !execProperties.isEmpty()) {
                lines.add("  exec_properties = { ")
                for (entry in execProperties.entries) {
                    lines.add("    \"" + entry.key + "\": \"" + entry.value + "\",")
                }
                lines.add("  }")
            }
            if (!flags.isEmpty()) {
                lines.add("  flags = [")
                for (flag in flags) {
                    lines.add("    '" + flag + "',")
                }
                lines.add("  ],")
            }
            lines.add(")")

            return lines.build()
        }

        @Throws(java.lang.Exception::class)
        fun write() {
            val lines = lines()
            val filename: String? = label.getPackageFragment().getRelative("BUILD").getPathString()
            scratch.appendFile(filename, lines.< T > toArray < T ? > (arrayOf<String?>()))
        }
    }
}
