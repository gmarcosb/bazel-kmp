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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * SkyFunction for [ContainingPackageLookupValue]s.
 */
class ContainingPackageLookupFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val dir: PackageIdentifier = skyKey.argument() as PackageIdentifier
        val pkgLookupValue: PackageLookupValue? =
            env.getValue(PackageLookupValue.key(dir)) as PackageLookupValue?
        if (pkgLookupValue == null) {
            return null
        }

        if (pkgLookupValue.packageExists()) {
            return ContainingPackageLookupValue.Companion.withContainingPackage(dir, pkgLookupValue.getRoot())
        }

        // Does the requested package cross into a sub-repository, which we should report via the
        // correct package identifier?
        if (pkgLookupValue
                    is IncorrectRepositoryReferencePackageLookupValue
        ) {
            val correctPackageIdentifier: PackageIdentifier? =
                pkgLookupValue.getCorrectedPackageIdentifier()
            return env.getValue(ContainingPackageLookupValue.Companion.key(correctPackageIdentifier))
        }

        if (ErrorReason.REPOSITORY_NOT_FOUND == pkgLookupValue.getErrorReason()) {
            return ContainingPackageLookupValue.Companion.noContainingPackage(pkgLookupValue.getErrorMsg())
        }
        val parentDir: PathFragment? = dir.getPackageFragment().getParentDirectory()
        if (parentDir == null) {
            return ContainingPackageLookupValue.Companion.NONE
        }
        val parentId: PackageIdentifier? = PackageIdentifier.create(dir.getRepository(), parentDir)
        return env.getValue(ContainingPackageLookupValue.Companion.key(parentId))
    }
}
