// Copyright 2016 The Bazel Authors. All rights reserved.
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
package net.starlark.java.annot

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/** Utility functions for Starlark annotations.  */
object StarlarkAnnotations {
    /**
     * Returns the more specific class of two classes. Class x is more specific than class y if x is
     * assignable to y. For example, of String.class and Object.class, String.class is more specific.
     * 
     * 
     * If either class is null, returns the other class.
     * 
     * 
     * If the classes are identical, returns the class.
     * 
     * @throws IllegalArgumentException if neither class is assignable to the other
     */
    private fun moreSpecific(x: java.lang.Class<*>?, y: java.lang.Class<*>?): java.lang.Class<*>? {
        if (x == null) {
            return y
        } else if (y == null) {
            return x
        } else if (x.isAssignableFrom(y)) {
            return y
        } else if (y.isAssignableFrom(x)) {
            return x
        } else {
            // If this exception occurs, it indicates the following error scenario:
            //
            // Suppose class A is a subclass of both B and C, where B and C are annotated with
            // @StarlarkBuiltin annotations (and are thus considered "Starlark types"). If B is not a
            // subclass of C (nor vice versa), then it's impossible to resolve whether A is of type
            // B or if A is of type C. It's both! The way to resolve this is usually to have A be its own
            // type (annotated with @StarlarkBuiltin), and thus have the explicit type of A be
            // semantically "B and C".
            throw java.lang.IllegalArgumentException(
                java.lang.String.format("Expected one of %s and %s to be a subclass of the other", x, y)
            )
        }
    }

    // A map from a class to its most-specific ancestor annotated as StarlarkBuiltin
    private val starlarkBuiltinAncestors: java.lang.ClassValue<java.lang.Class<*>?> =
        object : java.lang.ClassValue<java.lang.Class<*>?>() {
            override fun computeValue(type: java.lang.Class<*>): java.lang.Class<*>? {
                return net.starlark.java.annot.StarlarkAnnotations.findAnnotatedAncestorUncached(
                    type,
                    net.starlark.java.annot.StarlarkBuiltin::class.java
                )
            }
        }

    /**
     * Searches a class or interface's class hierarchy for the given class annotation.
     * 
     * 
     * If the given class annotation appears multiple times within the class hierarchy, this
     * chooses the annotation on the most-specified class in the hierarchy.
     * 
     * @return the best-fit class that declares the annotation, or null if no class in the hierarchy
     * declares it
     * @throws IllegalArgumentException if the most-specified class in the hierarchy having the
     * annotation is not unique
     */
    private fun findAnnotatedAncestor(
        classObj: java.lang.Class<*>, annotation: java.lang.Class<out Annotation?>?
    ): java.lang.Class<*>? {
        if (annotation == net.starlark.java.annot.StarlarkBuiltin::class.java) {
            return net.starlark.java.annot.StarlarkAnnotations.starlarkBuiltinAncestors.get(classObj)
        }
        return net.starlark.java.annot.StarlarkAnnotations.findAnnotatedAncestorUncached(classObj, annotation)
    }

    private fun findAnnotatedAncestorUncached(
        classObj: java.lang.Class<*>, annotation: java.lang.Class<out Annotation?>?
    ): java.lang.Class<*>? {
        if (classObj.isAnnotationPresent(annotation)) {
            return classObj
        }
        var bestCandidate: java.lang.Class<*>? = null
        val superclass: java.lang.Class<*>? = classObj.getSuperclass()
        if (superclass != null) {
            val result: java.lang.Class<*>? =
                net.starlark.java.annot.StarlarkAnnotations.findAnnotatedAncestor(superclass, annotation)
            bestCandidate = net.starlark.java.annot.StarlarkAnnotations.moreSpecific(result, bestCandidate)
        }
        for (interfaceObj in classObj.getInterfaces()) {
            val result: java.lang.Class<*>? =
                net.starlark.java.annot.StarlarkAnnotations.findAnnotatedAncestor(interfaceObj, annotation)
            bestCandidate = net.starlark.java.annot.StarlarkAnnotations.moreSpecific(result, bestCandidate)
        }
        return bestCandidate
    }

    /**
     * Returns the [StarlarkBuiltin] annotation for the given class, if it exists, and
     * null otherwise. The first annotation found will be returned, starting with `classObj`
     * and following its base classes and interfaces recursively.
     */
    fun getStarlarkBuiltin(classObj: java.lang.Class<*>): net.starlark.java.annot.StarlarkBuiltin? {
        val cls: java.lang.Class<*>? = net.starlark.java.annot.StarlarkAnnotations.findAnnotatedAncestor(
            classObj,
            net.starlark.java.annot.StarlarkBuiltin::class.java
        )
        return if (cls == null) null else cls.getAnnotation<net.starlark.java.annot.StarlarkBuiltin?>(net.starlark.java.annot.StarlarkBuiltin::class.java)
    }

