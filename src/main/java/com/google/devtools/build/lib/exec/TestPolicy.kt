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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.analysis.test.TestRunnerAction

/**
 * A policy for running tests. It currently only encompasses the environment computation for the
 * test.
 */
class TestPolicy(envVariables: com.google.common.collect.ImmutableMap<String?, String?>) {
    private val envVariables: com.google.common.collect.ImmutableMap<String?, String?>

    /**
     * Creates a new instance. The map's keys are the names of the environment variables, while the
     * values can be either fixed values, or one of the constants in this class, specifically [ ][.SYSTEM_USER_NAME], [.TEST_TMP_DIR], [.RUNFILES_DIR], or [.INHERITED].
     */
    init {
        this.envVariables = envVariables
    }

    /**
     * Returns a mutable map of the environment variables for a specific test. This is intended to be
     * the final, complete environment - callers should avoid relying on the mutability of the return
     * value, and instead change the policy itself.
     */
    fun computeTestEnvironment(
        testAction: TestRunnerAction,
        clientEnv: MutableMap<String?, String>,
        relativeRunfilesDir: PathFragment,
        tmpDir: PathFragment
    ): MutableMap<String?, String?> {
        val env: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()

        // Add all env variables, allow some string replacements and inheritance.
        val userProp: String = UserUtils.getUserName()
        val tmpDirPath: String = tmpDir.getPathString()
        val runfilesDirPath: String = relativeRunfilesDir.getPathString()
        for (entry in envVariables.entrySet()) {
            var `val`: String = entry.getValue()
            if (`val`.contains("\${")) {
                if (`val` == INHERITED) {
                    if (!clientEnv.containsKey(entry.getKey())) {
                        continue
                    }
                    `val` = clientEnv.get(entry.getKey())!!
                } else {
                    `val` = `val`.replace(SYSTEM_USER_NAME, userProp)
                    `val` = `val`.replace(TEST_TMP_DIR, tmpDirPath)
                    `val` = `val`.replace(RUNFILES_DIR, runfilesDirPath)
                }
            }
            env.put(entry.getKey(), `val`)
        }

        // Overwrite with the environment common to all actions, see --action_env.
        testAction.getConfiguration().getActionEnvironment().resolve(env, clientEnv)

        // Overwrite with the environment common to all tests, see --test_env.
        testAction.getConfiguration().getTestActionEnvironment().resolve(env, clientEnv)

        // Rule-specified test env.
        testAction.getExtraTestEnv().resolve(env, clientEnv)

        // Setup bazel test-specific env variables; note that this does not overwrite
        // some values if they're already set.
        testAction.setupEnvVariables(env)

        return env
    }

    companion object {
        /**
         * The user name of the user running Bazel; this may differ from ${USER} for tests that are run
         * remotely.
         */
        const val SYSTEM_USER_NAME: String = "\${SYSTEM_USER_NAME}"

        /** An absolute path to a writable directory that is reserved for the current test.  */
        const val TEST_TMP_DIR: String = "\${TEST_TMP_DIR}"

        /** The path of the runfiles directory.  */
        const val RUNFILES_DIR: String = "\${RUNFILES_DIR}"

        const val INHERITED: String = "\${inherited}"

        @kotlin.jvm.JvmField
        val EMPTY_POLICY: TestPolicy = TestPolicy(com.google.common.collect.ImmutableMap.of<String?, String?>())
    }
}
