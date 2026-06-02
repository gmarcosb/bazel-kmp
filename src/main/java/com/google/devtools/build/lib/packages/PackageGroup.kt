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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * This class represents a package group BUILD target. It has a name, a list of [ ]s, a list of [Label]s of other package groups this one includes, and
 * can be asked if a specific package is included in it.
 */
class PackageGroup(
    label: Label,
    pkg: Packageoid,
    packageSpecifications: MutableCollection<String?>,
    includes: MutableCollection<Label?>,
    allowPublicPrivate: Boolean,
    repoRootMeansCurrentRepo: Boolean,
    eventHandler: EventHandler,
    location: net.starlark.java.syntax.Location?
) : com.google.devtools.build.lib.packages.Target {
    private val containsErrors: Boolean
    private val label: Label?
    private val location: net.starlark.java.syntax.Location?
    private val containingPackageoid: Packageoid
    private val packageSpecifications: PackageGroupContents
    private val includes: MutableList<Label?>

    init {
        this.label = label
        this.location = location
        this.containingPackageoid = pkg
        this.includes = com.google.common.collect.ImmutableList.copyOf<Label?>(includes)

        // TODO(bazel-team): Consider refactoring so constructor takes a PackageGroupContents.
        val packagesBuilder: com.google.common.collect.ImmutableList.Builder<PackageSpecification?> =
            com.google.common.collect.ImmutableList.builder<PackageSpecification?>()
        var errorsFound = false
        for (packageSpecification in packageSpecifications) {
            var specification: PackageSpecification? = null
            try {
                specification =
                    PackageSpecification.Companion.fromString(
                        pkg.getMetadata().repositoryMapping,
                        label.getRepository(),
                        packageSpecification,
                        allowPublicPrivate,
                        repoRootMeansCurrentRepo
                    )
            } catch (e: InvalidPackageSpecificationException) {
                errorsFound = true
                eventHandler.handle(
                    com.google.devtools.build.lib.packages.Package.Companion.error(
                        location,
                        e.getMessage(),
                        Code.INVALID_PACKAGE_SPECIFICATION
                    )
                )
            }

            if (specification != null) {
                packagesBuilder.add(specification)
            }
        }
        this.containsErrors = errorsFound
        this.packageSpecifications = PackageGroupContents.Companion.create(packagesBuilder.build())
    }

    fun containsErrors(): Boolean {
        return containsErrors
    }

    fun getPackageSpecifications(): PackageGroupContents {
        return packageSpecifications
    }

    fun contains(pkgId: PackageIdentifier?): Boolean {
        return packageSpecifications.containsPackage(pkgId)
    }

    fun getIncludes(): MutableList<Label?> {
        return includes
    }

    // See PackageSpecification#asString.
    fun getContainedPackages(includeDoubleSlash: Boolean): MutableList<String?> {
        return packageSpecifications.packageStrings(includeDoubleSlash)
    }

    override fun getAssociatedRule(): com.google.devtools.build.lib.packages.Rule? {
        return null
    }

    override fun getLabel(): Label? {
        return label
    }

    override fun getLicense(): License {
        return License.Companion.NO_LICENSE
    }

    override fun getPackageoid(): Packageoid {
        return containingPackageoid
    }

    override fun getPackageMetadata(): com.google.devtools.build.lib.packages.Package.Metadata? {
        return containingPackageoid.getMetadata()
    }

    override fun getPackageDeclarations(): Declarations? {
        return containingPackageoid.getDeclarations()
    }

    override fun getTargetKind(): String {
        return targetKind()
    }

    override fun getLocation(): net.starlark.java.syntax.Location? {
        return location
    }

    override fun toString(): String {
        return targetKind() + " " + getLabel()
    }

    override fun getRawVisibility(): RuleVisibility? {
        return null
    }

    override fun getVisibility(): RuleVisibility {
        // Package groups are always public to avoid a PackageGroupConfiguredTarget
        // needing itself for the visibility check. It may work, but I did not
        // think it over completely.
        // (We override getRawVisibility() separately so as to not display this value during
        // introspection.)
        return RuleVisibility.Companion.PUBLIC
    }

    override fun isConfigurable(): Boolean {
        return false
    }

    override fun reduceForSerialization(): TargetData {
        return AutoValue_PackageGroup_PackageGroupData(getLocation(), getLabel())
    }

    @AutoValue
    internal abstract class PackageGroupData : TargetData {
        override fun getTargetKind(): String {
            return targetKind()
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun targetKind(): String {
            return "package group"
        }
    }
}
