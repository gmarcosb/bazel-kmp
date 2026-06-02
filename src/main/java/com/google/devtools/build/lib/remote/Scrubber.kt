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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ActionOwner

/**
 * The [Scrubber] implements scrubbing of remote cache keys.
 * 
 * 
 * See the documentation for the `--experimental_remote_scrub_config` flag for more
 * information.
 */
class Scrubber @com.google.common.annotations.VisibleForTesting internal constructor(configProto: Config) {
    /** An error that occurred while parsing the scrubbing configuration.  */
    class ConfigParseException private constructor(message: String?, cause: Throwable?) :
        java.lang.Exception(message, cause)

    private val spawnScrubbers: com.google.common.collect.ImmutableList<SpawnScrubber>

    init {
        val spawnScrubbers: java.util.ArrayList<SpawnScrubber?> = java.util.ArrayList<SpawnScrubber?>()
        for (ruleProto in configProto.getRulesList()) {
            spawnScrubbers.add(SpawnScrubber(ruleProto))
        }
        // Reverse the order so that later rules supersede earlier ones.
        Collections.reverse(spawnScrubbers)
        this.spawnScrubbers = com.google.common.collect.ImmutableList.copyOf<SpawnScrubber?>(spawnScrubbers)
    }

    /**
     * Returns a [SpawnScrubber] suitable for a [Spawn], or `null` if the spawn does
     * not need to be scrubbed.
     */
    fun forSpawn(spawn: Spawn): SpawnScrubber? {
        for (spawnScrubber in spawnScrubbers) {
            if (spawnScrubber.matches(spawn)) {
                return spawnScrubber
            }
        }
        return null
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        return o is Scrubber && spawnScrubbers == o.spawnScrubbers
    }

    override fun hashCode(): Int {
        return spawnScrubbers.hashCode()
    }

    /**
     * Encapsulates a set of transformations required to scrub the remote cache key for a set of
     * spawns.
     */
    class SpawnScrubber private constructor(ruleProto: Config.Rule) {
        private val mnemonicPattern: java.util.regex.Pattern
        private val labelPattern: java.util.regex.Pattern
        private val kindPattern: java.util.regex.Pattern
        private val matchTools: Boolean

        private val omittedInputPatterns: com.google.common.collect.ImmutableList<java.util.regex.Pattern>
        private val argReplacements: com.google.common.collect.ImmutableMap<java.util.regex.Pattern?, String?>

        /** Returns the scrubbing salt.  */
        val salt: String?

        init {
            val matcherProto: Config.Matcher = ruleProto.getMatcher()
            this.mnemonicPattern = java.util.regex.Pattern.compile(emptyToAll(matcherProto.getMnemonic()))
            this.labelPattern = java.util.regex.Pattern.compile(emptyToAll(matcherProto.getLabel()))
            this.kindPattern = java.util.regex.Pattern.compile(emptyToAll(matcherProto.getKind()))
            this.matchTools = matcherProto.getMatchTools()

            val transformProto: Config.Transform = ruleProto.getTransform()
            this.omittedInputPatterns =
                transformProto.getOmittedInputsList().stream()
                    .map(java.util.regex.Pattern::compile)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            this.argReplacements =
                transformProto.getArgReplacementsList().stream()
                    .collect(com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(java.util.function.Function { r: T? ->
                        java.util.regex.Pattern.compile(
                            r.getSource()
                        )
                    }, java.util.function.Function { r: T? -> r.getTarget() }))
            this.salt = ruleProto.getTransform().getSalt()
        }

        private fun emptyToAll(s: String): String {
            return if (s.isEmpty()) ".*" else s
        }

        /** Whether this scrubber applies to the given [Spawn].  */
        private fun matches(spawn: Spawn): Boolean {
            val mnemonic: String? = spawn.getMnemonic()
            val actionOwner: ActionOwner = spawn.getResourceOwner().getOwner()
            val label: String? = actionOwner.getLabel().getCanonicalForm()
            val kind: String? = actionOwner.getTargetKind()
            val isForTool: Boolean = actionOwner.isBuildConfigurationForTool()

            return (!isForTool || matchTools)
                    && mnemonicPattern.matcher(mnemonic).matches()
                    && labelPattern.matcher(label).matches()
                    && kindPattern.matcher(kind).matches()
        }

        /** Whether an input with the given exec-relative path should be omitted from the cache key.  */
        fun shouldOmitInput(execPath: PathFragment): Boolean {
            for (pattern in omittedInputPatterns) {
                if (pattern.matcher(execPath.getPathString()).matches()) {
                    return true
                }
            }
            return false
        }

        /** Transforms a command line argument.  */
        fun transformArgument(arg: String): String {
            var arg = arg
            for (entry in argReplacements.entrySet()) {
                val pattern: java.util.regex.Pattern = entry.getKey()
                val replacement: String? = entry.getValue()
                // Don't use Pattern#replaceFirst because it allows references to capture groups.
                val m: java.util.regex.Matcher = pattern.matcher(arg)
                if (m.find()) {
                    arg = arg.substring(0, m.start()) + replacement + arg.substring(m.end())
                }
            }
            return arg
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            return o is SpawnScrubber
                    && matchTools == o.matchTools && mnemonicPattern == o.mnemonicPattern
                    && labelPattern == o.labelPattern
                    && kindPattern == o.kindPattern
                    && omittedInputPatterns == o.omittedInputPatterns
                    && argReplacements == o.argReplacements
                    && salt == o.salt
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                mnemonicPattern,
                labelPattern,
                kindPattern,
                matchTools,
                omittedInputPatterns,
                argReplacements,
                salt
            )
        }
    }

    companion object {
        /**
         * Constructs a [Scrubber] from the given configuration file, which must contain a [ ] protocol buffer in text format.
         */
        @Throws(ConfigParseException::class)
        fun parse(configPath: String?): Scrubber {
            try {
                java.nio.file.Files.newBufferedReader(Paths.get(configPath)).use { reader ->
                    val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        Config.newBuilder()
                    TextFormat.getParser().merge(reader, builder)
                    return Scrubber(builder.build())
                }
            } catch (e: IOException) {
                throw ConfigParseException(e.getMessage(), e)
            } catch (e: PatternSyntaxException) {
                throw ConfigParseException(
                    java.lang.String.format("in regex '%s': %s", e.getPattern(), e.getMessage()), e
                )
            }
        }
    }
}
