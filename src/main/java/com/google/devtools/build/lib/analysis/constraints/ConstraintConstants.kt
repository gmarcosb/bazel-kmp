// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo
import java.util.function.BinaryOperator

/** Constants needed for use of the constraints system.  */
object ConstraintConstants {
    const val ENVIRONMENT_RULE: String = "environment"

    private val OS_CONSTRAINT_SETTING: ConstraintSettingInfo = ConstraintSettingInfo.create(
        com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:os")
    )

    @kotlin.jvm.JvmField
    val CPU_CONSTRAINT_SETTING: ConstraintSettingInfo = ConstraintSettingInfo.create(
        com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//cpu:cpu")
    )

    // Standard mapping between OS and the corresponding platform constraints.
    private val CONSTRAINT_VALUE_TO_OS: com.google.common.collect.ImmutableMap<ConstraintValueInfo?, com.google.devtools.build.lib.util.OS?> =
        com.google.common.collect.ImmutableMap.of<ConstraintValueInfo?, com.google.devtools.build.lib.util.OS?>(
            ConstraintValueInfo.create(
                OS_CONSTRAINT_SETTING,
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:linux")
            ),
            com.google.devtools.build.lib.util.OS.LINUX,
            ConstraintValueInfo.create(
                OS_CONSTRAINT_SETTING,
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:osx")
            ),
            com.google.devtools.build.lib.util.OS.DARWIN,
            ConstraintValueInfo.create(
                OS_CONSTRAINT_SETTING,
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:macos")
            ),
            com.google.devtools.build.lib.util.OS.DARWIN,
            ConstraintValueInfo.create(
                OS_CONSTRAINT_SETTING,
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:windows")
            ),
            com.google.devtools.build.lib.util.OS.WINDOWS,
            ConstraintValueInfo.create(
                OS_CONSTRAINT_SETTING,
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:freebsd")
            ),
            com.google.devtools.build.lib.util.OS.FREEBSD,
            ConstraintValueInfo.create(
                OS_CONSTRAINT_SETTING,
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:openbsd")
            ),
            com.google.devtools.build.lib.util.OS.OPENBSD,
            ConstraintValueInfo.create(
                OS_CONSTRAINT_SETTING,
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@platforms//os:none")
            ),
            com.google.devtools.build.lib.util.OS.UNKNOWN
        )

    // Only used for testing, so we accept the ambiguity of multiple constraints representing the same
    // OS.
    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val OS_TO_DEFAULT_CONSTRAINT_VALUE: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.util.OS?, ConstraintValueInfo?> =
        CONSTRAINT_VALUE_TO_OS.entries.stream()
            .collect(
                com.google.common.collect.ImmutableMap.toImmutableMap<MutableMap.MutableEntry<ConstraintValueInfo?, com.google.devtools.build.lib.util.OS?>?, com.google.devtools.build.lib.util.OS?, ConstraintValueInfo?>(
                    java.util.function.Function { java.util.Map.Entry.value },
                    java.util.function.Function { java.util.Map.Entry.key },
                    BinaryOperator { a: ConstraintValueInfo?, b: ConstraintValueInfo? -> a })
            )

    /**
     * Returns the OS corresponding to the given platform's constraint collection based on the
     * contained platform constraint, falling back to the host platform if none is found.
     */
    fun getOsFromConstraintsOrHost(platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo): com.google.devtools.build.lib.util.OS? {
        val osConstraintValue: ConstraintValueInfo? = platformInfo.constraints().get(OS_CONSTRAINT_SETTING)
        if (osConstraintValue == null) {
            // The platform doesn't specify any OS constraint, which makes it difficult to say how the
            // parts of Bazel that are OS-specific should behave. Purely for backwards compatibility and
            // to avoid unexpected breakages, we fall back to the host OS in this case.
            return com.google.devtools.build.lib.util.OS.getCurrent()
        }
        // If the constraint value isn't known to Bazel, it is certainly distinct from all the values
        // Bazel specifically cares about (e.g. for Windows- or macOS-specific behavior). This is best
        // modeled by returning UNKNOWN, which is distinct from all the specific OS values in the enum.
        return CONSTRAINT_VALUE_TO_OS.getOrDefault(osConstraintValue, com.google.devtools.build.lib.util.OS.UNKNOWN)
    }
}
