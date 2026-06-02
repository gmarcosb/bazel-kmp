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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.query2.engine.QueryEvalResult.isEmpty
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import java.util.HashSet

/**
 * Holds `--config=foo` definitions for the current invocation.
 * 
 * 
 * Callers can use this to determine which flags `--config=foo` sets:
 * 
 * {@snippet :
 * *   ConfigFlagDefinitions definitions = <get a reference from your favorite provider>;
 * *   try {
 * *     ConfigValue definition = ConfigFlagDefinitions.get("foo", configDefinitions);
 * *     List<String> expandedFlags = definition.flags();
 * *     List<String> rcfileSources = definition.rcSources();
 * *   } catch (OptionsParsingException e) {
 * *      // --config=foo doesn't exist or doesn't resolve.
 * *   }
 * * }
 * 
 * 
 * This is a pure data class, which makes it Skyframe- and cache-friendly. Resolution logic is a
 * pure static function over that data.
</String></String></get> */
class ConfigFlagDefinitions internal constructor(definitions: com.google.common.collect.ImmutableListMultimap<String?, ConfigDefinition?>) {
    private val definitions: com.google.common.collect.ImmutableListMultimap<String?, ConfigDefinition?>

    /**
     * There's no need for callers outside `lib.runtime` to construct this or see its underlying
     * data. The underlying data is a complicated implementation detail of the options parsing logic.
     */
    init {
        this.definitions = definitions
    }

    /**
     * `--config=foo` expansion.
     * 
     * @param flags the flags this --config expands to
     * @param rcSources full paths of the rc files that define this --config. Can be more than one
     * because it may call other --configs from other files.
     */
    @kotlin.jvm.JvmRecord
    data class ConfigValue(@kotlin.jvm.JvmField val flags: MutableList<String?>?, @kotlin.jvm.JvmField val rcSources: MutableSet<String?>?)

    /** Serialization- and BUILD library-friendly version of RcChunkOfArgs.  */
    @AutoCodec
    internal class ConfigDefinition(flags: com.google.common.collect.ImmutableList<String>?, rcSource: String?) {
        val flags: com.google.common.collect.ImmutableList<String>?
        val rcSource: String?

        init {
            this.flags = flags
            this.rcSource = rcSource
        }
    }

    override fun equals(o: Any?): Boolean {
        if (o is ConfigFlagDefinitions) {
            return o.definitions == definitions
        }
        return false
    }

    override fun hashCode(): Int {
        return definitions.hashCode()
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        val NONE: ConfigFlagDefinitions =
            ConfigFlagDefinitions(com.google.common.collect.ImmutableListMultimap.of<String?, ConfigDefinition?>())

        /**
         * Returns the definition of `--config=<configName>`
         * 
         * @throws OptionsParsingException if the config doesn't exist or doesn't resolve correctly
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun get(configName: String?, definitions: ConfigFlagDefinitions): ConfigValue {
            if (!definitions.definitions.containsKey(configName)) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        "--config=%s doesn't exist",
                        configName
                    )
                )
            }
            val flags: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            val rcSources: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            val seenConfigs: MutableSet<String?> = HashSet<String?>()
            applyDefinition(configName, configName, definitions, flags, rcSources, seenConfigs)
            return ConfigValue(flags.build(), rcSources.build())
        }

        /**
         * Expands `--config=<configName>`, making recursive calls any time it encounters `--config=something_else`. Throws an `OptionsParsingException` on bad references or
         * cycles.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun applyDefinition(
            origConfigName: String?,
            configName: String?,
            definitions: ConfigFlagDefinitions,
            flags: com.google.common.collect.ImmutableList.Builder<String?>,
            rcSources: com.google.common.collect.ImmutableSet.Builder<String?>,
            seenConfigs: MutableSet<String?>
        ) {
            val directFlags: com.google.common.collect.ImmutableList<ConfigDefinition> =
                definitions.definitions.get(configName)
            if (!seenConfigs.add(configName)) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        "--config=%s can't be evaluated because its definition has a cycle.",
                        origConfigName
                    )
                )
            }
            if (directFlags.isEmpty()) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        "--config=%s doesn't resolve because it expands to non-existent --config=%s",
                        origConfigName, configName
                    )
                )
            }
            // The underlying data stores a multimap: { name: Collection<Definition> }. This is more
            // important for non-config definitions like "build --a=b" where each rc file defines its own
            // "build" args. For --config, just choose the first.
            val def: ConfigDefinition = directFlags.get(0)
            rcSources.add(def.rcSource)
            for (flag in def.flags) {
                if (flag.startsWith("--config=")) {
                    applyDefinition(
                        origConfigName,
                        flag.substring(flag.indexOf("=") + 1),
                        definitions,
                        flags,
                        rcSources,
                        seenConfigs
                    )
                } else {
                    flags.add(flag)
                }
            }
        }
    }
}
