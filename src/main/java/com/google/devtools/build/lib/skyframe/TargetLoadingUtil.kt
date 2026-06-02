// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** Holds the utility method [.loadTarget].  */
object TargetLoadingUtil {
    /**
     * Loads the [Target] specified by the [Label] by loading the label's [Package],
     * in the context of a Skyframe evaluation.
     * 
     * 
     * Establishes all Skyframe dependencies needed for incremental correctness.
     * 
     * 
     * Returns [TargetAndErrorIfAny] if no dep was mising; otherwise, returns the [ ] specifying the missing dep.
     */
    // TODO(https://github.com/bazelbuild/bazel/issues/23852): support lazy macro expansion, don't
    // load full packages unless needed.
    @Throws(NoSuchTargetException::class, NoSuchPackageException::class, java.lang.InterruptedException::class)
    fun loadTarget(env: SkyFunction.Environment, label: Label): Any? {
        if (label.name.contains("/")) {
            // This target is in a subdirectory, therefore it could potentially be invalidated by
            // a new BUILD file appearing in the hierarchy.
            val containingDirectory: PathFragment? = getContainingDirectory(label)
            val newPkgId: PackageIdentifier? =
                PackageIdentifier.create(label.getRepository(), containingDirectory)
            val containingPackageLookupValue: ContainingPackageLookupValue?
            val containingPackageKey: SkyKey? = ContainingPackageLookupValue.key(newPkgId)
            try {
                containingPackageLookupValue =
                    env.getValueOrThrow<E1?, E2?>(
                        containingPackageKey,
                        BuildFileNotFoundException::class.java,
                        InconsistentFilesystemException::class.java
                    ) as ContainingPackageLookupValue?
            } catch (e: InconsistentFilesystemException) {
                throw NoSuchTargetException(label, e.getMessage())
            }
            if (containingPackageLookupValue == null) {
                return containingPackageKey
            }

            if (!containingPackageLookupValue.hasContainingPackage()) {
                // This means the label's package doesn't exist. E.g. there is no package 'a' and we are
                // trying to build the target for label 'a:b/foo'.
                throw BuildFileNotFoundException(
                    label.getPackageIdentifier(),
                    ("BUILD file not found on package path for '"
                            + label.getPackageFragment().getPathString()
                            + "'")
                )
            }
            if (!containingPackageLookupValue.containingPackageName
                    .equals(label.getPackageIdentifier())
            ) {
                throw NoSuchTargetException(
                    label,
                    java.lang.String.format(
                        "Label '%s' crosses boundary of subpackage '%s'",
                        label, containingPackageLookupValue.containingPackageName
                    )
                )
            }
        }

        val packageKey: SkyKey? = label.getPackageIdentifier()
        val packageValue: PackageValue? =
            env.getValueOrThrow<E?>(packageKey, NoSuchPackageException::class.java) as PackageValue?
        if (packageValue == null) {
            return packageKey
        }

        val pkg: Package = packageValue.getPackage()
        val target: Target? = pkg.getTarget(label.name)
        val error: NoSuchTargetException? = if (pkg.containsErrors()) NoSuchTargetException(target) else null
        return TargetAndErrorIfAny( /* packageLoadedSuccessfully= */
            !pkg.containsErrors(), error, target, pkg
        )
    }

    private fun getContainingDirectory(label: Label): PathFragment? {
        val pkg: PathFragment = label.getPackageFragment()
        val name: String = label.name
        return if (name == ".") pkg else pkg.getRelative(name).getParentDirectory()
    }

    /**
     * Returned by [.loadTarget]. Contains the loaded [Target] and also specifies whether
     * there were errors during the target's package load.
     */
    class TargetAndErrorIfAny @com.google.common.annotations.VisibleForTesting internal constructor(
        val isPackageLoadedSuccessfully: Boolean,
        errorLoadingTarget: NoSuchTargetException?,
        target: Target?,
        pkg: Package?
    ) {
        private val errorLoadingTarget: NoSuchTargetException?
        @kotlin.jvm.JvmField
        val target: Target?
        private val pkg: Package?

        init {
            this.errorLoadingTarget = errorLoadingTarget
            this.target = target
            this.pkg = pkg
        }

        fun getErrorLoadingTarget(): NoSuchTargetException? {
            return errorLoadingTarget
        }

        val `package`: Package?
            /**
             * Returns the target's full package (which, if lazy symbolic macro expansion is enabled, is not
             * the same thing as `target.getPackageoid()`).
             */
            get() = pkg
    }
}
