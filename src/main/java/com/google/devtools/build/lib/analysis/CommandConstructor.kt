// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * The interface to construct command line for different shells (Bash, Batch, Powershell). Used in
 * [com.google.devtools.build.lib.analysis.CommandHelper]
 */
interface CommandConstructor {
    /**
     * Given a string of command, return the arguments to run the command. eg. For Bash command,
     * asExecArgv("foo bar") -> ["/bin/bash", "-c", "foo bar"]
     */
    fun asExecArgv(command: String?): com.google.common.collect.ImmutableList<String?>?

    /** Given an artifact of a script, return the arguments to run this command.  */
    fun asExecArgv(scriptFileArtifact: Artifact?): com.google.common.collect.ImmutableList<String?>?

    /** Write the command to a script and return the artifact of the script.  */
    fun commandAsScript(ruleContext: RuleContext?, command: String?): Artifact?
}
