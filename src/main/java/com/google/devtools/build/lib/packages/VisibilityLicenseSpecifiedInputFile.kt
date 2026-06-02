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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * An input file to the build system which has a specified [.visibility] or [.license].
 */
class VisibilityLicenseSpecifiedInputFile internal constructor(
    pkg: Packageoid?,
    label: Label?,
    location: net.starlark.java.syntax.Location?,
    visibility: RuleVisibility?,
    license: License?
) : InputFile(pkg, label, location) {
    private val visibility: RuleVisibility?
    private val license: License?

    init {
        this.visibility = visibility
        this.license = license
    }

    override fun isVisibilitySpecified(): Boolean {
        return visibility != null
    }

    override fun getRawVisibility(): RuleVisibility? {
        return visibility
    }

    override fun isLicenseSpecified(): Boolean {
        return license != null && license.isSpecified()
    }

    override fun getLicense(): License? {
        if (license != null) {
            return license
        } else {
            return getPackageDeclarations().getPackageArgs().license()
        }
    }
}
