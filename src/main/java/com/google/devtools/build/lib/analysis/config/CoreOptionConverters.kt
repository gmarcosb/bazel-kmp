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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.packages.BuildType.LABEL

/**
 * [Converter]s for [com.google.devtools.common.options.Option]s that aren't
 * domain-specific (i.e. aren't consumed within a single [FragmentOptions]).
 */
object CoreOptionConverters {
    /**
     * The name of the flag used for shorthand aliasing in blaze. [ ][com.google.devtools.build.lib.analysis.config.CoreOptions.commandLineFlagAliases] for the
     * option definition.
     */
    const val BLAZE_ALIASING_FLAG: String = "flag_alias"

    /**
     * The set of converters used for [com.google.devtools.build.lib.packages.BuildSetting]
     * value parsing.
     */
    val BUILD_SETTING_CONVERTERS: com.google.common.collect.ImmutableMap<Type<*>?, Converter<*>?> =
        com.google.common.collect.ImmutableMap.Builder<Type<*>?, Converter<*>?>()
            .put(INTEGER, StarlarkIntConverter())
            .put(BOOLEAN, BooleanConverter())
            .put(STRING, StringConverter())
            .put(STRING_LIST, CommaSeparatedOptionListConverter())
            .put(STRING_SET, StringSetConverter())
            .put(LABEL, LabelConverter())
            .put(LABEL_LIST, LabelListConverter())
            .put(NODEP_LABEL, LabelConverter())
            .buildOrThrow()

    @Throws(OptionsParsingException::class)
    private fun convertOptionsLabel(input: String, conversionContext: Any?): Label {
        var input = input
        try {
            if (conversionContext is Label.PackageContext) {
                // This can happen if this converter is being used to convert flag values specified in
                // Starlark, for example in a transition implementation function.
                return Label.parseWithPackageContext(input, conversionContext as Label.PackageContext?)
            }
            // Check if the input starts with '/'. We don't check for "//" so that
            // we get a better error message if the user accidentally tries to use
            // an absolute path (starting with '/') for a label.
            if (!input.startsWith("/") && !input.startsWith("@")) {
                input = "//" + input
            }
            if (conversionContext == null) {
                // This can happen in the first round of option parsing, before repo mappings are
                // calculated. In this case, it actually doesn't matter how we parse label-typed flags, as
                // they shouldn't be used anywhere anyway.
                return Label.parseCanonical(input)
            }
            com.google.common.base.Preconditions.checkArgument(
                conversionContext is RepositoryMapping,
                "bad conversion context type: %s",
                conversionContext.getClass().getName()
            )
            // This can happen in the second round of option parsing.
            return Label.parseWithRepoContext(
                input, Label.RepoContext.of(RepositoryName.MAIN, conversionContext as RepositoryMapping)
            )
        } catch (e: LabelSyntaxException) {
            throw OptionsParsingException(e.getMessage())
        }
    }

    /**
     * A converter for comma-separated strings to sets of strings. This uses [ ] but returns a sorted set of the converted strings.
     */
    private class StringSetConverter

