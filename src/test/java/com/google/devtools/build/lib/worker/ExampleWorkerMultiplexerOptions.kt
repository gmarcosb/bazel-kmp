// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.common.options.Option

/** Options for the example worker itself.  */
@OptionsClass
abstract class ExampleWorkerMultiplexerOptions : OptionsBase() {
    /** Options for the example worker concerning single units of work.  */
    @OptionsClass
    abstract class ExampleWorkMultiplexerOptions : OptionsBase() {
        @get:Option(
            name = "output_file",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "",
            help = "Write the output to a file instead of stdout."
        )
        abstract val outputFile: String?

        @get:Option(
            name = "uppercase",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Uppercase the input."
        )
        abstract val uppercase: Boolean

        @get:Option(
            name = "write_uuid",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Writes a UUID into the output."
        )
        abstract val writeUUID: Boolean

        @get:Option(
            name = "write_counter",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Writes a counter that increases with each work unit processed into the output."
        )
        abstract val writeCounter: Boolean

        @get:Option(
            name = "print_inputs",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Writes a list of input files and their digests."
        )
        abstract val printInputs: Boolean

        @get:Option(
            name = "print_env",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Prints a list of all environment variables."
        )
        abstract val printEnv: Boolean

        @get:Option(
            name = "delay",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Randomly delay the worker response (between 100 to 300 ms)."
        )
        abstract val delay: Boolean

        @get:Option(
            name = "ignore_sandbox",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Ignore the sandbox settings in work requests."
        )
        abstract val ignoreSandbox: Boolean
    }

    @get:Option(
        name = "persistent_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "false"
    )
    abstract val persistentWorker: Boolean

    @get:Option(
        name = "exit_after",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "0",
        help = "The worker exits after processing this many work units (default: disabled)."
    )
    abstract val exitAfter: Int

    @get:Option(
        name = "poison_after",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "0",
        help = ("Poisons the worker after processing this many work units, so that it returns a "
                + "corrupt response instead of a response protobuf from then on (default: disabled).")
    )
    abstract val poisonAfter: Int

    @get:Option(
        name = "hard_poison",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "false",
        help = "Instead of writing an error message to stdout, write it to stderr and terminate."
    )
    abstract val hardPoison: Boolean
}
