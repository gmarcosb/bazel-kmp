// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.apple

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.common.collect.ComparisonChain
import com.google.common.collect.ImmutableList
import com.google.common.collect.Ordering
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.starlarkbuildapi.apple.DottedVersionApi
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Printer
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkValue
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Represents Xcode versions and allows parsing them.
 * 
 * 
 * Xcode versions are formed of multiple components, separated by periods, for example `4.5.6` or `5.0.1beta2`. Components must start with a non-negative integer and at least one
 * component must be present.
 * 
 * 
 * Specifically, the format of a component is `\d+([a-z0-9]*?)?(\d+)?`.
 * 
 * 
 * If this smells a lot like semver, it does, but Xcode versions are sometimes special. This is
 * why this class is in the `apple` package and has to remain as such.
 * 
 * 
 * Dotted versions are ordered using natural integer sorting on components in order from first to
 * last where any missing element is considered to have the value 0 if they don't contain any
 * non-numeric characters. For example:
 * 
 * <pre>
 * 3.1.25 > 3.1.1
 * 3.1.20 > 3.1.2
 * 3.1.1 > 3.1
 * 3.1 == 3.1.0.0
 * 3.2 > 3.1.8
</pre> * 
 * 
 * 
 * If the component contains any alphabetic characters after the leading integer, it is
 * considered **smaller** than any components with the same integer but larger than any
 * component with a smaller integer. If the integers are the same, the alphabetic sequences are
 * compared lexicographically, and if *they* turn out to be the same, the final (optional)
 * integer is compared. As with the leading integer, this final integer is considered to be 0 if not
 * present. For example:
 * 
 * <pre>
 * 3.1.1 > 3.1.1beta3
 * 3.1.1beta1 > 3.1.0
 * 3.1 > 3.1.0alpha1
 * 
 * 3.1.0beta0 > 3.1.0alpha5.6
 * 3.4.2alpha2 > 3.4.2alpha1
 * 3.4.2alpha2 > 3.4.2alpha1.5
 * 3.1alpha1 > 3.1alpha
</pre> * 
 * 
 * 
 * This class is immutable and can safely be shared among threads.
 */
