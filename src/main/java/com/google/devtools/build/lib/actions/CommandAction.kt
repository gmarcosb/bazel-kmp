// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * An action that exposes command line arguments and environment variables for the process in which
 * that command line is executed.
 */
interface CommandAction : com.google.devtools.build.lib.actions.Action {
    /**
     * Returns a list of command line arguments that implements this action.
     * 
     * 
     * In most cases, this expands any params files. One notable exception is C/C++ compilation
     * with the "compiler_param_file" feature.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun getArguments(): MutableList<String?>?

    /**
     * Returns a map of command line variables to their values that constitute the environment in
     * which this action should be run. This excludes any inherited environment variables, as this
     * method does not provide access to the client environment.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(ActionExecutionException::class)
    fun getIncompleteEnvironmentForTesting(): com.google.common.collect.ImmutableMap<String?, String?>?

    /** Returns inputs to this action, including inputs that may be pruned.  */
    @com.google.common.annotations.VisibleForTesting
    fun getPossibleInputsForTesting(): NestedSet<Artifact?>?
}
