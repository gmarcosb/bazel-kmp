// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.AbstractCommandLine

/**
 * Memory-efficient [CommandLine] for a shell command.
 * 
 * 
 * Equivalent to invoking [.shExecutable] (e.g. `/bin/bash`) followed by `-c`
 * and [.command]. Supports optionally padding the command line with an empty argument, which
 * can be useful to ensure that any subsequent arguments get assigned to `$1` etc.
 */
internal class ShellCommand(shExecutable: PathFragment?, command: String?, private val pad: Boolean) :
    AbstractCommandLine() {
    private val shExecutable: PathFragment
    private val command: String

    init {
        this.shExecutable = com.google.common.base.Preconditions.checkNotNull<PathFragment>(shExecutable)
        this.command = com.google.common.base.Preconditions.checkNotNull<String>(command)
    }

    public override fun arguments(): com.google.common.collect.ImmutableList<String?> {
        return if (pad)
            com.google.common.collect.ImmutableList.of<E?>(shExecutable.expandToCommandLine(), "-c", command, "")
        else
            com.google.common.collect.ImmutableList.of<E?>(shExecutable.expandToCommandLine(), "-c", command)
    }
}
