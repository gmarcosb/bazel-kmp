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
package com.google.devtools.build.lib.analysis

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.SortedMap
import java.util.stream.Collectors

/**
 * Determines the version information of the current process.
 * 
 * 
 * The version information is a dictionary mapping from string keys to string values.  For
 * build stamping, it should have the key "Build label", which contains among others a
 * XXXXXXX-YYYY.MM.DD string to indicate the version of the release.  If no data is available
 * (eg. when running non-released version), [.isAvailable] returns false.
 */
class BlazeVersionInfo(info: MutableMap<String?, String?>) {
    private val buildData: com.google.common.collect.ImmutableSortedMap<String?, String?>

    init {
        buildData = com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(info)
    }

    /**
     * Indicates whether version information is available.
     */
    fun isAvailable(): Boolean {
        return !buildData.isEmpty()
    }

    /**
     * Returns the summary which gets displayed in the 'version' command. The summary is a list of
     * formatted key / value pairs.
     */
    fun getSummary(): String? {
        if (buildData.isEmpty()) {
            return null
        }
        return buildData.entrySet().stream()
            .map<String?>(java.util.function.Function { e: MutableMap.MutableEntry<String?, String?>? -> e.getKey() + ": " + e.getValue() })
            .collect(Collectors.joining("\n"))
    }

    /**
     * Returns true iff this binary is released--that is, a
     * binary built with a release label.
     */
    fun isReleasedBlaze(): Boolean {
        val buildLabel: String? =
            buildData.get(com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.BUILD_LABEL)
        return buildLabel != null && buildLabel.length() > 0
    }

    /**
     * Returns the release label, if any, or "development version".
     */
    fun getReleaseName(): String {
        val buildLabel: String? =
            buildData.get(com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.BUILD_LABEL)
        return if (buildLabel != null && buildLabel.length() > 0)
            "release " + buildLabel
        else
            "development version"
    }

    /**
     * Returns the version, if any, or `""`. The returned version number is easier to process
     * than the version returned by #getReleaseName().
     */
    fun getVersion(): String {
        val buildLabel: String? =
            buildData.get(com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.BUILD_LABEL)
        if (buildLabel != null) {
            return buildLabel
        }
        val override: String? =
            java.lang.System.getenv(com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.BAZEL_DEV_VERSION_OVERRIDE_ENV_VAR)
        if (override != null) {
            return override
        }
        return ""
    }

    /**
     * Returns the release timestamp in seconds.
     */
    fun getTimestamp(): Long {
        val timestamp: String? =
            buildData.get(com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.BUILD_TIMESTAMP)
        if (timestamp == null || timestamp == "0") {
            return java.util.Date().getTime()
        }
        return java.lang.Long.parseLong(timestamp)
    }

    @com.google.common.annotations.VisibleForTesting
    fun getBuildData(): SortedMap<String?, String?> {
        return buildData
    }

    override fun hashCode(): Int {
        return buildData.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is BlazeVersionInfo) {
            return false
        }
        return buildData == other.buildData
    }

    override fun toString(): String {
        return "BlazeVersionInfo{" + "buildData=" + buildData + '}'
    }

    companion object {
        const val BUILD_LABEL: String = "Build label"

        /** Key for the release timestamp is seconds.  */
        const val BUILD_TIMESTAMP: String = "Build timestamp as int"

        // If the current version is a development version, this environment variable can be used to
        // override the version string (e.g. to deal with version-based feature detection during a
        // bisect).
        const val BAZEL_DEV_VERSION_OVERRIDE_ENV_VAR: String = "BAZEL_DEV_VERSION_OVERRIDE"

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private var instance: BlazeVersionInfo? = null

        /**
         * Accessor method for BlazeVersionInfo singleton.
         * 
         * 
         * If setBuildInfo was not called, returns an empty BlazeVersionInfo instance, which should
         * not be persisted.
         */
        @kotlin.jvm.Synchronized
        fun instance(): BlazeVersionInfo {
            if (com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.instance == null) {
                return com.google.devtools.build.lib.analysis.BlazeVersionInfo(com.google.common.collect.ImmutableMap.of<String?, String?>())
            }
            return com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.instance
        }

        private fun logVersionInfo(info: BlazeVersionInfo) {
            if (info.getSummary() == null) {
                com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.logger.atWarning()
                    .log("Bazel release version information not available")
            } else {
                com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.logger.atInfo()
                    .log("Bazel version info: %s", info.getSummary())
            }
        }

        /**
         * Sets build info.
         * 
         * 
         * This should be called once in the program execution, as early soon as possible, so we can
         * have the version information even before modules are initialized.
         */
        @kotlin.jvm.Synchronized
        fun setBuildInfo(info: MutableMap<String?, String?>) {
            check(com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.instance == null) { "setBuildInfo called twice." }
            com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.instance =
                com.google.devtools.build.lib.analysis.BlazeVersionInfo(info)
            com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.logVersionInfo(com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.instance)
        }

        @com.google.common.annotations.VisibleForTesting
        @kotlin.jvm.Synchronized
        fun setBuildInfoForTesting(info: MutableMap<String?, String?>) {
            com.google.devtools.build.lib.analysis.BlazeVersionInfo.Companion.instance =
                com.google.devtools.build.lib.analysis.BlazeVersionInfo(info)
        }
    }
}
