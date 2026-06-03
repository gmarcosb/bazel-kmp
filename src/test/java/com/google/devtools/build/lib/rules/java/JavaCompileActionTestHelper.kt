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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.buildjar.OptionsParser

// TODO(djasper): Investigate removing this class and moving the functions to JavaCompileAction.
/**
 * A collection of utilities for extracting the values of command-line options passed to JavaBuilder
 * from a Java compilation action , for testing.
 */
object JavaCompileActionTestHelper {
    const val JAVA_LANGUAGE_VERSION_FOR_TESTING: String = "21"

    @Throws(java.lang.Exception::class)
    fun getDirectJars(javac: JavaCompileAction): MutableSet<String?> {
        return getOptions(javac).directJars()
    }

    @Throws(java.lang.Exception::class)
    fun getProcessorNames(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getProcessorNames()
    }

    @Throws(java.lang.Exception::class)
    fun getProcessorPath(javac: JavaCompileAction): MutableList<String?> {
        return getProcessorpath(javac)
    }

    @Throws(java.lang.Exception::class)
    fun getProcessorpath(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getProcessorPath()
    }

    @Throws(java.lang.Exception::class)
    fun getJavacOpts(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getJavacOpts()
    }

    @Throws(java.lang.Exception::class)
    fun getSourceFiles(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getSourceFiles()
    }

    @Throws(java.lang.Exception::class)
    fun getSourceJars(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getSourceJars()
    }

    @Throws(java.lang.Exception::class)
    fun getStrictJavaDepsMode(javac: JavaCompileAction): StrictDepsMode {
        val strictJavaDeps: String? = getOptions(javac).getStrictJavaDeps()
        return if (strictJavaDeps != null) StrictDepsMode.valueOf(strictJavaDeps) else StrictDepsMode.OFF
    }

    @Throws(java.lang.Exception::class)
    fun getClasspath(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getClassPath()
    }

    @Throws(java.lang.Exception::class)
    fun getCompileTimeDependencyArtifacts(javac: JavaCompileAction): MutableSet<String?> {
        return getOptions(javac).getDepsArtifacts()
    }

    @Throws(java.lang.Exception::class)
    fun getFixDepsTool(javac: JavaCompileAction): String {
        return getOptions(javac).getFixDepsTool()
    }

    @Throws(java.lang.Exception::class)
    fun getBootClassPath(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getBootClassPath()
    }

    @Throws(java.lang.Exception::class)
    fun getSystem(javac: JavaCompileAction): String {
        return getOptions(javac).getSystem()
    }

    @Throws(java.lang.Exception::class)
    fun getSourcePathEntries(javac: JavaCompileAction): MutableList<String?> {
        return getOptions(javac).getSourcePath()
    }

    /** Returns the JavaBuilder command line, up to the main class or deploy jar.  */
    @Throws(java.lang.Exception::class)
    fun getJavacCommand(action: JavaCompileAction): MutableList<String?>? {
        val args: MutableList<String> = action.commandLines.allArguments()
        return args.subList(0, mainClassIndex(args))
    }

    /** Returns the JavaBuilder options.  */
    @Throws(java.lang.Exception::class)
    fun getJavacArguments(action: JavaCompileAction): MutableList<String?>? {
        val args: MutableList<String> = action.commandLines.allArguments()
        return args.subList(mainClassIndex(args), args.size)
    }

    // Find the index of the last argument of the JavaBuilder command, and before the first option
    // that is passed to JavaBuilder.
    private fun mainClassIndex(args: MutableList<String>): Int {
        for (idx in args.indices) {
            val arg = args.get(idx)
            if (arg == "-jar") {
                return idx + 2
            }
            if (arg.contains("JavaBuilder") && !arg.endsWith(".jar")) {
                return idx + 1
            }
        }
        throw java.lang.IllegalStateException(args.toString())
    }

    @Throws(java.lang.Exception::class)
    private fun getOptions(javac: JavaCompileAction): OptionsParser {
        com.google.common.base.Preconditions.checkArgument(
            javac.mnemonic == "Javac",
            "expected a Javac action, was %s",
            javac.mnemonic
        )
        return OptionsParser(getJavacArguments(javac))
    }
}