    /**
     * Searches `classObj`'s class hierarchy and returns the first superclass or interface that
     * is annotated with [StarlarkBuiltin] (including possibly `classObj` itself), or null
     * if none is found.
     */
    fun getParentWithStarlarkBuiltin(classObj: java.lang.Class<*>): java.lang.Class<*>? {
        return net.starlark.java.annot.StarlarkAnnotations.findAnnotatedAncestor(
            classObj,
            net.starlark.java.annot.StarlarkBuiltin::class.java
        )
    }

    /**
     * Returns the [StarlarkMethod] annotation for the given method, if it exists, and null
     * otherwise.
     * 
     * 
     * Note that the annotation may be defined on a supermethod, rather than directly on the given
     * method.
     * 
     * 
     * `classObj` is the class on which the given method is defined.
     */
    fun getStarlarkMethod(
        classObj: java.lang.Class<*>,
        method: java.lang.reflect.Method
    ): net.starlark.java.annot.StarlarkMethod? {
        val callable: net.starlark.java.annot.StarlarkMethod? =
            net.starlark.java.annot.StarlarkAnnotations.getAnnotationOnClassMatchingSignature(classObj, method)
        if (callable != null) {
            return callable
        }
        if (classObj.getSuperclass() != null) {
            val annotation: net.starlark.java.annot.StarlarkMethod? =
                net.starlark.java.annot.StarlarkAnnotations.getStarlarkMethod(classObj.getSuperclass(), method)
            if (annotation != null) {
                return annotation
            }
        }
        for (interfaceObj in classObj.getInterfaces()) {
            val annotation: net.starlark.java.annot.StarlarkMethod? =
                net.starlark.java.annot.StarlarkAnnotations.getStarlarkMethod(interfaceObj, method)
            if (annotation != null) {
                return annotation
            }
        }
        return null
    }

    /**
     * Convenience version of `getAnnotationsFromParentClass(Class, Method)` that uses the
     * declaring class of the method.
     */
    fun getStarlarkMethod(method: java.lang.reflect.Method): net.starlark.java.annot.StarlarkMethod? {
        return net.starlark.java.annot.StarlarkAnnotations.getStarlarkMethod(method.getDeclaringClass(), method)
    }

    /**
     * Returns the `StarlarkMethod` annotation corresponding to the given method of the given
     * class, or null if there is no such annotation.
     * 
     * 
     * This method checks assignability instead of exact matches for purposes of generics. If Clazz
     * has parameters BarT (extends BarInterface) and BazT (extends BazInterface), then foo(BarT,
     * BazT) should match if the given method signature is foo(BarImpl, BazImpl). The signatures are
     * in inexact match, but an "assignable" match.
     */
    private fun getAnnotationOnClassMatchingSignature(
        classObj: java.lang.Class<*>, signatureToMatch: java.lang.reflect.Method
    ): net.starlark.java.annot.StarlarkMethod? {
        // TODO(b/79877079): This method validates several invariants of @StarlarkMethod. These
        // invariants should be verified in annotation processor or in test, and left out of this
        // method.
        val methods: Array<java.lang.reflect.Method> = classObj.getDeclaredMethods()
        val paramsToMatch: Array<java.lang.Class<*>?> = signatureToMatch.getParameterTypes()

        var callable: net.starlark.java.annot.StarlarkMethod? = null

        for (method in methods) {
            if (signatureToMatch.getName() == method.getName()
                && method.isAnnotationPresent(net.starlark.java.annot.StarlarkMethod::class.java)
            ) {
                val paramTypes: Array<java.lang.Class<*>?> = method.getParameterTypes()

                if (paramTypes.size == paramsToMatch.size) {
                    for (i in paramTypes.indices) {
                        // This verifies assignability of the method signature to ensure this is not a
                        // coincidental overload. We verify assignability instead of matching exact parameter
                        // classes in order to match generic methods.
                        check(paramTypes[i].isAssignableFrom(paramsToMatch[i])) {
                            java.lang.String.format(
                                "Class %s has an incompatible overload of annotated method %s declared by %s",
                                classObj, signatureToMatch.getName(), signatureToMatch.getDeclaringClass()
                            )
                        }
                    }
                }
                if (callable == null) {
                    callable =
                        method.getAnnotation<net.starlark.java.annot.StarlarkMethod?>(net.starlark.java.annot.StarlarkMethod::class.java)
                } else {
                    throw java.lang.IllegalStateException(
                        java.lang.String.format(
                            "Class %s has multiple overloaded methods named '%s' annotated "
                                    + "with @StarlarkMethod",
                            classObj, signatureToMatch.getName()
                        )
                    )
                }
            }
        }
        return callable
    }
}
