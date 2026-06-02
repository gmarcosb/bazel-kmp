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
package com.google.devtools.build.lib.query2.query

import com.google.devtools.build.lib.cmdline.Label

/**
 * Record errors, such as missing package/target or rules containing errors, encountered during
 * visitation. Emit an error message upon encountering missing edges
 * 
 * 
 * The accessor [.hasErrors]) may not be called until the concurrent phase is over, i.e.
 * all external calls to visit() methods have completed.
 * 
 * 
 * If you need to report errors to the console during visitation, use the subclass [ ].
 */
internal open class TargetEdgeErrorObserver : TargetEdgeObserver {
    /**
     * True iff errors were encountered. Note, may be set to "true" during the concurrent phase.
     * Volatile, because it is assigned by worker threads and read by the main thread without monitor
     * synchronization.
     */
    @kotlin.concurrent.Volatile
    private var hasErrors = false

    private val errorCode: AtomicReference<DetailedExitCode?> = AtomicReference<DetailedExitCode?>()

    /**
     * Reports an unresolved label error and records the fact that an error was encountered.
     * 
     * @param target the target that referenced the unresolved label
     * @param label the label that could not be resolved
     * @param e the exception that was thrown when the label could not be resolved
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    public override fun missingEdge(target: Target?, label: Label?, e: NoSuchThingException) {
        hasErrors = true
        errorCode.compareAndSet( /*expectedValue=*/null,  /*newValue=*/e.getDetailedExitCode())
    }

    /**
     * Returns true iff any errors (such as missing targets or packages, or rules with errors) have
     * been encountered during any work.
     * 
     * 
     * Not thread-safe; do not call during visitation.
     * 
     * @return true iff no errors (such as missing targets or packages, or rules with errors) have
     * been encountered during any work.
     */
    fun hasErrors(): Boolean {
        return hasErrors
    }

    val detailedExitCode: DetailedExitCode?
        /** Returns the first [DetailedExitCode] encountered, or `null` if there were none.  */
        get() = errorCode.get()

    public override fun edge(from: Target?, attribute: Attribute?, to: Target?) {
        // No-op.
    }

    public override fun node(node: Target) {
        if (node.getPackageoid().containsErrors()
            || (node is Rule && node.containsErrors())
        ) {
            this.hasErrors = true
            val failureDetail: FailureDetail? = node.getPackageoid().getFailureDetail()
            if (failureDetail != null) {
                errorCode.compareAndSet( /*expectedValue=*/
                    null,  /*newValue=*/DetailedExitCode.of(failureDetail)
                )
            } else {
                BugReport.sendNonFatalBugReport(
                    java.lang.IllegalStateException("Undetailed error from package: " + node)
                )
            }
        }
    }
}
