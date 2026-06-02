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

import com.google.devtools.build.lib.actions.Artifact

/**
 * To be implemented by actions (such as C++ compilation steps) whose inputs can be scanned to
 * discover other implicit inputs (such as C++ header files).
 * 
 * 
 * This is useful for remote execution strategies to be able to compute the complete set of files
 * that must be distributed in order to execute such an action.
 */
interface IncludeScannable {
    /**
     * Returns the built-in list of system include paths for the toolchain compiler. All paths in this
     * list should be relative to the exec directory. They may be absolute if they are also installed
     * on the remote build nodes or for local compilation.
     */
    val builtInIncludeDirectories: MutableList<PathFragment>?

    /**
     * Returns an immutable list of "-iquote" include paths that should be used by
     * the IncludeScanner for this action. GCC searches these paths first, but
     * only for `#include "foo"`, not for `#include &lt;foo&gt;`.
     */
    val quoteIncludeDirs: MutableList<PathFragment>?

    /**
     * Returns an immutable list of "-I" include paths that should be used by the
     * IncludeScanner for this action. GCC searches these paths ahead of the
     * system include paths, but after "-iquote" include paths.
     */
    val includeDirs: MutableList<PathFragment>?

    /**
     * Returns an immutable list of "-F" framework include paths that should be used by the
     * IncludeScanner for this action. The include scanner searches these paths after "-iquote"
     * include paths, but before other non-framework include paths.
     */
    val frameworkIncludeDirs: com.google.common.collect.ImmutableList<PathFragment?>?

    /**
     * Returns an artifact that the compiler may unconditionally include, even if the source file does
     * not mention it.
     */
    val builtInIncludeFiles: MutableList<Artifact>?

    /**
     * Returns the artifact relative to which the `getCmdlineIncludes()` should be interpreted.
     */
    val mainIncludeScannerSource: Artifact?

    /**
     * Returns an immutable list of sources that the IncludeScanner should scan
     * for this action.
     * 
     * 
     * Must contain `getMainIncludeScannerSource()`.
     */
    val includeScannerSources: MutableCollection<Artifact>?

    /**
     * Returns explicit header files (i.e., header files explicitly listed) of transitive deps.
     */
    val declaredIncludeSrcs: NestedSet<Artifact?>?

    /**
     * Returns an artifact that is the executable for grepping #include lines from a file.
     */
    val grepIncludes: Artifact?

    /** Returns modules necessary for building and using the output of this action.  */
    @kotlin.jvm.JvmField
    val discoveredModules: NestedSet<Artifact?>?
}
