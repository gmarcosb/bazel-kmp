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

import com.google.devtools.build.lib.analysis.config.FeatureSet

/**
 * A group of [Package] argument values that may be provided by `package()` or `repo()` calls.
 * 
 * 
 * Unless otherwise specified, these are only used when the rule does not provide an explicit
 * override value in the associated attribute.
 */
@AutoValue
abstract class PackageArgs {
    /** The default visibility value for the package.  */
    abstract fun defaultVisibility(): RuleVisibility?

    /** The default testonly value for the package.  */
    abstract fun defaultTestOnly(): Boolean?

    /** The default deprecation value for the package.  */
    abstract fun defaultDeprecation(): String?

    /**
     * The default (generally C/C++) features value for the package.
     * 
     * 
     * Note that this is actually additive with features set by a rule where the rule has priority
     * for turning specific features on or off.
     */
    abstract fun features(): FeatureSet?

    /** The default license value for the package.  */
    abstract fun license(): License?

    /** The default [RuleClass.COMPATIBLE_ENVIRONMENT_ATTR] value for the package.  */
    abstract fun defaultCompatibleWith(): com.google.common.collect.ImmutableSet<Label?>?

    /** The default [RuleClass.RESTRICTED_ENVIRONMENT_ATTR] value for the package.  */
    abstract fun defaultRestrictedTo(): com.google.common.collect.ImmutableSet<Label?>?

    /** The default package metadata list value for the package.  */
    abstract fun defaultPackageMetadata(): com.google.common.collect.ImmutableList<Label?>?

    /** The transitive visibility settings this package says it belongs to.  */
    abstract fun transitiveVisibility(): Label?

    // TODO(blaze-team): this should just act like other attributes in that
    //   it is public and does not have getters defined
    /** The default (C/C++) header strictness checking mode for the package.  */
    abstract fun defaultHdrsCheck(): String?

    /** Gets the default header checking mode.  */
    fun getDefaultHdrsCheck(): String? {
        return if (defaultHdrsCheck() != null) defaultHdrsCheck() else "strict"
    }

    /** Returns whether the default header checking mode has been set or it is the default value.  */
    fun isDefaultHdrsCheckSet(): Boolean {
        return defaultHdrsCheck() != null
    }

    abstract fun toBuilder(): Builder

    /** Builder type for [PackageArgs].  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun setDefaultVisibility(x: RuleVisibility?): Builder?

        abstract fun setDefaultTestOnly(x: Boolean?): Builder?

        abstract fun setDefaultDeprecation(x: String?): Builder?

        abstract fun features(): FeatureSet?

        abstract fun setFeatures(x: FeatureSet?): Builder?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mergeFeatures(x: FeatureSet?): Builder? {
            return setFeatures(FeatureSet.merge(features(), x))
        }

        abstract fun setLicense(x: License?): Builder?

        /** Note that we don't check dupes in this method. Check beforehand!  */
        abstract fun setDefaultCompatibleWith(x: Iterable<Label?>?): Builder?

        /** Note that we don't check dupes in this method. Check beforehand!  */
        abstract fun setDefaultRestrictedTo(x: Iterable<Label?>?): Builder?

        abstract fun defaultPackageMetadata(): com.google.common.collect.ImmutableList<Label?>?

        /** Note that we don't check dupes in this method. Check beforehand!  */
        abstract fun setDefaultPackageMetadata(x: MutableList<Label?>?): Builder?

        abstract fun setTransitiveVisibility(x: Label?): Builder?

        abstract fun setDefaultHdrsCheck(x: String?): Builder?

