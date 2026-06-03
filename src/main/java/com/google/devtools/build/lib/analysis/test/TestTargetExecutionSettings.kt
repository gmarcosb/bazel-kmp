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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost

/**
 * Container for common test execution settings shared by all TestRunnerAction instances for the
 * given test target.
 */
class TestTargetExecutionSettings internal constructor(
    ruleContext: RuleContext,
    runfilesSupport: RunfilesSupport,
    executable: Artifact?,
    instrumentedFileManifest: Artifact?,
    shards: Int,
    runs: Int,
    executionPlatform: PlatformInfo?
) {
    private val testArguments: CommandLine?
    private val testFilter: String?
    private val totalShards: Int
    private val totalRuns: Int
    private val runUnder: RunUnder?
    private val runUnderExecutable: Artifact?
    private val executable: Artifact?
    private val runfilesSymlinksCreated: Boolean
    private val runfilesDir: Path?
    private val runfiles: com.google.devtools.build.lib.analysis.Runfiles?
    private val runfilesInputManifest: Artifact?
    private val instrumentedFileManifest: Artifact?
    private val testRunnerFailFast: Boolean
    private val executionOs: OS

    init {
        com.google.common.base.Preconditions.checkArgument(TargetUtils.isTestRule(ruleContext.getRule()))
        com.google.common.base.Preconditions.checkArgument(shards >= 0)
        val config: BuildConfigurationValue? = ruleContext.getConfiguration()
        val testConfig: TestConfiguration = config.getFragment<T>(TestConfiguration::class.java)

        val targetArgs: CommandLine? = runfilesSupport.getArgs()
        testArguments =
            CommandLine.concat(
                targetArgs,
                com.google.common.collect.ImmutableList.copyOf(testConfig.getTestArguments())
            )

        totalShards = shards
        totalRuns = runs
        runUnder = config.getRunUnder()
        runUnderExecutable = getRunUnderExecutable(ruleContext)

        this.testFilter = testConfig.getTestFilter()
        this.testRunnerFailFast = testConfig.getTestRunnerFailFast()
        this.executable = executable
        this.runfilesSymlinksCreated = runfilesSupport.getRunfilesTree().isBuildRunfileLinks()
        this.runfilesDir = runfilesSupport.getRunfilesDirectory()
        this.runfiles = runfilesSupport.getRunfiles()
        this.runfilesInputManifest = runfilesSupport.getRunfilesInputManifest()
        this.instrumentedFileManifest = instrumentedFileManifest
        this.executionOs = getOsFromConstraintsOrHost(executionPlatform)
    }

    fun getRunUnderExecutable(): Artifact? {
        return runUnderExecutable
    }

    fun getArgs(): CommandLine? {
        return testArguments
    }

    fun getTestFilter(): String? {
        return testFilter
    }

    fun getTestRunnerFailFast(): Boolean {
        return testRunnerFailFast
    }

    fun getTotalShards(): Int {
        return totalShards
    }

    fun getTotalRuns(): Int {
        return totalRuns
    }

    fun getRunUnder(): RunUnder? {
        return runUnder
    }

    fun getExecutable(): Artifact? {
        return executable
    }

    /** Returns whether or not the runfiles symlinks were created.  */
    fun getRunfilesSymlinksCreated(): Boolean {
        return runfilesSymlinksCreated
    }

    /** Returns the directory of the runfiles.  */
    fun getRunfilesDir(): Path? {
        return runfilesDir
    }

    /** Returns the runfiles for the test.  */
    fun getRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
        return runfiles
    }

    /**
     * Returns the input runfiles manifest for this test.
     * 
     * 
     * This always returns the input manifest outside of the runfiles tree.
     * 
     * @see com.google.devtools.build.lib.analysis.RunfilesSupport.getRunfilesInputManifest
     */
    fun getInputManifest(): Artifact? {
        return runfilesInputManifest
    }

    /** Returns instrumented file manifest or null if code coverage is not collected.  */
    fun getInstrumentedFileManifest(): Artifact? {
        return instrumentedFileManifest
    }

    fun getExecutionOs(): OS {
        return executionOs
    }

    fun needsShell(): Boolean {
        if (getRunUnder() is CommandRunUnder) {
            val command: String = commandRunUnder.command()
            // --run_under commands that do not contain '/' are either shell built-ins or need to be
            // located on the PATH env, so we wrap them in a shell invocation. Note that we
            // shell-tokenize
            // the --run_under parameter and getCommand only returns the first such token.
            return !command.contains("/") && (!executionOs.equals(OS.WINDOWS) || !command.contains("\\"))
        } else {
            return false
        }
    }

    companion object {
        private fun getRunUnderExecutable(ruleContext: RuleContext): Artifact? {
            val runUnderTarget: TransitiveInfoCollection? = ruleContext.getRunUnderPrerequisite()
            return if (runUnderTarget == null)
                null
            else
                runUnderTarget.getProvider(FilesToRunProvider::class.java).getExecutable()
        }
    }
}
