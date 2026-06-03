// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/**
 * Strings used to express requirements on action execution environments.
 * 
 * 
 * If you are adding a new execution requirement, pay attention to the following:
 *  1. If its name starts with one of the supported prefixes, then it can be also used as a tag on
 * a target and will be propagated to the execution requirements, see for prefixes [       ][com.google.devtools.build.lib.packages.TargetUtils.getExecutionInfo]
 *  1. If this is a potentially conflicting execution requirements, e.g. you are adding a pair
 * 'requires-x' and 'block-x', you MUST take care of a potential conflict in the Executor that
 * is using new execution requirements. As an example, see [       ][Spawns.requiresNetwork].
 * 
 */
object ExecutionRequirements {
    /** If specified, the timeout of this action in seconds. Must be decimal integer.  */
    const val TIMEOUT: String = "timeout"

    /** If an action would not successfully run other than on Darwin.  */
    const val REQUIRES_DARWIN: String = "requires-darwin"

    /** Whether we should disable prefetching of inputs before running a local action.  */
    const val DISABLE_LOCAL_PREFETCH: String = "disable-local-prefetch"

    /** How many hardware threads an action requires for execution.  */
    val CPU: ParseableRequirement = ParseableRequirement.Companion.create(
        "cpu:<int>",
        java.util.regex.Pattern.compile("cpu:(.+)"),
        com.google.common.base.Function { s: String? ->
            com.google.common.base.Preconditions.checkNotNull<String?>(s)
            val value: Int
            try {
                value = s.toInt()
            } catch (e: java.lang.NumberFormatException) {
                return@create "can't be parsed as an integer"
            }

            // De-and-reserialize & compare to only allow canonical integer formats.
            if (value.toString() != s) {
                return@create "must be in canonical format (e.g. '4' instead of '+04')"
            }

            if (value < 1) {
                return@create "can't be zero or negative"
            }
            null
        })

    /** How many extra resources an action requires for execution.  */
    val RESOURCES: ParseableRequirement = ParseableRequirement.Companion.create(
        "resources:<str>:<float>",
        java.util.regex.Pattern.compile("resources:(.+:.+)"),
        com.google.common.base.Function { s: String? ->
            com.google.common.base.Preconditions.checkNotNull<String?>(s)
            val splitIndex: Int = s.indexOf(":")
            val resourceCount: String = s.substring(splitIndex + 1)
            val value: Float
            try {
                value = resourceCount.toFloat()
            } catch (e: java.lang.NumberFormatException) {
                return@create "can't be parsed as a float"
            }

            if (value < 0) {
                return@create "can't be negative"
            }
            null
        })

    /**
     * Parses resource requirements from a string map. Handles both execution info (tag format) and
     * exec_properties (key-value format):
     * 
     * 
     *  * Tags: key is the full tag (e.g. `"resources:cpu:4"` or `"cpu:2"`), value is
     * empty
     *  * exec_properties: key is the resource prefix (e.g. `"resources:cpu"`), value is the
     * amount (e.g. `"4"`)
     * 
     * 
     * 
     * In both cases, the entry is normalized to tag format and parsed with [.RESOURCES] and
     * [.CPU].
     * 
     * @return resource name to amount mapping; empty if no resource entries are found
     * @throws UserExecException if a matching entry has an invalid value or a resource is specified
     * more than once
     */
    @Throws(UserExecException::class)
    fun parseResources(map: MutableMap<String?, String?>): com.google.common.collect.ImmutableMap<String?, Double?> {
        if (map.isEmpty()) {
            return com.google.common.collect.ImmutableMap.of<String?, Double?>()
        }

        val resources: MutableMap<String?, Double?> = HashMap<String?, Double?>()
        for (entry in map.entries) {
            // Normalize to tag format: tags have the value baked into the key (e.g. "resources:cpu:4"
            // with empty value), exec_properties split it (e.g. key "resources:cpu", value "4").
            val tag: String =
                (if (entry.value.isEmpty()) entry.key else entry.key + ":" + entry.value)!!

            var requirement = RESOURCES
            val resource: String?
            val amount: String?
            try {
                val parsed = requirement.parseIfMatches(tag)
                if (parsed != null) {
                    val splitIndex: Int = parsed.indexOf(":")
                    resource = parsed.substring(0, splitIndex)
                    amount = parsed.substring(splitIndex + 1)
                } else {
                    requirement = CPU
                    val cpuValue = requirement.parseIfMatches(tag)
                    if (cpuValue == null) {
                        if (tag.startsWith("resources:")) {
                            // A key clearly intended as a resource that didn't match the expected format (e.g.
                            // "resources:cpu:" with an empty amount).
                            throw UserExecException(
                                createFailureDetail(
                                    String.format(
                                        "'%s' is not a valid '%s' entry", tag, RESOURCES.userFriendlyName
                                    ),
                                    Code.INVALID_CPU_TAG
                                )
                            )
                        }
                        continue
                    }
                    resource = "cpu"
                    amount = cpuValue
                }
            } catch (e: ParseableRequirement.ValidationException) {
                throw UserExecException(
                    createFailureDetail(
                        String.format(
                            "'%s' has a '%s' entry, but its value '%s' didn't pass validation: %s",
                            tag, requirement.userFriendlyName, e.tagValue, e.message
                        ),
                        Code.INVALID_CPU_TAG
                    )
                )
            }
            if (resources.containsKey(resource)) {
                throw UserExecException(
                    createFailureDetail(
                        String.format(
                            "'%s' has more than one entry for resource '%s', but duplicates aren't"
                                    + " allowed",
                            tag, resource
                        ),
                        Code.DUPLICATE_CPU_TAGS
                    )
                )
            }
            resources.put(resource, amount.toDouble())
        }
        return com.google.common.collect.ImmutableMap.copyOf<String?, Double?>(resources)
    }