        abstract fun build(): PackageArgs?
    }

    /**
     * Returns a new [PackageArgs] containing the result of merging `other` into `this`. `other`'s fields take precedence if specified.
     */
    fun mergeWith(other: PackageArgs): PackageArgs? {
        val builder = toBuilder()
        if (other.defaultVisibility() != null) {
            builder.setDefaultVisibility(other.defaultVisibility())
        }
        if (other.defaultTestOnly() != null) {
            builder.setDefaultTestOnly(other.defaultTestOnly())
        }
        if (other.defaultDeprecation() != null) {
            builder.setDefaultDeprecation(other.defaultDeprecation())
        }
        if (!other.features().equals(FeatureSet.EMPTY)) {
            builder.mergeFeatures(other.features())
        }
        if (other.license() != null) {
            builder.setLicense(other.license())
        }
        if (other.defaultCompatibleWith() != null) {
            builder.setDefaultCompatibleWith(other.defaultCompatibleWith())
        }
        if (other.defaultRestrictedTo() != null) {
            builder.setDefaultRestrictedTo(other.defaultRestrictedTo())
        }
        if (other.defaultPackageMetadata() != null) {
            builder.setDefaultPackageMetadata(other.defaultPackageMetadata())
        }
        if (other.transitiveVisibility() != null) {
            builder.setTransitiveVisibility(other.transitiveVisibility())
        }
        if (other.defaultHdrsCheck() != null) {
            builder.setDefaultHdrsCheck(other.defaultHdrsCheck())
        }
        return builder.build()
    }

    companion object {
        @kotlin.jvm.JvmField
        val EMPTY: PackageArgs? = builder().build()

        @kotlin.jvm.JvmField
        val DEFAULT: PackageArgs? = builder()
            .setDefaultVisibility(RuleVisibility.Companion.PRIVATE)!!
            .setDefaultTestOnly(false)!!
            .setFeatures(FeatureSet.EMPTY)!!
            .setLicense(License.Companion.NO_LICENSE)!!
            .setDefaultCompatibleWith(com.google.common.collect.ImmutableSet.of<Label?>())!!
            .setDefaultRestrictedTo(com.google.common.collect.ImmutableSet.of<Label?>())!!
            .setDefaultPackageMetadata(com.google.common.collect.ImmutableList.of<Label?>())!!
            .build()

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return Builder().setFeatures(FeatureSet.EMPTY)!!
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun throwIfHasDupes(labels: MutableList<Label?>?, what: String?): MutableList<Label?>? {
            val dupes: com.google.common.collect.ImmutableSet<out Any?> =
                com.google.common.collect.ImmutableSortedSet.copyOf(CollectionUtils.duplicatedElementsOf(labels))
            if (!dupes.isEmpty()) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "duplicate label(s) in %s: %s",
                    what,
                    com.google.common.base.Joiner.on(", ").join(dupes)
                )
            }
            return labels
        }

        /**
         * Processes the given Starlark parameter to the `package()/repo()` call into a field on a
         * [Builder] object.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun processParam(
            name: String, rawValue: Any?, what: String?, labelConverter: LabelConverter?, builder: Builder
        ) {
            when (name) {
                "default_visibility" -> builder.setDefaultVisibility(
                    RuleVisibility.Companion.parse(BuildType.LABEL_LIST.convert(rawValue, what, labelConverter))
                )

                "default_testonly" -> builder.setDefaultTestOnly(
                    com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN.convert(
                        rawValue,
                        what,
                        labelConverter
                    )
                )

                "default_deprecation" -> builder.setDefaultDeprecation(
                    com.google.devtools.build.lib.packages.Type.Companion.STRING.convert(
                        rawValue,
                        what,
                        labelConverter
                    )
                )

                "features" -> builder.mergeFeatures(
                    FeatureSet.parse(
                        com.google.devtools.build.lib.packages.Types.STRING_LIST.convert(
                            rawValue,
                            what,
                            labelConverter
                        )
                    )
                )

                "licenses" -> builder.setLicense(BuildType.LICENSE.convert(rawValue, what, labelConverter))
                "default_compatible_with" -> builder.setDefaultCompatibleWith(
                    throwIfHasDupes(BuildType.LABEL_LIST.convert(rawValue, what, labelConverter), name)
                )

                "default_restricted_to" -> builder.setDefaultRestrictedTo(
                    throwIfHasDupes(BuildType.LABEL_LIST.convert(rawValue, what, labelConverter), name)
                )

                "default_applicable_licenses", "default_package_metadata" -> {
                    if (builder.defaultPackageMetadata() != null) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Can not set both default_package_metadata and default_applicable_licenses."
                                    + " Move all declarations to default_package_metadata."
                        )
                    }
                    builder.setDefaultPackageMetadata(
                        throwIfHasDupes(BuildType.LABEL_LIST.convert(rawValue, what, labelConverter), name)
                    )
                }

                "default_hdrs_check" -> builder.setDefaultHdrsCheck(
                    com.google.devtools.build.lib.packages.Type.Companion.STRING.convert(
                        rawValue,
                        what,
                        labelConverter
                    )
                )

                "transitive_visibility" -> {
                    val transitiveVisibility: Label? = BuildType.LABEL.convert(rawValue, what, labelConverter)
                    builder.setTransitiveVisibility(transitiveVisibility)
                }

                else -> throw net.starlark.java.eval.Starlark.errorf("unexpected keyword argument: %s", name)
            }
        }
    }
}
