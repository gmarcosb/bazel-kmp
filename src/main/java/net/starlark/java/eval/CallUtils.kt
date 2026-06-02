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
package net.starlark.java.eval

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper functions for [StarlarkMethod]-annotated methods.
 * 
 * 
 * This class is public for the benefit of serialization in Bazel. Other code outside the
 * Starlark interpreter should not rely on it.
 */
object CallUtils {
    /** A map for obtaining a [BuiltinManager] from a [StarlarkSemantics].  */ // Historically, this code used to have a big map from (StarlarkSemantics, Class) pairs to
    // ClassDescriptors. This caused unnecessary GC churn and method call overhead for the dedicated
    // tuple objects, which became observable at scale. It was subsequently rewritten to be a
    // double-layer map from Semantics to Class to ClassDescriptor, which optimized for the common
    // case of few (typically just one) StarlarkSemantics instances. The inner map was then abstracted
    // into BuiltinManager.
    //
    // Avoid ConcurrentHashMap#computeIfAbsent because it is not reentrant: If a ClassDescriptor is
    // looked up before Starlark.UNIVERSE is initialized then the computation will re-enter the cache
    // and have a cycle; see b/161479826 for history.
    // TODO(bazel-team): Does the above cycle concern still exist?
    private val managerForSemantics: ConcurrentHashMap<net.starlark.java.eval.StarlarkSemantics?, BuiltinManager?> =
        ConcurrentHashMap<net.starlark.java.eval.StarlarkSemantics?, BuiltinManager?>()

    fun getBuiltinManager(semantics: net.starlark.java.eval.StarlarkSemantics): BuiltinManager {
        // TODO: b/513244797 - Eliminate need for getBuiltinManagerCacheKey.
        val key: net.starlark.java.eval.StarlarkSemantics? = semantics.getBuiltinManagerCacheKey()
        var manager: BuiltinManager? = net.starlark.java.eval.CallUtils.managerForSemantics.get(key)
        if (manager == null) {
            manager = net.starlark.java.eval.CallUtils.BuiltinManager(semantics)
            val prev: BuiltinManager? = net.starlark.java.eval.CallUtils.managerForSemantics.putIfAbsent(key, manager)
            if (prev != null) {
                manager = prev // first thread wins
            }
        }
        return manager!!
    }

