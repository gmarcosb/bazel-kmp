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
package com.google.devtools.build.lib.analysis.config


import com.google.devtools.build.lib.actions.ArtifactRoot

/**
 * Logic for figuring out what base directories to place outputs generated from a given
 * configuration.
 * 
 * 
 * In other words, when your output ends up in `blaze-out/x86-fastbuild/...`, this
 * class is why.
 */
class OutputDirectories internal constructor(
    directories: BlazeDirectories,
    options: CoreOptions,
    platformOptions: PlatformOptions?,
    mnemonic: String?,
    workspaceName: String?,
    siblingRepositoryLayout: Boolean
) {
    /**
     * Directories in the output tree.
     * 
     * 
     * The computation of the output directory should be a non-injective mapping from
     * BuildConfigurationValue instances to strings. The result should identify the aspects of the
     * configuration that should be reflected in the output file names. Furthermore the returned
     * string must not contain shell metacharacters.
     * 
     * 
     * For configuration settings which are NOT part of the output directory name, rebuilding with
     * a different value of such a setting will build in the same output directory. This means that
     * any actions whose keys (see Action.getKey()) have changed will be rerun. That may result in a
     * lot of recompilation.
     * 
     * 
     * For configuration settings which ARE part of the output directory name, rebuilding with a
     * different value of such a setting will rebuild in a different output directory; this will
     * result in higher disk usage and more work the *first* time you rebuild with a different
     * setting, but will result in less work if you regularly switch back and forth between different
     * settings.
     * 
     * 
     * With one important exception, it's sound to choose any subset of the config's components for
     * this string, it just alters the dimensionality of the cache. In other words, it's a trade-off
     * on the "injectiveness" scale: at one extreme (output directory name contains all data in the
     * config, and is thus injective) you get extremely precise caching (no competition for the same
     * output-file locations) but you have to rebuild for even the slightest change in configuration.
     * At the other extreme (the output (directory name is a constant) you have very high competition
     * for output-file locations, but if a slight change in configuration doesn't affect a particular
     * build step, you're guaranteed not to have to rebuild it. The important exception has to do with
     * multiple configurations: every configuration in the build must have a different output
     * directory name so that their artifacts do not conflict.
     */
    enum class OutputDirectory(name: String) {
        BIN("bin"),
        GENFILES("genfiles"),
        TESTLOGS("testlogs"),
        OUTPUT("");

        private val name: String

        init {
            // Must be a legal basename for root - multiple segments not allowed.
            if (!name.isEmpty()) {
                FileSystemUtils.checkBaseName(name)
            }
            this.name = name
        }

        fun getRoot(
            outputDirName: String?, directories: BlazeDirectories, workspaceName: String?
        ): ArtifactRoot {
            // e.g., execroot/my_workspace
            val execRoot: Path? = directories.getExecRoot(workspaceName)
            // e.g., [[execroot/my_workspace]/bazel-out/config/bin]
            return ArtifactRoot.asDerivedRoot(
                execRoot, RootType.OUTPUT, directories.getRelativeOutputPath(), outputDirName, name
            )
        }
    }

    private val directories: BlazeDirectories
    private val mnemonic: String?

    private val outputDirectory: ArtifactRoot
    private val binDirectory: ArtifactRoot
    private val genfilesDirectory: ArtifactRoot
    private val testlogsDirectory: ArtifactRoot

    private val mergeGenfilesDirectory: Boolean

    private val siblingRepositoryLayout: Boolean

    private val execRoot: Path?

    init {
        this.directories = directories
        this.mnemonic = mnemonic

        this.outputDirectory = OutputDirectory.OUTPUT.getRoot(mnemonic, directories, workspaceName)
        this.binDirectory = OutputDirectory.BIN.getRoot(mnemonic, directories, workspaceName)
        this.genfilesDirectory = OutputDirectory.GENFILES.getRoot(mnemonic, directories, workspaceName)
        this.testlogsDirectory = OutputDirectory.TESTLOGS.getRoot(mnemonic, directories, workspaceName)

        this.mergeGenfilesDirectory = options.getMergeGenfilesDirectory()
        this.siblingRepositoryLayout = siblingRepositoryLayout
        this.execRoot = directories.getExecRoot(workspaceName)
    }

    private fun buildDerivedRoot(nameFragment: String?, repository: RepositoryName): ArtifactRoot {
        return ArtifactRoot.asDerivedRoot(
            execRoot,  // e.g., execroot/mainRepoName/bazel-out/[repoName/]config/bin
            // TODO(jungjw): Ideally, we would like to do execroot_base/repoName/bazel-out/config/bin
            // instead. However, it requires individually symlinking the top-level elements of external
            // repositories, which is blocked by a Windows symlink issue #8704.
            if (repository.isMain()) RootType.SIBLING_MAIN_OUTPUT else RootType.SIBLING_EXTERNAL_OUTPUT,
            directories.getRelativeOutputPath(),
            repository.getName(),
            mnemonic,
            nameFragment
        )
    }

    /** Returns the output directory for this build configuration.  */
    fun getOutputDirectory(repositoryName: RepositoryName): ArtifactRoot? {
        return if (siblingRepositoryLayout) buildDerivedRoot("", repositoryName) else outputDirectory
    }

    /** Returns the bin directory for this build configuration.  */
    fun getBinDirectory(repositoryName: RepositoryName): ArtifactRoot? {
        return if (siblingRepositoryLayout) buildDerivedRoot("bin", repositoryName) else binDirectory
    }

    /** Returns the genfiles directory for this build configuration.  */
    fun getGenfilesDirectory(repositoryName: RepositoryName): ArtifactRoot? {
        return if (mergeGenfilesDirectory)
            getBinDirectory(repositoryName)
        else
            if (siblingRepositoryLayout)
                buildDerivedRoot("genfiles", repositoryName)
            else
                genfilesDirectory
    }

    /** Returns the testlogs directory for this build configuration.  */
    fun getTestLogsDirectory(repositoryName: RepositoryName): ArtifactRoot? {
        return if (siblingRepositoryLayout)
            buildDerivedRoot("testlogs", repositoryName)
        else
            testlogsDirectory
    }

    /** Returns a relative path to the genfiles directory at execution time.  */
    fun getGenfilesFragment(repositoryName: RepositoryName): PathFragment {
        return getGenfilesDirectory(repositoryName).getExecPath()
    }

    /**
     * Returns the path separator for the host platform. This is basically the same as [ ][java.io.File.pathSeparator], except that that returns the value for this JVM, which may or may
     * not match the host platform. You should only use this when invoking tools that are known to use
     * the native path separator, i.e., the path separator for the machine that they run on.
     */
    fun getHostPathSeparator(): String {
        // TODO(bazel-team): Maybe do this in the constructor instead? This isn't serialization-safe.
        return if (OS.getCurrent() === OS.WINDOWS) ";" else ":"
    }

    fun getMnemonic(): String? {
        return mnemonic
    }

    fun getOutputDirName(): String? {
        return getMnemonic()
    }

    fun mergeGenfilesDirectory(): Boolean {
        return mergeGenfilesDirectory
    }

    fun getDirectories(): BlazeDirectories {
        return directories
    }
}
