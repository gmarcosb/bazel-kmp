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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.platform.PlatformValue
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.state.StateMachine
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrException2Sink

/** Retrieves [PlatformValue] for a given platform.  */
internal class PlatformProducer
    (
    platformLabel: com.google.devtools.build.lib.cmdline.Label?,
    flagAliasMappings: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>?,
    sink: ResultSink,
    runAfter: StateMachine?
) : StateMachine,
    ValueOrException2Sink<InvalidPlatformException?, com.google.devtools.common.options.OptionsParsingException?> {
    internal interface ResultSink {
        fun acceptPlatformValue(value: PlatformValue?)

        fun acceptPlatformInfoError(error: InvalidPlatformException?)

        fun acceptOptionsParsingError(error: com.google.devtools.common.options.OptionsParsingException?)
    }

    // -------------------- Input --------------------
    private val platformLabel: com.google.devtools.build.lib.cmdline.Label?
    private val flagAliasMappings: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>?

    // -------------------- Output --------------------
    private val sink: ResultSink

    // -------------------- Sequencing --------------------
    private val runAfter: StateMachine?

    init {
        this.platformLabel = platformLabel
        this.flagAliasMappings = flagAliasMappings
        this.sink = sink
        this.runAfter = runAfter
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine? {
        tasks.lookUp<InvalidPlatformException?, com.google.devtools.common.options.OptionsParsingException?>(
            PlatformValue.Companion.key(platformLabel, flagAliasMappings),
            InvalidPlatformException::class.java,
            com.google.devtools.common.options.OptionsParsingException::class.java,
            this
        )
        return runAfter
    }

    override fun acceptValueOrException2(
        value: SkyValue?,
        invalidPlatformException: InvalidPlatformException?,
        optionsParsingException: com.google.devtools.common.options.OptionsParsingException?
    ) {
        if (value != null) {
            sink.acceptPlatformValue(value as PlatformValue)
        } else if (invalidPlatformException != null) {
            sink.acceptPlatformInfoError(invalidPlatformException)
        } else {
            sink.acceptOptionsParsingError(optionsParsingException)
        }
    }
}
