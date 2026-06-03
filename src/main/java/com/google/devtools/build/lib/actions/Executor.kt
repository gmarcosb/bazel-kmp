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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.vfs.FileSystem

/**
 * The Executor provides the context for the execution of actions. It is only valid during the
 * execution phase, and references should not be cached.
 * 
 * 
 * This class provides the actual logic to execute actions. The platonic ideal of this system is
 * that [Action]s are immutable objects that tell Blaze **what** to do and
 * <link></link>ActionContexts tell Blaze **how** to do it (however, we do have an "execute"
 * method on actions now).
 * 
 * 
 * In theory, most of the methods below would not exist and they would be methods on action
 * contexts, but in practice, that would require some refactoring work so we are stuck with these
 * for the time being.
 * 
 * 
 * In theory, we could also merge [Executor] with [ActionExecutionContext], since
 * they both provide services to actions being executed and are passed to almost the same places.
 */
interface Executor : ActionContextRegistry {
    /** Returns the file system of blaze.  */
    fun getFileSystem(): FileSystem?

    /**
     * Returns the execution root. This is the directory underneath which Blaze builds its entire
     * output working tree, including the source symlink forest. All build actions are executed
     * relative to this directory.
     */
    fun getExecRoot(): Path?

    /**
     * Returns a clock. This is not hermetic, and should only be used for build info actions or
     * performance measurements / reporting.
     */
    fun getClock(): com.google.devtools.build.lib.clock.Clock?

    /**
     * Returns [BugReporter] instance to use to report bugs.
     * 
     * 
     * To facilitate testing, prefer using this instead of calling [BugReport.sendBugReport].
     */
    fun getBugReporter(): BugReporter?

    /** Returns the command line options of the Blaze command being executed.  */
    fun getOptions(): OptionsProvider?

    /**
     * Whether this Executor reports subcommands. If not, reportSubcommand has no effect.
     * This is provided so the caller of reportSubcommand can avoid wastefully constructing the
     * subcommand string.
     */
    fun reportsSubcommands(): ShowSubcommands?
}
