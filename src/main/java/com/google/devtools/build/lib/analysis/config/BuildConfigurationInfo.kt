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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.actions.BuildConfigurationEvent

/**
 * Provides build configuration dependent information.
 * 
 * 
 * By having this interface, we don't need to create a full [BuildConfigurationValue]
 * instance when only the four fields defined in this interface is provided.
 * 
 * 
 * This provides some convenience to construct [ ][com.google.devtools.build.lib.actions.ActionOwner.SYSTEM_ACTION_OWNER] and other [ ] instances in tests.
 */
interface BuildConfigurationInfo {
    /**
     * Returns the configuration-dependent string for this configuration.
     * 
     * 
     * This is also the name of the configuration's base output directory. See also [ ][com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.getOutputDirectoryName].
     */
    fun getMnemonic(): String?

    /** Returns the cache key of the build options used to create this configuration.  */
    fun checksum(): String?

    /**
     * Returns the `BuildEvent` associated with [ ].
     */
    fun toBuildEvent(): BuildConfigurationEvent?

    /** Returns true if this is a tool-related configuration.  */
    fun isToolConfiguration(): Boolean

    fun getCommandLineLimits(): CommandLineLimits?

    /**
     * An auto value class of [BuildConfigurationInfo]. This provides a convenient way for
     * creating [BuildConfigurationInfo] with only the four fields provided.
     */
    @AutoValue
    class AutoBuildConfigurationInfo : BuildConfigurationInfo {
        override fun getCommandLineLimits(): CommandLineLimits {
            return CommandLineLimits.UNLIMITED
        }

        companion object {
            fun create(
                mnemonic: String?,
                checksum: String?,
                buildConfigurationEvent: BuildConfigurationEvent?,
                isToolConfiguration: Boolean
            ): AutoBuildConfigurationInfo {
                return AutoValue_BuildConfigurationInfo_AutoBuildConfigurationInfo(
                    mnemonic, checksum, buildConfigurationEvent, isToolConfiguration
                )
            }
        }
    }
}
