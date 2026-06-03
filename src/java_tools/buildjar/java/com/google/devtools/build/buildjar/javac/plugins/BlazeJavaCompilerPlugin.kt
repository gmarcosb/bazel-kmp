// Copyright 2011 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.buildjar.javac.plugins

import com.google.devtools.build.buildjar.InvalidCommandLineException
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.sun.tools.javac.comp.AttrContext

/**
 * An interface for additional static analyses that need access to the javac compiler's AST at
 * specific points in the compilation process. This class provides callbacks after the attribute and
 * flow phases of the javac compilation process. A static analysis may be implemented by subclassing
 * this abstract class and performing the analysis in the callback methods. The analysis may then be
 * registered with the BlazeJavaCompiler to be run during the compilation process. See [ ] for an example.
 */
abstract class BlazeJavaCompilerPlugin {
    protected var context: com.sun.tools.javac.util.Context? = null
    protected var log: com.sun.tools.javac.util.Log? = null
    protected var statisticsBuilder: com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder? =
        null

    /**
     * Preprocess the command-line flags that were passed to javac. This is called before [ ][.init] and [ ][.initializeContext].
     * 
     * @param standardJavacopts The standard javac command-line flags.
     * @param blazeJavacopts Blaze-specific command-line flags.
     * @throws InvalidCommandLineException if the arguments are invalid
     */
    @Throws(InvalidCommandLineException::class)
    open fun processArgs(
        standardJavacopts: com.google.common.collect.ImmutableList<String?>?,
        blazeJavacopts: com.google.common.collect.ImmutableList<String?>?
    ) {
    }

    /**
     * Called after all plugins have processed arguments and can be used to customize the Java
     * compiler context.
     */
    open fun initializeContext(context: com.sun.tools.javac.util.Context?) {
        this.context = context
    }

    /**
     * Performs analysis actions after the attribute phase of the javac compiler. The attribute phase
     * performs symbol resolution on the parse tree.
     * 
     * @param env The attributed parse tree (after symbol resolution)
     */
    open fun postAttribute(env: com.sun.tools.javac.comp.Env<AttrContext?>?) {}

    /**
     * Performs analysis actions after the flow phase of the javac compiler. The flow phase performs
     * dataflow checks, such as finding unreachable statements.
     * 
     * @param env The attributed parse tree (after symbol resolution)
     */
    open fun postFlow(env: com.sun.tools.javac.comp.Env<AttrContext?>?) {}

    /**
     * Performs analysis actions when the compiler is done and is about to wipe clean its internal
     * data structures (such as the symbol table).
     */
    fun finish() {}

    /**
     * Initializes the plugin. Called by [ ]'s constructor.
     * 
     * @param context The Context object from the enclosing BlazeJavaCompiler instance
     * @param log The Log object from the enclosing BlazeJavaCompiler instance
     * @param compiler The enclosing BlazeJavaCompiler instance
     * @param statisticsBuilder The builder object for statistics, so that this plugin may report
     * performance or auxiliary information.
     */
    fun init(
        context: com.sun.tools.javac.util.Context?,
        log: com.sun.tools.javac.util.Log?,
        compiler: com.sun.tools.javac.main.JavaCompiler?,
        statisticsBuilder: com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder?
    ) {
        this.context = context
        this.log = log
        this.statisticsBuilder = statisticsBuilder
    }

    /** Returns true if the plugin should run on compilations with attribution errors.  */
    fun runOnAttributionErrors(): Boolean {
        return false
    }

    /** Returns true if the plugin should run on compilations with flow errors.  */
    fun runOnFlowErrors(): Boolean {
        return false
    }
}
