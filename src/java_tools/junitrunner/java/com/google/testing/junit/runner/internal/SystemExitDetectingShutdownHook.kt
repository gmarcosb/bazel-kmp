// Copyright 2024 The Bazel Authors. All Rights Reserved.
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
// limitations under the License.package com.google.testing.junit.runner.internal;
package com.google.testing.junit.runner.internal

import java.io.PrintStream

/**
 * Shutdown hook to detect when the shutdown is due to someone calling `System.exit`. Tests
 * should never do that. Previously we had a security manager that intercepted such calls. The JDK
 * will remove security managers in a future release, so instead we just detect when it happens and
 * print a stack trace so users can find and fix the call.
 */
object SystemExitDetectingShutdownHook {
    fun newShutdownHook(testRunnerOut: PrintStream): java.lang.Thread {
        val hook: java.lang.Runnable =
            java.lang.Runnable {
                var foundRuntimeExit = false
                for (stack in java.lang.Thread.getAllStackTraces().values) {
                    val framesStartingWithRuntimeExit: MutableList<String?> = java.util.ArrayList<String?>()
                    var foundRuntimeExitInThisThread = false
                    for (frame in stack) {
                        if (!foundRuntimeExitInThisThread && frame.getClassName() == "java.lang.Runtime"
                            && frame.getMethodName() == "exit"
                        ) {
                            foundRuntimeExitInThisThread = true
                        }
                        if (foundRuntimeExitInThisThread) {
                            framesStartingWithRuntimeExit.add(frameString(frame))
                        }
                    }
                    if (foundRuntimeExitInThisThread) {
                        foundRuntimeExit = true
                        testRunnerOut.println("\nSystem.exit or Runtime.exit was called!")
                        testRunnerOut.println(java.lang.String.join("\n", framesStartingWithRuntimeExit))
                    }
                }
                if (foundRuntimeExit) {
                    // We must call halt rather than exit, because exit would lead to a deadlock. We use a
                    // hopefully unique exit code to make it easier to identify this case.
                    java.lang.Runtime.getRuntime().halt(121)
                }
            }
        return java.lang.Thread(hook, "SystemExitDetectingShutdownHook")
    }

    private fun frameString(frame: java.lang.StackTraceElement): String? {
        return String.format(
            "        at %s.%s(%s:%d)",
            frame.getClassName(), frame.getMethodName(), frame.getFileName(), frame.getLineNumber()
        )
    }
}
