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
 * Represents one of the following:
 * 
 * 
 *  * A single package (e.g. "//foo/bar")
 *  * All transitive subpackages of a package, inclusive (e.g. "//foo/bar/...", which includes
 * "//foo/bar")
 *  * All packages (i.e. "//...")
 * 
 * 
 * 
 * Typically (exclusively?) used for package visibility, as part of a [PackageGroup]
 * target.
 * 
 * 
 * A package specification is specific to a single [RepositoryName] unless it is the "all
 * packages" specification.
 */
// TODO(b/279784354): Delete PackageSpecification; reimplement bzl visibility using something else.
abstract class PackageSpecification {
    /** Returns `true` if the package spec includes the provided `packageName`.  */
    abstract fun containsPackage(packageName: PackageIdentifier?): Boolean

    /**
     * Returns a string representation of this package spec.
     * 
     * 
     * The repository is included, unless it is the main repository, in which case there will be no
     * leading @. For instance, `"@somerepo//pkg/subpkg"` and `"//otherpkg/..."` are both valid outputs.
     * 
     * 
     * Note that since [.fromString] does not accept label strings with repositories, this
     * representation is not guaranteed to be round-trippable.
     * 
     * 
     * If `includeDoubleSlash` is false, then in the case of the main repository, the leading
     * `//` will also be omitted, so that the output looks like `otherpkg/...`. This form
     * is deprecated.
     */
    // TODO(b/77598306): Remove the parameter after switching all callers to pass true.
    protected abstract fun asString(includeDoubleSlash: Boolean): String

    override fun toString(): String {
        return asString( /*includeDoubleSlash=*/false)
    }

    private class SinglePackage(singlePackageName: PackageIdentifier) : PackageSpecification() {
        private val singlePackageName: PackageIdentifier

        init {
            this.singlePackageName = singlePackageName
        }

        protected override fun containsPackage(packageName: PackageIdentifier?): Boolean {
            return this.singlePackageName.equals(packageName)
        }

        override fun asString(includeDoubleSlash: Boolean): String {
            return PackageGroupContents.Companion.stringForSinglePackage(singlePackageName, includeDoubleSlash)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is SinglePackage) {
                return false
            }
            val that = o
            return singlePackageName.equals(that.singlePackageName)
        }

