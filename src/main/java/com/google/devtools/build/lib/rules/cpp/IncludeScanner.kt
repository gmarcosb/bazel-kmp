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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.ActionExecutionContext

/**
 * Scans source files to determine the bounding set of transitively referenced include files.
 * 
 * 
 * Include scanning is performance-critical code since it has to parse a lot of C++ code, mostly
 * on the same machine where Blaze runs.
 * 
 * 
 * Include scanning works by first adding all the potential header files to the "scheduling
 * dependencies" of the action. This makes Skyframe build these files (if they are generated) before
 * the action is executed. Note that this means that the "inputs" of the action as seen by Skyframe
 * are different from what `Action.getInputs()` returns: the former includes scheduling
 * dependencies, whereas the latter does not.
 * 
 * 
 * Then `Action.discoverInputs()` is called, which then runs the include scanning machinery
 * and eventually calls `Action.updateInputs()`. That method in turn adds the discovered
 * inputs to what `getInputs()` returns. It's implemented in a separate method because when
 * the action is a local action cache hit, the discovered inputs of the action are read from the
 * local action cache and added to the action's inputs by calling `updateInputs()` without
 * calling `discoverInputs()`.
 * 
 * 
 * The include scanner consists of two parts:
 * 
 * 
 *  1. The part that parses the source files and extracts the include directives.
 *  1. The logic that evaluates the include directives and recursively parses the referenced
 * files.
 * 
 * 
 * 
 * Parsing source files can be done in two ways: locally (in `IncludeParser`) and remotely
 * (using `GrepIncludesAction`). The latter is useful when parsing generated source files: if
 * the file is large, it's beneficial not to have to shuttle the file from the remote execution
 * cluster to Bazel. In either case, the result of parsing is a list of include directives, which
 * are essentially a pair of (include style, include path), where the style is the Cartesian product
 * of "quote"/"angle" and "regular include"/"include next".
 * 
 * 
 * This parsing is very simplistic: it doesn't run an actual preprocessor, only parses its
 * directives. This means that computed includes cannot possibly be handled, but otherwise, it's an
 * overestimate of the actual headers used (for example, it takes both branches of an `#if`)
 * directive. This works because if an unused file is handed over to the compile action, it's
 * suboptimal but still results in a successful compilation.
 * 
 * 
 * Computed includes are handled by adding hints to the include scanner. This is implemented in
 * `IncludeHintsFunction` which is short-circuited in Bazel (not at Google, though)
 * 
 * 
 * Evaluating the include directives is implemented in `LegacyIncludeScanner`, which,
 * despite its name is not really legacy. Notably, it maintains a cache of the results of its work.
 * Its key is not simply the file processed, but the file processed and the set of include
 * directories used (because the latter can obviously affect the result). This cache is flushed on
 * every Bazel command.
 * 
 * 
 * After the include scanner is done, the resulting inputs are handed over to the regular action
 * execution machinery. Once the action is executed, the .d file produced by the compiler (or the
 * output of the `/showIncludes` command line flag when using MSVC) is parsed to figure out
 * which headers were actually used. This is implemented in `discoverInputsFromDotdFiles()`
 * and `discoverInputsFromShowIncludes()` in `CppCompileAction`. Then the result of this
 * is used to remove the headers from the inputs of the action that the compiler didn't end up
 * using.
 */
interface IncludeScanner {
    /**
     * Processes source files and a list of includes extracted from command line flags. Adds all found
     * files to the provided set `includes`.
     * 
     * 
     * The resulting set will include `mainSource` and `sources`. This has no real
     * impact in the case that we are scanning a single source file, since it is already known to be
     * an input. However, this is necessary when we have more than one source to scan from, for
     * example when building C++ modules. In that case we have one of two possibilities:
     * 
     * 
     *  1. We compile a header module - there, the .cppmap file is the main source file (which we do
     * not include-scan, as that would require an extra parser), and thus already in the input;
     * all headers in the .cppmap file are our entry points for include scanning, but are not
     * yet in the inputs - they get added here.
     *  1. We compile an object file that uses a header module; currently using a header module
     * requires all headers it can reference to be available for the compilation. The header
     * module can reference headers that are not in the transitive include closure of the
     * current translation unit. Therefore, [CppCompileAction] adds all headers specified
     * transitively for compiled header modules as include scanning entry points, and we need to
     * add the entry points to the inputs here.
     * 
     * 
     * 
     * `mainSource` is the source file relative to which the `cmdlineIncludes` are
     * interpreted.
     * 
     * 
     * Additional dependencies may be requested via [ ][ActionExecutionContext.getEnvironmentForDiscoveringInputs]. If any dependency is not
     * immediately available, processing will be short-circuited. The caller should check [ ][com.google.devtools.build.skyframe.SkyFunction.Environment.valuesMissing] - if it returns
     * `true`, then include scanning did not complete and a skyframe restart is necessary.
     * 
     * @throws NoSuchPackageException if hint collection fails due to package problems
     */
    @Throws(
        IOException::class,
        NoSuchPackageException::class,
        ExecException::class,
        java.lang.InterruptedException::class
    )
    fun processAsync(
        mainSource: Artifact?,
        sources: MutableCollection<Artifact?>?,
        includeScanningHeaderData: IncludeScanningHeaderData?,
        cmdlineIncludes: MutableList<String?>?,
        includes: MutableSet<Artifact?>?,
        actionExecutionMetadata: ActionExecutionMetadata?,
        actionExecutionContext: ActionExecutionContext?,
        grepIncludes: Artifact?,
        grepIncludesExecutionPlatform: PlatformInfo?
    )

