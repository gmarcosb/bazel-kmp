// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.bugreport.BugReporter.logUnexpected
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.util.TestType
import javax.management.ObjectName

/**
 * A helper class to offset some items from the final heap metrics.
 * 
 * 
 * Context: b/311665999.
 */
object HeapOffsetHelper {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    @kotlin.jvm.JvmStatic
    val isWorkaroundNeeded: Boolean
        get() =// Verify we're using OpenJDK 21.
            com.google.common.base.StandardSystemProperty.JAVA_VM_NAME.value().contains("OpenJDK")
                    && java.lang.Runtime.version().feature() >= 21

    /**
     * Workaround for the FillerArray issue with JDK21. TODO(b/311665999) Remove this ASAP.
     * 
     * 
     * With JDK21, an arbitrary amount of FillerArray instances is retained on the heap at the end
     * of each command, which fluctuates our heap metrics a lot. This method looks into the heap
     * histogram and gets the total #bytes attributed to the FillerArray. We'll offset that against
     * the final value to make it more stable.
     */
    fun getSizeOfFillerArrayOnHeap(
        internalJvmObjectPattern: java.util.regex.Pattern, bugReporter: BugReporter
    ): Long {
        var sizeInBytes: Long = 0

        // Verify we're using OpenJDK 21 before proceeding.
        if (!isWorkaroundNeeded) {
            return 0
        }

        var foundInternal = false
        var hasManagementApi = false
        var histogram = ""
        try {
            histogram =
                java.lang.management.ManagementFactory.getPlatformMBeanServer()
                    .invoke(
                        ObjectName("com.sun.management:type=DiagnosticCommand"),
                        "gcClassHistogram",
                        arrayOf<Any?>(null),
                        arrayOf<String>("[Ljava.lang.String;")
                    ) as String
            hasManagementApi = true
            for (line in histogram.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                val m: java.util.regex.Matcher = internalJvmObjectPattern.matcher(line)
                // ["", <num>, <#instances>, <#bytes>, <class name>]
                if (m.find()) {
                    foundInternal = true
                    sizeInBytes += line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[3].toLong()
                }
            }
        } catch (e: java.lang.Exception) {
            // This should already be false, but just to be sure set it again because
            // something went wrong trying to get the management API histogram.
            hasManagementApi = false
            // Swallow all exceptions.
            logger.atWarning().withCause(e).log(
                "Failed to obtain the size of jdk.internal.vm.FillerArray"
            )
        }

        logIfMissingFillerArray(bugReporter, foundInternal, hasManagementApi, histogram)

        if (sizeInBytes > 0) {
            logger.atInfo().log(
                "Offsetting %d bytes of jdk.internal.vm.FillerArray in the retained heap metric.",
                sizeInBytes
            )
        }
        return sizeInBytes
    }

    /**
     * Logs a non-fatal bug report if the filler-array type wasn't found.
     * 
     * 
     * Note that this can happen if we're issuing command fast enough for the filler array to not
     * be present yet, as can happen in shell tests. For this reason logging is skipped in shell
     * tests, since otherwise bug-report logging is configured to crash in tests.
     */
    private fun logIfMissingFillerArray(
        bugReporter: BugReporter, foundInternal: Boolean, hasManagementApi: Boolean, histogram: String?
    ) {
        if (TestType.Companion.getTestType() == TestType.SHELL_INTEGRATION) {
            return
        }

        if (!foundInternal && hasManagementApi) {
            bugReporter.logUnexpected(
                "Unable to identify JDK 21+ G1 GC internal 'filler' array. Reported Blaze JVM memory"
                        + " metrics are volatile See b/311665999.  vm.name=%s, feature=%d histogram=%s",
                com.google.common.base.StandardSystemProperty.JAVA_VM_NAME.value(),
                java.lang.Runtime.version().feature(),
                histogram
            )
        }
    }
}
