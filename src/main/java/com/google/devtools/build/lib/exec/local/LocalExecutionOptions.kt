// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec.local

/** Local execution options.  */
@com.google.devtools.common.options.OptionsClass
abstract class LocalExecutionOptions : com.google.devtools.common.options.OptionsBase() {
    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "local_termination_grace_seconds",
        oldName = "local_sigkill_grace_seconds",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        defaultValue = "15",
        help = ("Time to wait between terminating a local process due to timeout and forcefully "
                + "shutting it down.")
    )
    abstract var localSigkillGraceSeconds: Int

    @get:com.google.devtools.common.options.Option(
        name = "allowed_local_actions_regex",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        converter = com.google.devtools.common.options.Converters.RegexPatternConverter::class,
        defaultValue = "null",
        help = ("A regex whitelist for action types which may be run locally. If unset, "
                + "all actions are allowed to execute locally")
    )
    abstract val allowedLocalAction: com.google.devtools.common.options.RegexPatternOption?

    abstract fun setAllowedLocalAction(value: com.google.devtools.common.options.RegexPatternOption?)

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_local_lockfree_output",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("When true, the local spawn runner doesn't lock the output tree during dynamic "
                + "execution. Instead, spawns are allowed to execute until they are explicitly "
                + "interrupted by a faster remote action.")
    )
    abstract var localLockfreeOutput: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_process_wrapper_graceful_sigterm",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("When true, make the process-wrapper propagate SIGTERMs (used by the dynamic scheduler "
                + "to stop process trees) to the subprocesses themselves, giving them the grace "
                + "period in --local_termination_grace_seconds before forcibly sending a SIGKILL.")
    )
    abstract var processWrapperGracefulSigterm: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_local_retries_on_crash",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Number of times to retry a local action when we detect that it crashed. This exists "
                + "to workaround a bug in OSXFUSE which is tickled by the use of the dynamic "
                + "scheduler and --experimental_local_lockfree_output due to constant process "
                + "churn. The bug can be triggered by a cancelled process that ran *before* the "
                + "process we are trying to run, introducing corruption in its file reads.")
    )
    abstract var localRetriesOnCrash: Int

    val localSigkillGraceSecondsDuration: java.time.Duration?
        get() =// TODO(ulfjack): Change localSigkillGraceSeconds type to Duration.
            java.time.Duration.ofSeconds(this.localSigkillGraceSeconds.toLong())
}
