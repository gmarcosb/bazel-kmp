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
package com.google.devtools.common.options

/**
 * The representation of a parsed option instance.
 * 
 * 
 * An option instance is distinct from the final value of an option, as multiple instances
 * provide values may be overridden or combined in some way.
 */
class ParsedOptionDescription private constructor(
  optionDefinition: com.google.devtools.common.options.OptionDefinition?,
  @kotlin.jvm.JvmField private val commandLineForm: String?,
  @kotlin.jvm.JvmField private val unconvertedValue: String?,
  origin: com.google.devtools.common.options.OptionInstanceOrigin?,
  private val conversionContext: Any?,
  private val oldNameUsed: Boolean
) {
    private val optionDefinition: com.google.devtools.common.options.OptionDefinition
    private val origin: com.google.devtools.common.options.OptionInstanceOrigin

    init {
        this.optionDefinition =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.OptionDefinition>(
                optionDefinition
            )
        this.origin =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.OptionInstanceOrigin>(
                origin
            )
    }

    fun getOptionDefinition(): com.google.devtools.common.options.OptionDefinition {
        return optionDefinition
    }

    fun getCommandLineForm(): String? {
        return commandLineForm
    }

    fun getCanonicalForm(): String {
        return getCanonicalFormWithValueEscaper(java.util.function.Function { s: String? -> s })
    }

    fun getCanonicalFormWithValueEscaper(escapingFunction: java.util.function.Function<String?, String?>): String {
        // For boolean flags (note that here we do not check for TriState flags, only flags with actual
        // boolean values, so that we know the return type of getConvertedValue), use the --[no]flag
        // form for the canonical value.
        if (optionDefinition.getType() == Boolean::class.javaPrimitiveType) {
            try {
                return (if (getConvertedValue() as Boolean) "--" else "--no") + optionDefinition.getOptionName()
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw java.lang.RuntimeException("Unexpected parsing exception", e)
            }
        } else {
            var optionString = "--" + optionDefinition.getOptionName()
            if (unconvertedValue != null) { // Can be null for Void options.
                optionString += "=" + escapingFunction.apply(unconvertedValue)
            }
            return optionString
        }
    }

    @Deprecated("")
    fun getDeprecatedCanonicalForm(): String? {
        var value = unconvertedValue
        // For boolean flags (note that here we do not check for TriState flags, only flags with actual
        // boolean values, so that we know the return type of getConvertedValue), set them all to 1 or
        // 0, instead of keeping the wide variety of values we accept in their original form.
        if (optionDefinition.getType() == Boolean::class.javaPrimitiveType) {
            try {
                value = if (getConvertedValue() as Boolean) "1" else "0"
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw java.lang.RuntimeException("Unexpected parsing exception", e)
            }
        }
        return java.lang.String.format("--%s=%s", optionDefinition.getOptionName(), value)
    }

    private fun documentationCategory(): com.google.devtools.common.options.OptionDocumentationCategory? {
        return optionDefinition.getDocumentationCategory()
    }

    private fun metadataTags(): com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionMetadataTag?> {
        return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.common.options.OptionMetadataTag?>(
            optionDefinition.getOptionMetadataTags()
        )
    }

    fun isDocumented(): Boolean {
        return documentationCategory() != com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED && !isHidden()
    }

    fun isHidden(): Boolean {
        val tags: com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionMetadataTag?> =
            metadataTags()
        return tags.contains(com.google.devtools.common.options.OptionMetadataTag.HIDDEN) || tags.contains(com.google.devtools.common.options.OptionMetadataTag.INTERNAL)
    }

    fun getUnconvertedValue(): String? {
        return unconvertedValue
    }

    fun getOrigin(): com.google.devtools.common.options.OptionInstanceOrigin {
        return origin
    }

    fun getPriority(): com.google.devtools.common.options.OptionPriority? {
        return origin.getPriority()
    }

    fun isOldNameUsed(): Boolean {
        return oldNameUsed
    }

    fun getSource(): String? {
        return origin.getSource()
    }

    fun getImplicitDependent(): ParsedOptionDescription? {
        return origin.getImplicitDependent()
    }

    fun getExpandedFrom(): ParsedOptionDescription? {
        return origin.getExpandedFrom()
    }

    fun isExplicit(): Boolean {
        return origin.getExpandedFrom() == null && origin.getImplicitDependent() == null // Exclude options from PROJECT.scl files, which are not considered explicit.
                && !(origin.getSource() != null && origin.getSource().endsWith("PROJECT.scl"))
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun getConvertedValue(): Any? {
        val converter: com.google.devtools.common.options.Converter<*> = optionDefinition.getConverter()
        try {
            return converter.convert(unconvertedValue, conversionContext)
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            // The converter doesn't know the option name, so we supply it here by re-throwing:
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.String.format("While parsing option %s: %s", commandLineForm, e.getMessage()), e
            )
        }
    }

    override fun toString(): String {
        // Check that a dummy value-less option instance does not output all the default information.
        if (commandLineForm == null) {
            return optionDefinition.toString()
        }
        val source: String? = origin.getSource()
        return java.lang.String.format(
            "option '%s'%s",
            commandLineForm, if (source == null) "" else java.lang.String.format(" (source %s)", source)
        )
    }

    companion object {
        fun newParsedOptionDescription(
            optionDefinition: com.google.devtools.common.options.OptionDefinition?,
            commandLineForm: String?,
            unconvertedValue: String?,
            origin: com.google.devtools.common.options.OptionInstanceOrigin?,
            conversionContext: Any?
        ): ParsedOptionDescription {
            // An actual ParsedOptionDescription should always have a form in which it was parsed, but some
            // options, such as expansion options, legitimately have no value.
            return com.google.devtools.common.options.ParsedOptionDescription(
                optionDefinition,
                com.google.common.base.Preconditions.checkNotNull<String?>(commandLineForm),
                unconvertedValue,
                origin,
                conversionContext,
                false
            )
        }

        fun newParsedOptionDescription(
            optionDefinition: com.google.devtools.common.options.OptionDefinition?,
            commandLineForm: String?,
            unconvertedValue: String?,
            origin: com.google.devtools.common.options.OptionInstanceOrigin?,
            conversionContext: Any?,
            oldNameUsed: Boolean
        ): ParsedOptionDescription {
            // An actual ParsedOptionDescription should always have a form in which it was parsed, but some
            // options, such as expansion options, legitimately have no value.
            return com.google.devtools.common.options.ParsedOptionDescription(
                optionDefinition,
                com.google.common.base.Preconditions.checkNotNull<String?>(commandLineForm),
                unconvertedValue,
                origin,
                conversionContext,
                oldNameUsed
            )
        }

        /**
         * This factory should be used when there is no actual parsed option, since in those cases we do
         * not have an original value or form that the option took.
         */
        fun newDummyInstance(
            optionDefinition: com.google.devtools.common.options.OptionDefinition?,
            origin: com.google.devtools.common.options.OptionInstanceOrigin?,
            conversionContext: Any?
        ): ParsedOptionDescription {
            return com.google.devtools.common.options.ParsedOptionDescription(
                optionDefinition, null, null, origin, conversionContext, false
            )
        }
    }
}
