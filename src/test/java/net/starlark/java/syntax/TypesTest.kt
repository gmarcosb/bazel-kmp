// Copyright 2025 The Bazel Authors. All Rights Reserved.
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
package net.starlark.java.syntax

import com.google.common.truth.Truth
import net.starlark.java.syntax.Types.CallableType.toSignatureString
import net.starlark.java.syntax.Types.callable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests of built-in type objects.  */ // TODO: #27370 - Move this to match whichever package Types.java is going to live in.
@RunWith(JUnit4::class)
class TypesTest {
    @org.junit.Test
    fun assignability_reflexivity() {
        assertLt(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.INT)
        assertLt(net.starlark.java.syntax.Types.ANY, net.starlark.java.syntax.Types.ANY)
        assertLt(net.starlark.java.syntax.Types.OBJECT, net.starlark.java.syntax.Types.OBJECT)
    }

    @org.junit.Test
    fun assignability_anyPassesEitherDirection() {
        assertLtAndGt(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.ANY)
    }

    @org.junit.Test
    fun assignability_objectIsTop() {
        assertStrictLt(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.OBJECT)
        assertLtAndGt(net.starlark.java.syntax.Types.ANY, net.starlark.java.syntax.Types.OBJECT)
    }

    @org.junit.Test
    fun assignability_primitiveTypesAreIncompatible() {
        assertIncomparable(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.STR)
        assertIncomparable(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.FLOAT) // unlike Python
        assertIncomparable(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.FLOAT)
        assertIncomparable(net.starlark.java.syntax.Types.BOOL, net.starlark.java.syntax.Types.INT) // unlike Python
        assertIncomparable(net.starlark.java.syntax.Types.NONE, net.starlark.java.syntax.Types.STR)
    }

    @org.junit.Test
    fun assignability_union() {
        val intOrStr: net.starlark.java.syntax.StarlarkType =
            net.starlark.java.syntax.Types.union(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.STR)
        val intOrFloatOrStr: net.starlark.java.syntax.StarlarkType = net.starlark.java.syntax.Types.union(
            net.starlark.java.syntax.Types.INT,
            net.starlark.java.syntax.Types.FLOAT,
            net.starlark.java.syntax.Types.STR
        )
        val floatOrStr: net.starlark.java.syntax.StarlarkType = net.starlark.java.syntax.Types.union(
            net.starlark.java.syntax.Types.FLOAT,
            net.starlark.java.syntax.Types.STR
        )
        // Assignability of a primitive type to a union
        assertLt(net.starlark.java.syntax.Types.INT, intOrStr)
        assertLt(net.starlark.java.syntax.Types.STR, intOrStr)
        assertLt(net.starlark.java.syntax.Types.ANY, intOrStr)
        assertNotLt(net.starlark.java.syntax.Types.FLOAT, intOrStr)
        assertNotLt(net.starlark.java.syntax.Types.OBJECT, intOrStr)

        // Assignability of a union to a primitive type
        assertLt(intOrStr, net.starlark.java.syntax.Types.ANY)
        assertLt(intOrStr, net.starlark.java.syntax.Types.OBJECT)
        assertNotLt(intOrStr, net.starlark.java.syntax.Types.INT)
        assertNotLt(intOrStr, net.starlark.java.syntax.Types.STR)

        // Assignability between unions
        assertLt(intOrStr, intOrStr)
        assertLt(intOrStr, intOrFloatOrStr)
        assertNotLt(intOrFloatOrStr, intOrStr)
        assertNotLt(intOrStr, floatOrStr)
        assertNotLt(floatOrStr, intOrStr)
    }

    // Application-defined Sequence subtype.
    private class CustomSequenceType : net.starlark.java.syntax.Types.AbstractSequenceType() {
        val elementType: net.starlark.java.syntax.StarlarkType?
            get() = net.starlark.java.syntax.Types.ANY
    }

    // Application-defined Mapping subtype.
    private class CustomMappingType : net.starlark.java.syntax.Types.AbstractMappingType() {
        val keyType: net.starlark.java.syntax.StarlarkType?
            get() = net.starlark.java.syntax.Types.ANY

        val valueType: net.starlark.java.syntax.StarlarkType?
            get() = net.starlark.java.syntax.Types.ANY
    }

