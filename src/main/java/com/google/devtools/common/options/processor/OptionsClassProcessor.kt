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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.common.options.*
import java.io.IOException
import java.io.PrintWriter
import java.lang.Deprecated
import java.lang.String
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import java.util.regex.Pattern
import java.util.stream.Collectors
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.*
import javax.lang.model.type.*
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.Double
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.Long
import kotlin.plus
import kotlin.toString

/**
 * An annotation processor that generates an implementation class for options classes annotated with
 * [OptionsClass].
 */
@SupportedAnnotationTypes("com.google.devtools.common.options.OptionsClass")
class OptionsClassProcessor : AbstractProcessor() {
    private var typeUtils: Types? = null
    private var elementUtils: Elements? = null
    private var messager: Messager? = null
    private var defaultConverters: ImmutableMap<TypeMirror?, Converter<*>?>? = null
    private var primitiveTypeMap: ImmutableMap<Class<*>?, PrimitiveType?>? = null

    override fun getSupportedSourceVersion(): SourceVersion? {
        return SourceVersion.latestSupported()
    }

    @kotlin.jvm.Synchronized
    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)

        typeUtils = processingEnv.getTypeUtils()
        elementUtils = processingEnv.getElementUtils()
        messager = processingEnv.getMessager()

        primitiveTypeMap =
            ImmutableMap.Builder<Class<*>?, PrimitiveType?>()
                .put(Int::class.javaPrimitiveType, typeUtils!!.getPrimitiveType(TypeKind.INT))
                .put(Double::class.javaPrimitiveType, typeUtils!!.getPrimitiveType(TypeKind.DOUBLE))
                .put(Boolean::class.javaPrimitiveType, typeUtils!!.getPrimitiveType(TypeKind.BOOLEAN))
                .put(Long::class.javaPrimitiveType, typeUtils!!.getPrimitiveType(TypeKind.LONG))
                .buildOrThrow()

        val defaultConverterMap: MutableMap<Class<*>?, Converter<*>?>? = getDefaultConverters()
        if (defaultConverterMap == null) {
            defaultConverters = null
            return
        }
        val converterMapBuilder =
            ImmutableMap.Builder<TypeMirror?, Converter<*>?>()

        for (entry in defaultConverterMap.entrySet()) {
            val converterClass: Class<*> = entry.getKey()
            val typeName = converterClass.getCanonicalName()
            val typeElement = elementUtils!!.getTypeElement(typeName)
            if (typeElement != null) {
                converterMapBuilder.put(typeElement.asType(), entry.getValue())
            } else {
                if (primitiveTypeMap!!.containsKey(converterClass)) {
                    val primitiveType = primitiveTypeMap!!.get(converterClass)
                    converterMapBuilder
                        .put(primitiveType, entry.getValue())
                        .put(typeUtils!!.boxedClass(primitiveType).asType(), entry.getValue())
                }
            }
        }
        defaultConverters = converterMapBuilder.buildOrThrow()
    }

    override fun process(annotations: MutableSet<out TypeElement?>?, roundEnv: RoundEnvironment): Boolean {
        for (annotatedElement in roundEnv.getElementsAnnotatedWith(OptionsClass::class.java)) {
            if (annotatedElement.getKind() != ElementKind.CLASS) {
                continue
            }
            val typeElement = annotatedElement as TypeElement
            generateWrapper(typeElement)
        }
        return false
    }

    private fun generateWrapper(typeElement: TypeElement) {
        val optionsBase =
            elementUtils!!.getTypeElement("com.google.devtools.common.options.OptionsBase").asType()
        if (!typeUtils!!.isAssignable(typeElement.asType(), optionsBase)) {
            messager!!.printMessage(
                Diagnostic.Kind.ERROR,
                "@Option annotated fields can only be in classes that inherit from OptionsBase.",
                typeElement
            )
            return
        }

        val packageName =
            processingEnv.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString()
        val className: String =
            typeElement.getQualifiedName().toString().substring(packageName.length() + 1)
        val implClassName = className.replace('.', '_') + "Impl"

        data class OptionInfo(val fieldType: String?, val capitalizedFieldName: String?, val hasSetterInBase: Boolean)

        val options: MutableList<OptionInfo> = ArrayList<OptionInfo>()
        var hasErrors = false

        // First pass: collect option info
        for (member in processingEnv.getElementUtils().getAllMembers(typeElement)) {
            if (member.getAnnotation<Option?>(Option::class.java) == null) {
                continue
            }
            if (member.getKind() != ElementKind.METHOD) {
                messager!!.printMessage(
                    Diagnostic.Kind.ERROR, "@Option must be on a method in @OptionsClass classes", member
                )
                hasErrors = true
                continue
            }

            val method = member as ExecutableElement
            try {
                checkMethodOption(method)
            } catch (e: OptionProcessorException) {
                messager!!.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.getElementInError())
                hasErrors = true
                continue
            }

            val methodName = method.getSimpleName().toString()
            val fieldType: String? = method.getReturnType().toString()
            val capitalizedFieldName: String = methodName.substring("get".length())

            var setter: ExecutableElement? = null
            val setterName = "set" + capitalizedFieldName
            for (e in processingEnv.getElementUtils().getAllMembers(typeElement)) {
                if (e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals(setterName)) {
                    setter = e as ExecutableElement
                    break
                }
            }

            if (setter != null) {
                if (!setter.getModifiers().contains(Modifier.ABSTRACT)) {
                    messager!!.printMessage(Diagnostic.Kind.ERROR, "Setter must be abstract", setter)
                    hasErrors = true
                    continue
                }

                if (setter.getParameters().size() != 1) {
                    messager!!.printMessage(
                        Diagnostic.Kind.ERROR, "Setter must have exactly one argument", setter
                    )
                    hasErrors = true
                    continue
                }

                if (!processingEnv
                        .getTypeUtils()
                        .isSameType(setter.getParameters().get(0).asType(), method.getReturnType())
                ) {
                    messager!!.printMessage(
                        Diagnostic.Kind.ERROR,
                        String.format(
                            "Setter argument type must be same as getter return type (%s)", fieldType
                        ),
                        setter
                    )
                    hasErrors = true
                    continue
                }
            }

            options.add(OptionInfo(fieldType, capitalizedFieldName, setter != null))
        }

        if (hasErrors) {
            return
        }

        try {
            // Generate the Impl class
            val implJfo =
                processingEnv.getFiler().createSourceFile(packageName + "." + implClassName)
            PrintWriter(implJfo.openWriter()).use { out ->
                out.printf(
                    """
            package %1${'$'}s;

            import com.google.devtools.common.options.Options;
            import com.google.devtools.common.options.OptionDefinition;
            import com.google.devtools.common.options.OptionsBase;
            import java.util.Map;
            import java.util.Objects;

            public final class %2${'$'}s extends %3${'$'}s {
              public %2${'$'}s() {
                super();
              }

              @Override
              @SuppressWarnings("unchecked")
              public final Class<? extends %3${'$'}s> getOptionsClass() {
                return (Class<? extends %3${'$'}s>) %3${'$'}s.class;
              }

              @Override
              public final String toString() {
                return %3${'$'}s.class.getName() + Options.toMap(this);
              }

              @Override
              public final boolean equals(Object that) {
                if (this == that) {
                  return true;
                }
                if (!(that instanceof %2${'$'}s other)) {
                  return false;
                }
                for (OptionDefinition def : OptionDefinition.getOptionDefinitions(getOptionsClass())) {
                  if (!Objects.equals(def.getValue(this), def.getValue(other))) {
                    return false;
                  }
                }
                return true;
              }

              @Override
              public final int hashCode() {
                return %2${'$'}s.class.hashCode() + Options.toMap(this).hashCode();
              }
            
            """.trimIndent(),
                    packageName, implClassName, typeElement.getQualifiedName()
                )
                for (option in options) {
                    val fieldName =
                        (option.capitalizedFieldName.substring(0, 1).toLowerCase(Locale.ROOT)
                                + option.capitalizedFieldName.substring(1))
                    out.printf(
                        """
                private %1${'$'}s %2${'$'}s;

                @Override
                public %1${'$'}s get%3${'$'}s() {
                  return this.%2${'$'}s;
                }

                %4${'$'}s
                public void set%3${'$'}s(%1${'$'}s %2${'$'}s) {
                  this.%2${'$'}s = %2${'$'}s;
                }
              
              """.trimIndent(),
                        option.fieldType,
                        fieldName,
                        option.capitalizedFieldName,
                        if (option.hasSetterInBase) "@Override" else ""
                    )
                }
                out.println("}")
            }
        } catch (e: IOException) {
            messager!!.printMessage(
                Diagnostic.Kind.ERROR,
                "Failed to generate implementation for " + className + ": " + e.getMessage()
            )
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkMethodOption(method: ExecutableElement) {
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
            throw OptionProcessorException(method, "@Option method must be public")
        }
        if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
            throw OptionProcessorException(method, "@Option method must be abstract")
        }

        val methodName = method.getSimpleName().toString()
        if (!methodName.startsWith("get") || methodName.length() < 4 || !Character.isUpperCase(methodName.charAt(3))) {
            throw OptionProcessorException(
                method, "Annotated method name must start with 'get' followed by an uppercase letter"
            )
        }

        checkOptionName(method)
        checkOldCategoriesAreNotUsed(method)
        checkExpansionOptions(method)
        checkConverter(method)
        checkEffectTagRationality(method)
        checkMetadataTagAndCategoryRationality(method)
        checkNoDefaultValueForMultipleOption(method)
        checkDeprecated(method)
    }

    @Throws(OptionProcessorException::class)
    private fun checkOptionName(method: ExecutableElement) {
        val annotation = method.getAnnotation<Option>(Option::class.java)
        val optionName = annotation.name
        if (optionName.isEmpty()) {
            throw OptionProcessorException(method, "Option must have an actual name.")
        }

        if (!ImmutableList.copyOf<OptionMetadataTag?>(annotation.metadataTags).contains(OptionMetadataTag.INTERNAL)) {
            if (!Pattern.matches("([\\w:-])*", optionName)) {
                // Ideally, this would be just \w, but - and : are needed for legacy options. We can lie in
                // the error though, no harm in encouraging good behavior.
                throw OptionProcessorException(
                    method,
                    "Options that are used on the command line as flags must have names made from word "
                            + "characters only."
                )
            }
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkEffectTagRationality(method: ExecutableElement) {
        val annotation = method.getAnnotation<Option>(Option::class.java)
        val effectTags: Array<OptionEffectTag?> = annotation.effectTags
        if (effectTags.size < 1) {
            throw OptionProcessorException(
                method,
                "Option does not list at least one OptionEffectTag. If the option has no effect, "
                        + "please be explicit and add NO_OP. Otherwise, add a tag representing its effect."
            )
        } else if (effectTags.size > 1) {
            // If there are more than 1 tag, make sure that NO_OP and UNKNOWN is not one of them.
            // These don't make sense if other effects are listed.
            val tags = ImmutableList.copyOf<OptionEffectTag?>(effectTags)
            if (tags.contains(OptionEffectTag.UNKNOWN)) {
                throw OptionProcessorException(
                    method,
                    "Option includes UNKNOWN with other, known, effects. Please remove UNKNOWN from "
                            + "the list."
                )
            }
            if (tags.contains(OptionEffectTag.NO_OP)) {
                throw OptionProcessorException(
                    method,
                    "Option includes NO_OP with other effects. This doesn't make much sense. Please "
                            + "remove NO_OP or the actual effects from the list, whichever is correct."
                )
            }
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkMetadataTagAndCategoryRationality(method: ExecutableElement) {
        val annotation = method.getAnnotation<Option>(Option::class.java)
        val metadataTags: Array<OptionMetadataTag?> = annotation.metadataTags
        val category: OptionDocumentationCategory? = annotation.documentationCategory

        for (tag in metadataTags) {
            if (tag == OptionMetadataTag.HIDDEN || tag == OptionMetadataTag.INTERNAL) {
                if (category != OptionDocumentationCategory.UNDOCUMENTED) {
                    throw OptionProcessorException(
                        method,
                        "Option has metadata tag %s but does not have category UNDOCUMENTED. Please fix.",
                        tag
                    )
                }
            }
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkOldCategoriesAreNotUsed(method: ExecutableElement) {
        val annotation = method.getAnnotation<Option>(Option::class.java)
        if (DEPRECATED_CATEGORIES.contains(annotation.category)) {
            throw OptionProcessorException(
                method,
                ("Documentation level is no longer read from the option category. Category \""
                        + annotation.category
                        + "\" is disallowed, see OptionMetadataTags for the relevant tags.")
            )
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkExpansionOptions(method: ExecutableElement) {
        val annotation = method.getAnnotation<Option>(Option::class.java)
        val isExpansion = annotation.expansion.size > 0
        val hasImplicitRequirements = annotation.implicitRequirements.size > 0

        if (isExpansion && hasImplicitRequirements) {
            throw OptionProcessorException(
                method,
                "Can't set an option to be both an expansion option and have implicit requirements."
            )
        }

        if (isExpansion || hasImplicitRequirements) {
            if (annotation.allowMultiple) {
                throw OptionProcessorException(
                    method,
                    "Can't set an option to accumulate multiple values and let it expand to other flags."
                )
            }
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkNoDefaultValueForMultipleOption(method: ExecutableElement) {
        val annotation = method.getAnnotation<Option>(Option::class.java)
        if (annotation.allowMultiple
            && (annotation.defaultValue != "null") && !ImmutableList.of<kotlin.String?>(
                "runs_per_test",
                "flaky_test_attempts"
            ).contains(annotation.name)
        ) {
            throw OptionProcessorException(
                method,
                "Default values for multiple options are not allowed - use \"null\" special value"
            )
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkDeprecated(method: ExecutableElement) {
        val annotation = method.getAnnotation<Option>(Option::class.java)
        val effectTags = ImmutableList.copyOf<OptionEffectTag?>(annotation.effectTags)
        val metadataTags = ImmutableList.copyOf<OptionMetadataTag?>(annotation.metadataTags)
        val hasDeprecatedAnnotation = method.getAnnotation<Deprecated?>(Deprecated::class.java) != null
        val hasDeprecatedMetadataTag = metadataTags.contains(OptionMetadataTag.DEPRECATED)

        if (effectTags.contains(OptionEffectTag.NO_OP)
            && !metadataTags.contains(OptionMetadataTag.HIDDEN) && !metadataTags.contains(OptionMetadataTag.INTERNAL) && !hasDeprecatedAnnotation
        ) {
            // Allowlist for tests - these are in the process of being fixed.
            val enclosingClassName = method.getEnclosingElement().toString()
            val allowlisted: Boolean =
                NO_OP_OPTION_ALLOWLIST.stream()
                    .anyMatch(Predicate { prefix: kotlin.String? -> enclosingClassName.startsWith(prefix) })
            if (!allowlisted) {
                throw OptionProcessorException(
                    method,
                    "No-op options must be annotated with @Deprecated, or have metadata tag HIDDEN or"
                            + " INTERNAL. Alternatively add %s to the allowlist.",
                    enclosingClassName
                )
            }
        }

        if (hasDeprecatedMetadataTag && !hasDeprecatedAnnotation) {
            throw OptionProcessorException(
                method, "Options with metadata tag DEPRECATED must be annotated with @Deprecated."
            )
        }
        if (hasDeprecatedAnnotation && !hasDeprecatedMetadataTag) {
            throw OptionProcessorException(
                method, "Options annotated with @Deprecated must have metadata tag DEPRECATED."
            )
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkConverter(method: ExecutableElement) {
        val optionType = method.getReturnType()
        val annotation = method.getAnnotation<Option>(Option::class.java)
        val acceptedConverterReturnTypes =
            getAcceptedConverterReturnTypes(method)

        // For simple, static expansions, don't accept non-Void types.
        if (annotation.expansion.size != 0
            && !typeUtils!!.isSameType(
                optionType, elementUtils!!.getTypeElement(Void::class.java.getCanonicalName()).asType()
            )
        ) {
            throw OptionProcessorException(
                method,
                "Option is an expansion flag with a static expansion, but does not have Void type."
            )
        }

        // Obtain the converter for this option.
        val optionMirror =
            ProcessorUtils.getAnnotation(elementUtils, typeUtils, method, Option::class.java)
        val defaultConverterElement =
            elementUtils!!.getTypeElement(Converter::class.java.getCanonicalName())
        val converterElement =
            ProcessorUtils.getClassTypeFromAnnotationField(elementUtils, optionMirror, "converter")

        if (typeUtils!!.isSameType(converterElement.asType(), defaultConverterElement.asType())) {
            // Find a matching converter in the default converter list, and check that it successfully
            // parses the default value for this option.
            checkForDefaultConverter(method, acceptedConverterReturnTypes, annotation.defaultValue)
        } else {
            // Check that the provided converter has an accepted return type.
            checkProvidedConverter(method, acceptedConverterReturnTypes, converterElement)
        }
    }

    @Throws(OptionProcessorException::class)
    private fun getAcceptedConverterReturnTypes(method: ExecutableElement): ImmutableList<TypeMirror?> {
        val optionType = method.getReturnType()
        val annotation = method.getAnnotation<Option>(Option::class.java)
        val listType = elementUtils!!.getTypeElement(MutableList::class.java.getCanonicalName()).asType()

        if (annotation.allowMultiple) {
            if (optionType.getKind() != TypeKind.DECLARED) {
                throw OptionProcessorException(
                    method,
                    "Option that allows multiple occurrences must be of type %s, but is of type %s",
                    listType,
                    optionType
                )
            }
            val optionDeclaredType = optionType as DeclaredType
            if (!typeUtils!!.isAssignable(typeUtils!!.erasure(optionDeclaredType), listType)) {
                throw OptionProcessorException(
                    method,
                    "Option that allows multiple occurrences must be assignable to type %s, but is of type"
                            + " %s",
                    listType,
                    optionType
                )
            }
            val genericParameters = optionDeclaredType.getTypeArguments()
            if (genericParameters.size() != 1) {
                throw OptionProcessorException(
                    method,
                    "Option that allows multiple occurrences must be of type %s, where E is the type of an"
                            + " individual command-line mention of this option, but is of type %s",
                    listType,
                    optionType
                )
            }
            return ImmutableList.of<TypeMirror?>(genericParameters.get(0), optionType)
        } else {
            return ImmutableList.of<TypeMirror?>(optionType)
        }
    }

    @Throws(OptionProcessorException::class)
    private fun checkForDefaultConverter(
        method: ExecutableElement?, acceptedConverterReturnTypes: MutableList<TypeMirror?>, defaultValue: kotlin.String?
    ) {
        if (defaultConverters == null) {
            // Bootstrapping. Do not do this check.
            return
        }

        for (acceptedConverterReturnType in acceptedConverterReturnTypes) {
            val converterInstance = findDefaultConverter(acceptedConverterReturnType)
            if (converterInstance == null) {
                continue
            }
            try {
                converterInstance.convert(defaultValue, null)
            } catch (e: OptionsParsingException) {
                val converter =
                    elementUtils!!.getTypeElement(converterInstance.getClass().getCanonicalName())
                throw OptionProcessorException(
                    method,
                    e,
                    "Option lists a default value (%s) that is not parsable by the option's converter (%s)",
                    defaultValue,
                    converter
                )
            }
            return
        }
        throw OptionProcessorException(
            method,
            "Cannot find valid converter for option of type %s",
            acceptedConverterReturnTypes.get(0)
        )
    }

    private fun findDefaultConverter(type: TypeMirror?): Converter<*>? {
        // According to the documentation of TypeMirror, equality check is not how one checks whether
        // two instances reference the same type but Types.isSameType().
        for (entry in defaultConverters.entrySet()) {
            if (typeUtils!!.isSameType(type, entry.getKey())) {
                return entry.getValue()
            }
        }
        return null
    }

    @Throws(OptionProcessorException::class)
    private fun checkProvidedConverter(
        method: ExecutableElement?,
        acceptedConverterReturnTypes: ImmutableList<TypeMirror?>,
        converterElement: TypeElement
    ) {
        if (converterElement.getModifiers().contains(Modifier.ABSTRACT)) {
            throw OptionProcessorException(
                method, "The converter type %s must be a concrete type", converterElement.asType()
            )
        }

        val converterType = converterElement.asType() as DeclaredType?
        val methodList =
            elementUtils!!.getAllMembers(converterElement).stream()
                .filter { element: Element -> element.getKind() == ElementKind.METHOD }
                .map<ExecutableElement?> { methodElement: Element -> methodElement as ExecutableElement }
                .filter(Predicate { methodElement: ExecutableElement? ->
                    methodElement!!.getSimpleName().contentEquals("convert")
                })
                .filter(
                    Predicate { methodElement: ExecutableElement? ->
                        methodElement!!.getParameters().size() == 2 && typeUtils!!.isSameType(
                            methodElement.getParameters().get(0).asType(),
                            elementUtils!!.getTypeElement(kotlin.String::class.java.getCanonicalName()).asType()
                        )
                                && typeUtils!!.isSameType(
                            methodElement.getParameters().get(1).asType(),
                            elementUtils!!.getTypeElement(Any::class.java.getCanonicalName()).asType()
                        )
                    })
                .collect(Collectors.toList())

        if (methodList.size() != 1) {
            throw OptionProcessorException(
                method,
                "Converter %s has %d methods 'convert(String, Object)', expected 1: %s",
                converterElement,
                methodList.size(),
                methodList.stream().map<kotlin.String?>(Function { obj: ExecutableElement? -> obj.toString() })
                    .collect(Collectors.joining(", "))
            )
        }

        val convertMethodType =
            typeUtils!!.asMemberOf(converterType, methodList.get(0)) as ExecutableType
        val convertMethodResultType = convertMethodType.getReturnType()
        for (acceptedConverterReturnType in acceptedConverterReturnTypes) {
            if (typeUtils!!.isAssignable(convertMethodResultType, acceptedConverterReturnType)) {
                return
            }
        }
        throw OptionProcessorException(
            method,
            "Type of field (%s) must be assignable from the converter's return type (%s)",
            acceptedConverterReturnTypes.get(0),
            convertMethodResultType
        )
    }

    companion object {
        // This method is necessary because when bootstrapping Bazel, we need to run the option class
        // annotation processor so we need to build it first. But if we simply reference Converters, we
        // also need all of its transitive dependencies, which is a lot. So instead reference it using
        // reflection and report that no default converters are available during bootstrapping.
        // uses reflection, can't have generic arguments
        private fun getDefaultConverters(): MutableMap<Class<*>?, Converter<*>?>? {
            val converters: Class<*>
            try {
                converters = Class.forName("com.google.devtools.common.options.Converters")
            } catch (e: ClassNotFoundException) {
                return null
            }

            try {
                return converters.getField("DEFAULT_CONVERTERS").get(null) as MutableMap<Class<*>?, Converter<*>?>?
            } catch (e: ReflectiveOperationException) {
                throw IllegalArgumentException(e)
            }
        }

        private val DEPRECATED_CATEGORIES: ImmutableSet<kotlin.String?> =
            ImmutableSet.of<kotlin.String?>("undocumented", "hidden", "internal")

        // TODO(Silic0nS0ldier): Remove this allowlist once all tests have been fixed.
        private val NO_OP_OPTION_ALLOWLIST: ImmutableList<kotlin.String?> = ImmutableList.of<kotlin.String?>(
            "com.google.devtools.build.lib.analysis.AnalysisCachingTest.",
            "com.google.devtools.build.lib.analysis.config.BuildOptionDetailsTest.",
            "com.google.devtools.build.lib.analysis.config.BuildOptionsTest.",
            "com.google.devtools.build.lib.analysis.LateBoundSplitUtil.",
            "com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyMapProducerTest.",
            "com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyProducerTest.",
            "com.google.devtools.build.lib.analysis.RequiredConfigFragmentsTest.",
            "com.google.devtools.build.lib.analysis.starlark.StarlarkTransitionTest.",
            "com.google.devtools.build.lib.analysis.starlark.StarlarkTransitionTest.",
            "com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.",
            "com.google.devtools.build.lib.analysis.util.DummyTestFragment.",
            "com.google.devtools.build.lib.buildtool.ConvenienceSymlinkTest.",
            "com.google.devtools.build.lib.rules.config.ConfigSettingTest.",
            "com.google.devtools.build.lib.runtime.AbstractCommandTest.",
            "com.google.devtools.build.lib.runtime.BlazeCommandDispatcherRcoptionsTest.",
            "com.google.devtools.build.lib.runtime.BlazeCommandDispatcherTest.",
            "com.google.devtools.build.lib.runtime.CommandInterruptionTest.",
            "com.google.devtools.build.lib.skyframe.config.ParsedFlagsValueTest.",
            "com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionTest.",
            "com.google.devtools.build.lib.skyframe.config.PlatformMappingValueTest.",
            "com.google.devtools.build.lib.testing.common.FakeOptionsTest.",
            "com.google.devtools.build.lib.util.OptionsUtilsTest.",
            "com.google.devtools.build.lib.worker.ExampleWorkerMultiplexerOptions",
            "com.google.devtools.build.lib.worker.ExampleWorkerOptions",
            "com.google.devtools.common.options.BoolOrEnumConverterTest.",
            "com.google.devtools.common.options.EnumConverterTest.",
            "com.google.devtools.common.options.FieldOptionDefinitionTest.",
            "com.google.devtools.common.options.OptionsTest.",
            "com.google.devtools.common.options.OptionsDataTest.",
            "com.google.devtools.common.options.OptionsParserTest.",
            "com.google.devtools.common.options.OptionsTest.",
            "com.google.devtools.common.options.processor.OptionProcessorTest.",
            "com.google.devtools.common.options.testing.OptionsTesterTest.",
            "com.google.devtools.common.options.TestOptions"
        )
    }
}