    private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
        return FailureDetail.newBuilder()
            .setMessage(message)
            .setTestAction(TestAction.newBuilder().setCode(detailedCode))
            .build()
    }

    /** If an action supports running in persistent worker mode.  */
    const val SUPPORTS_WORKERS: String = "supports-workers"

    const val SUPPORTS_MULTIPLEX_WORKERS: String = "supports-multiplex-workers"

    /** Specify the type of worker protocol the worker uses.  */
    const val REQUIRES_WORKER_PROTOCOL: String = "requires-worker-protocol"

    const val SUPPORTS_WORKER_CANCELLATION: String = "supports-worker-cancellation"

    const val SUPPORTS_MULTIPLEX_SANDBOXING: String = "supports-multiplex-sandboxing"

    /** Override for the action's mnemonic to allow for better worker process reuse.  */
    const val WORKER_KEY_MNEMONIC: String = "worker-key-mnemonic"

    val WORKER_MODE_ENABLED: com.google.common.collect.ImmutableMap<String?, String?> =
        com.google.common.collect.ImmutableMap.of<String?, String?>(
            SUPPORTS_WORKERS, "1"
        )

    val WORKER_MULTIPLEX_MODE_ENABLED: com.google.common.collect.ImmutableMap<String?, String?> =
        com.google.common.collect.ImmutableMap.of<String?, String?>(
            SUPPORTS_MULTIPLEX_WORKERS, "1"
        )

    /**
     * Requires local execution without sandboxing for a spawn.
     * 
     * 
     * This tag is deprecated; use no-cache, no-remote, or no-sandbox instead.
     */
    const val LOCAL: String = "local"

    /**
     * Disables local and remote caching for a spawn, but note that the local action cache may still
     * apply.
     * 
     * 
     * This tag can also be set on an action, in which case it completely disables all caching for
     * that action, but note that action-generated spawns may still be cached, unless they also carry
     * this tag.
     */
    const val NO_CACHE: String = "no-cache"

    /** Disables remote caching of a spawn. Note: does not disable remote execution  */
    const val NO_REMOTE_CACHE: String = "no-remote-cache"

    /** Disables upload part of remote caching of a spawn. Note: does not disable remote execution  */
    const val NO_REMOTE_CACHE_UPLOAD: String = "no-remote-cache-upload"

    /** Disables remote execution of a spawn. Note: does not disable remote caching  */
    const val NO_REMOTE_EXEC: String = "no-remote-exec"

    /** Tag for Google internal use. Requires local execution with correct permissions.  */
    const val NO_TESTLOASD: String = "no-testloasd"

    /**
     * Disables both remote execution and remote caching of a spawn. This is the equivalent of using
     * no-remote-cache and no-remote-exec together.
     */
    const val NO_REMOTE: String = "no-remote"

    /** Disables local execution of a spawn.  */
    const val NO_LOCAL: String = "no-local"

    /** Disables local sandboxing of a spawn.  */
    const val LEGACY_NOSANDBOX: String = "nosandbox"

    /** Disables local sandboxing of a spawn.  */
    const val NO_SANDBOX: String = "no-sandbox"

    /**
     * Set for Xcode-related rules. Used for quality control to make sure that all Xcode-dependent
     * rules propagate the necessary configurations. Begins with "supports" so as not to be filtered
     * out for Bazel by `TargetUtils`.
     */
    const val REQUIREMENTS_SET: String = "supports-xcode-requirements-set"

    /**
     * Enables networking for a spawn if possible (only if sandboxing is enabled and if the sandbox
     * supports it).
     */
    const val REQUIRES_NETWORK: String = "requires-network"

    /**
     * Disables networking for a spawn if possible (only if sandboxing is enabled and if the sandbox
     * supports it).
     */
    const val BLOCK_NETWORK: String = "block-network"

    /**
     * On linux, if sandboxing is enabled, ensures that a spawn is run with uid 0, i.e., root. Has no
     * effect otherwise.
     */
    const val REQUIRES_FAKEROOT: String = "requires-fakeroot"

    /** Suppress CLI reporting for this spawn - it's part of another action.  */
    const val DO_NOT_REPORT: String = "internal-do-not-report"

    /** Use this to request eager fetching of a single remote output into local memory.  */
    const val REMOTE_EXECUTION_INLINE_OUTPUTS: String = "internal-inline-outputs"

    /** Tag for Google internal use. Indicates a memory estimate in bytes.  */
    const val MEMORY_ESTIMATE: String = "internal-memory-estimate"

    /**
     * Request graceful termination of subprocesses on interrupt (that is, an initial `SIGTERM`
     * followed by a `SIGKILL` after a grace period).
     */
    const val GRACEFUL_TERMINATION: String = "supports-graceful-termination"

    /** Requires the execution service to support a given Xcode version e.g. "xcode_version:1.0".  */
    const val REQUIRES_XCODE: String = "requires-xcode"

    /**
     * Requires the execution service to support a "label" in addition to the Xcode version. The user
     * specifies the label as a hyphenated extension to their requested version. For example, if the
     * user requests "--xcode_version=1.0-unstable", the action request will include
     * "requires-xcode-label:unstable" and "requires-xcode:1.0".
     */
    const val REQUIRES_XCODE_LABEL: String = "requires-xcode-label"

    /** Requires the execution service do NOT share caches across different workspace.  */
    const val DIFFERENTIATE_WORKSPACE_CACHE: String = "internal-differentiate-workspace-cache"

    /**
     * Indicates that the action is compatible with path mapping, e.g., removing the configuration
     * segment from the paths of all inputs and outputs.
     */
    const val SUPPORTS_PATH_MAPPING: String = "supports-path-mapping"

    /** An execution requirement that can be split into a key and a value part using a regex.  */
    class ParseableRequirement(
        userFriendlyName: String?,
        detectionPattern: java.util.regex.Pattern?,
        validator: com.google.common.base.Function<String?, String?>?
    ) {
        /**
         * Thrown when a [ParseableRequirement] feels responsible for a tag, but the [ ][.validator] method returns an error.
         */
        class ValidationException
        /**
         * Creates a new [ValidationException].
         * 
         * @param tagValue the erroneous value that was parsed from the tag.
         * @param errorMsg an error message that tells the user what's wrong with the value.
         */(
            /**
             * Returns the erroneous value of the parsed tag.
             * 
             * 
             * Useful to put in error messages shown to the user.
             */
            val tagValue: String?, errorMsg: String?
        ) : java.lang.Exception(errorMsg)

        /**
         * Returns the parsed value from a tag, if this [ParseableRequirement] detects that it is
         * responsible for it, otherwise returns `null`.
         * 
         * @throws ValidationException if the value parsed out of the tag doesn't pass the validator.
         */
        @Throws(com.google.devtools.build.lib.actions.ExecutionRequirements.ParseableRequirement.ValidationException::class)
        fun parseIfMatches(tag: String?): String? {
            val matcher: java.util.regex.Matcher = this.detectionPattern.matcher(tag)
            if (!matcher.matches()) {
                return null
            }
            val tagValue: String? = matcher.group(1)
            val errorMsg: String? = this.validator.apply(tagValue)
            if (errorMsg != null) {
                throw com.google.devtools.build.lib.actions.ExecutionRequirements.ParseableRequirement.ValidationException(
                    tagValue,
                    errorMsg
                )
            }
            return tagValue
        }

        val userFriendlyName: String?
        val detectionPattern: java.util.regex.Pattern?
        val validator: com.google.common.base.Function<String?, String?>?

        init {
            this.validator = validator
            this.detectionPattern = detectionPattern
            this.userFriendlyName = userFriendlyName
            String > java.util.Objects.requireNonNull<String?>(userFriendlyName, "userFriendlyName")
            Pattern > java.util.Objects.requireNonNull<java.util.regex.Pattern?>(detectionPattern, "detectionPattern")
            java.util.Objects.requireNonNull<com.google.common.base.Function<String?, String?>?>(validator, "validator")
        }

        companion object {
            /**
             * Create a new parseable execution requirement definition.
             * 
             * 
             * If a tag doesn't match the detectionPattern, it will be ignored. If a tag matches the
             * detectionPattern, but not the validationPattern, it is assumed that the value is somehow
             * wrong (e.g. the user put a float or random string where we expected an integer).
             * 
             * @param userFriendlyName a human readable name of the tag and its format, e.g. "cpu:<int>"
             * @param detectionPattern a regex that will be used to detect whether a tag matches this
             * execution requirement. It should have one capture group that grabs the value of the tag.
             * This should be general enough to permit even wrong value types. Example: "cpu:(.+)".
             * @param validator a Function that will be used to validate the value of the tag. It should
             * return null if the value is fine to use or a human-friendly error message describing why
             * the value is not valid.
            </int> */
            fun create(
                userFriendlyName: String?,
                detectionPattern: java.util.regex.Pattern?,
                validator: com.google.common.base.Function<String?, String?>?
            ): ParseableRequirement {
                return ParseableRequirement(userFriendlyName, detectionPattern, validator)
            }
        }
    }

    /** Denotes what the type of worker protocol the worker uses.  */
    enum class WorkerProtocolFormat {
        JSON,
        PROTO,
    }
}
