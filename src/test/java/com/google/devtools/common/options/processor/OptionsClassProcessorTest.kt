// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options.processor

import com.google.common.truth.Truth
import com.google.devtools.common.options.processor.OptionsClassProcessor
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourceSubjectFactory
import com.google.testing.compile.JavaSourcesSubject
import com.google.testing.compile.JavaSourcesSubject.SingleSourceAdapter
import com.google.testing.compile.JavaSourcesSubjectFactory
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import javax.tools.JavaFileObject

/** Unit tests for [OptionsClassProcessor].  */
@RunWith(JUnit4::class)
class OptionsClassProcessorTest {
    @org.junit.Test
    fun optionsInNonOptionBasesAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("OptionInNonOptionBase.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "@Option annotated fields can only be in classes that inherit from OptionsBase."
            )
    }

    @org.junit.Test
    fun privatelyDeclaredOptionsAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("PrivateOptionField.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("@Option method must be public")
    }

    @org.junit.Test
    fun protectedOptionsAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ProtectedOptionField.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("@Option method must be public")
    }

    @org.junit.Test
    fun staticOptionsAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StaticOptionField.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("@Option method must be abstract")
    }

    @org.junit.Test
    fun finalOptionsAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("FinalOptionField.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("@Option method must be abstract")
    }

    @org.junit.Test
    fun namelessOptionsAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NamelessOption.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("Option must have an actual name.")
    }

    @org.junit.Test
    fun badNamesAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("BadNameForDocumentedOption.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Options that are used on the command line as flags must have names made from word "
                        + "characters only."
            )
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("BadNameWithEqualsSign.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Options that are used on the command line as flags must have names made from word "
                        + "characters only."
            )
    }

    @org.junit.Test
    fun badNamesForHiddenOptionsPass() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("BadNameForInternalOption.java"))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    fun deprecatedCategorySaysUndocumented() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("DeprecatedUndocumentedCategory.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Documentation level is no longer read from the option category. Category "
                        + "\"undocumented\" is disallowed, see OptionMetadataTags for the relevant tags."
            )
    }

    @org.junit.Test
    fun deprecatedCategorySaysHidden() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("DeprecatedHiddenCategory.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Documentation level is no longer read from the option category. Category "
                        + "\"hidden\" is disallowed, see OptionMetadataTags for the relevant tags."
            )
    }

    @org.junit.Test
    fun deprecatedCategorySaysInternal() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("DeprecatedInternalCategory.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Documentation level is no longer read from the option category. Category "
                        + "\"internal\" is disallowed, see OptionMetadataTags for the relevant tags."
            )
    }

    @org.junit.Test
    fun optionMustHaveEffectExplicitlyStated() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("EffectlessOption.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                ("Option does not list at least one OptionEffectTag. "
                        + "If the option has no effect, please be explicit and add NO_OP. "
                        + "Otherwise, add a tag representing its effect.")
            )
    }

    @org.junit.Test
    fun contradictingEffectTagsAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("OptionWithContradictingNoopEffects.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Option includes NO_OP with other effects. This doesn't make much sense. "
                        + "Please remove NO_OP or the actual effects from the list, whichever is correct."
            )
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("OptionWithContradictingUnknownEffects.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Option includes UNKNOWN with other, known, effects. "
                        + "Please remove UNKNOWN from the list."
            )
    }

    @org.junit.Test
    fun contradictoryDocumentationCategoryIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("HiddenOptionWithCategory.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Option has metadata tag HIDDEN but does not have category UNDOCUMENTED. "
                        + "Please fix."
            )
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("InternalOptionWithCategory.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Option has metadata tag INTERNAL but does not have category UNDOCUMENTED. "
                        + "Please fix."
            )
    }

    @org.junit.Test
    fun defaultConvertersAreFound() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("AllDefaultConverters.java"))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    fun converterReturnsListForAllowMultipleIsAllowed() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("MultipleOptionWithListTypeConverter.java"))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    fun correctCustomConverterForPrimitiveTypePasses() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("CorrectCustomConverterForPrimitiveType.java"))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    fun converterlessOptionIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ConverterlessOption.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Cannot find valid converter for option of type "
                        + "java.util.Map<java.lang.String,java.lang.String>"
            )
    }

    @org.junit.Test
    fun allowMultipleOptionWithCollectionTypeIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("CollectionTypeForAllowMultipleOption.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Option that allows multiple occurrences must be assignable to type java.util.List<E>,"
                        + " but is of type java.util.Collection<java.lang.String>"
            )
    }

    @org.junit.Test
    fun allowMultipleOptionWithNonListTypeIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NonListTypeForAllowMultipleOption.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Option that allows multiple occurrences must be assignable to type java.util.List<E>,"
                        + " but is of type java.lang.String"
            )
    }

    @org.junit.Test
    fun allowMultipleOptionWithImmutableListTypeIsAllowed() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ImmutableListTypeForAllowMultipleOption.java"))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    fun allowMultipleOptionsWithDefaultValuesAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("AllowMultipleOptionWithDefaultValue.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Default values for multiple options are not allowed - use \"null\" special value"
            )
    }

    @org.junit.Test
    fun optionWithIncorrectConverterIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("IncorrectConverterType.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Type of field (java.lang.String) must be assignable from the converter's return type "
                        + "(java.lang.Integer)"
            )
    }

    @org.junit.Test
    fun allowMultipleOptionWithIncorrectConverterIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("IncorrectConverterTypeForAllowMultipleOption.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Type of field (java.lang.String) must be assignable from the converter's return type "
                        + "(java.lang.Integer)"
            )
    }

    @org.junit.Test
    fun expansionOptionThatAllowsMultipleIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ExpansionOptionWithAllowMultiple.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Can't set an option to accumulate multiple values and let it expand to other flags."
            )
    }

    @org.junit.Test
    fun expansionOptionWithImplicitRequirementIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ExpansionOptionWithImplicitRequirement.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Can't set an option to be both an expansion option and have implicit requirements."
            )
    }

    @org.junit.Test
    fun deprecatedAttributeWithoutMetadataTagIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("DeprecatedAttributeWithoutMetadataTag.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Options annotated with @Deprecated must have metadata tag DEPRECATED."
            )
    }

    @org.junit.Test
    fun deprecatedMetadataTagWithoutAttributeIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("DeprecatedMetadataTagWithoutAttribute.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Options with metadata tag DEPRECATED must be annotated with @Deprecated."
            )
    }

    @org.junit.Test
    fun noopWithoutReasonIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NoopWithoutReason.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "No-op options must be annotated with @Deprecated, or have metadata tag HIDDEN or"
                        + " INTERNAL."
            )
    }

    @org.junit.Test
    fun nonPublicOptionMethodIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NonPublicOptionMethod.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("@Option method must be public")
    }

    @org.junit.Test
    fun nonAbstractOptionMethodIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NonAbstractGetter.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("@Option method must be abstract")
    }

    @org.junit.Test
    fun nonAbstractSetterIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NonAbstractSetter.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("Setter must be abstract")
    }

    @org.junit.Test
    fun setterWithMultipleArgumentsIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("SetterWithMultipleArguments.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("Setter must have exactly one argument")
    }

    @org.junit.Test
    fun setterWithIncorrectArgumentTypeIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("SetterWithIncorrectArgumentType.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining("Setter argument type must be same as getter return type (int)")
    }

    @org.junit.Test
    fun invalidOptionMethodNameIsRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("InvalidOptionMethodName.java"))
            .processedWith(OptionsClassProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Annotated method name must start with 'get' followed by an uppercase letter"
            )
    }

    @org.junit.Test
    fun validOptionsClassCompiles() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ValidOptions.java"))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    fun nestedOptionsClassesWithSameSimpleNameCompile() {
        Truth.assertAbout<JavaSourcesSubject?, Iterable<out JavaFileObject?>?>(JavaSourcesSubjectFactory.javaSources())
            .that(java.util.Arrays.asList<JavaFileObject?>(getFile("Outer1.java"), getFile("Outer2.java")))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    fun inheritingOptionsClassCompiles() {
        val baseOptions: JavaFileObject =
            JavaFileObjects.forSourceString(
                "com.google.devtools.common.options.processor.BaseOptions",
                """
            package com.google.devtools.common.options.processor;
            import com.google.devtools.common.options.Option;
            import com.google.devtools.common.options.OptionDocumentationCategory;
            import com.google.devtools.common.options.OptionEffectTag;
            import com.google.devtools.common.options.OptionsBase;
            public abstract class BaseOptions extends OptionsBase {
              @Option(
                  name = "base",
                  documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
                  effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
                  defaultValue = "baseDefault")
              public abstract String getBase();
              public abstract void setBase(String base);
            }
            
            """.trimIndent()
            )
        val inheritingOptions: JavaFileObject =
            JavaFileObjects.forSourceString(
                "com.google.devtools.common.options.processor.InheritingOptions",
                """
            package com.google.devtools.common.options.processor;
            import com.google.devtools.common.options.Option;
            import com.google.devtools.common.options.OptionDocumentationCategory;
            import com.google.devtools.common.options.OptionEffectTag;
            import com.google.devtools.common.options.OptionsClass;
            @OptionsClass
            public abstract class InheritingOptions extends BaseOptions {
              @Option(
                  name = "derived",
                  documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
                  effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
                  defaultValue = "derivedDefault")
              public abstract String getDerived();
              public abstract void setDerived(String derived);
            }
            
            """.trimIndent()
            )
        Truth.assertAbout<JavaSourcesSubject?, Iterable<out JavaFileObject?>?>(JavaSourcesSubjectFactory.javaSources())
            .that(java.util.Arrays.asList<JavaFileObject?>(baseOptions, inheritingOptions))
            .processedWith(OptionsClassProcessor())
            .compilesWithoutError()
    }

    companion object {
        private fun getFile(pathToFile: String?): JavaFileObject {
            return JavaFileObjects.forResource(
                com.google.common.io.Resources.getResource(
                    "com/google/devtools/common/options/processor/optiontestsources/" + pathToFile
                )
            )
        }
    }
}
