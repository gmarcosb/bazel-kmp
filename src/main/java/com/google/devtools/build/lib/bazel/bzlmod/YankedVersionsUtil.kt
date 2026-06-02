// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.server.FailureDetails

/** Utility class to parse and evaluate yanked version specifications and exceptions.  */
object YankedVersionsUtil {
    @kotlin.jvm.JvmField
    val ALLOWED_YANKED_VERSIONS: Precomputed<MutableList<String?>?> =
        Precomputed<MutableList<String?>?>("allowed_yanked_versions")
    const val BZLMOD_ALLOWED_YANKED_VERSIONS_ENV: String = "BZLMOD_ALLOW_YANKED_VERSIONS"

    /**
     * Parse a set of allowed yanked version from command line flag (--allowed_yanked_versions) and
     * environment variable (ALLOWED_YANKED_VERSIONS). If `all` is specified, return Optional.empty();
     * otherwise returns the set of parsed modulel key.
     */
    @Throws(ExternalDepsException::class)
    fun parseAllowedYankedVersions(
        allowedYankedVersionsFromEnv: String?, allowedYankedVersionsFromFlag: MutableList<String>
    ): java.util.Optional<com.google.common.collect.ImmutableSet<ModuleKey?>?> {
        val allowedYankedVersionBuilder: com.google.common.collect.ImmutableSet.Builder<ModuleKey?> =
            com.google.common.collect.ImmutableSet.Builder<ModuleKey?>()
        if (allowedYankedVersionsFromEnv != null) {
            if (parseModuleKeysFromString(
                    allowedYankedVersionsFromEnv,
                    allowedYankedVersionBuilder,
                    java.lang.String.format(
                        "environment variable %s=%s",
                        BZLMOD_ALLOWED_YANKED_VERSIONS_ENV, allowedYankedVersionsFromEnv
                    )
                )
            ) {
                return java.util.Optional.empty<com.google.common.collect.ImmutableSet<ModuleKey?>?>()
            }
        }
        for (allowedYankedVersions in allowedYankedVersionsFromFlag) {
            if (parseModuleKeysFromString(
                    allowedYankedVersions,
                    allowedYankedVersionBuilder,
                    java.lang.String.format("command line flag --allow_yanked_versions=%s", allowedYankedVersions)
                )
            ) {
                return java.util.Optional.empty<com.google.common.collect.ImmutableSet<ModuleKey?>?>()
            }
        }
        return java.util.Optional.of<com.google.common.collect.ImmutableSet<ModuleKey?>?>(allowedYankedVersionBuilder.build())
    }

    /**
     * Parse of a comma-separated list of module version(s) of the form '<module name>@<version>' or
     * 'all' from the string. Returns true if 'all' is present, otherwise returns false.
    </version></module> */
    @Throws(ExternalDepsException::class)
    private fun parseModuleKeysFromString(
        input: String,
        allowedYankedVersionBuilder: com.google.common.collect.ImmutableSet.Builder<ModuleKey?>,
        context: String?
    ): Boolean {
        val moduleStrs: com.google.common.collect.ImmutableList<String> =
            com.google.common.collect.ImmutableList.copyOf<String?>(
                com.google.common.base.Splitter.on(',').split(input)
            )

        for (moduleStr in moduleStrs) {
            if (moduleStr == "all") {
                return true
            }

            if (moduleStr.isEmpty()) {
                continue
            }

            val pieces: Array<String?> = moduleStr.split("@", 2)

            if (pieces.length != 2) {
                throw withMessage(
                    FailureDetails.ExternalDeps.Code.VERSION_RESOLUTION_ERROR,
                    "Parsing %s failed, module versions must be of the form '<module name>@<version>'",
                    context
                )
            }

            if (!RepositoryName.VALID_MODULE_NAME.matcher(pieces[0]).matches()) {
                throw ExternalDepsException.Companion.withMessage(
                    FailureDetails.ExternalDeps.Code.VERSION_RESOLUTION_ERROR,
                    ("Parsing %s failed, invalid module name '%s': valid names must 1) only contain"
                            + " lowercase letters (a-z), digits (0-9), dots (.), hyphens (-), and"
                            + " underscores (_); 2) begin with a lowercase letter; 3) end with a lowercase"
                            + " letter or digit."),
                    context,
                    pieces[0]
                )
            }

            val version: com.google.devtools.build.lib.bazel.bzlmod.Version?
            try {
                version = com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(pieces[1])
            } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
                throw ExternalDepsException.Companion.withCauseAndMessage(
                    FailureDetails.ExternalDeps.Code.VERSION_RESOLUTION_ERROR,
                    e,
                    "Parsing %s failed, invalid version specified for module: %s",
                    context,
                    pieces[1]
                )
            }

            allowedYankedVersionBuilder.add(ModuleKey(pieces[0], version))
        }
        return false
    }
}
