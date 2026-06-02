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
package com.google.devtools.common.options

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Knowledge about how the option parser should get type information about options and set their
 * values that are defined by methods.
 */
class MethodOptionDefinition private constructor(
    method: java.lang.reflect.Method,
    optionAnnotation: com.google.devtools.common.options.Option?
) : com.google.devtools.common.options.OptionDefinition(optionAnnotation) {
    private val method: java.lang.reflect.Method
    private val fieldName: String

    // This is needed because options classes sometimes inherit from each other. In this case, the
    // field storing the value can be on multiple classes (e.g. if FooOptions inherits from
    // BarOptions, the implementation of both will have fields corresponding to the options in
    // BarOptions)
    private val fieldCache: ConcurrentMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, java.lang.reflect.Field> =
        ConcurrentHashMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, java.lang.reflect.Field>()

    init {
        this.method = method
        val methodName: String = method.getName()
        com.google.common.base.Verify.verify(methodName.startsWith("get")) // Enforced by the annotation processor
        this.fieldName = methodName.substring(3, 4).toLowerCase(Locale.ROOT) + methodName.substring(4)
    }

    override fun <C : com.google.devtools.common.options.OptionsBase?> getDeclaringClass(baseClass: java.lang.Class<C?>): java.lang.Class<out C?>? {
        // The implementation class is not technically the "declaring" class, but it's the one that is
        // referenced everywhere, so this is what needs to be returned. In particular, that's the one
        // that needs to be passed to getOptions().
        val methodClass: java.lang.Class<*>? = method.getDeclaringClass()
        com.google.common.base.Preconditions.checkArgument(baseClass.isAssignableFrom(methodClass))
        val castClass:  // This should be safe based on the previous check.
                java.lang.Class<out C?>? = methodClass as java.lang.Class<out C?>?
        return castClass
    }

    override fun getRawValue(optionsBase: com.google.devtools.common.options.OptionsBase?): Any? {
        try {
            return method.invoke(optionsBase)
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    private fun getField(optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>): java.lang.reflect.Field {
        try {
            val implClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?> =
                com.google.devtools.common.options.MethodOptionDefinition.Companion.getImplClass(
                    optionsClass.asSubclass<com.google.devtools.common.options.OptionsBase?>(
                        com.google.devtools.common.options.OptionsBase::class.java
                    )
                )
            val f: java.lang.reflect.Field = implClass.getDeclaredField(fieldName)
            f.setAccessible(true)
            return f
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.IllegalStateException(
                "Could not find field " + fieldName + " in implementation of " + optionsClass, e
            )
        }
    }

    override fun setValue(optionsBase: com.google.devtools.common.options.OptionsBase, value: Any?) {
        val field: java.lang.reflect.Field =
            fieldCache.computeIfAbsent(
                optionsBase.getOptionsClass()
                    .asSubclass<com.google.devtools.common.options.OptionsBase?>(com.google.devtools.common.options.OptionsBase::class.java),
                java.util.function.Function { optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>? ->
                    this.getField(optionsClass)
                })
        try {
            field.set(optionsBase, value)
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    override fun isDeprecated(): Boolean {
        return method.isAnnotationPresent(java.lang.Deprecated::class.java)
    }

    override fun getType(): java.lang.Class<*>? {
        return method.getReturnType()
    }

    override fun getSingularType(): java.lang.reflect.Type? {
        return method.getGenericReturnType()
    }

    override fun equals(o: Any?): Boolean {
        if (o !is MethodOptionDefinition) {
            return false
        }
        return this.method == o.method
    }

    override fun hashCode(): Int {
        return method.hashCode()
    }

    override fun getMemberName(): String {
        return method.getName()
    }

    override fun toString(): String {
        return java.lang.String.format("option '--%s'", getOptionName())
    }

    companion object {
        /** Returns an `MethodOptionDefinition` for the given method.  */
        fun from(method: java.lang.reflect.Method): MethodOptionDefinition? {
            val annotation: com.google.devtools.common.options.Option? =
                method.getAnnotation<com.google.devtools.common.options.Option?>(com.google.devtools.common.options.Option::class.java)
            if (annotation == null) {
                return null
            }
            return com.google.devtools.common.options.MethodOptionDefinition(method, annotation)
        }

        /** Returns the generated implementation class for the given options class.  */
        fun getImplClass(
            optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>
        ): java.lang.Class<out com.google.devtools.common.options.OptionsBase?> {
            com.google.common.base.Verify.verify(optionsClass.isAnnotationPresent(com.google.devtools.common.options.OptionsClass::class.java))
            val packageName: String = optionsClass.getPackage().getName()
            val className: String = optionsClass.getName().substring(packageName.length() + 1)
            val implClassName = packageName + "." + className.replace('$', '_') + "Impl"
            try {
                return java.lang.Class.forName(implClassName, true, optionsClass.getClassLoader())
                    .asSubclass<com.google.devtools.common.options.OptionsBase?>(com.google.devtools.common.options.OptionsBase::class.java)
            } catch (e: java.lang.ClassNotFoundException) {
                throw java.lang.IllegalStateException(e) // The annotation processor should have been run
            }
        }

        /**
         * Returns an `MethodOptionDefinition` for the given method name in the given class.
         * 
         * 
         * This is intended to be used by the generated implementation classes.
         */
        fun get(
            optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>, methodName: String
        ): MethodOptionDefinition {
            try {
                check(optionsClass.isAnnotationPresent(com.google.devtools.common.options.OptionsClass::class.java)) { optionsClass.toString() + " is not an @OptionsClass" }
                val method: java.lang.reflect.Method = optionsClass.getMethod(methodName)
                val result: com.google.devtools.common.options.Option =
                    method.getAnnotation<com.google.devtools.common.options.Option>(com.google.devtools.common.options.Option::class.java)
                checkNotNull(result) { methodName + " is not an @Option" }
                return com.google.devtools.common.options.MethodOptionDefinition(method, result)
            } catch (e: java.lang.NoSuchMethodException) {
                throw java.lang.IllegalStateException(e)
            }
        }
    }
}