@ThreadSafety.Immutable
class DottedVersion private constructor(
    private val components: ImmutableList<Component>,
    private val stringRepresentation: String,
    private val numOriginalComponents: Int
) : DottedVersionApi<DottedVersion?> {
    /**
     * Wrapper class for [DottedVersion] whose [.equals] method is string
     * equality.
     * 
     * 
     * This is necessary because Bazel assumes that [ ] that are equal yield fragments
     * that are the same. However, this does not hold if the options hold a [DottedVersion]
     * because trailing zeroes are not considered significant when comparing them, but they do matter
     * in configuration fragments (for example, they end up in output directory names).
     * 
     * 
     * When read from the `settings` dictionary in a Starlark transition function, these
     * values are effectively opaque and need to be converted to strings for further use, such as
     * comparing them by passing the string to `apple_common.dotted_version` to construct an
     * instance of the actual version object.
     */
    @ThreadSafety.Immutable
    class Option private constructor(version: DottedVersion?) : StarlarkValue {
        private val version: DottedVersion

        init {
            this.version = Preconditions.checkNotNull<DottedVersion>(version)
        }

        fun get(): DottedVersion {
            return version
        }

        override fun isImmutable(): Boolean {
            return true
        }

        override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
            printer.append(version.toString())
        }

        override fun toString(): String {
            return version.toString()
        }

        override fun hashCode(): Int {
            return version.stringRepresentation.hashCode()
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }

            if (o !is Option) {
                return false
            }

            return version.stringRepresentation == o.version.stringRepresentation
        }
    }

    /** Exception thrown when parsing an invalid dotted version.  */
    class InvalidDottedVersionException : Exception {
        internal constructor(msg: String?) : super(msg)

        internal constructor(msg: String?, cause: Throwable?) : super(msg, cause)
    }

    override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    override fun compareTo(other: DottedVersion): Int {
        val maxComponents: Int = max(components.size, other.components.size)
        for (componentIndex in 0..<maxComponents) {
            val myComponent = getComponent(componentIndex)
            val otherComponent = other.getComponent(componentIndex)
            val comparison = myComponent.compareTo(otherComponent)
            if (comparison != 0) {
                return comparison
            }
        }
        return 0
    }

    override fun compareTo_starlark(other: DottedVersion): Int {
        return compareTo(other)
    }

    /**
     * Returns the string representation of this dotted version, padded or truncated to the specified
     * number of components.
     * 
     * 
     * For example, a dotted version of "7.3.0" will return "7" if one is requested, "7.3" if two
     * are requested, "7.3.0" if three are requested, and "7.3.0.0" if four are requested.
     * 
     * @param numComponents a positive number of dot-separated numbers that should be present in the
     * returned string representation
     */
    fun toStringWithComponents(numComponents: Int): String {
        Preconditions.checkArgument(
            numComponents > 0,
            "Can't serialize as a version with %s components", numComponents
        )
        val stringComponents = ImmutableList.builder<Component?>()
        if (numComponents <= components.size) {
            stringComponents.addAll(components.subList(0, numComponents))
        } else {
            stringComponents.addAll(components)
            for (i in components.size..<numComponents) {
                stringComponents.add(ZERO_COMPONENT)
            }
        }
        return Joiner.on('.').join(stringComponents.build())
    }

    /**
     * Returns the string representation of this dotted version, padded to a minimum number of
     * components if the string representation does not already contain that many components.
     * 
     * 
     * For example, a dotted version of "7.3" will return "7.3" with either one or two components
     * requested, "7.3.0" if three are requested, and "7.3.0.0" if four are requested.
     * 
     * 
     * Trailing zero components at the end of a string representation will not be removed. For
     * example, a dotted version of "1.0.0" will return "1.0.0" if only one or two components are
     * requested.
     * 
     * @param numMinComponents the minimum number of dot-separated numbers that should be present in
     * the returned string representation
     */
    fun toStringWithMinimumComponents(numMinComponents: Int): String {
        return toStringWithComponents(max(this.numOriginalComponents, numMinComponents))
    }

    /**
     * Returns true if this version number has any alphabetic characters, such as 'alpha' in
     * "7.3alpha.2".
     */
    fun hasAlphabeticCharacters(): Boolean {
        for (component in components) {
            if (component.alphaSequence != NO_ALPHA_SEQUENCE) {
                return true
            }
        }
        return false
    }

    /**
     * Returns the number of components in this version number. For example, "7.3.0" has three
     * components.
     */
    fun numComponents(): Int {
        return components.size
    }

    @StarlarkMethod(
        name = "to_string",
        doc = "Returns the string representation of a dotted version.",
        structField = true
    )
    override fun toString(): String {
        return stringRepresentation
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }

        return compareTo(other as DottedVersion) == 0
    }

    override fun hashCode(): Int {
        return Objects.hash(components)
    }

    private fun getComponent(groupIndex: Int): Component {
        if (components.size > groupIndex) {
            return components.get(groupIndex)
        }
        return ZERO_COMPONENT
    }

    override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
        printer.append(stringRepresentation)
    }

    private class Component(
        private val firstNumber: Int,
        private val alphaSequence: String?,
        private val secondNumber: Int,
        private val stringRepresentation: String?
    ) : Comparable<Component?> {
        override fun compareTo(other: Component): Int {
            return ComparisonChain.start()
                .compare(firstNumber, other.firstNumber)
                .compare<String?>(
                    alphaSequence,
                    other.alphaSequence,
                    Ordering.natural<Comparable<*>?>().nullsLast<String?>()
                )
                .compare(secondNumber, other.secondNumber)
                .result()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null || javaClass != other.javaClass) {
                return false
            }

            return compareTo(other as Component) == 0
        }

        override fun hashCode(): Int {
            return Objects.hash(firstNumber, alphaSequence, secondNumber)
        }

        override fun toString(): String {
            return stringRepresentation!!
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun maybeUnwrap(option: Option?): DottedVersion? {
            return if (option != null) option.get() else null
        }

        fun option(version: DottedVersion?): Option? {
            return if (version == null) null else DottedVersion.Option(version)
        }

        private val DOT_SPLITTER: Splitter = Splitter.on('.')
        private val COMPONENT_PATTERN: Pattern = Pattern.compile("(\\d+)([a-z0-9]*?)?(\\d+)?", Pattern.CASE_INSENSITIVE)
        private val DESCRIPTIVE_COMPONENT_PATTERN: Pattern = Pattern.compile("([a-z]\\w*)", Pattern.CASE_INSENSITIVE)
        private val ILLEGAL_VERSION =
            ("Dotted version components must all start with the form \\d+([a-z0-9]*?)?(\\d+)? "
                    + "but got '%s'")
        private val NO_ALPHA_SEQUENCE: String? = null
        private val ZERO_COMPONENT = Component(0, NO_ALPHA_SEQUENCE, 0, "0")

        /**
         * Create a dotted version by parsing the given version string. Throws an unchecked exception if
         * the argument is malformed.
         */
        @kotlin.jvm.JvmStatic
        fun fromStringUnchecked(version: String?): DottedVersion {
            try {
                return fromString(version)
            } catch (e: InvalidDottedVersionException) {
                throw IllegalArgumentException(e)
            }
        }

        /**
         * Generates a new dotted version from the given version string.
         * 
         * @throws InvalidDottedVersionException if the passed string is not a valid dotted version
         */
        @kotlin.jvm.JvmStatic
        @Throws(InvalidDottedVersionException::class)
        fun fromString(version: String?): DottedVersion {
            if (Strings.isNullOrEmpty(version)) {
                throw InvalidDottedVersionException(String.format(ILLEGAL_VERSION, version))
            }
            val components = ArrayList<Component?>()
            for (component in DOT_SPLITTER.split(version)) {
                if (isDescriptiveComponent(component)) {
                    break
                }
                components.add(toComponent(component, version))
            }

            if (components.isEmpty()) {
                throw InvalidDottedVersionException(String.format(ILLEGAL_VERSION, version))
            }

            val numOriginalComponents = components.size

            // Remove trailing (but not the first or middle) zero components for easier comparison and
            // hashcoding.
            for (i in components.size - 1 downTo 1) {
                if (components.get(i) == ZERO_COMPONENT) {
                    components.removeAt(i)
                } else {
                    break
                }
            }

            return DottedVersion(ImmutableList.copyOf<Component?>(components), version!!, numOriginalComponents)
        }

        // Some of special build versions contains descriptive components like "experimental" or
        // "internal". These components are usually by the end of version number, and can be ignored.
        private fun isDescriptiveComponent(component: String?): Boolean {
            return DESCRIPTIVE_COMPONENT_PATTERN.matcher(component).matches()
        }

        @Throws(InvalidDottedVersionException::class)
        private fun toComponent(component: String?, version: String?): Component {
            val parsedComponent: Matcher = COMPONENT_PATTERN.matcher(component)
            if (!parsedComponent.matches()) {
                throw InvalidDottedVersionException(String.format(ILLEGAL_VERSION, version))
            }

            val firstNumber: Int
            var alphaSequence: String? = NO_ALPHA_SEQUENCE
            var secondNumber = 0
            firstNumber = parseNumber(parsedComponent, 1, version)

            if (!Strings.isNullOrEmpty(parsedComponent.group(2))) {
                alphaSequence = parsedComponent.group(2)
            }

            if (!Strings.isNullOrEmpty(parsedComponent.group(3))) {
                secondNumber = parseNumber(parsedComponent, 3, version)
            }

            return Component(firstNumber, alphaSequence, secondNumber, component)
        }

        @Throws(InvalidDottedVersionException::class)
        private fun parseNumber(parsedComponent: Matcher, group: Int, version: String?): Int {
            val firstNumber: Int
            try {
                firstNumber = parsedComponent.group(group).toInt()
            } catch (e: NumberFormatException) {
                throw InvalidDottedVersionException(String.format(ILLEGAL_VERSION, version), e)
            }
            return firstNumber
        }
    }
}
