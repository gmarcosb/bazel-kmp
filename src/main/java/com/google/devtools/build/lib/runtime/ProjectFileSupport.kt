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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.pkgcache.PackageOptions

/**
 * Provides support for implementations for [ ] to work with [ProjectFile].
 */
object ProjectFileSupport {
    const val PROJECT_FILE_PREFIX: String = "+"

    /**
     * Reads any project files specified on the command line and updates the options parser
     * accordingly. If project files cannot be read or if they contain unparsable options, or if they
     * are not enabled, then it throws an exception instead.
     */
    @Throws(
        com.google.devtools.common.options.OptionsParsingException::class,
        java.lang.InterruptedException::class,
        AbruptExitException::class
    )
    fun handleProjectFiles(
        eventHandler: ExtendedEventHandler,
        projectFileProvider: com.google.devtools.build.lib.runtime.ProjectFile.Provider?,
        workspaceDir: PathFragment?,
        workingDir: com.google.devtools.build.lib.vfs.Path?,
        optionsParser: com.google.devtools.common.options.OptionsParser,
        command: String?
    ) {
        val targets: MutableList<String?> = optionsParser.getResidue()
        if (projectFileProvider != null && !targets.isEmpty() && targets.get(0).startsWith(PROJECT_FILE_PREFIX)) {
            if (targets.size() > 1) {
                throw com.google.devtools.common.options.OptionsParsingException("Cannot handle more than one +<file> argument yet")
            }
            if (!optionsParser.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
                    .getAllowProjectFiles()
            ) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "project file support is not enabled. "
                            + "Pass --experimental_allow_project_files to enable."
                )
            }
            // TODO(bazel-team): This is currently treated as a path relative to the workspace - if the
            // cwd is a subdirectory of the workspace, that will be surprising, and we should interpret it
            // relative to the cwd instead.
            val projectFilePath: PathFragment? = PathFragment.create(targets.get(0).substring(1))
            val packagePath: MutableList<Root?>? =
                PathPackageLocator.create( // We only need a non-null outputBase for the PathPackageLocator if we support
                    // external
                    // repositories, which we don't for project files.
                    /* outputBase= */
                    null,
                    optionsParser.getOptions<O?>(PackageOptions::class.java).getPackagePath(),
                    eventHandler,
                    workspaceDir,
                    workingDir,
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
                    .getPathEntries()
            val projectFile: ProjectFile =
                projectFileProvider.getProjectFile(
                    workingDir, packagePath, projectFilePath, optionsParser
                )
            eventHandler.handle(com.google.devtools.build.lib.events.Event.info("Using " + projectFile.getName()))

            optionsParser.parse(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.RC_FILE,
                projectFile.getName(),
                projectFile.getCommandLineFor(command, eventHandler)
            )
            eventHandler.post(GotProjectFileEvent(projectFile.getName()))
        }
    }

    /**
     * Returns a list of targets from the options residue. If a project file is supplied as the first
     * argument, it will be ignored, on the assumption that handleProjectFiles() has been called to
     * process it.
     */
    fun getTargets(
        projectFileProvider: com.google.devtools.build.lib.runtime.ProjectFile.Provider?,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): MutableList<String?>? {
        val targets: MutableList<String?> = options.getResidue()
        if (projectFileProvider != null && !targets.isEmpty() && targets.get(0).startsWith(PROJECT_FILE_PREFIX)) {
            return targets.subList(1, targets.size())
        }
        return targets
    }
}
