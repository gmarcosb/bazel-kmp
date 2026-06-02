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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.actions.FileValue

/**
 * Reads the contents of the lockfiles into [BazelLockFileValue]s.
 * 
 * 
 * See [BazelLockFileValue] for more information.
 */
class BazelLockFileFunction(
    rootDirectory: com.google.devtools.build.lib.vfs.Path?,
    outputBase: com.google.devtools.build.lib.vfs.Path?
) : SkyFunction {
    private val rootDirectory: com.google.devtools.build.lib.vfs.Path?
    private val outputBase: com.google.devtools.build.lib.vfs.Path?

    init {
        this.rootDirectory = rootDirectory
        this.outputBase = outputBase
    }

    @Throws(BazelLockfileFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val forHiddenLockfile = skyKey === BazelLockFileValue.Companion.HIDDEN_KEY
        val lockfilePath: RootedPath =
            RootedPath.toRootedPath(
                Root.fromPath(if (forHiddenLockfile) outputBase else rootDirectory),
                LabelConstants.MODULE_LOCKFILE_NAME
            )

        // Add dependency on the lockfile to recognize changes to it
        if (env.getValue(FileValue.key(lockfilePath)) == null) {
            return null
        }

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(
                    com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                    if (forHiddenLockfile) "parse hidden lockfile" else "parse lockfile"
                ).use { c ->
                    return getLockfileValue(
                        lockfilePath, if (forHiddenLockfile) LockfileMode.UPDATE else LOCKFILE_MODE.get(env)
                    )
                }
        } catch (e: IOException) {
            if (forHiddenLockfile) {
                return BazelLockFileValue.Companion.EMPTY_LOCKFILE
            }
            val actionSuffix: String?
            if (e.getMessage() != null
                && POSSIBLE_MERGE_CONFLICT_PATTERN.matcher(e.getMessage()).find()
            ) {
                actionSuffix =
                    (" This looks like a merge conflict. See"
                            + " https://bazel.build/external/lockfile#merge-conflicts for advice.")
            } else {
                actionSuffix = " Try deleting it and rerun the build."
            }
            throw BazelLockfileFunctionException(
                ExternalDepsException.Companion.withMessage(
                    Code.BAD_LOCKFILE,
                    "Failed to read and parse the MODULE.bazel.lock file with error: %s.%s",
                    e.getMessage(),
                    actionSuffix
                ),
                Transience.PERSISTENT
            )
        } catch (e: JsonSyntaxException) {
            if (forHiddenLockfile) {
                return BazelLockFileValue.Companion.EMPTY_LOCKFILE
            }
            val actionSuffix: String?
            if (e.getMessage() != null
                && POSSIBLE_MERGE_CONFLICT_PATTERN.matcher(e.getMessage()).find()
            ) {
                actionSuffix =
                    (" This looks like a merge conflict. See"
                            + " https://bazel.build/external/lockfile#merge-conflicts for advice.")
            } else {
                actionSuffix = " Try deleting it and rerun the build."
            }
            throw BazelLockfileFunctionException(
                ExternalDepsException.Companion.withMessage(
                    Code.BAD_LOCKFILE,
                    "Failed to read and parse the MODULE.bazel.lock file with error: %s.%s",
                    e.getMessage(),
                    actionSuffix
                ),
                Transience.PERSISTENT
            )
        } catch (e: java.lang.NullPointerException) {
            if (forHiddenLockfile) {
                return BazelLockFileValue.Companion.EMPTY_LOCKFILE
            }
            val actionSuffix: String?
            if (e.getMessage() != null
                && POSSIBLE_MERGE_CONFLICT_PATTERN.matcher(e.getMessage()).find()
            ) {
                actionSuffix =
                    (" This looks like a merge conflict. See"
                            + " https://bazel.build/external/lockfile#merge-conflicts for advice.")
            } else {
                actionSuffix = " Try deleting it and rerun the build."
            }
            throw BazelLockfileFunctionException(
                ExternalDepsException.Companion.withMessage(
                    Code.BAD_LOCKFILE,
                    "Failed to read and parse the MODULE.bazel.lock file with error: %s.%s",
                    e.getMessage(),
                    actionSuffix
                ),
                Transience.PERSISTENT
            )
        } catch (e: java.lang.IllegalArgumentException) {
            if (forHiddenLockfile) {
                return BazelLockFileValue.Companion.EMPTY_LOCKFILE
            }
            val actionSuffix: String?
            if (e.getMessage() != null
                && POSSIBLE_MERGE_CONFLICT_PATTERN.matcher(e.getMessage()).find()
            ) {
                actionSuffix =
                    (" This looks like a merge conflict. See"
                            + " https://bazel.build/external/lockfile#merge-conflicts for advice.")
            } else {
                actionSuffix = " Try deleting it and rerun the build."
            }
            throw BazelLockfileFunctionException(
                ExternalDepsException.Companion.withMessage(
                    Code.BAD_LOCKFILE,
                    "Failed to read and parse the MODULE.bazel.lock file with error: %s.%s",
                    e.getMessage(),
                    actionSuffix
                ),
                Transience.PERSISTENT
            )
        }
    }

    internal class BazelLockfileFunctionException(cause: ExternalDepsException?, transience: Transience?) :
        SkyFunctionException(cause, transience)

    companion object {
        @kotlin.jvm.JvmField
        val LOCKFILE_MODE: Precomputed<LockfileMode?> = Precomputed<LockfileMode?>("lockfile_mode")

        private val LOCKFILE_VERSION_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("\"lockFileVersion\":\\s*(\\d+)")

        private val POSSIBLE_MERGE_CONFLICT_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("<<<<<<<|=======|" + java.util.regex.Pattern.quote("|||||||") + "|>>>>>>>")

        @Throws(IOException::class, BazelLockfileFunctionException::class)
        fun getLockfileValue(
            lockfilePath: RootedPath, lockfileMode: LockfileMode?
        ): BazelLockFileValue? {
            try {
                val json: String = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
                    lockfilePath.asPath(),
                    java.nio.charset.StandardCharsets.UTF_8
                )
                val matcher: java.util.regex.Matcher = LOCKFILE_VERSION_PATTERN.matcher(json)
                val version = if (matcher.find()) java.lang.Integer.parseInt(matcher.group(1)) else -1
                if (version == BazelLockFileValue.Companion.LOCK_FILE_VERSION) {
                    return GsonTypeAdapterUtil.LOCKFILE_GSON.fromJson<BazelLockFileValue?>(
                        json,
                        BazelLockFileValue::class.java
                    )
                } else {
                    // This is an old version, its information can't be used.
                    if (lockfileMode == LockfileMode.ERROR) {
                        throw BazelLockfileFunctionException(
                            ExternalDepsException.Companion.withMessage(
                                Code.BAD_LOCKFILE,
                                ("The version of MODULE.bazel.lock is not supported by this version of Bazel."
                                        + " Please run `bazel mod deps --lockfile_mode=update` to update your"
                                        + " lockfile.")
                            ),
                            Transience.PERSISTENT
                        )
                    }
                    return BazelLockFileValue.Companion.EMPTY_LOCKFILE
                }
            } catch (e: FileNotFoundException) {
                return BazelLockFileValue.Companion.EMPTY_LOCKFILE
            }
        }
    }
}