    private fun buildClassDescriptor(manager: BuiltinManager, clazz: java.lang.Class<*>): ClassDescriptor {
        var selfCall: net.starlark.java.eval.MethodDescriptor? = null
        val methodsBuilder: com.google.common.collect.ImmutableMap.Builder<String?, net.starlark.java.eval.MethodDescriptor?> =
            com.google.common.collect.ImmutableMap.builder<String?, net.starlark.java.eval.MethodDescriptor?>()

        val typeConstructor: net.starlark.java.syntax.TypeConstructor? =
            net.starlark.java.eval.CallUtils.getAssociatedTypeConstructor(clazz)

        val annotation: net.starlark.java.annot.StarlarkBuiltin? =
            net.starlark.java.annot.StarlarkAnnotations.getStarlarkBuiltin(clazz)
        var overridesGetStarlarkType = false
        if (annotation != null) {
            try {
                if (clazz // LINT.IfChange
                        .getMethod(
                            "getStarlarkType",
                            net.starlark.java.eval.StarlarkSemantics::class.java
                        ) // LINT.ThenChange(//src/main/java/net/starlark/java/eval/StarlarkValue.java)
                        .getDeclaringClass()
                    != net.starlark.java.eval.StarlarkValue::class.java
                ) {
                    overridesGetStarlarkType = true
                }
            } catch (e: java.lang.NoSuchMethodException) {
                // All StarlarkBuiltin-annotated classes must implement StarlarkValue and thus have a
                // getStarlarkType method.
                throw java.lang.IllegalStateException(
                    java.lang.String.format("%s missing getStarlarkType(StarlarkSemantics) method", clazz), e
                )
            }
        }

        // Sort methods by Java name, for determinism.
        val classMethods: Array<java.lang.reflect.Method> = clazz.getMethods()
        java.util.Arrays.sort<java.lang.reflect.Method?>(
            classMethods,
            java.util.Comparator.comparing<java.lang.reflect.Method?, String?>(java.util.function.Function { obj: java.lang.reflect.Method? -> obj.getName() })
        )
        for (method in classMethods) {
            // Synthetic methods lead to false multiple matches
            if (method.isSynthetic()) {
                continue
            }

            // annotated?
            val callable: net.starlark.java.annot.StarlarkMethod? =
                net.starlark.java.annot.StarlarkAnnotations.getStarlarkMethod(method)
            if (callable == null) {
                continue
            }

            // enabled by semantics?
            if (!manager
                    .getSemantics()
                    .isFeatureEnabledBasedOnTogglingFlags(
                        callable.enableOnlyWithFlag, callable.disableWithFlag
                    )
            ) {
                continue
            }

            val descriptor: net.starlark.java.eval.MethodDescriptor =
                net.starlark.java.eval.MethodDescriptor.Companion.of(manager, method, callable)

            // self-call method?
            if (callable.selfCall) {
                require(selfCall == null) {
                    java.lang.String.format(
                        "Class %s has two selfCall methods defined",
                        clazz.getName()
                    )
                }
                selfCall = descriptor
                continue
            }

            // regular method
            methodsBuilder.put(callable.name, descriptor)
        }
        val methods: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.MethodDescriptor?> =
            methodsBuilder.buildOrThrow()

        val classDescriptor: ClassDescriptor = net.starlark.java.eval.CallUtils.ClassDescriptor()
        classDescriptor.manager = manager
        classDescriptor.selfCall = selfCall
        classDescriptor.methods = methods
        classDescriptor.typeConstructor = typeConstructor
        if (net.starlark.java.eval.StarlarkValue::class.java.isAssignableFrom(clazz) && !overridesGetStarlarkType) {
            val typeName: String? = if (annotation != null) annotation.name else clazz.getSimpleName()
            classDescriptor.classStarlarkType =
                net.starlark.java.eval.CallUtils.ClassDescriptor.ClassStarlarkType(typeName, selfCall, methods)
        }
        return classDescriptor
    }

