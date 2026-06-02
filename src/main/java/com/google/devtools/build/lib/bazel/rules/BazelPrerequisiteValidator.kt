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
package com.google.devtools.build.lib.bazel.rules

import com.google.devtools.build.lib.analysis.CommonPrerequisiteValidator

/** Ensures that a target's prerequisites are visible to it and match its testonly status.  */
class BazelPrerequisiteValidator : CommonPrerequisiteValidator() {
    protected override fun isSameLogicalPackage(
        thisPackage: PackageIdentifier, prerequisitePackage: PackageIdentifier?
    ): Boolean {
        return thisPackage == prerequisitePackage
    }

    public override fun packageUnderExperimental(packageIdentifier: PackageIdentifier?): Boolean {
        return false
    }

    public override fun packageUnderPrototypes(packageIdentifier: PackageIdentifier?): Boolean {
        return false
    }

    protected override fun checkVisibilityForExperimental(context: RuleContext.Builder?): Boolean {
        // It does not matter whether we return true or false here if packageUnderExperimental always
        // returns false.
        return true
    }

    protected override fun checkVisibilityForPrototypes(context: RuleContext.Builder?): Boolean {
        return true
    }

    protected override fun allowExperimentalDeps(context: RuleContext.Builder?): Boolean {
        // It does not matter whether we return true or false here if packageUnderExperimental always
        // returns false.
        return false
    }
}
