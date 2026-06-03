// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory.createRequest
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.run
import net.starlark.java.syntax.Identifier.getName
import org.junit.runner.JUnitCore

/**
 * A straightforward JUnit test runner that runs the test in the specified class using
 * [JUnitCore].
 */
object TestRunner {
    private val PACKAGE: String? = TestRunner::class.java.getPackage().getName()

    @Throws(java.lang.ClassNotFoundException::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        require(args.size != 0) { "Must specify at least one argument (source files of the tests to run)!" }

        val junitCore: JUnitCore = JUnitCore()
        junitCore.addListener(com.google.testing.junit.runner.TestListener())
        val request: org.junit.runner.Request? = com.google.testing.junit.runner.TestRunner.createRequest(args)
        val result: org.junit.runner.Result = junitCore.run(request)

        java.lang.System.exit(if (result.wasSuccessful()) 0 else 1)
    }

    @Throws(java.lang.ClassNotFoundException::class)
    private fun createRequest(filepaths: Array<String>): org.junit.runner.Request? {
        val classes: MutableList<java.lang.Class<*>?> = java.util.ArrayList<java.lang.Class<*>?>(filepaths.size)
        for (path in filepaths) {
            classes.add(com.google.testing.junit.runner.TestRunner.getClass(path))
        }
        return org.junit.runner.Request.classes(*classes.toTypedArray<java.lang.Class<*>?>())
    }

    @Throws(java.lang.ClassNotFoundException::class)
    private fun getClass(filepath: String): java.lang.Class<*>? {
        var className: String = filepath.replace('/', '.')
        if (filepath.endsWith(".java")) {
            className = className.substring(0, className.length - 5)
        }
        return java.lang.Class.forName(com.google.testing.junit.runner.TestRunner.PACKAGE + "." + className)
    }
}