    @org.junit.Test
    fun assignability_collection_subtypes() {
        val intOrStr: net.starlark.java.syntax.StarlarkType =
            net.starlark.java.syntax.Types.union(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.STR)

        assertStrictLtChain(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.NEVER),  // empty list literal
            net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.INT),
            net.starlark.java.syntax.Types.sequence(net.starlark.java.syntax.Types.INT),
            net.starlark.java.syntax.Types.collection(net.starlark.java.syntax.Types.INT)
        )

        // List rvalues are assignable to any compatible list (and supertypes)
        assertStrictLtChain(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.INT),
            net.starlark.java.syntax.Types.list(intOrStr),
            net.starlark.java.syntax.Types.sequence(intOrStr),
            net.starlark.java.syntax.Types.collection(intOrStr)
        )
        // ... but not to incompatible collection types (e.g. mappings, dicts, sets, or
        // application-defined collection types).
        assertIncomparable(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.ANY),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            )
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.ANY),
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.ANY, net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.ANY),
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.ANY),
            net.starlark.java.syntax.Types.set(net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.ANY),
            CustomSequenceType()
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.listRvalue(net.starlark.java.syntax.Types.ANY),
            CustomMappingType()
        )

        assertStrictLtChain(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.homogeneousTuple(intOrStr),
            net.starlark.java.syntax.Types.sequence(intOrStr),
            net.starlark.java.syntax.Types.collection(intOrStr)
        )

        // An empty tuple is assignable to any homogeneous tuple type.
        assertStrictLtChain(
            net.starlark.java.syntax.Types.EMPTY_TUPLE,
            net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.ANY),
            net.starlark.java.syntax.Types.sequence(net.starlark.java.syntax.Types.ANY),
            net.starlark.java.syntax.Types.collection(net.starlark.java.syntax.Types.ANY)
        )

        assertStrictLtChain(
            net.starlark.java.syntax.Types.set(net.starlark.java.syntax.Types.STR),
            net.starlark.java.syntax.Types.collection(net.starlark.java.syntax.Types.STR)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.set(net.starlark.java.syntax.Types.STR),
            net.starlark.java.syntax.Types.sequence(net.starlark.java.syntax.Types.STR)
        )

        assertStrictLtChain(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.NEVER,
                net.starlark.java.syntax.Types.NEVER
            ),  // empty dict literal
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.INT),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.collection(net.starlark.java.syntax.Types.STR)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ), net.starlark.java.syntax.Types.sequence(net.starlark.java.syntax.Types.STR)
        )

        // Dict rvalues are assignable to any compatible dict (and supertypes)
        assertStrictLtChain(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.dict(intOrStr, intOrStr),
            net.starlark.java.syntax.Types.mapping(intOrStr, intOrStr),
            net.starlark.java.syntax.Types.collection(intOrStr)
        )
        // ... but not to incompatible collection types (e.g. sequences, lists, sets, or
        // application-defined collection types).
        assertIncomparable(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            ), net.starlark.java.syntax.Types.sequence(net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            ), net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            ), net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            ), net.starlark.java.syntax.Types.set(net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            ), CustomSequenceType()
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dictRvalue(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            ), CustomMappingType()
        )

        // Works with unions too.
        assertStrictLtChain(
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.dict(
                    net.starlark.java.syntax.Types.STR,
                    net.starlark.java.syntax.Types.INT
                ), net.starlark.java.syntax.Types.list(net.starlark.java.syntax.Types.STR)
            ),
            net.starlark.java.syntax.Types.union(
                net.starlark.java.syntax.Types.collection(net.starlark.java.syntax.Types.STR),
                net.starlark.java.syntax.Types.collection(net.starlark.java.syntax.Types.BOOL)
            )
        )
    }

    @org.junit.Test
    fun assignability_homogeneousCollections_covariance() {
        // Immutable and rvalue collections: covariant in element type
        val immutableCollectionConstructors: com.google.common.collect.ImmutableList<java.util.function.Function<net.starlark.java.syntax.StarlarkType?, net.starlark.java.syntax.StarlarkType?>> =
            com.google.common.collect.ImmutableList.of<java.util.function.Function<net.starlark.java.syntax.StarlarkType?, net.starlark.java.syntax.StarlarkType?>?>(
                java.util.function.Function { elementType: net.starlark.java.syntax.StarlarkType? ->
                    net.starlark.java.syntax.Types.collection(
                        elementType
                    )
                },
                java.util.function.Function { elementType: net.starlark.java.syntax.StarlarkType? ->
                    net.starlark.java.syntax.Types.sequence(elementType)
                },
                java.util.function.Function { elementType: net.starlark.java.syntax.StarlarkType? ->
                    net.starlark.java.syntax.Types.homogeneousTuple(elementType)
                },
                java.util.function.Function { elementType: net.starlark.java.syntax.StarlarkType? ->
                    net.starlark.java.syntax.Types.listRvalue(elementType)
                })
        for (ctor in immutableCollectionConstructors) {
            assertLtAndGt(
                ctor.apply(net.starlark.java.syntax.Types.INT),
                ctor.apply(net.starlark.java.syntax.Types.ANY)
            )
            assertIncomparable(
                ctor.apply(net.starlark.java.syntax.Types.INT),
                ctor.apply(net.starlark.java.syntax.Types.FLOAT)
            )
            assertStrictLtChain(
                ctor.apply(net.starlark.java.syntax.Types.NEVER),
                ctor.apply(net.starlark.java.syntax.Types.INT),
                ctor.apply(net.starlark.java.syntax.Types.NUMERIC),
                ctor.apply(net.starlark.java.syntax.Types.OBJECT)
            )
        }

        // Mutable collections: invariant in element type.
        val mutableCollectionConstructors: com.google.common.collect.ImmutableList<java.util.function.Function<net.starlark.java.syntax.StarlarkType?, net.starlark.java.syntax.StarlarkType?>> =
            com.google.common.collect.ImmutableList.of<java.util.function.Function<net.starlark.java.syntax.StarlarkType?, net.starlark.java.syntax.StarlarkType?>?>(
                java.util.function.Function { elementType: net.starlark.java.syntax.StarlarkType? ->
                    net.starlark.java.syntax.Types.list(elementType)
                },
                java.util.function.Function { elementType: net.starlark.java.syntax.StarlarkType? ->
                    net.starlark.java.syntax.Types.set(elementType)
                })
        for (ctor in mutableCollectionConstructors) {
            assertLtAndGt(
                ctor.apply(net.starlark.java.syntax.Types.INT),
                ctor.apply(net.starlark.java.syntax.Types.ANY)
            )
            assertIncomparable(
                ctor.apply(net.starlark.java.syntax.Types.INT),
                ctor.apply(net.starlark.java.syntax.Types.FLOAT),
                ctor.apply(net.starlark.java.syntax.Types.NUMERIC),
                ctor.apply(net.starlark.java.syntax.Types.OBJECT)
            )
        }
    }

    @org.junit.Test
    fun assignability_fixedLengthTuple_covariance() {
        // Covariant in element types; element count must match exactly.
        assertLtAndGt(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(net.starlark.java.syntax.Types.ANY, net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ), net.starlark.java.syntax.Types.tuple(net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.ANY
            )
        )

        assertIncomparable(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.FLOAT,
                net.starlark.java.syntax.Types.STR
            )
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.BOOL
            )
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.INT)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ), net.starlark.java.syntax.Types.tuple(net.starlark.java.syntax.Types.INT)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.BOOL
            )
        )

        assertStrictLtChain(
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.NUMERIC,
                net.starlark.java.syntax.Types.STR
            ),
            net.starlark.java.syntax.Types.tuple(
                net.starlark.java.syntax.Types.OBJECT,
                net.starlark.java.syntax.Types.OBJECT
            )
        )
    }

    @org.junit.Test
    fun assignability_mapping_covariance() {
        // Invariant in key type (but allowing Any); covariant in value type.

        // keys

        assertLtAndGt(
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.ANY,
                net.starlark.java.syntax.Types.INT
            )
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.OBJECT,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.NUMERIC,
                net.starlark.java.syntax.Types.INT
            )
        )

        // values
        assertLtAndGt(
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.ANY
            )
        )
        assertStrictLtChain(
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.NUMERIC
            ),
            net.starlark.java.syntax.Types.mapping(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.OBJECT
            )
        )
    }

    @org.junit.Test
    fun assignability_dict_invariance() {
        // Invariant in key and value types.

        // keys

        assertLtAndGt(
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.ANY, net.starlark.java.syntax.Types.INT)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.INT),
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.OBJECT,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.INT, net.starlark.java.syntax.Types.INT),
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.NUMERIC,
                net.starlark.java.syntax.Types.INT
            )
        )

        // values
        assertLtAndGt(
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.INT
            ),
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.ANY)
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.dict(net.starlark.java.syntax.Types.STR, net.starlark.java.syntax.Types.INT),
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.FLOAT
            ),
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.NUMERIC
            ),
            net.starlark.java.syntax.Types.dict(
                net.starlark.java.syntax.Types.STR,
                net.starlark.java.syntax.Types.OBJECT
            )
        )
    }

    @org.junit.Test
    fun assignability_struct() {
        assertLtAndGt(
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT
                )
            ),
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.ANY
                )
            )
        )
        assertStrictLtChain(
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT
                )
            ),
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT
                )
            )
        )

        // Order of fields is irrelevant.
        assertLtAndGt(
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "g",
                    net.starlark.java.syntax.Types.BOOL
                )
            ),
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "g",
                    net.starlark.java.syntax.Types.BOOL,
                    "f",
                    net.starlark.java.syntax.Types.INT
                )
            )
        )
        assertLtAndGt(
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "g",
                    net.starlark.java.syntax.Types.BOOL
                )
            ),
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "g",
                    net.starlark.java.syntax.Types.BOOL,
                    "f",
                    net.starlark.java.syntax.Types.INT
                )
            )
        )

        assertIncomparable(
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "g",
                    net.starlark.java.syntax.Types.INT
                )
            ),
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "h",
                    net.starlark.java.syntax.Types.INT
                )
            )
        )

        assertLtAndGt(
            net.starlark.java.syntax.Types.STRUCT_OF_ANY,
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.ANY
                )
            ),
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT
                )
            ),
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "g",
                    net.starlark.java.syntax.Types.INT
                )
            ),
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "h",
                    net.starlark.java.syntax.Types.INT
                )
            )
        )
        assertIncomparable(
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT
                )
            ),
            net.starlark.java.syntax.Types.partialStruct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.FLOAT
                )
            )
        )

        assertStrictLtChain(
            net.starlark.java.syntax.Types.STRUCT_OF_ANY,
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "g",
                    net.starlark.java.syntax.Types.STR,
                    "h",
                    net.starlark.java.syntax.Types.BOOL
                )
            ),
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.INT,
                    "h",
                    net.starlark.java.syntax.Types.ANY
                )
            ),
            net.starlark.java.syntax.Types.struct(
                com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>(
                    "f",
                    net.starlark.java.syntax.Types.union(
                        net.starlark.java.syntax.Types.INT,
                        net.starlark.java.syntax.Types.FLOAT
                    )
                )
            ),
            net.starlark.java.syntax.Types.struct(com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.StarlarkType?>())
        )
    }

    @org.junit.Test
    fun callable_toSignatureString() {
        // ordinary only
        Truth.assertThat(
            net.starlark.java.syntax.Types.callable( /* parameterNames= */
                com.google.common.collect.ImmutableList.of<String?>("a"),  /* parameterTypes= */
                com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(net.starlark.java.syntax.Types.INT),  /* numPositionalOnlyParameters= */
                0,  /* numPositionalParameters= */
                1,  /* mandatoryParams= */
                com.google.common.collect.ImmutableSet.of<String?>("a"),  /* varargsType= */
                null,  /* kwargsType= */
                null,
                net.starlark.java.syntax.Types.BOOL
            )
                .toSignatureString()
        )
            .isEqualTo("(a: int) -> bool")
        // kwonly only
        Truth.assertThat(
            net.starlark.java.syntax.Types.callable( /* parameterNames= */
                com.google.common.collect.ImmutableList.of<String?>("a"),  /* parameterTypes= */
                com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(net.starlark.java.syntax.Types.INT),  /* numPositionalOnlyParameters= */
                0,  /* numPositionalParameters= */
                0,  /* mandatoryParams= */
                com.google.common.collect.ImmutableSet.of<String?>("a"),  /* varargsType= */
                null,  /* kwargsType= */
                null,
                net.starlark.java.syntax.Types.BOOL
            )
                .toSignatureString()
        )
            .isEqualTo("(*, a: int) -> bool")
        // positional-only only
        Truth.assertThat(
            net.starlark.java.syntax.Types.callable( /* parameterNames= */
                com.google.common.collect.ImmutableList.of<String?>("x"),  /* parameterTypes= */
                com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(net.starlark.java.syntax.Types.INT),  /* numPositionalOnlyParameters= */
                1,  /* numPositionalParameters= */
                0,  /* mandatoryParams= */
                com.google.common.collect.ImmutableSet.of<String?>(),  /* varargsType= */
                null,  /* kwargsType= */
                null,
                net.starlark.java.syntax.Types.BOOL
            )
                .toSignatureString()
        )
            .isEqualTo("([int], /) -> bool")
        // no params
        Truth.assertThat(
            net.starlark.java.syntax.Types.callable( /* parameterNames= */
                com.google.common.collect.ImmutableList.of<String?>(),  /* parameterTypes= */
                com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(),  /* numPositionalOnlyParameters= */
                0,  /* numPositionalParameters= */
                0,  /* mandatoryParams= */
                com.google.common.collect.ImmutableSet.of<String?>(),  /* varargsType= */
                null,  /* kwargsType= */
                null,
                net.starlark.java.syntax.Types.BOOL
            )
                .toSignatureString()
        )
            .isEqualTo("() -> bool")
        // all kinds of params
        Truth.assertThat(
            net.starlark.java.syntax.Types.callable( /* parameterNames= */
                com.google.common.collect.ImmutableList.of<String?>("x", "a", "b", "c", "d"),  /* parameterTypes= */
                com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.StarlarkType?>(
                    net.starlark.java.syntax.Types.BOOL,
                    net.starlark.java.syntax.Types.INT,
                    net.starlark.java.syntax.Types.FLOAT,
                    net.starlark.java.syntax.Types.NONE,
                    net.starlark.java.syntax.Types.ANY
                ),  /* numPositionalOnlyParameters= */
                1,  /* numPositionalParameters= */
                3,  /* mandatoryParams= */
                com.google.common.collect.ImmutableSet.of<String?>("a", "c", "x"),  /* varargsType= */
                net.starlark.java.syntax.Types.INT,  /* kwargsType= */
                net.starlark.java.syntax.Types.INT,
                net.starlark.java.syntax.Types.BOOL
            )
                .toSignatureString()
        )
            .isEqualTo(
                "(bool, /, a: int, b: [float], *args: int, c: None, d: [Any], **kwargs: int) -> bool"
            )
    }

    companion object {
        /** Asserts `t1` is assignable to `t2`.  */
        private fun assertLt(t1: net.starlark.java.syntax.StarlarkType, t2: net.starlark.java.syntax.StarlarkType?) {
            Truth.assertWithMessage("%s is expected to be assignable to %s", t1, t2)
                .that(net.starlark.java.syntax.StarlarkType.assignableFrom(t2, t1))
                .isTrue()
        }

        /** Asserts `t1` is *not* assignable to `t2`.  */
        private fun assertNotLt(t1: net.starlark.java.syntax.StarlarkType, t2: net.starlark.java.syntax.StarlarkType?) {
            Truth.assertWithMessage("%s is expected to be *not* assignable to %s", t1, t2)
                .that(net.starlark.java.syntax.StarlarkType.assignableFrom(t2, t1))
                .isFalse()
        }

        /** Asserts `t1` is assignable to `t2`, but not vice versa.  */
        private fun assertStrictLt(
            t1: net.starlark.java.syntax.StarlarkType,
            t2: net.starlark.java.syntax.StarlarkType
        ) {
            assertLt(t1, t2)
            assertNotLt(t2, t1)
        }

        /** Asserts that the given types are assignable in both directions.  */
        private fun assertLtAndGt(vararg types: net.starlark.java.syntax.StarlarkType?) {
            for (i in 0..<types.size - 1) {
                for (j in i + 1..<types.size) {
                    assertLt(types[i], types[j])
                    assertLt(types[j], types[i])
                }
            }
        }

        /** Asserts that the given types are *not* assignable in either direction.  */
        private fun assertIncomparable(vararg types: net.starlark.java.syntax.StarlarkType?) {
            for (i in 0..<types.size - 1) {
                for (j in i + 1..<types.size) {
                    assertNotLt(types[i], types[j])
                    assertNotLt(types[j], types[i])
                }
            }
        }

        /**
         * Asserts that the given types form a strict chain of assignability, with the ith element being
         * assignable to all jth elements where i < j, but not vice versa.
         */
        private fun assertStrictLtChain(vararg types: net.starlark.java.syntax.StarlarkType?) {
            for (i in 0..<types.size - 1) {
                for (j in i + 1..<types.size) {
                    assertStrictLt(types[i], types[j])
                }
            }
        }
    }
}
