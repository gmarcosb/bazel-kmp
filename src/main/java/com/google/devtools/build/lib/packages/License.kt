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

/** Support for license and distribution checking.  */
@Immutable
@ThreadSafe
class License private constructor(
    licenseTypes: com.google.common.collect.ImmutableSet<LicenseType?>,
    exceptions: com.google.common.collect.ImmutableSet<Label?>
) : LicenseApi {
    private val licenseTypes: com.google.common.collect.ImmutableSet<LicenseType?>
    private val exceptions: com.google.common.collect.ImmutableSet<Label?>

    /**
     * The error that's thrown if a build file contains an invalid license string.
     */
    class LicenseParsingException internal constructor(s: String?) : java.lang.Exception(s)

    /**
     * LicenseType is the basis of the License lattice - stricter licenses should
     * be declared before less-strict licenses in the enum.
     * 
     * 
     * Note that the order is important for the purposes of finding the least
     * restrictive license.
     */
    enum class LicenseType {
        BY_EXCEPTION_ONLY,
        RESTRICTED,
        RESTRICTED_IF_STATICALLY_LINKED,
        RECIPROCAL,
        NOTICE,
        PERMISSIVE,
        UNENCUMBERED,
        NONE
    }

    init {
        // Defensive copy is done in .of()
        this.licenseTypes = licenseTypes
        this.exceptions = exceptions
    }

    /**
     * @return an immutable set of [LicenseType]s contained in this `License`
     */
    fun getLicenseTypes(): MutableSet<LicenseType?> {
        return licenseTypes
    }

    /**
     * @return an immutable set of [Label]s that describe exceptions to the
     * `License`
     */
    fun getExceptions(): MutableSet<Label?> {
        return exceptions
    }

    fun isSpecified(): Boolean {
        return this !== NO_LICENSE
    }

    /**
     * A simple toString implementation which generates a canonical form of the
     * license. (The order of license types is guaranteed to be canonical by
     * EnumSet, and the order of exceptions is guaranteed to be lexicographic
     * order by TreeSet.)
     */
    override fun toString(): String {
        if (exceptions.isEmpty()) {
            return com.google.common.base.Ascii.toLowerCase(licenseTypes.toString())
        } else {
            return com.google.common.base.Ascii.toLowerCase(licenseTypes.toString()) + " with exceptions " + exceptions
        }
    }

    /**
     * A simple equals implementation leveraging the support built into Set that
     * delegates to its contents.
     */
    override fun equals(o: Any?): Boolean {
        return o === this || (o is License
                && o.licenseTypes == this.licenseTypes
                && o.exceptions == this.exceptions)
    }

    /**
     * A simple hashCode implementation leveraging the support built into Set that
     * delegates to its contents.
     */
    override fun hashCode(): Int {
        return licenseTypes.hashCode() * 43 + exceptions.hashCode()
    }

    override fun isImmutable(): Boolean {
        return true // licences are Starlark-hashable
    }

    /**
     * Represents the License as a canonically ordered list of strings that can be parsed by [ ][License.parseLicense] to get back an equal License.
     */
    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        // The order of license types is guaranteed to be canonical by EnumSet, and the order of
        // exceptions is guaranteed to be lexicographic order by TreeSet.
        printer.printList(
            java.util.stream.Stream.concat<String?>(
                licenseTypes.stream().map<String?>(java.util.function.Function { licenseType: LicenseType? ->
                    com.google.common.base.Ascii.toLowerCase(licenseType.toString())
                }),
                exceptions.stream()
                    .map<String?>(java.util.function.Function { label: Label? -> EXCEPTION_PREFIX + label.getCanonicalForm() })
            )
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>()),
            "[",
            ", ",
            "]",
            semantics
        )
    }

    companion object {
        private const val EXCEPTION_PREFIX = "exception="

        /**
         * Gets the least restrictive license type from the list of licenses declared for a target. For
         * the purposes of license checking, the license type set of a declared license can be reduced to
         * its least restrictive member.
         * 
         * @param types a collection of license types
         * @return the least restrictive license type
         */
        fun leastRestrictive(types: MutableCollection<LicenseType?>): LicenseType? {
            // TODO(gregce): move this method to LicenseCheckingModule when Bazel's tests no longer use it
            return if (types.isEmpty()) com.google.devtools.build.lib.packages.License.LicenseType.BY_EXCEPTION_ONLY else Collections.max<LicenseType?>(
                types
            )
        }

        /**
         * An instance of LicenseType.None with no exceptions, used for packages outside of third_party
         * which have no license clause in their BUILD files.
         */
        @kotlin.jvm.JvmField
        val NO_LICENSE: License = License(
            com.google.common.collect.ImmutableSet.of<LicenseType?>(com.google.devtools.build.lib.packages.License.LicenseType.NONE),
            com.google.common.collect.ImmutableSet.of<Label?>()
        )

        fun of(licenses: MutableCollection<LicenseType?>, exceptions: MutableCollection<Label?>): License? {
            val licenseSet: com.google.common.collect.ImmutableSet<LicenseType?> =
                com.google.common.collect.ImmutableSet.copyOf<LicenseType?>(licenses)
            val exceptionSet: com.google.common.collect.ImmutableSet<Label?> =
                com.google.common.collect.ImmutableSet.copyOf<Label?>(exceptions)

            if (exceptionSet.isEmpty() && licenseSet == com.google.common.collect.ImmutableSet.of<LicenseType?>(com.google.devtools.build.lib.packages.License.LicenseType.NONE)) {
                return NO_LICENSE
            }

            return License(licenseSet, exceptionSet)
        }

        /**
         * Computes a license which can be used to check if a package is compatible
         * with some kinds of distribution. The list of licenses is scanned for the
         * least restrictive, and the exceptions are added.
         * 
         * @param licStrings the list of license strings declared for the package
         * @throws LicenseParsingException if there are any parsing problems
         */
        @Throws(LicenseParsingException::class)
        fun parseLicense(licStrings: MutableList<String>): License? {
            /*
     * The semantics of comparison for licenses depends on a stable iteration
     * order for both license types and exceptions. For licenseTypes, it will be
     * the comparison order from the enumerated types; for exceptions, it will
     * be lexicographic order achieved using TreeSets.
     */
            val licenseTypes: MutableSet<LicenseType?> =
                EnumSet.noneOf<LicenseType?>(com.google.devtools.build.lib.packages.License.LicenseType::class.java)
            val exceptions: MutableSet<Label?> = com.google.common.collect.Sets.newTreeSet<Label?>()
            for (str in licStrings) {
                if (str.startsWith(EXCEPTION_PREFIX)) {
                    try {
                        val label: Label? = Label.parseCanonical(str.substring(EXCEPTION_PREFIX.length()))
                        exceptions.add(label)
                    } catch (e: LabelSyntaxException) {
                        throw LicenseParsingException(e.getMessage())
                    }
                } else {
                    try {
                        licenseTypes.add(
                            com.google.devtools.build.lib.packages.License.LicenseType.valueOf(
                                str.toUpperCase(
                                    Locale.ENGLISH
                                )
                            )
                        )
                    } catch (e: java.lang.IllegalArgumentException) {
                        throw LicenseParsingException("invalid license type: '" + str + "'")
                    }
                }
            }

            return of(licenseTypes, exceptions)
        }
    }
}