        override fun hashCode(): Int {
            return singlePackageName.hashCode()
        }
    }

    private class AllPackagesBeneath(prefix: PackageIdentifier) : PackageSpecification() {
        private val prefix: PackageIdentifier

        init {
            this.prefix = prefix
        }

        protected override fun containsPackage(packageName: PackageIdentifier): Boolean {
            return packageName.getRepository().equals(prefix.getRepository())
                    && packageName.getPackageFragment().startsWith(prefix.getPackageFragment())
        }

        override fun asString(includeDoubleSlash: Boolean): String {
            return PackageGroupContents.Companion.stringForAllPackagesBeneath(prefix, includeDoubleSlash)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is AllPackagesBeneath) {
                return false
            }
            val that = o
            return prefix.equals(that.prefix)
        }

        override fun hashCode(): Int {
            return prefix.hashCode()
        }
    }

    /** A package specification for a negative match, e.g. `-//pkg/sub/...`.  */
    private class NegativePackageSpecification(private val delegate: PackageSpecification) : PackageSpecification() {
        protected override fun containsPackage(packageName: PackageIdentifier?): Boolean {
            return delegate.containsPackage(packageName)
        }

        override fun asString(includeDoubleSlash: Boolean): String {
            return "-" + delegate.asString(includeDoubleSlash)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            return obj is NegativePackageSpecification
                    && delegate == obj.delegate
        }

        override fun hashCode(): Int {
            return NegativePackageSpecification::class.java.hashCode() xor delegate.hashCode()
        }
    }

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class AllPackages : PackageSpecification() {
        protected override fun containsPackage(packageName: PackageIdentifier?): Boolean {
            return true
        }

        override fun asString(includeDoubleSlash: Boolean): String {
            return PackageGroupContents.Companion.stringForAllPackages(includeDoubleSlash)
        }

        override fun equals(o: Any?): Boolean {
            return o is AllPackages
        }

        override fun hashCode(): Int {
            return AllPackages::class.java.hashCode()
        }

        companion object {
            @SerializationConstant
            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            val INSTANCE: PackageSpecification = AllPackages()
        }
    }

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class NoPackages : PackageSpecification() {
        protected override fun containsPackage(packageName: PackageIdentifier?): Boolean {
            return false
        }

        override fun asString(includeDoubleSlash: Boolean): String {
            return "private"
        }

        override fun equals(o: Any?): Boolean {
            return o is NoPackages
        }

        override fun hashCode(): Int {
            return NoPackages::class.java.hashCode()
        }

        companion object {
            @SerializationConstant
            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            val INSTANCE: PackageSpecification = NoPackages()
        }
    }

    /** Exception class to be thrown when a specification cannot be parsed.  */
    internal class InvalidPackageSpecificationException private constructor(message: String?) :
        java.lang.Exception(message)

    /**
     * Represents a collection of [PackageSpecification]s logically corresponding to a single
     * `package_group`'s `packages` attribute.
     * 
     * 
     * Supports testing whether a given package is contained, taking into account negative specs.
     * 
     * 
     * Duplicate specs (e.g., ["//foo", "//foo"]) may or may not be deduplicated. Iteration order
     * may vary from the order in which specs were provided, but is guaranteed to be deterministic.
     * 
     * 
     * For modeling a `package_group`'s transitive contents (i.e., via the `includes`
     * attribute), see [PackageSpecificationProvider].
     */
    @AutoValue
    abstract class PackageGroupContents {
        // This class is optimized for memory and cpu.
        // TODO(b/279784354): Further improvements are possible.
        //
        // PackageGroupContents instances are retained through and after the analysis phase. Therefore,
        // in order to save memory, we don't retain PackageSpecification instances but instead unroll
        // them, storing just lists and sets of PackageIdentifier instances.
        //
        // To save cpu, we store the PackageIdentifier instances corresponding to positive/negative
        // single package specifications in sets so we get a fast-path hit on them. Also, we check
        // negatives first since package_group semantics require we check all negatives no matter what.
        // Both of these cpu optimizations combine well in practice since there are some package_group
        // targets with very large lists of negative single package specifications.
        abstract fun singlePackagePositives(): com.google.common.collect.ImmutableSet<PackageIdentifier>?

        abstract fun allPackagesBeneathPositives(): com.google.common.collect.ImmutableList<PackageIdentifier>

        abstract fun hasPositiveAllPackages(): Boolean

        abstract fun singlePackageNegatives(): com.google.common.collect.ImmutableSet<PackageIdentifier>?

        abstract fun allPackagesBeneathNegatives(): com.google.common.collect.ImmutableList<PackageIdentifier>

        abstract fun hasPrivate(): Boolean

        abstract fun hasNegativeAllPackages(): Boolean

        /**
         * Returns true if the given package matches at least one of this `PackageGroupContents`'
         * positive specifications and none of its negative specifications.
         */
        fun containsPackage(packageIdentifier: PackageIdentifier): Boolean {
            // DO NOT use streams or iterators here as they create excessive garbage.
            if (hasNegativeAllPackages()) {
                return false
            }
            if (singlePackageNegatives().contains(packageIdentifier)) {
                return false
            }
            // The following line is just so that we don't call the method inside the loop (which may or
            // may not be optimized away... better be on the safe side).
            val allPackagesBeneathNegatives: com.google.common.collect.ImmutableList<PackageIdentifier> =
                allPackagesBeneathNegatives()
            for (i in allPackagesBeneathNegatives.indices) {
                if (matchesAllPackagesBeneath(packageIdentifier, allPackagesBeneathNegatives.get(i))) {
                    return false
                }
            }
            if (hasPositiveAllPackages()) {
                return true
            }
            if (singlePackagePositives().contains(packageIdentifier)) {
                return true
            }
            val allPackagesBeneathPositives: com.google.common.collect.ImmutableList<PackageIdentifier> =
                allPackagesBeneathPositives()
            for (i in allPackagesBeneathPositives.indices) {
                if (matchesAllPackagesBeneath(packageIdentifier, allPackagesBeneathPositives.get(i))) {
                    return true
                }
            }
            return false
        }

        /**
         * Does the equivalent of mapping [PackageSpecification.asString] to the component package
         * specs.
         * 
         * 
         * Note that strings for specs that cross repositories can't be reparsed using [ ][PackageSpecification.fromString].
         * 
         * 
         * The special public constant will serialize as `"public"` if `includeDoubleSlash` is true, and `"//..."` otherwise. The private constant will always
         * serialize as `"private"`,
         */
        fun packageStrings(includeDoubleSlash: Boolean): com.google.common.collect.ImmutableList<String?> {
            val resultBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (pkgId in singlePackagePositives()) {
                resultBuilder.add(stringForSinglePackage(pkgId, includeDoubleSlash))
            }
            for (pkgId in allPackagesBeneathPositives()) {
                resultBuilder.add(stringForAllPackagesBeneath(pkgId, includeDoubleSlash))
            }
            for (pkgId in singlePackageNegatives()) {
                resultBuilder.add("-" + stringForSinglePackage(pkgId, includeDoubleSlash))
            }
            for (pkgId in allPackagesBeneathNegatives()) {
                resultBuilder.add("-" + stringForAllPackagesBeneath(pkgId, includeDoubleSlash))
            }
            if (hasPositiveAllPackages()) {
                resultBuilder.add(stringForAllPackages(includeDoubleSlash))
            }
            if (hasPrivate()) {
                resultBuilder.add("private")
            }
            if (hasNegativeAllPackages()) {
                resultBuilder.add("-" + stringForAllPackages(includeDoubleSlash))
            }
            return resultBuilder.build()
        }

        /**
         * / ** Returns a string representation of this package spec without the repository, and which is
         * round-trippable through [.fromString].
         * 
         * 
         * For instance, `@somerepo//pkg/subpkg/...` turns into `"//pkg/subpkg/..."`.
         * 
         * 
         * Omitting the repository means that the returned strings are ambiguous in the absence of
         * additional context. But, for instance, if interpreted with respect to a `package_group`'s `packages` attribute, the strings always have the same repository as
         * the package group.
         * 
         * 
         * Note that this is ambiguous w.r.t. specs that reference other repositories.
         * 
         * 
         * The special public and private constants will serialize as `"public"` and `"private"` respectively.
         */
        fun packageStringsWithDoubleSlashAndWithoutRepository(): com.google.common.collect.ImmutableList<String?> {
            val resultBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (pkgId in singlePackagePositives()) {
                resultBuilder.add(stringForSinglePackageWithDoubleSlashAndWithoutRepository(pkgId))
            }
            for (pkgId in allPackagesBeneathPositives()) {
                resultBuilder.add(stringForAllPackagesBeneathWithDoubleSlashAndWithoutRepository(pkgId))
            }
            for (pkgId in singlePackageNegatives()) {
                resultBuilder.add("-" + stringForSinglePackageWithDoubleSlashAndWithoutRepository(pkgId))
            }
            for (pkgId in allPackagesBeneathNegatives()) {
                resultBuilder.add(
                    "-" + stringForAllPackagesBeneathWithDoubleSlashAndWithoutRepository(pkgId)
                )
            }
            if (hasPositiveAllPackages()) {
                resultBuilder.add("public")
            }
            if (hasPrivate()) {
                resultBuilder.add("private")
            }
            return resultBuilder.build()
        }

        companion object {
            /**
             * Creates a [PackageGroupContents] representing a collection of [ ]s.
             */
            fun create(
                packageSpecifications: com.google.common.collect.ImmutableList<PackageSpecification>
            ): PackageGroupContents {
                val singlePackagePositivesBuilder: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
                    com.google.common.collect.ImmutableSet.builder<PackageIdentifier?>()
                val allPackagesBeneathPositivesBuilder: com.google.common.collect.ImmutableList.Builder<PackageIdentifier?> =
                    com.google.common.collect.ImmutableList.builder<PackageIdentifier?>()
                var hasPositiveAllPackages = false
                val singlePackageNegativesBuilder: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
                    com.google.common.collect.ImmutableSet.builder<PackageIdentifier?>()
                val allPackagesBeneathNegativesBuilder: com.google.common.collect.ImmutableList.Builder<PackageIdentifier?> =
                    com.google.common.collect.ImmutableList.builder<PackageIdentifier?>()
                var hasPrivate = false
                var hasNegativeAllPackages = false

                for (spec in packageSpecifications) {
                    if (spec is SinglePackage) {
                        singlePackagePositivesBuilder.add(spec.singlePackageName)
                        continue
                    }

                    if (spec is AllPackagesBeneath) {
                        allPackagesBeneathPositivesBuilder.add(spec.prefix)
                        continue
                    }

                    if (spec is AllPackages) {
                        hasPositiveAllPackages = true
                        continue
                    }

                    if (spec is NoPackages) {
                        // We can't drop NoPackages because it still needs to be serialized, e.g. in bazel query
                        // output.
                        hasPrivate = true
                        continue
                    }

                    val delegate: PackageSpecification? = (spec as NegativePackageSpecification).delegate
                    if (delegate is SinglePackage) {
                        singlePackageNegativesBuilder.add(delegate.singlePackageName)
                        continue
                    }

                    if (delegate is AllPackagesBeneath) {
                        allPackagesBeneathNegativesBuilder.add(delegate.prefix)
                        continue
                    }

                    if (delegate is AllPackages) {
                        hasNegativeAllPackages = true
                        continue
                    }

                    throw java.lang.IllegalStateException(spec.toString())
                }

                return AutoValue_PackageSpecification_PackageGroupContents(
                    singlePackagePositivesBuilder.build(),
                    allPackagesBeneathPositivesBuilder.build(),
                    hasPositiveAllPackages,
                    singlePackageNegativesBuilder.build(),
                    allPackagesBeneathNegativesBuilder.build(),
                    hasPrivate,
                    hasNegativeAllPackages
                )
            }

            private fun matchesAllPackagesBeneath(
                pkgId: PackageIdentifier, prefix: PackageIdentifier
            ): Boolean {
                return pkgId.getRepository().equals(prefix.getRepository())
                        && pkgId.getPackageFragment().startsWith(prefix.getPackageFragment())
            }

            private fun stringForAllPackages(includeDoubleSlash: Boolean): String {
                // Under legacy formatting rules, use legacy syntax. This avoids ambiguity between "public"
                // and "//public", and ensures that AllPackages is round-trippable when the value of
                // includeDoubleSlash matches allowPublicPrivate.
                return if (includeDoubleSlash) "public" else "//..."
            }

            private fun stringForSinglePackage(
                pkgId: PackageIdentifier, includeDoubleSlash: Boolean
            ): String {
                if (includeDoubleSlash) {
                    return pkgId.getCanonicalForm()
                } else {
                    // PackageIdentifier#toString implements the legacy behavior of omitting the double slash
                    // for the main repo.
                    return pkgId.toString()
                }
            }

            private fun stringForAllPackagesBeneath(
                pkgId: PackageIdentifier, includeDoubleSlash: Boolean
            ): String {
                if (pkgId.getPackageFragment().equals(PathFragment.EMPTY_FRAGMENT)) {
                    // Special case: Emit "//..." rather than suffixing "/...", which would yield "/...".
                    // Make sure not to strip the repo in the case of "@repo//...".
                    //
                    // Note that "//..." is the desired result, not "...", even under the legacy behavior of
                    // includeDoubleSlash=false.
                    return pkgId.getCanonicalForm() + "..."
                }
                if (includeDoubleSlash) {
                    return pkgId.getCanonicalForm() + ALL_BENEATH_SUFFIX
                } else {
                    // PackageIdentifier#toString implements the legacy behavior of omitting the double slash
                    // for the main repo.
                    return pkgId.toString() + ALL_BENEATH_SUFFIX
                }
            }

            private fun stringForSinglePackageWithDoubleSlashAndWithoutRepository(
                pkgId: PackageIdentifier
            ): String {
                return "//" + pkgId.getPackageFragment().getPathString()
            }

            private fun stringForAllPackagesBeneathWithDoubleSlashAndWithoutRepository(
                pkgId: PackageIdentifier
            ): String {
                val pathFragment: PathFragment = pkgId.getPackageFragment()
                return if (pathFragment == PathFragment.EMPTY_FRAGMENT)
                    "//..."
                else
                    "//" + pathFragment.getPathString() + ALL_BENEATH_SUFFIX
            }
        }
    }

    companion object {
        private const val PUBLIC_VISIBILITY = "public"
        private const val PRIVATE_VISIBILITY = "private"
        private const val ALL_BENEATH_SUFFIX = "/..."
        private const val NEGATIVE_PREFIX = "-"

        // Used for interpreting `visibility` labels.
        private const val PACKAGE_LABEL = "__pkg__"
        private const val SUBTREE_LABEL = "__subpackages__"

        /**
         * Parses the string `spec` into a [PackageSpecification], within the context of the
         * given repository name.
         * 
         * 
         * `spec` may have the following forms:
         * 
         * 
         *  1. The full name of a single package, without repository qualification, prefixed with "//"
         * (e.g. "//foo/bar"). The resulting specification contains exactly that package.
         *  1. The same, but suffixed with "/..." for a non-root package ("//foo/bar/...") or "..." for
         * the root package ("//..."). The resulting specification contains that package and all its
         * subpackages.
         *  1. The string constants "public" or "private". The resulting specification contains either
         * all packages or no packages, respectively.
         * 
         * 
         * In the first two cases, the repository of the given package name is taken to be `repositoryName`. In the third case the repository name is ignored.
         * 
         * 
         * In the first two cases, `spec` may also be prefixed by a "-". The resulting
         * specification contains the same set of packages but is marked as being negated. (Negation logic
         * is applied at the level of [PackageGroupContents].)
         * 
         * 
         * Setting `allowPublicPrivate` to false disallows the string constants "public" and
         * "private". Note that if [.asString] is called with `includeDoubleSlash` set to
         * false, the stringification of "public" and "//public" is ambiguous (likewise for private),
         * hence why it might be appropriate to prohibit these forms.
         * 
         * 
         * Setting `repoRootMeansCurrentRepo` to false restores the following legacy behavior: In
         * the specific case where `spec` is "//..." (or its negation), the package specification
         * contains *all* packages (possibly marked as negated) rather than just those packages in
         * `repositoryName`. In other words, "//..." behaves the same as "public". However, "//"
         * still represents just the root package of `repositoryName`.
         * 
         * 
         * To protect against requiring users to update to a disallowed syntax, it is illegal to
         * specify `repoRootMeansCurrentRepo` without also specifying `allowPublicPrivate`.
         * 
         * @throws InvalidPackageSpecificationException if the string does not fit one of these forms
         */
        // TODO(#16365): Remove allowPublicPrivate.
        // TODO(#16324): Remove legacy behavior and repoRootMeansCurrentRepo param.
        @Throws(InvalidPackageSpecificationException::class)
        fun fromString(
            repositoryMapping: RepositoryMapping?,
            repositoryName: RepositoryName?,
            spec: String,
            allowPublicPrivate: Boolean,
            repoRootMeansCurrentRepo: Boolean
        ): PackageSpecification? {
            var spec = spec
            if (repoRootMeansCurrentRepo && !allowPublicPrivate) {
                throw InvalidPackageSpecificationException(
                    ("Cannot use new \"//...\" meaning without allowing new \"public\" syntax. Try enabling"
                            + " --incompatible_package_group_has_public_syntax or disabling"
                            + " --incompatible_fix_package_group_reporoot_syntax.")
                )
            }
            if (!allowPublicPrivate
                && (spec == PUBLIC_VISIBILITY || spec == PRIVATE_VISIBILITY)
            ) {
                throw InvalidPackageSpecificationException(
                    java.lang.String.format(
                        "Use of \"%s\" package specification requires enabling"
                                + " --incompatible_package_group_has_public_syntax",
                        spec
                    )
                )
            }
            var negative = false
            if (spec.startsWith(NEGATIVE_PREFIX)) {
                negative = true
                spec = spec.substring(NEGATIVE_PREFIX.length())
                if (spec == PUBLIC_VISIBILITY || spec == PRIVATE_VISIBILITY) {
                    throw InvalidPackageSpecificationException(
                        java.lang.String.format("Cannot negate \"%s\" package specification", spec)
                    )
                }
            }
            val packageSpecification =
                fromStringPositive(repositoryMapping, repositoryName, spec, repoRootMeansCurrentRepo)
            return if (negative) NegativePackageSpecification(packageSpecification) else packageSpecification
        }

        @Throws(InvalidPackageSpecificationException::class)
        private fun fromStringPositive(
            repositoryMapping: RepositoryMapping?,
            repositoryName: RepositoryName?,
            spec: String,
            repoRootMeansCurrentRepo: Boolean
        ): PackageSpecification {
            if (spec == PUBLIC_VISIBILITY) {
                return AllPackages.Companion.INSTANCE
            } else if (spec == PRIVATE_VISIBILITY) {
                return NoPackages.Companion.INSTANCE
            }

            if (!spec.startsWith("//") && !spec.startsWith("@")) {
                throw InvalidPackageSpecificationException(
                    java.lang.String.format(
                        "invalid package name '%s': must start with '//', '@', or be 'public' or 'private'",
                        spec
                    )
                )
            }

            try {
                val label: Label =
                    Label.parseWithRepoContext(spec, Label.RepoContext.of(repositoryName, repositoryMapping))
                val mappedSpec = fromLabel(label)
                if (mappedSpec != null) {
                    return mappedSpec
                }
            } catch (e: LabelSyntaxException) {
                // Fall through to parse as a package path (e.g. //foo/...)
            }

            var pkgPath: String?
            var allBeneath = false
            if (spec.endsWith(ALL_BENEATH_SUFFIX)) {
                allBeneath = true
                pkgPath = spec.substring(0, spec.length() - ALL_BENEATH_SUFFIX.length())
                if (pkgPath == "/") {
                    // spec was "//...".
                    if (repoRootMeansCurrentRepo) {
                        pkgPath = "//"
                    } else {
                        // Legacy behavior: //... is "public".
                        return AllPackages.Companion.INSTANCE
                    }
                } else if (spec.endsWith("//...")) {
                    // spec was "@repo//..."
                    pkgPath += "/"
                }
            } else {
                pkgPath = spec
            }

            val pkgId: PackageIdentifier
            try {
                pkgId =
                    Label.parseWithRepoContext(
                        pkgPath + ":__pkg__", Label.RepoContext.of(repositoryName, repositoryMapping)
                    )
                        .getPackageIdentifier()
            } catch (e: LabelSyntaxException) {
                throw InvalidPackageSpecificationException(
                    java.lang.String.format("invalid package name '%s': %s", spec, e.getMessage())
                )
            }
            return if (allBeneath) AllPackagesBeneath(pkgId) else SinglePackage(pkgId)
        }

        /**
         * Parses a string to a `PackageSpecification` for use with .bzl load visibility.
         * 
         * 
         * This rejects negative package patterns, and translates the exception type into `EvalException`.
         * 
         * 
         * Note that load visibility package specifications always behave as if `--incompatible_package_group_has_public_syntax` and `--incompatible_fix_package_group_reporoot_syntax` are enabled.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromStringForBzlVisibility(
            repoMapping: RepositoryMapping?, repositoryName: RepositoryName?, spec: String
        ): PackageSpecification? {
            val result: PackageSpecification?
            try {
                result =
                    fromString(
                        repoMapping,
                        repositoryName,
                        spec,  /* allowPublicPrivate= */
                        true,  /* repoRootMeansCurrentRepo= */
                        true
                    )
            } catch (e: InvalidPackageSpecificationException) {
                throw net.starlark.java.eval.EvalException(e.getMessage())
            }
            if (result is NegativePackageSpecification) {
                throw net.starlark.java.eval.Starlark.errorf("Cannot use negative package patterns here")
            }
            return result
        }

        /**
         * Parses the provided [Label] into a [PackageSpecification] specific to the [ ] associated with the label.
         * 
         * 
         * If `label.getName.equals("__pkg__")` then this results in a [ ] that contains exactly the named package.
         * 
         * 
         * If `label.getName.equals("__subpackages__")` then this results in a [ ] that contains all transitive subpackages of that package, inclusive.
         * 
         * 
         * If the label's name is neither "__pkg__" nor "__subpackages__", this returns `null`.
         * 
         * 
         * Note that there is no [Label] associated with the [RepositoryName]-agnostic
         * "public" specification ("//..." under legacy semantics).
         */
        fun fromLabel(label: Label): PackageSpecification? {
            if (label.name.equals(PACKAGE_LABEL)) {
                return SinglePackage(label.getPackageIdentifier())
            } else if (label.name.equals(SUBTREE_LABEL)) {
                return AllPackagesBeneath(label.getPackageIdentifier())
            } else {
                return null
            }
        }

        fun everything(): PackageSpecification {
            return AllPackages.Companion.INSTANCE
        }

        fun nothing(): PackageSpecification {
            return NoPackages.Companion.INSTANCE
        }
    }
}
