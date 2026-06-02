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
package com.google.devtools.build.buildjar.javac.plugins.dependency

import com.google.devtools.build.lib.view.proto.Deps
import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.util.Context
import java.lang.reflect.Field
import java.nio.file.Path

/**
 * A lightweight mechanism for extracting compile-time dependencies from javac, by performing a scan
 * of the symbol table after compilation finishes. It only includes dependencies from jar files,
 * which can be interface jars or regular third_party jars, matching the compilation model of Blaze.
 * Note that JDK8 may provide support for extracting per-class, finer-grained dependencies, and if
 * that implementation has reasonable overhead it may be a future option.
 */
class ImplicitDependencyExtractor(depsMap: MutableMap<Path?, Deps.Dependency?>, platformJars: MutableSet<Path?>) {
    /** Map collecting dependency information, used for the proto output  */
    private val depsMap: MutableMap<Path?, Deps.Dependency?>

    private val platformJars: MutableSet<Path?>

    /**
     * ImplicitDependencyExtractor does not guarantee any ordering of the reported dependencies.
     * Clients should preserve the original classpath ordering if trying to minimize their classpaths
     * using this information.
     */
    init {
        this.depsMap = depsMap
        this.platformJars = platformJars
    }

    /**
     * Collects the implicit dependencies of the given set of ClassSymbol roots. As we're interested
     * in differentiating between symbols that were just resolved vs. symbols that were fully
     * completed by the compiler, we start the analysis by finding all the implicit dependencies
     * reachable from the given set of roots. For completeness, we then walk the symbol table
     * associated with the given context and collect the jar files of the remaining class symbols
     * found there.
     * 
     * @param context compilation context
     * @param roots root classes in the implicit dependency collection
     */
    fun accumulate(context: Context, roots: MutableSet<Symbol.ClassSymbol?>?) {
        val symtab: Symtab = Symtab.instance(context)
        // Collect all other partially resolved types
        for (cs in symtab.getAllClasses()) {
            // When recording we want to differentiate between jar references through completed symbols
            // and incomplete symbols
            val completed: Boolean = cs.isCompleted()
            if (cs.classfile != null) {
                collectJarOf(cs.classfile, platformJars, completed)
            } else if (cs.sourcefile != null) {
                collectJarOf(cs.sourcefile, platformJars, completed)
            }
        }
    }

    /**
     * Attempts to add the jar associated with the given JavaFileObject, if any, to the collection,
     * filtering out jars on the compilation bootclasspath.
     * 
     * @param reference JavaFileObject representing a class or source file
     * @param platformJars classes on javac's bootclasspath
     * @param completed whether the jar was referenced through a completed symbol
     */
    private fun collectJarOf(reference: JavaFileObject?, platformJars: MutableSet<Path?>, completed: Boolean) {
        val path: Path? = getJarPath(reference)
        if (path == null) {
            return
        }

        // Filter out classes in rt.jar
        if (platformJars.contains(path)) {
            return
        }

        val currentDep: Deps.Dependency? = depsMap.get(path)

        // If the dep hasn't been recorded we add it to the map
        // If it's been recorded as INCOMPLETE but is now complete we upgrade the dependency
        if (currentDep == null
            || (completed && currentDep.getKind() === Deps.Dependency.Kind.INCOMPLETE)
        ) {
            depsMap.put(
                path,
                Deps.Dependency.newBuilder()
                    .setKind(if (completed) Deps.Dependency.Kind.IMPLICIT else Deps.Dependency.Kind.INCOMPLETE)
                    .setPath(path.toString())
                    .build()
            )
        }
    }

    companion object {
        fun getJarPath(file: JavaFileObject?): Path? {
            if (file == null) {
                return null
            }
            try {
                val field: Field = file.javaClass.getDeclaredField("userJarPath")
                field.setAccessible(true)
                return field.get(file) as Path?
            } catch (e: NoSuchFieldException) {
                return null
            } catch (e: ReflectiveOperationException) {
                throw LinkageError(e.message, e)
            }
        }
    }
}