    /**
     * Returns the type constructor identified by calling the given class's `getAssociatedTypeConstructor()` static method, or null if it does not have such a method.
     * 
     * @throws IllegalArgumentException if the method exists but has an unexpected signature, or if it
     * does not evaluate successfully
     */
    private fun getAssociatedTypeConstructor(clazz: java.lang.Class<*>): net.starlark.java.syntax.TypeConstructor? {
        // Special-case bool, which is represented by Java booleans and does not have its own class.
        // (String.class does not need special-casing because it's already been replaced by
        // StringModule.class by this point.)
        if (clazz == Boolean::class.java || clazz == Boolean::class.javaPrimitiveType) {
            return net.starlark.java.syntax.Types.BOOL_CONSTRUCTOR
        }

        var found: java.lang.reflect.Method? = null
        for (m in clazz.getDeclaredMethods()) {
            if (m.getName() == "getAssociatedTypeConstructor") {
                require(found == null) {
                    java.lang.String.format(
                        "Class %s has multiple methods named getAssociatedTypeConstructor",
                        clazz.getName()
                    )
                }
                found = m
            }
        }
        if (found == null) {
            return null
        }

        // Signature check.
        require(
            !(!java.lang.reflect.Modifier.isPublic(found.getModifiers()) || !java.lang.reflect.Modifier.isStatic(
                found.getModifiers()
            ) || (found.getReturnType() != net.starlark.java.syntax.TypeConstructor::class.java) || found.getParameterCount() != 0)
        ) {
            java.lang.String.format(
                "Method %s#getAssociatedTypeConstructor has an invalid signature; "
                        + "expected 'public static TypeConstructor getAssociatedTypeConstructor()'",
                clazz.getName()
            )
        }

        try {
            return found.invoke(null) as net.starlark.java.syntax.TypeConstructor?
        } catch (e: java.lang.IllegalAccessException) {
            throw java.lang.IllegalArgumentException(
                java.lang.String.format("Error invoking %s#getAssociatedTypeConstructor", clazz.getName()), e
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw java.lang.IllegalArgumentException(
                java.lang.String.format("Error invoking %s#getAssociatedTypeConstructor", clazz.getName()), e
            )
        } catch (e: java.lang.RuntimeException) {
            throw java.lang.IllegalArgumentException(
                java.lang.String.format("Error invoking %s#getAssociatedTypeConstructor", clazz.getName()), e
            )
        }
    }

    /**
     * Describes the Starlark methods - meaning methods annotated with [StarlarkMethod] -
     * available for a particular Java class under a particular [StarlarkSemantics].
     * 
     * 
     * Generally, instances of this class are valid as Starlark values, and the class itself is
     * generally annotated with [StarlarkBuiltin]. However, there are exceptions. For example,
     * compilations of global functions, such as [MethodLibrary], are not Starlark values. Some
     * internal [StarlarkValue] implementations, such as [Starlark.UnboundMarker], do not
     * have a [StarlarkBuiltin] annotation. And [StringModule] cannot be used as a valid
     * Starlark value despite having a [StarlarkBuiltin] annotation.
     * 
     * 
     * Although a `ClassDescriptor` does not directly embed the `StarlarkSemantics`,
     * its contents vary based on them. In contrast, [MethodDescriptor] and [ ] do not vary with the semantics.
     */
    private class ClassDescriptor {
        /** The manager that created this descriptor. Used for obtaining method type information.  */
        var manager:  // TODO: #28325 - Use it for obtaining StarlarkTypes.
                BuiltinManager? = null

        /**
         * The descriptor for the unique `@StarlarkMethod`-annotated method on this class that has
         * [StarlarkMethod.selfCall] set to true (ex: "struct" in Bazel), or null if there is no
         * such method.
         */
        var selfCall: net.starlark.java.eval.MethodDescriptor? = null

        /**
         * A map of the method descriptors that are available as fields of this object.
         * 
         * 
         * This includes methods with [StarlarkMethod.structField] set to true, i.e.
         * non-callable Starlark fields.
         * 
         * 
         * The `selfCall` method is omitted (if one even exists). Any methods that are disabled
         * by flag guarding via the [StarlarkSemantics] are also omitted.
         * 
         * 
         * The map is keyed on the Starlark field name, and sorted by Java method name.
         */
        var methods: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.MethodDescriptor?>? = null

        /**
         * The type constructor to be called when the Starlark symbol that acts as this class's Starlark
         * constructor appears in a type application expression; or null if this class cannot be used as
         * a Starlark type.
         * 
         * 
         * For example, for [StarlarkList]'s descriptor this is [Types.LIST_CONSTRUCTOR].
         * 
         * 
         * See [StarlarkMethod.isTypeConstructor].
         */
        var typeConstructor: net.starlark.java.syntax.TypeConstructor? = null

        /**
         * The Starlark type to be used for values of this class if neither this class nor its
         * superclasses overrides [StarlarkValue.getStarlarkType]; or null if there is such an
         * override.
         */
        var classStarlarkType: ClassStarlarkType? = null

        private class ClassStarlarkType(
            private val name: String?,
            selfCall: net.starlark.java.eval.MethodDescriptor?,
            methods: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.MethodDescriptor?>
        ) : net.starlark.java.syntax.StarlarkType() {
            private val selfCall: net.starlark.java.eval.MethodDescriptor? // shared with ClassDescriptor
            private val methods: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.MethodDescriptor?> // shared with ClassDescriptor

            init {
                this.selfCall = selfCall
                this.methods = methods
            }

            override fun getSupertypes(): com.google.common.collect.ImmutableList<net.starlark.java.syntax.StarlarkType?> {
                // TODO: #28325 - Populate supertypes where possible.
                return if (selfCall == null)
                    com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>() // Values of a self-call type are callable, with the self-call method's signature.
                else
                    com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(selfCall.getStarlarkType())
            }

            override fun getField(
                name: String?,
                context: net.starlark.java.syntax.TypeContext?
            ): net.starlark.java.syntax.StarlarkType? {
                val method: net.starlark.java.eval.MethodDescriptor? = methods.get(name)
                return if (method == null) null else method.getStarlarkType()
            }

            override fun toString(): String {
                return name!!
            }
        }
    }

    /**
     * A manager for obtaining descriptors for native-defined Starlark objects and methods, under a
     * specific `StarlarkSemantics`.
     * 
     * 
     * This class is public for the benefit of serialization in Bazel. Other code outside the
     * Starlark interpreter should not rely on it.
     */
    class BuiltinManager private constructor(semantics: net.starlark.java.eval.StarlarkSemantics?) {
        private val semantics: net.starlark.java.eval.StarlarkSemantics?

        private val classDescriptorCache: java.lang.ClassValue<ClassDescriptor?> =
            object : java.lang.ClassValue<ClassDescriptor?>() {
                override fun computeValue(clazz: java.lang.Class<*>): ClassDescriptor {
                    var clazz: java.lang.Class<*> = clazz
                    if (clazz == String::class.java) {
                        clazz = net.starlark.java.eval.StringModule::class.java
                    }
                    return net.starlark.java.eval.CallUtils.buildClassDescriptor(this@BuiltinManager, clazz)
                }
            }

        init {
            this.semantics = semantics
        }

        /**
         * Returns the Starlark type to be used for valid Starlark values of the given class which
         * doesn't override [StarlarkValue.getStarlarkType]; or null if it (or one of its
         * superclasses) does override [StarlarkValue.getStarlarkType].
         */
        fun getClassStarlarkType(clazz: java.lang.Class<*>?): net.starlark.java.syntax.StarlarkType? {
            return getClassDescriptor(clazz)!!.classStarlarkType
        }

        fun getSemantics(): net.starlark.java.eval.StarlarkSemantics? {
            return semantics
        }

        /**
         * Returns the [ClassDescriptor] for the given [Class], under the BuiltinManager's
         * [StarlarkSemantics].
         * 
         * 
         * This method is a hotspot! It's called on every function call and field access. A single
         * `bazel build` invocation can make tens or even hundreds of millions of calls to this method.
         */
        private fun getClassDescriptor(clazz: java.lang.Class<*>?): ClassDescriptor? {
            return classDescriptorCache.get(clazz)
        }

        /**
         * Returns the type constructor associated with the given Java class under a given `StarlarkSemantics`, or null if there is none.
         * 
         * 
         * An example would be getting the type constructor for the `list` type from the class
         * `StarlarkList`.
         * 
         * 
         * The returned constructor has complete type information about the available Starlark
         * methods of the class.
         */
        fun getTypeConstructor(clazz: java.lang.Class<*>?): net.starlark.java.syntax.TypeConstructor? {
            return getClassDescriptor(clazz)!!.typeConstructor
        }

        /**
         * Returns the set of all StarlarkMethod-annotated Java methods (excluding the self-call method)
         * of the specified class.
         */
        fun getAnnotatedMethods(objClass: java.lang.Class<*>?): com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.MethodDescriptor?>? {
            return getClassDescriptor(objClass)!!.methods
        }

        /**
         * Returns a [MethodDescriptor] object representing a function which calls the selfCall
         * java method of the given object (the [StarlarkMethod] method with [ ][StarlarkMethod.selfCall] set to true). Returns null if no such method exists.
         */
        fun getSelfCallMethodDescriptor(objClass: java.lang.Class<*>?): net.starlark.java.eval.MethodDescriptor? {
            return getClassDescriptor(objClass)!!.selfCall
        }

        /**
         * Returns a `selfCall=true` method for the given class under the given Starlark
         * semantics, or null if no such method exists.
         */
        fun getSelfCallMethod(objClass: java.lang.Class<*>?): java.lang.reflect.Method? {
            val descriptor: net.starlark.java.eval.MethodDescriptor? = getClassDescriptor(objClass)!!.selfCall
            if (descriptor == null) {
                return null
            }
            return descriptor.getMethod()
        }
    }
}
