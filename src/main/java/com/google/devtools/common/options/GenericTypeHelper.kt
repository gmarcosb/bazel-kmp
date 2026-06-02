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
package com.google.devtools.common.options

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/**
 * A helper class for [OptionsParserImpl] to help checking the return type
 * of a [Converter] against the type of a field or the element type of a
 * list.
 * 
 * 
 * This class has to go through considerable contortion to get the correct result
 * from the Java reflection system, unfortunately. If the generic reflection part
 * had been better designed, some of this would not be necessary.
 */
internal object GenericTypeHelper {
    /**
     * Returns the raw type of t, if t is either a raw or parameterized type.
     * Otherwise, this method throws an [AssertionError].
     */
    @com.google.common.annotations.VisibleForTesting
    fun getRawType(t: java.lang.reflect.Type): java.lang.Class<*> {
        if (t is java.lang.Class<*>) {
            return t as java.lang.Class<*>
        } else if (t is java.lang.reflect.ParameterizedType) {
            return (t as java.lang.reflect.ParameterizedType).getRawType() as java.lang.Class<*>?
        } else {
            throw java.lang.AssertionError("A known concrete type is not concrete")
        }
    }

    /**
     * If type is a parameterized type, searches the given type variable in the list of declared type
     * variables, and then returns the corresponding actual type. Returns null if the type variable is
     * not defined by type.
     */
    private fun matchTypeVariable(
        type: java.lang.reflect.Type?,
        variable: java.lang.reflect.TypeVariable<*>
    ): java.lang.reflect.Type? {
        if (type is java.lang.reflect.ParameterizedType) {
            val rawInterfaceType: java.lang.Class<*> =
                com.google.devtools.common.options.GenericTypeHelper.getRawType(type)
            val typeParameters: Array<java.lang.reflect.TypeVariable<*>?> = rawInterfaceType.getTypeParameters()
            for (i in typeParameters.indices) {
                if (variable == typeParameters[i]) {
                    return (type as java.lang.reflect.ParameterizedType).getActualTypeArguments()[i]
                }
            }
        }
        return null
    }

    /**
     * Resolves the return type of a method, in particular if the generic return
     * type ([Method.getGenericReturnType]) is a type variable
     * ([TypeVariable]), by checking all super-classes and directly
     * implemented interfaces.
     * 
     * 
     * The method m must be defined by the given type or by its raw class type.
     * 
     * @throws AssertionError if the generic return type could not be resolved
     */
    // TODO(bazel-team): also check enclosing classes and indirectly implemented
    // interfaces, which can also contribute type variables. This doesn't happen
    // in the existing use cases.
    fun getActualReturnType(type: java.lang.reflect.Type?, method: java.lang.reflect.Method): java.lang.reflect.Type {
        var type: java.lang.reflect.Type? = type
        val returnType: java.lang.reflect.Type = method.getGenericReturnType()
        if (returnType is java.lang.Class<*>) {
            return returnType
        } else if (returnType is java.lang.reflect.ParameterizedType) {
            return returnType
        } else if (returnType is java.lang.reflect.TypeVariable<*>) {
            val variable: java.lang.reflect.TypeVariable<*> = returnType as java.lang.reflect.TypeVariable<*>
            while (type != null) {
                var candidate: java.lang.reflect.Type? =
                    com.google.devtools.common.options.GenericTypeHelper.matchTypeVariable(type, variable)
                if (candidate != null) {
                    return candidate
                }

                val rawType: java.lang.Class<*> = com.google.devtools.common.options.GenericTypeHelper.getRawType(type)
                for (interfaceType in rawType.getGenericInterfaces()) {
                    candidate =
                        com.google.devtools.common.options.GenericTypeHelper.matchTypeVariable(interfaceType, variable)
                    if (candidate != null) {
                        return candidate
                    }
                }

                type = rawType.getGenericSuperclass()
            }
        }
        throw java.lang.AssertionError(
            ("The type " + returnType
                    + " is not a Class, ParameterizedType, or TypeVariable")
        )
    }

    /**
     * Determines if a value of a particular type (from) is assignable to a field of
     * a particular type (to). Also allows assigning wrapper types to primitive
     * types.
     * 
     * 
     * The checks done here should be identical to the checks done by
     * [java.lang.reflect.Field.set]. I.e., if this method returns true, a
     * subsequent call to [java.lang.reflect.Field.set] should succeed.
     */
    fun isAssignableFrom(to: java.lang.reflect.Type, from: java.lang.reflect.Type): Boolean {
        if (to is java.lang.Class<*>) {
            val toClass: java.lang.Class<*> = to as java.lang.Class<*>
            if (toClass.isPrimitive()) {
                return com.google.common.primitives.Primitives.wrap(toClass) == from
            }
        }
        return com.google.common.reflect.TypeToken.of(to).isSupertypeOf(from)
    }
}