    /**
     * Holds pre-aggregated information that the [IncludeScanner] needs from the compilation
     * action.
     */
    class IncludeScanningHeaderData(
        pathToDeclaredHeader: MutableMap<PathFragment?, Artifact?>,
        modularHeaders: MutableSet<Artifact?>,
        systemIncludeDirs: MutableList<PathFragment?>?,
        cmdlineIncludes: MutableList<String?>?,
        isValidUndeclaredHeader: java.util.function.Predicate<Artifact?>?
    ) {
        /**
         * Lookup table to find the [Artifact]s of generated files based on their [ ][Artifact.getExecPath].
         */
        private val pathToDeclaredHeader: MutableMap<PathFragment?, Artifact?>

        /**
         * The set of headers that are modular, i.e. are going to be read as a serialized AST rather
         * than from the textual source file. Depending on the implementation, it is likely that further
         * input discovery through such headers is unnecessary as the serialized AST is self-contained.
         */
        private val modularHeaders: MutableSet<Artifact?>

        /**
         * The list of "-isystem" include paths that should be used by the IncludeScanner for this
         * action. The compiler searches these paths ahead of the built-in system include paths, but
         * after all other paths. "-isystem" paths are treated the same as normal system directories.
         */
        private val systemIncludeDirs: MutableList<PathFragment?>?

        /**
         * A list of "-include" inclusions specified explicitly on the command line of this action. The
         * compiler will imagine that these files have been quote-included at the beginning of each
         * source file.
         */
        val cmdlineIncludes: MutableList<String?>?

        /**
         * Tests whether the given artifact is a valid header even if it is not declared, i.e. a
         * transitive dependency. If null, assume all headers can be included.
         */
        private val isValidUndeclaredHeader: java.util.function.Predicate<Artifact?>?

        init {
            this.pathToDeclaredHeader = pathToDeclaredHeader
            this.modularHeaders = modularHeaders
            this.systemIncludeDirs = systemIncludeDirs
            this.cmdlineIncludes = cmdlineIncludes
            this.isValidUndeclaredHeader = isValidUndeclaredHeader
        }

        fun isDeclaredHeader(header: PathFragment?): Boolean {
            return pathToDeclaredHeader.containsKey(header)
        }

        fun getHeaderArtifact(header: PathFragment?): Artifact? {
            return pathToDeclaredHeader.get(header)
        }

        fun isModularHeader(header: Artifact?): Boolean {
            return modularHeaders.contains(header)
        }

        fun getSystemIncludeDirs(): MutableList<PathFragment?>? {
            return systemIncludeDirs
        }

        fun isLegalHeader(header: Artifact): Boolean {
            return isValidUndeclaredHeader == null || pathToDeclaredHeader.containsKey(header.getExecPath())
                    || isValidUndeclaredHeader.test(header)
        }

        class Builder(
            pathToDeclaredHeader: MutableMap<PathFragment?, Artifact?>,
            modularHeaders: MutableSet<Artifact?>
        ) {
            private val pathToDeclaredHeader: MutableMap<PathFragment?, Artifact?>
            private val modularHeaders: MutableSet<Artifact?>
            private var systemIncludeDirs: MutableList<PathFragment?>? =
                com.google.common.collect.ImmutableList.of<PathFragment?>()
            private var cmdlineIncludes: MutableList<String?>? = com.google.common.collect.ImmutableList.of<String?>()
            private var isValidUndeclaredHeader: java.util.function.Predicate<Artifact?>? = null

            init {
                this.pathToDeclaredHeader = pathToDeclaredHeader
                this.modularHeaders = modularHeaders
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setSystemIncludeDirs(systemIncludeDirs: MutableList<PathFragment?>?): Builder {
                this.systemIncludeDirs = systemIncludeDirs
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setCmdlineIncludes(cmdlineIncludes: MutableList<String?>?): Builder {
                this.cmdlineIncludes = cmdlineIncludes
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setIsValidUndeclaredHeader(
                isValidUndeclaredHeader: java.util.function.Predicate<Artifact?>?
            ): Builder {
                this.isValidUndeclaredHeader = isValidUndeclaredHeader
                return this
            }

            fun build(): IncludeScanningHeaderData {
                return IncludeScanningHeaderData(
                    pathToDeclaredHeader,
                    modularHeaders,
                    systemIncludeDirs,
                    cmdlineIncludes,
                    isValidUndeclaredHeader
                )
            }
        }
    }
}