        : Contextless<com.google.common.collect.ImmutableSortedSet<String?>?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): com.google.common.collect.ImmutableSortedSet<String?> {
            val result: com.google.common.collect.ImmutableList<String?> =
                com.google.devtools.build.lib.analysis.config.CoreOptionConverters.StringSetConverter.Companion.COMMA_SEPARATED_OPTION_LIST_CONVERTER.convert(
                    input
                )
            return com.google.common.collect.ImmutableSortedSet.copyOf<String?>(result)
        }

        public override fun getTypeDescription(): String {
            return "comma-separated set of strings"
        }

        companion object {
            private val COMMA_SEPARATED_OPTION_LIST_CONVERTER: CommaSeparatedOptionListConverter =
                CommaSeparatedOptionListConverter()
        }
    }

    /** A converter from strings to Starlark int values.  */
    private class StarlarkIntConverter : Contextless<StarlarkInt?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): StarlarkInt {
            // Note that Starlark rule attribute values are currently restricted
            // to the signed 32-bit range, but Starlark-based flags may take on
            // any integer value.
            try {
                return StarlarkInt.parse(input, 0)
            } catch (ex: java.lang.NumberFormatException) {
                throw OptionsParsingException("invalid int: " + ex.getMessage())
            }
        }

        public override fun getTypeDescription(): String {
            return "an int"
        }
    }

    /** A converter from strings to Labels.  */
    class LabelConverter : Converter<Label?> {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String, conversionContext: Any?): Label {
            return convertOptionsLabel(input, conversionContext)
        }

        public override fun getTypeDescription(): String {
            return "a build target label"
        }
    }

    /** A converter from comma-separated strings to Label lists.  */
    open class LabelListConverter : Converter<MutableList<Label?>?> {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String, conversionContext: Any?): MutableList<Label> {
            val result: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()
            for (label in com.google.common.base.Splitter.on(",").omitEmptyStrings().split(input)) {
                result.add(convertOptionsLabel(label, conversionContext))
            }
            return result.build()
        }

        public override fun getTypeDescription(): String {
            return "a build target label"
        }
    }

    /**
     * A converter from comma-separated strings to Labels which preserves order, but in case of
     * duplicates, keeps only the first copy.
     */
    class LabelOrderedSetConverter : LabelListConverter() {
        @Throws(OptionsParsingException::class)
        override fun convert(input: String, conversionContext: Any?): MutableList<Label?> {
            val alreadySeen: MutableSet<Label?> = HashSet<Label?>()
            val result: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()
            for (label in super.convert(input, conversionContext)) {
                if (alreadySeen.add(label)) {
                    result.add(label)
                }
            }
            return result.build()
        }
    }

    /**
     * A converter that returns null if the input string is empty, otherwise it converts the input to
     * a label.
     */
    class EmptyToNullLabelConverter : Converter<Label?> {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String, conversionContext: Any?): Label? {
            return if (input.isEmpty()) null else convertOptionsLabel(input, conversionContext)
        }

        public override fun getTypeDescription(): String {
            return "a build target label"
        }
    }

    /** Flag converter for a map of unique keys with optional labels as values.  */
    class LabelMapConverter : Converter<MutableMap<String?, Label?>?> {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String, conversionContext: Any?): MutableMap<String?, Label?> {
            // Use LinkedHashMap so we can report duplicate keys more easily while preserving order
            val result: MutableMap<String?, Label?> = LinkedHashMap<String?, Label?>()
            for (entry in com.google.common.base.Splitter.on(",").omitEmptyStrings().trimResults().split(input)) {
                val key: String?
                val label: Label?
                val sepIndex: Int = entry.indexOf('='.code)
                if (sepIndex < 0) {
                    key = entry
                    label = null
                } else {
                    key = entry.substring(0, sepIndex)
                    val value: String = entry.substring(sepIndex + 1)
                    label = if (value.isEmpty()) null else convertOptionsLabel(value, conversionContext)
                }
                if (result.containsKey(key)) {
                    throw OptionsParsingException("Key '" + key + "' appears twice")
                }
                result.put(key, label)
            }
            return Collections.unmodifiableMap<String?, Label?>(result)
        }

        public override fun starlarkConvertible(): Boolean {
            return true
        }

        public override fun reverseForStarlark(converted: Any?): String? {
            val typedValue: MutableMap<String?, Label?> = converted as MutableMap<String?, Label?>
            return typedValue.entrySet().stream()
                .map<String?>(
                    java.util.function.Function { e: MutableMap.MutableEntry<String?, Label?>? ->
                        if (e.getValue() == null)
                            e.getKey()
                        else
                            java.lang.String.format("%s=%s", e.getKey(), e.getValue())
                    })
                .collect(Collectors.joining(","))
        }

        public override fun getTypeDescription(): String {
            return "a comma-separated list of keys optionally followed by '=' and a label"
        }
    }

    /** Flag converter for assigning a Label to a String.  */
    class LabelToStringEntryConverter : Converter<MutableMap.MutableEntry<Label?, String?>?> {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String, conversionContext: Any?): MutableMap.MutableEntry<Label?, String?> {
            // TODO(twigg): This doesn't work well if the labels can themselves have an '='
            val equalsCount: Long = input.chars().filter(IntPredicate { c: Int -> c == '='.code }).count()
            if (equalsCount != 1L || input.charAt(0) == '=' || input.charAt(input.length() - 1) == '=') {
                throw OptionsParsingException(
                    "Variable definitions must be in the form of a 'name=value' assignment. 'name' and"
                            + " 'value' must be non-empty and may not include '='."
                )
            }
            val pos: Int = input.indexOf("=")
            val name: Label = convertOptionsLabel(input.substring(0, pos), conversionContext)
            val value: String = input.substring(pos + 1)
            return com.google.common.collect.Maps.immutableEntry<Label?, String?>(name, value)
        }

        public override fun getTypeDescription(): String {
            return "a 'label=value' assignment"
        }
    }

    /**
     * Flag converter for canonicalizing a label (possibly with a "/..." suffix) and/or define by
     * converting the label to unambiguous canonical form.
     */
    class CustomFlagConverter : Converter<String?> {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String, conversionContext: Any?): String {
            if (!input.startsWith("//") && !input.startsWith("@")) {
                // This is a --define flag.
                return input
            }
            // A "/..." suffix is not valid label syntax, so replace it with arbitrary valid syntax and
            // transform it back after conversion.
            val invalidSubpackagesSuffix = SUBPACKAGES_SUFFIX
            val validSubpackagesSuffix = ":__subpackages__"
            val escapedUnconvertedLabel =
                if (input.endsWith(invalidSubpackagesSuffix))
                    (input.substring(0, input.length() - invalidSubpackagesSuffix.length())
                            + validSubpackagesSuffix)
                else
                    input
            val escapedConvertedLabel: String =
                convertOptionsLabel(escapedUnconvertedLabel, conversionContext)
                    .getUnambiguousCanonicalForm()
            if (escapedConvertedLabel.endsWith(validSubpackagesSuffix)) {
                return (escapedConvertedLabel.substring(
                    0, escapedConvertedLabel.length() - validSubpackagesSuffix.length()
                )
                        + invalidSubpackagesSuffix)
            }
            return escapedConvertedLabel
        }

        public override fun getTypeDescription(): String {
            return "an absolute label or define"
        }

        companion object {
            const val SUBPACKAGES_SUFFIX: String = "/..."
        }
    }

    /** Values for the --strict_*_deps option  */
    enum class StrictDepsMode {
        /** Silently allow referencing transitive dependencies.  */
        OFF,

        /** Warn about transitive dependencies being used directly.  */
        WARN,

        /** Fail the build when transitive dependencies are used directly.  */
        ERROR,

        /** Transition to strict by default.  */
        STRICT,

        /** When no flag value is specified on the command line.  */
        DEFAULT
    }

    /** Converter for the --strict_*_deps option.  */
    class StrictDepsConverter :
        EnumConverter<StrictDepsMode?>(StrictDepsMode::class.java, "strict dependency checking level")

    /**
     * A converter for command line flag aliases. It does additional validation on the name and value
     * of the assignment to ensure they conform to the naming limitations.
     */
    class FlagAliasConverter : Converter<MutableMap.MutableEntry<String?, Label?>?> {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String, conversionContext: Any?): MutableMap.MutableEntry<String?, Label?> {
            val pos: Int = input.indexOf("=")
            if (pos <= 0) {
                throw OptionsParsingException(
                    "Flag alias definitions must be in the form of a 'name=label' assignment"
                )
            }
            val shortForm: String = input.substring(0, pos)
            val longForm: String = input.substring(pos + 1)

            val cmdLineAlias = "--" + BLAZE_ALIASING_FLAG + "=" + input

            if (!java.util.regex.Pattern.matches("\\w*", shortForm)) {
                throw OptionsParsingException(
                    shortForm + " should only consist of word characters to be a valid alias name.",
                    cmdLineAlias
                )
            }
            if (longForm.contains("=")) {
                throw OptionsParsingException(
                    "--" + BLAZE_ALIASING_FLAG + " does not support flag value assignment.", cmdLineAlias
                )
            }

            // Remove this check if native options are permitted to be aliased
            val longFormWithDashes = "--" + longForm
            if (STARLARK_SKIPPED_PREFIXES.stream().noneMatch(longFormWithDashes::startsWith)) {
                throw OptionsParsingException(
                    "--" + BLAZE_ALIASING_FLAG + " only supports Starlark build settings.", cmdLineAlias
                )
            }

            return com.google.common.collect.Maps.immutableEntry<String?, Label?>(
                shortForm,
                convertOptionsLabel(longForm, conversionContext)
            )
        }

        public override fun getTypeDescription(): String {
            return "a 'name=label' flag alias"
        }
    }
}
