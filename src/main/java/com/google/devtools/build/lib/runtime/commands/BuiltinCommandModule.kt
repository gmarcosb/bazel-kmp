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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.BlazeModule

/** Internal module for the built-in commands.  */
open class BuiltinCommandModule protected constructor(runCommand: RunCommand?) : BlazeModule() {
    private val runCommand: RunCommand?

    init {
        this.runCommand = runCommand
    }

    public override fun serverInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        builder: ServerBuilder
    ) {
        builder.addCommands(
            BuildCommand(),
            CanonicalizeCommand(),
            CleanCommand(),
            CoverageCommand(),
            DumpCommand(),
            com.google.devtools.build.lib.runtime.commands.HelpCommand(),
            InfoCommand(),
            PrintActionCommand(),
            QueryCommand(),
            runCommand,
            ShutdownCommand(),
            com.google.devtools.build.lib.runtime.commands.TestCommand(),
            VersionCommand(),
            AqueryCommand(),
            CqueryCommand(),
            ConfigCommand()
        )
        // Only enable the "license" command when this binary has an embedded LICENSE file.
        if (LicenseCommand.Companion.isSupported()) {
            builder.addCommands(LicenseCommand())
        }
    }
}
