// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.platform.PlatformValue
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue

/**
 * A platform's [PlatformInfo] along with its parsed flags.
 * 
 * @param parsedFlags Only present if the platform specifies flags.
 */
@AutoCodec
class PlatformValue(
    platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?,
    parsedFlags: java.util.Optional<ParsedFlagsValue?>?
) : SkyValue {
    /** Key definition.  */
    @AutoCodec
    class Key private constructor(
        label: com.google.devtools.build.lib.cmdline.Label?,
        flagAliasMappings: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>?
    ) : SkyKey {
        private val label: com.google.devtools.build.lib.cmdline.Label
        private val flagAliasMappings: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>? =
            null
        private val hashCode: Int

        init {
            .also {
                this.label = it
            }<Label> java . util . Objects . requireNonNull < com . google . devtools . build . lib . cmdline . Label ? > (label)
            TODO(
                """
                |Cannot convert element
                |With text:
                |this.flagAliasMappings = <ImmutableMap<String, Label>>requireNonNull(flagAliasMappings);
                """.trimMargin()
            )
            this.hashCode = java.util.Objects.hash(label, flagAliasMappings)
        }

        fun label(): com.google.devtools.build.lib.cmdline.Label {
            return label
        }

        fun flagAliasMappings(): com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?> {
            return flagAliasMappings
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PLATFORM
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.analysis.platform.PlatformValue.Key.Companion.interner

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Key) {
                return false
            }
            return label == o.label && flagAliasMappings == o.flagAliasMappings
        }

        override fun hashCode(): Int {
            return hashCode
        }

        override fun toString(): String {
            return "Key[label=" + label + ", flagAliasMappings=" + flagAliasMappings + "]"
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKeyInterner<Key?>()

            @AutoCodec.Instantiator
            fun create(
                label: com.google.devtools.build.lib.cmdline.Label?,
                flagAliasMappings: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>?
            ): Key? {
                return com.google.devtools.build.lib.analysis.platform.PlatformValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.analysis.platform.PlatformValue.Key(
                        label,
                        flagAliasMappings
                    )
                )
            }
        }
    }

    val platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?
    val parsedFlags: java.util.Optional<ParsedFlagsValue?>?

    init {
        this.parsedFlags = parsedFlags
        this.platformInfo = platformInfo
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.analysis.platform.PlatformInfo?>(
            platformInfo,
            "platformInfo"
        )
        java.util.Objects.requireNonNull<java.util.Optional<ParsedFlagsValue?>?>(parsedFlags, "parsedFlags")
    }

    companion object {
        fun noFlags(platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?): PlatformValue {
            return PlatformValue(platformInfo,  /* parsedFlags= */java.util.Optional.empty<ParsedFlagsValue?>())
        }

        fun withFlags(
            platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo?,
            parsedFlags: ParsedFlagsValue
        ): PlatformValue {
            return PlatformValue(platformInfo, java.util.Optional.of<ParsedFlagsValue?>(parsedFlags))
        }

        fun key(
            platformLabel: com.google.devtools.build.lib.cmdline.Label?,
            flagAliasMappings: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>?
        ): Key? {
            return com.google.devtools.build.lib.analysis.platform.PlatformValue.Key.Companion.create(
                platformLabel,
                flagAliasMappings
            )
        }
    }
}
