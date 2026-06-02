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
package com.google.devtools.build.lib.skyframe

import java.util.concurrent.atomic.AtomicInteger

/**
 * A class that, when being told the end of a target or aspect being configured, keeps track of the
 * configuration progress and provides it as a human-readable string intended for the progress bar.
 * 
 * 
 * Non-final to be mockable.
 */
class AnalysisProgressReceiver {
    private val configuredTargetsCompleted: AtomicInteger = AtomicInteger()
    private val configuredTargetsDownloaded: AtomicInteger = AtomicInteger()
    private val configuredAspectsCompleted: AtomicInteger = AtomicInteger()
    private val configuredAspectsDownloaded: AtomicInteger = AtomicInteger()

    /** Register that a target has been configured.  */
    fun doneConfigureTarget() {
        configuredTargetsCompleted.incrementAndGet()
    }

    /** Register that a configured target has been downloaded from a remote cache.  */
    fun doneDownloadedConfiguredTarget() {
        configuredTargetsCompleted.incrementAndGet()
        configuredTargetsDownloaded.incrementAndGet()
    }

    /** Register that a aspect has been configured.  */
    fun doneConfigureAspect() {
        configuredAspectsCompleted.incrementAndGet()
    }

    /** Register that a configured target has been downloaded from a remote cache.  */
    fun doneDownloadedConfiguredAspect() {
        configuredAspectsCompleted.incrementAndGet()
        configuredAspectsDownloaded.incrementAndGet()
    }

    /**
     * Reset all instance variables of this object to a state equal to that of a newly
     * constructed object.
     */
    fun reset() {
        configuredTargetsCompleted.set(0)
        configuredTargetsDownloaded.set(0)
        configuredAspectsCompleted.set(0)
        configuredAspectsDownloaded.set(0)
    }

    val progressString: String
        /**
         * Return a snapshot of the configuration progress as human-readable description of the number of
         * targets and aspects configured so far.
         */
        get() {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()

            val targets: Long = configuredTargetsCompleted.get().toLong()
            sb.append(com.google.devtools.build.lib.util.StringUtil.formatCount(targets))
                .append(if (targets == 1L) " target" else " targets")
                .append(" configured")

            val downloadedTargets: Long = configuredTargetsDownloaded.get().toLong()
            if (downloadedTargets > 0) {
                sb.append(" (")
                    .append(com.google.devtools.build.lib.util.StringUtil.formatCount(downloadedTargets))
                    .append(" remote cache hits)")
            }

            val aspects: Long = configuredAspectsCompleted.get().toLong()
            if (aspects > 0) {
                sb.append(", ")
                    .append(com.google.devtools.build.lib.util.StringUtil.formatCount(aspects))
                    .append(if (aspects == 1L) " aspect application" else " aspect applications")
                val downloadedAspects: Long = configuredAspectsDownloaded.get().toLong()
                if (downloadedAspects > 0) {
                    sb.append(" (")
                        .append(com.google.devtools.build.lib.util.StringUtil.formatCount(downloadedAspects))
                        .append(" remote cache hits)")
                }
            }

            return sb.toString()
        }
}
