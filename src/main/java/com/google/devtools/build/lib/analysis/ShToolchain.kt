// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants

/** Class to work with the shell toolchain, e.g. get the shell interpreter's path.  */
object ShToolchain {
    /** Returns the default shell executable's path for the host OS.  */
    fun getPathForHost(config: BuildConfigurationValue): PathFragment? {
        return getPathForPlatform(config,  /* platformInfo= */null)
    }

    /**
     * Returns the shell executable's path. Prefers, in order
     * 
     * 
     * 1) the default path set by `--shell_executable`
     * 
     * 
     * 2) the path for the provided platform if not null
     * 
     * 
     * 3) the path for the host platform
     * 
     * 
     * 4) a hard-coded default path.
     */
    fun getPathForPlatform(
        config: BuildConfigurationValue, platformInfo: PlatformInfo?
    ): PathFragment? {
        val shellConfiguration: ShellConfiguration? =
            config.getFragment<ShellConfiguration?>(ShellConfiguration::class.java)

        if (shellConfiguration != null && shellConfiguration.getOptionsBasedDefault() != null) {
            return shellConfiguration.getOptionsBasedDefault()
        }

        return java.util.Optional.ofNullable<Any?>(platformInfo)
            .map<Any?>(ConstraintConstants::getOsFromConstraintsOrHost)
            .flatMap<U?>(java.util.function.Function { os: Any? -> ShellConfiguration.Companion.getShellExecutable(os) })
            .or(java.util.function.Supplier { ShellConfiguration.Companion.getShellExecutable(OS.UNKNOWN) })
            .orElseThrow()
    }
}
