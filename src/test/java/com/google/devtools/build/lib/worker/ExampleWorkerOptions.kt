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

import com.google.devtools.build.lib.actions.ExecutionRequirements

/** Options for the example worker itself.  */
@OptionsClass
abstract class ExampleWorkerOptions : OptionsBase() {
    /** Options for the example worker concerning single units of work.  */
    @OptionsClass
    abstract class ExampleWorkOptions : OptionsBase() {
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
            name = "print_requests",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Prints out all requests."
        )
        abstract val printRequests: Boolean

        @get:Option(
            name = "print_inputs",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Writes a list of input files and their digests."
        )
        abstract val printInputs: Boolean

        @get:Option(
            name = "print_dir_listing",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "",
            help = "Writes a recursive listing of the given directory, not following symlinks."
        )
        abstract val printDirListing: String?

        @get:Option(
            name = "print_env",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false",
            help = "Prints a list of all environment variables."
        )
        abstract val printEnv: Boolean

        @get:Option(
            name = "work_time",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            converter = DurationConverter::class,
            defaultValue = "0",
            help = ("When the worker receives a work request, it will sleep for this long before "
                    + "responding.")
        )
        abstract val workTime: java.time.Duration?
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
        name = "exit_during",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "0",
        help = "The worker exits during processing after this many work units (default: disabled)."
    )
    abstract val exitDuring: Int

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

    @get:Option(
        name = "wait_for_cancel",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "false",
        help = "Don't send a response until receiving a cancel request."
    )
    abstract val waitForCancel: Boolean

    @get:Option(
        name = "ignored_argument",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "false",
        help = "An argument that does nothing, but whose presence can be asserted in a test."
    )
    abstract val ignoredArgument: Boolean

    /** Enum converter for --worker_protocol.  */
    class WorkerProtocolEnumConverter

        : EnumConverter<ExecutionRequirements.WorkerProtocolFormat?>(
        ExecutionRequirements.WorkerProtocolFormat::class.java,
        "worker protocol format option"
    )

    @get:Option(
        name = "worker_protocol",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "proto",
        help = "The protocol (JSON or proto) to use for communication between this worker and Bazel.",
        converter = WorkerProtocolEnumConverter::class
    )
    abstract val workerProtocol: ExecutionRequirements.WorkerProtocolFormat?
}
