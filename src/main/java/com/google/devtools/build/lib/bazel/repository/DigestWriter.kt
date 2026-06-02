// Copyright 2025 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Handles writing and reading of repo marker files.  */
class DigestWriter private constructor(
    directories: BlazeDirectories,
    repositoryName: RepositoryName,
    predeclaredInputHash: String?
) {
    private val directories: BlazeDirectories?
    val predeclaredInputHash: String?
    val markerPath: com.google.devtools.build.lib.vfs.Path

    init {
        this.directories = directories
        this.predeclaredInputHash = predeclaredInputHash
        this.markerPath = getMarkerPath(directories, repositoryName)
    }

    @Throws(RepositoryFunctionException::class)
    fun writeMarkerFile(recordedInputValues: MutableList<WithValue?>) {
        val builder: java.lang.StringBuilder = java.lang.StringBuilder()
        builder.append(predeclaredInputHash).append('\n')
        for (recordedInputValue in recordedInputValues) {
            builder.append(recordedInputValue).append('\n')
        }
        val content = builder.toString()
        try {
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
                markerPath,
                java.nio.charset.StandardCharsets.ISO_8859_1,
                content
            )
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    /**
     * Checks if the state of the repo in the filesystem is consistent with its current definition.
     * Returns [Optional.empty] if they are consistent; otherwise, returns a description of
     * why they are not.
     * 
     * 
     * This method treats a missing Skyframe dependency as if the repo is not up to date. The
     * caller is responsible for checking `env.valuesMissing()`.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(RepositoryFunctionException::class, java.lang.InterruptedException::class)
    fun areRepositoryAndMarkerFileConsistent(
        env: SkyFunction.Environment?,
        markerPath: com.google.devtools.build.lib.vfs.Path = this.markerPath
    ): java.util.Optional<String?> {
        if (!markerPath.exists()) {
            return java.util.Optional.of<String?>("repo hasn't been fetched yet")
        }

        try {
            val content: String = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
                markerPath,
                java.nio.charset.StandardCharsets.ISO_8859_1
            )
            val recordedInputValues: java.util.Optional<com.google.common.collect.ImmutableList<WithValue?>?> =
                readMarkerFile(
                    content,
                    com.google.common.base.Preconditions.checkNotNull<String?>(predeclaredInputHash)
                )
            if (recordedInputValues.isEmpty()) {
                return java.util.Optional.of<String?>("Bazel version, flags, repo rule definition or attributes changed")
            }
            // Check inputs in batches to prevent Skyframe cycles caused by outdated dependencies.
            for (batch in RepoRecordedInput.WithValue.splitIntoBatches(recordedInputValues.get())) {
                val outdatedReason: java.util.Optional<String?> =
                    RepoRecordedInput.isAnyValueOutdated(env, directories, batch)
                if (outdatedReason.isPresent()) {
                    return outdatedReason
                }
            }
            return java.util.Optional.empty<String?>()
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    companion object {
        // The marker file version is inject in the rule key digest so the rule key is always different
        // when we decide to update the format.
        private const val MARKER_FILE_VERSION = 8

        /** Returns null if and only if a Skyframe restart is needed.  */
        @Throws(java.lang.InterruptedException::class)
        fun create(
            env: SkyFunction.Environment,
            directories: BlazeDirectories,
            repositoryName: RepositoryName,
            repoDefinition: RepoDefinition,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
        ): DigestWriter? {
            val predeclaredInputHash =
                computePredeclaredInputHash(env, repoDefinition, starlarkSemantics)
            if (predeclaredInputHash == null) {
                return null
            }
            return DigestWriter(directories, repositoryName, predeclaredInputHash)
        }

        /**
         * Returns a list of recorded inputs with their values parsed from the given marker file if the
         * predeclared input hash matches, or `Optional.empty()` if the hash doesn't match or any
         * error occurs during parsing.
         */
        @kotlin.jvm.JvmStatic
        fun readMarkerFile(
            content: String, predeclaredInputHash: String?
        ): java.util.Optional<com.google.common.collect.ImmutableList<WithValue?>?> {
            val lines: Iterable<String> = com.google.common.base.Splitter.on('\n').split(content)

            val recordedInputValues: com.google.common.collect.ImmutableList.Builder<WithValue?> =
                com.google.common.collect.ImmutableList.builder<WithValue?>()
            var firstLineVerified = false
            for (line in lines) {
                if (line.isEmpty()) {
                    continue
                }
                if (!firstLineVerified) {
                    if (line != predeclaredInputHash) {
                        // Break early, need to reload anyway. This also detects marker file version changes
                        // so that unknown formats are not parsed.
                        return java.util.Optional.empty<com.google.common.collect.ImmutableList<WithValue?>?>()
                    }
                    firstLineVerified = true
                } else {
                    val inputAndValue: java.util.Optional<WithValue?> = RepoRecordedInput.WithValue.parse(line)
                    if (inputAndValue.isEmpty()) {
                        // On parse failure, just forget everything else and mark the whole input out of date.
                        return java.util.Optional.empty<com.google.common.collect.ImmutableList<WithValue?>?>()
                    }
                    recordedInputValues.add(inputAndValue.get())
                }
            }
            if (!firstLineVerified) {
                return java.util.Optional.empty<com.google.common.collect.ImmutableList<WithValue?>?>()
            }
            return java.util.Optional.of<com.google.common.collect.ImmutableList<WithValue?>?>(recordedInputValues.build())
        }

        @Throws(java.lang.InterruptedException::class)
        fun computePredeclaredInputHash(
            env: SkyFunction.Environment,
            repoDefinition: RepoDefinition,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
        ): String? {
            val environ: com.google.common.collect.ImmutableSortedMap<String?, java.util.Optional<String?>?>? =
                RepoEnvironmentFunction.getEnvironmentView(env, repoDefinition.repoRule.environ)
            if (environ == null) {
                return null
            }
            val environInputs: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.rules.repository.RepoRecordedInput.EnvVar?, java.util.Optional<String?>?> =
                RepoRecordedInput.EnvVar.wrap(environ)
            val fp: Fingerprint =
                Fingerprint()
                    .addInt(MARKER_FILE_VERSION)
                    .addBytes(BuildLanguageOptions.stableFingerprint(starlarkSemantics))
                    .addString(repoDefinition.repoRule.id.bzlFileLabel().toString())
                    .addString(repoDefinition.repoRule.id.ruleName())
                    .addBytes(repoDefinition.repoRule.transitiveBzlDigest)
                    .addString(repoDefinition.name)
                    .addString(
                        GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON.toJson(
                            repoDefinition.attrValues
                        )
                    ) // This info is accessible via rctx.os.{name,arch} and can also influence the
                    // result of a repo rule in subtle ways (e.g. behavior of host tools, line breaks,
                    // etc).
                    .addString(com.google.common.base.StandardSystemProperty.OS_NAME.value().toLowerCase(Locale.ROOT))
                    .addString(java.lang.System.getProperty("os.arch").toLowerCase(Locale.ROOT))
            fp.addInt(environInputs.size())
            environInputs.forEach(
                java.util.function.BiConsumer { key: com.google.devtools.build.lib.rules.repository.RepoRecordedInput.EnvVar?, value: java.util.Optional<kotlin.String?>? ->
                    fp.addString(
                        key.toString()
                    ).addNullableString(value.orElse(null))
                })
            fp.addInt(repoDefinition.repoRule.recordedRepoMappingEntries.cellSet().size())
            repoDefinition
                .repoRule
                .recordedRepoMappingEntries
                .cellSet()
                .forEach(
                    java.util.function.Consumer { entry: com.google.common.collect.Table.Cell<RepositoryName?, kotlin.String?, RepositoryName?>? ->
                        fp.addString(entry.getRowKey().getName())
                        fp.addString(entry.getColumnKey())
                        fp.addString(entry.getValue().getName())
                    })
            return fp.hexDigestAndReset()
        }

        private fun getMarkerPath(
            directories: BlazeDirectories,
            repo: RepositoryName
        ): com.google.devtools.build.lib.vfs.Path {
            return RepositoryUtils.getExternalRepositoryDirectory(directories)
                .getChild(repo.getMarkerFileName())
        }

        @Throws(RepositoryFunctionException::class)
        fun clearMarkerFile(directories: BlazeDirectories, repo: RepositoryName) {
            try {
                getMarkerPath(directories, repo).delete()
            } catch (e: IOException) {
                throw RepositoryFunctionException(e, Transience.TRANSIENT)
            }
        }
    }
}
