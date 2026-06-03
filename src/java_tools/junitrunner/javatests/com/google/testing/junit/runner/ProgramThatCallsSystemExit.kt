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
// limitations under the License.
package com.google.testing.junit.runner

import com.google.testing.junit.runner.internal.SystemExitDetectingShutdownHook

/**
 * A simple program that installs a shutdown hook using [SystemExitDetectingShutdownHook] and
 * then calls `System.exit`. This is used to test that the shutdown hook detects the `System.exit` call and prints a stack trace.
 */
object ProgramThatCallsSystemExit {
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        val shutdownHook: java.lang.Thread = SystemExitDetectingShutdownHook.newShutdownHook(java.lang.System.err)
        java.lang.Runtime.getRuntime().addShutdownHook(shutdownHook)
        java.lang.System.exit(0)
    }
}
