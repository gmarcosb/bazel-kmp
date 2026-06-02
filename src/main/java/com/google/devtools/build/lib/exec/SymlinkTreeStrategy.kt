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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionExecutionContext

/**
 * Implements SymlinkTreeAction by using the output service or by running an embedded script to
 * create the symlink tree.
 */
class SymlinkTreeStrategy(outputService: OutputService, workspaceName: String?) : SymlinkTreeActionContext {
    private val outputService: OutputService
    private val workspaceName: String?

    init {
        this.outputService = outputService
        this.workspaceName = workspaceName
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun createSymlinks(
        action: SymlinkTreeAction, actionExecutionContext: ActionExecutionContext
    ) {
        actionExecutionContext.getEventHandler().post(RunningActionEvent(action, "local"))
        com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.logged(
            "running " + action.prettyPrint(),
            MIN_LOGGING
        ).use { p ->
            val helper: SymlinkTreeHelper = createSymlinkTreeHelper(action, actionExecutionContext)
            // TODO(tjgq): Respect RunfileSymlinksMode.SKIP even in the presence of an OutputService.
            try {
                // Note that the output manifest must always be created last, as its presence ascertains
                // that the runfiles tree has been updated (only the output manifest is an action output,
                // so Skyframe cannot invalidate the symlink tree).
                if (outputService.canCreateSymlinkTree()) {
                    val symlinks: MutableMap<PathFragment?, PathFragment?>?
                    if (action.isFilesetTree()) {
                        symlinks = getFilesetMap(action, actionExecutionContext)
                    } else {
                        // TODO(tjgq): This produces an incorrect path for unresolved symlinks, which should be
                        // created textually.
                        symlinks =
                            com.google.common.collect.Maps.transformValues<PathFragment?, Artifact?, PathFragment?>(
                                getRunfilesMap(action), TO_PATH
                            )
                    }
                    outputService.createSymlinkTree(
                        symlinks, action.getOutputManifest().getExecPath().getParentDirectory()
                    )
                    helper.linkManifest()
                } else if (action.getRunfileSymlinksMode() === RunfileSymlinksMode.SKIP) {
                    // Clear the runfiles directory, then create just the output manifest and the workspace
                    // subdirectory. This is required because only the output manifest is considered an action
                    // output, so if the previous invocation created a symlink tree, Skyframe will not clear
                    // it for us.
                    helper.createMinimalRunfilesDirectory()
                } else {
                    if (action.isFilesetTree()) {
                        helper.createFilesetSymlinks(getFilesetMap(action, actionExecutionContext))
                    } else {
                        helper.createRunfilesSymlinks(getRunfilesMap(action))
                    }
                    helper.linkManifest()
                }
            } catch (e: ExecException) {
                throw ActionExecutionException.fromExecException(e, action)
            }
        }
    }

    private fun createSymlinkTreeHelper(
        action: SymlinkTreeAction, actionExecutionContext: ActionExecutionContext
    ): SymlinkTreeHelper {
        return SymlinkTreeHelper(
            actionExecutionContext.getInputPath(action.getInputManifest()),
            actionExecutionContext.getInputPath(action.getOutputManifest()),
            actionExecutionContext.getInputPath(action.getOutputManifest()).getParentDirectory(),
            workspaceName
        )
    }

    companion object {
        private val MIN_LOGGING: java.time.Duration? = java.time.Duration.ofMillis(100)

        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val TO_PATH: com.google.common.base.Function<Artifact?, PathFragment?> =
            com.google.common.base.Function { artifact: Artifact? ->
                if (artifact == null) null else artifact.getPath().asFragment()
            }

        private fun getFilesetMap(
            action: SymlinkTreeAction, actionExecutionContext: ActionExecutionContext
        ): com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?> {
            val filesetLinks: com.google.common.collect.ImmutableList<FilesetOutputSymlink?> =
                actionExecutionContext
                    .getInputMetadataProvider()
                    .getFileset(action.getInputManifest())
                    .symlinks()
            return SymlinkTreeHelper.Companion.processFilesetLinks(filesetLinks, action.getWorkspaceNameForFileset())
        }

        private fun getRunfilesMap(action: SymlinkTreeAction): MutableMap<PathFragment?, Artifact?> {
            // This call outputs warnings about overlapping symlinks. However, since this has already been
            // called by the SourceManifestAction, we silence the warnings here.
            return action.getRunfiles().getRunfilesInputs(action.getRepoMappingManifest())
        }
    }
}
