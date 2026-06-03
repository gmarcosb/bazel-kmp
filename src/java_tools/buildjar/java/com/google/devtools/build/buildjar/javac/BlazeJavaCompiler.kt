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
package com.google.devtools.build.buildjar.javac

import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.model.XmlWriter.close
import com.sun.tools.javac.comp.AttrContext
import com.sun.tools.javac.comp.CompileStates.CompileState

/**
 * An extended version of the javac compiler, providing support for composable static analyses via a
 * plugin mechanism. BlazeJavaCompiler keeps a list of plugins and calls callback methods in those
 * plugins after certain compiler phases. The plugins perform the actual static analyses.
 */
class BlazeJavaCompiler private constructor(
    context: com.sun.tools.javac.util.Context,
    plugins: Iterable<BlazeJavaCompilerPlugin>
) : com.sun.tools.javac.main.JavaCompiler(context) {
    /** A list of plugins to run at particular points in the compile  */
    private val plugins: MutableList<BlazeJavaCompilerPlugin> = java.util.ArrayList<BlazeJavaCompilerPlugin>()

    init {
        val statisticsBuilder: com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder? =
            context.get<com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder?>(com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder::class.java)
        // initialize all plugins
        for (plugin in plugins) {
            plugin.init(context, log, this, statisticsBuilder)
            this.plugins.add(plugin)
        }
    }

    override fun attribute(env: com.sun.tools.javac.comp.Env<AttrContext?>?): com.sun.tools.javac.comp.Env<AttrContext?>? {
        val result: com.sun.tools.javac.comp.Env<AttrContext?>? = super.attribute(env)
        // don't run plugins if there were compilation errors
        val errors = errorCount() > 0
        // Iterate over all plugins, calling their postAttribute methods
        for (plugin in plugins) {
            if (!errors || plugin.runOnAttributionErrors()) {
                plugin.postAttribute(result)
            }
        }

        return result
    }

    override fun flow(
        env: com.sun.tools.javac.comp.Env<AttrContext?>?,
        results: java.util.Queue<com.sun.tools.javac.comp.Env<AttrContext?>?>?
    ) {
        val isDone: Boolean = compileStates.isDone(env, CompileState.FLOW)
        super.flow(env, results)
        if (isDone) {
            return
        }
        // don't run plugins if there were compilation errors
        val errors = errorCount() > 0
        // Iterate over all plugins, calling their postFlow methods
        for (plugin in plugins) {
            if (!errors || plugin.runOnFlowErrors()) {
                plugin.postFlow(env)
            }
        }
    }

    override fun close() {
        for (plugin in plugins) {
            plugin.finish()
        }
        plugins.clear()
        super.close()
    }

    /**
     * Testing purposes only. Returns true if the collection of plugins in this instance contains one
     * of the provided type.
     */
    fun pluginsContain(klass: java.lang.Class<out BlazeJavaCompilerPlugin?>): Boolean {
        for (plugin in plugins) {
            if (klass.isInstance(plugin)) {
                return true
            }
        }
        return false
    }

    companion object {
        /**
         * Adds an initialization hook to the Context, such that requests for a JavaCompiler (i.e., a
         * lookup for 'compilerKey' of our superclass, JavaCompiler) will actually construct and return
         * BlazeJavaCompiler.
         * 
         * 
         * This is the preferred way for extending behavior within javac, per the documentation in
         * [Context].
         * 
         * 
         * Prior to JDK-8038455 additional JavaCompilers were created for annotation processing rounds,
         * but we now expect a single compiler instance per compilation. The factory is still seems to be
         * necessary to avoid context-ordering issues, but we assert that the factory is only called once,
         * and save the output after its call for introspection.
         */
        fun preRegister(
            context: com.sun.tools.javac.util.Context, plugins: Iterable<BlazeJavaCompilerPlugin>
        ) {
            context.put<com.sun.tools.javac.main.JavaCompiler?>(
                com.sun.tools.javac.main.JavaCompiler.compilerKey,
                object : com.sun.tools.javac.util.Context.Factory<com.sun.tools.javac.main.JavaCompiler?> {
                    var first: Boolean = true

                    override fun make(c: com.sun.tools.javac.util.Context): com.sun.tools.javac.main.JavaCompiler {
                        if (!first) {
                            throw java.lang.AssertionError("Expected a single creation of BlazeJavaCompiler.")
                        }
                        first = false
                        return BlazeJavaCompiler(c, plugins)
                    }
                })
        }
    }
}
