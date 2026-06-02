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
package com.google.devtools.build.lib.analysis.starlark.annotations.processor

import com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.*
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

/**
 * Annotation processor for [StarlarkConfigurationField].
 * 
 * 
 * Checks the following invariants about [StarlarkConfigurationField]-annotated methods:
 * 
 * 
 *  * The annotated method must be on a configuration fragment.
 *  * The method must have return type Label.
 *  * The method must be public.
 *  * The method must have zero arguments.
 *  * The method must not throw exceptions.
 * 
 * 
 * 
 * These properties can be relied upon at runtime without additional checks.
 */
@SupportedAnnotationTypes(
    "com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField"
)
class StarlarkConfigurationFieldProcessor : AbstractProcessor() {
    private var messager: Messager? = null
    private var typeUtils: Types? = null
    private var elementUtils: Elements? = null
    private var labelType: TypeElement? = null
    private var configurationFragmentType: TypeElement? = null

    override fun getSupportedSourceVersion(): SourceVersion? {
        return SourceVersion.latestSupported()
    }

    @kotlin.jvm.Synchronized
    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        messager = processingEnv.getMessager()
        typeUtils = processingEnv.getTypeUtils()
        elementUtils = processingEnv.getElementUtils()
        labelType =
            elementUtils!!.getTypeElement("com.google.devtools.build.lib.cmdline.Label")
        configurationFragmentType =
            elementUtils!!.getTypeElement("com.google.devtools.build.lib.analysis.config.Fragment")
    }

    override fun process(annotations: MutableSet<out TypeElement?>?, roundEnv: RoundEnvironment): Boolean {
        for (element in roundEnv.getElementsAnnotatedWith(StarlarkConfigurationField::class.java)) {
            // Only methods are annotated with StarlarkConfigurationField. This is verified by the
            // @Target(ElementType.METHOD) annotation.
            val methodElement = element as ExecutableElement

            if (!isMethodOfStarlarkExposedConfigurationFragment(methodElement)) {
                error(
                    methodElement, "@StarlarkConfigurationField annotated methods must be methods "
                            + "of configuration fragments."
                )
            }
            // If labelType is null, then Label isn't even included
            // in the current build, so the method clearly does not return it.
            if (labelType == null
                || !typeUtils!!.isSameType(methodElement.getReturnType(), labelType!!.asType())
            ) {
                error(methodElement, "@StarlarkConfigurationField annotated methods must return Label.")
            }
            if (!methodElement.getModifiers().contains(Modifier.PUBLIC)) {
                error(methodElement, "@StarlarkConfigurationField annotated methods must be public.")
            }
            if (!methodElement.getParameters().isEmpty()) {
                error(
                    methodElement,
                    "@StarlarkConfigurationField annotated methods must have zero arguments."
                )
            }
            if (!methodElement.getThrownTypes().isEmpty()) {
                error(
                    methodElement,
                    "@StarlarkConfigurationField annotated must not throw exceptions."
                )
            }
        }
        return false
    }

    private fun isMethodOfStarlarkExposedConfigurationFragment(
        methodElement: ExecutableElement
    ): Boolean {
        if (methodElement.getEnclosingElement().getKind() != ElementKind.CLASS) {
            return false
        }
        val classElement = methodElement.getEnclosingElement()
        // If configurationFragmentType is null, then the fragment isn't even included in the current
        // build, so the class clearly does not depend on it.
        if (configurationFragmentType == null
            || !typeUtils!!.isAssignable(classElement.asType(), configurationFragmentType!!.asType())
        ) {
            return false
        }

        return true
    }

    /**
     * Prints an error message & fails the compilation.
     * 
     * @param e The element which has caused the error. Can be null
     * @param msg The error message
     */
    fun error(e: Element?, msg: String?) {
        messager!!.printMessage(Diagnostic.Kind.ERROR, msg, e)
    }
}
