// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.platform.PlatformInfo

/**
 * Partial implementation of [ActionAnalysisMetadata] to ensure consistent [ ][ActionAnalysisMetadata.getKey] computation.
 */
abstract class ActionKeyComputer : ActionAnalysisMetadata {
    @Throws(java.lang.InterruptedException::class)
    override fun getKey(
        actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
    ): String? {
        val fp: Fingerprint = Fingerprint()

        try {
            computeKey(actionKeyContext, inputMetadataProvider, fp)
        } catch (e: CommandLineExpansionException) {
            return ActionAnalysisMetadata.Companion.KEY_ERROR
        } catch (e: EvalException) {
            return ActionAnalysisMetadata.Companion.KEY_ERROR
        }

        val executionPlatform: PlatformInfo? = getExecutionPlatform()
        if (executionPlatform == null) {
            fp.addBoolean(false)
        } else {
            fp.addBoolean(true)
            executionPlatform.addTo(fp)
        }

        return fp.addStringMap(getExecProperties()).addInt(ACTION_KEY_UNIQUIFIER).hexDigestAndReset()
    }

    /**
     * See the javadoc for [Action] and [ActionAnalysisMetadata.getKey] for the contract
     * of this method.
     */
    @Throws(CommandLineExpansionException::class, EvalException::class, java.lang.InterruptedException::class)
    abstract fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint?
    )

    companion object {
        /**
         * Integer embedded in every action key.
         * 
         * 
         * The purpose of this member and associated property is to allow to easily invalidate the
         * action cache in case we want to mitigate bugs resulting with false-sharing.
         */
        private val ACTION_KEY_UNIQUIFIER: Int =
            java.lang.Integer.parseInt(java.lang.System.getProperty("ACTION_KEY_UNIQUIFIER", "0"))
    }
}
