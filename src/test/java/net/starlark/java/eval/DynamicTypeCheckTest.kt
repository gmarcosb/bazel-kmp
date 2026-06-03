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
package net.starlark.java.eval

import com.google.common.truth.StringSubject
import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.eval.EvaluationTestCase
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.TypeConstructor.createStarlarkType
import net.starlark.java.syntax.Types.CallableType.toSignatureString
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for Starlark type checking at evaluation time.  */
@RunWith(JUnit4::class)
class DynamicTypeCheckTest {
    private var ev: EvaluationTestCase? = null

    // TODO: #27728 - No need to add these mocks to the testing Module in setup() once the production
    // version of these symbols are available in the actual Starlark universe.
    private class CollectionSymbol : StarlarkValue, net.starlark.java.syntax.TypeConstructor {
        @Throws(net.starlark.java.syntax.TypeConstructor.Failure::class)
        override fun createStarlarkType(argsTuple: com.google.common.collect.ImmutableList<net.starlark.java.syntax.TypeConstructor.Arg?>?): net.starlark.java.syntax.StarlarkType? {
            return net.starlark.java.syntax.Types.COLLECTION_CONSTRUCTOR.createStarlarkType(argsTuple)
        }
    }

    private class SequenceSymbol : StarlarkValue, net.starlark.java.syntax.TypeConstructor {
        @Throws(net.starlark.java.syntax.TypeConstructor.Failure::class)
        override fun createStarlarkType(argsTuple: com.google.common.collect.ImmutableList<net.starlark.java.syntax.TypeConstructor.Arg?>?): net.starlark.java.syntax.StarlarkType? {
            return net.starlark.java.syntax.Types.SEQUENCE_CONSTRUCTOR.createStarlarkType(argsTuple)
        }
    }

    private class MappingSymbol : StarlarkValue, net.starlark.java.syntax.TypeConstructor {
        @Throws(net.starlark.java.syntax.TypeConstructor.Failure::class)
        override fun createStarlarkType(argsTuple: com.google.common.collect.ImmutableList<net.starlark.java.syntax.TypeConstructor.Arg?>?): net.starlark.java.syntax.StarlarkType? {
            return net.starlark.java.syntax.Types.MAPPING_CONSTRUCTOR.createStarlarkType(argsTuple)
        }
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        ev = EvaluationTestCase()
        ev.setFileOptions(
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).resolveTypeSyntax(true).build()
        )
        ev.setSemantics(
            StarlarkSemantics.builder()
                .setBool(StarlarkSemantics.EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING, true)
                .build()
        )

        ev.update("Collection", CollectionSymbol())
        ev.update("Sequence", SequenceSymbol())
        ev.update("Mapping", MappingSymbol())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typechecking_disabledByFlag() {
        ev.setSemantics(
            StarlarkSemantics.builder()
                .setBool(StarlarkSemantics.EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING, false)
                .build()
        )

        ev.exec("def f(a : int): pass", "f('abc')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_primitiveTypes() {
        ev.exec("def f(a: None): pass", "f(None)")
        ev.exec("def f(a: bool): pass", "f(True)")
        ev.exec("def f(a: int): pass", "f(1)")
        ev.exec("def f(a: float): pass", "f(1.1)")
        ev.exec("def f(a: str): pass", "f('abc')")
        ev.exec("def f(a): pass", "f('abc')")
        ev.exec("def f(a): pass", "f(['abc'])")
        ev.exec("def f(x): pass", "def g(x): pass", "f(g)")
        // int is not below float
        assertExecThrows<T?>(EvalException::class.java, "def f(a: float): pass", "f(1)")
            .isEqualTo("in call to f(), parameter 'a' got value of type 'int', want 'float'")

        assertExecThrows<T?>(EvalException::class.java, "def f(a: int): pass", "f('abc')")
            .isEqualTo("in call to f(), parameter 'a' got value of type 'str', want 'int'")

        assertExecThrows(EvalException::class.java, "def f(a: int = 'abc'): pass")
            .isEqualTo("f(): parameter 'a' has default value of type 'str', declares 'int'")

        assertExecThrows<T?>(EvalException::class.java, "def f() -> int: return 'abc'", "f()")
            .isEqualTo("f(): returns value of type 'str', declares 'int'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_list() {
        ev.exec("def f(a: list): pass", "f([1, 2])")
        ev.exec("def f(a: list[int]): pass", "f([1, 2])")
        ev.exec("def f(a: list[int]): pass", "f([])")
        ev.exec("def f(a: list[list[int]]): pass", "f([[], [1]])")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: list[int]): pass", "f([True])")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'list[bool]', want 'list[int]'"
            )
        assertExecThrows<T?>(EvalException::class.java, "def f(a: list[list[int]]): pass", "f([[1], [True]])")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'list[list[int]|list[bool]]', "
                        + "want 'list[list[int]]'"
            )
        assertExecThrows<T?>(EvalException::class.java, "def f(a: list[list[int]]): pass", "f([[1, True]])")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'list[list[int|bool]]', "
                        + "want 'list[list[int]]'"
            )
        // invariance
        assertExecThrows<T?>(EvalException::class.java, "def f(a: list[None|int]): pass", "f([1])")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'list[int]', want 'list[None|int]'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_unions() {
        ev.exec("def f(a: None|bool): pass", "f(None)")
        ev.exec("def f(a: None|bool): pass", "f(True)")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: None|bool): pass", "f(1)")
            .isEqualTo("in call to f(), parameter 'a' got value of type 'int', want 'None|bool'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_dict() {
        ev.exec("def f(a: dict[int, str]): pass", "f({1: 'a', 2: 'b'})")
        ev.exec("def f(a: dict[int, str]): pass", "f({})")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: dict[int, str]): pass", "f({'a': 1})")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'dict[str, int]', "
                        + "want 'dict[int, str]'"
            )
        assertExecThrows<T?>(EvalException::class.java, "def f(a: dict[int, str]): pass", "f({1: 1})")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'dict[int, int]', "
                        + "want 'dict[int, str]'"
            )
        ev.exec("def f(a: dict[int, list[str]]): pass", "f({1: ['a'], 2: ['b']})")
        assertExecThrows<T?>(
            EvalException::class.java, "def f(a: dict[int, list[str]]): pass", "f({1: [1], 2: [2]})"
        )
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'dict[int, list[int]]', "
                        + "want 'dict[int, list[str]]'"
            )
        assertExecThrows<T?>(
            EvalException::class.java, "def f(a: dict[int, list[str]]): pass", "f({1: ['a', 1]})"
        )
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'dict[int, list[str|int]]', "
                        + "want 'dict[int, list[str]]'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_set() {
        ev.exec("def f(a: set): pass", "f(set([1, 2]))")
        ev.exec("def f(a: set[int]): pass", "f(set([1, 2]))")
        ev.exec("def f(a: set[int]): pass", "f(set())")
        // invariance
        assertExecThrows<T?>(EvalException::class.java, "def f(a: set[int|str]): pass", "f(set([1, 2]))")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'set[int]', want 'set[int|str]'"
            )
        assertExecThrows<T?>(EvalException::class.java, "def f(a: set[int]): pass", "f(set([True]))")
            .isEqualTo("in call to f(), parameter 'a' got value of type 'set[bool]', want 'set[int]'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_tuple() {
        ev.exec("def f(a: tuple[()]): pass", "f(())")
        ev.exec("def f(a: tuple[int, str]): pass", "f((1, 'a'))")
        ev.exec("def f(a: tuple[int, str, bool]): pass", "f((1, 'a', True))")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: tuple[int, str]): pass", "f((1, 2))")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'tuple[int, int]', want 'tuple[int,"
                        + " str]'"
            )
        assertExecThrows<T?>(EvalException::class.java, "def f(a: tuple[int, str]): pass", "f((1,))")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'tuple[int]', want 'tuple[int,"
                        + " str]'"
            )
        ev.exec("def f(a: tuple[int, tuple[str, bool]]): pass", "f((1, ('a', True)))")
        // Covariance
        ev.exec("def f(a: tuple[None|int]): pass", "f((1,))")
        // Homogeneous tuples
        ev.exec("def f(a: tuple[int | str, ...]): pass", "f((1, 2, '3'))")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: tuple[int, ...]): pass", "f((1, 2, '3'))")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'tuple[int, int, str]', want"
                        + " 'tuple[int, ...]'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_collection() {
        ev.exec("def f(a: Collection[int]): pass", "f([1, 2])")
        ev.exec("def f(a: Collection[str]): pass", "f({'a': 1, 'b': 2})")
        ev.exec("def f(a: Collection[str]): pass", "f(set(['a', 'b']))")
        ev.exec("def f(a: Collection[str]): pass", "f(('a', 'b'))")
        ev.exec("def f(a: Collection[list[str]]): pass", "f([['a', 'b'], ['c']])")
        ev.exec("def f(a: Collection[int|str]): pass", "f(['a', 'b'])")
        ev.exec("def f(a: Collection[int|str]): pass", "f(['a', 1])")
        ev.exec("def f(a: Collection[int|str]): pass", "f(('a', 1))")
        ev.exec("def f(a: Collection[Collection[str]]): pass", "f([['a', 'b'], ['c']])")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: Collection[int]): pass", "f({'a': 1})")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'dict[str, int]', want"
                        + " 'Collection[int]'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_sequence() {
        ev.exec("def f(a: Sequence[int]): pass", "f([1, 2])")
        ev.exec("def f(a: Sequence[str]): pass", "f(('a', 'b'))")
        ev.exec("def f(a: Sequence[list[str]]): pass", "f([['a', 'b'], ['c']])")
        ev.exec("def f(a: Sequence[int|str]): pass", "f(['a', 'b'])")
        ev.exec("def f(a: Sequence[int|str]): pass", "f(['a', 1])")
        ev.exec("def f(a: Sequence[int|str]): pass", "f(('a', 1))")
        ev.exec("def f(a: Sequence[Sequence[str]]): pass", "f([['a', 'b'], ['c']])")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: Sequence[int]): pass", "f({'a': 1})")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'dict[str, int]', want"
                        + " 'Sequence[int]'"
            )
        assertExecThrows<T?>(EvalException::class.java, "def f(a: Sequence[int]): pass", "f(set([1, 2]))")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'set[int]', want"
                        + " 'Sequence[int]'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runtimeTypecheck_mapping() {
        ev.exec("def f(a: Mapping[str, int]): pass", "f({'a': 1, 'b': 2})")
        assertExecThrows<T?>(EvalException::class.java, "def f(a: Mapping[str, int]): pass", "f([1, 2])")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'list[int]', want"
                        + " 'Mapping[str, int]'"
            )
        assertExecThrows<T?>(EvalException::class.java, "def f(a: Mapping[str, int]): pass", "f(set([1, 2]))")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'set[int]', want"
                        + " 'Mapping[str, int]'"
            )
        // Covariance in value
        ev.exec("def f(a: Mapping[str, None|int]): pass", "f({'a': 1})")
        // Invariance in key
        assertExecThrows<T?>(EvalException::class.java, "def f(a: Mapping[None|str, int]): pass", "f({'a': 1})")
            .isEqualTo(
                "in call to f(), parameter 'a' got value of type 'dict[str, int]', want"
                        + " 'Mapping[None|str, int]'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun union_edgeCaseSyntax() {
        ev.exec("def f(a: None|None): pass", "f(None)")
        ev.exec("def f(a: None|bool|bool): pass", "f(None)")
        ev.exec("def f(a: None|bool|str): pass", "f(None)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lambdaDoesntFail() {
        // Lambda has functionType set to null
        ev.exec(
            "def f(a: None):",  //
            "  x = lambda y: 1",
            "  x(1)",
            "  y = lambda y = 1: 1",
            "  y(1)",
            "f(None)"
        )
    }

    @org.junit.Test
    fun testStarlarkUniverseTypes() {
        val builder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (entry in Starlark.UNIVERSE.entrySet()) {
            val type: net.starlark.java.syntax.StarlarkType? =
                Starlark.getStarlarkType(entry.getValue(), ev.getStarlarkThread().getSemantics())
            if (type is net.starlark.java.syntax.Types.CallableType) {
                builder.add(entry.getKey() + ": " + type.toSignatureString())
            } else {
                builder.add(entry.getKey() + ": " + type)
            }
        }

        Truth.assertThat(builder.build())
            .containsAtLeast(
                "False: bool",
                "True: bool",
                "None: None",
                "hash: (str, /) -> int",
                "bool: ([object], /) -> bool",
                "getattr: (object, str, [object], /) -> Any",
                "hasattr: (object, str, /) -> bool",
                "repr: (object, /) -> str",
                "str: (object, /) -> str",
                "type: (object, /) -> str",
                "float: ([str|bool|int|float], /) -> float",
                "int: (str|bool|int|float, /, base: [int]) -> int",
                "dir: (object, /) -> list[str]",
                "all: (Collection[object], /) -> bool",
                "any: (Collection[object], /) -> bool",
                "range: (int, [int], [int], /) -> Sequence[int]",
                "len: (Collection[object]|str, /) -> int"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringFields() {
        val s = "Hello!"
        val builder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (name in Starlark.dir(Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT, s)) {
            val type: net.starlark.java.syntax.StarlarkType? =
                Starlark.getStarlarkType(
                    Starlark.getattr(Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT, s, name, null),
                    ev.getStarlarkThread().getSemantics()
                )
            if (type is net.starlark.java.syntax.Types.CallableType) {
                builder.add(name + ": " + type.toSignatureString())
            } else {
                builder.add(name + ": " + type)
            }
        }

        Truth.assertThat(builder.build())
            .containsAtLeast(
                "capitalize: () -> str",
                "count: (str, [int|None], [int|None], /) -> int",
                "elems: () -> Sequence[str]",
                "find: (str, [int|None], [int|None], /) -> int",
                "index: (str, [int|None], [int|None], /) -> int",
                "isalnum: () -> bool",
                "isalpha: () -> bool",
                "isdigit: () -> bool",
                "islower: () -> bool",
                "isspace: () -> bool",
                "istitle: () -> bool",
                "isupper: () -> bool",
                "join: (Collection[str], /) -> str",
                "lower: () -> str",
                "lstrip: ([str|None], /) -> str",
                "removeprefix: (str, /) -> str",
                "removesuffix: (str, /) -> str",
                "replace: (str, str, [int], /) -> str",
                "rfind: (str, [int|None], [int|None], /) -> int",
                "rindex: (str, [int|None], [int|None], /) -> int",
                "rsplit: (sep: str, maxsplit: [int]) -> list[str]",
                "rstrip: ([str|None], /) -> str",
                "split: (sep: str, maxsplit: [int]) -> list[str]",
                "splitlines: ([bool], /) -> Sequence[str]",
                "strip: ([str|None], /) -> str",
                "title: () -> str",
                "upper: () -> str"
            )
        // TODO(ilist@): format (args,kwargs), partition, rpartition (returns tuple), startswith,
        // endswith (takes tuple)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListFields() {
        val list: StarlarkList<String?>? = StarlarkList.immutableOf("abc")
        val builder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (name in Starlark.dir(Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT, list)) {
            val type: net.starlark.java.syntax.StarlarkType? =
                Starlark.getStarlarkType(
                    Starlark.getattr(Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT, list, name, null),
                    ev.getStarlarkThread().getSemantics()
                )
            if (type is net.starlark.java.syntax.Types.CallableType) {
                builder.add(name + ": " + type.toSignatureString())
            } else {
                builder.add(name + ": " + type)
            }
        }

        // TODO(ilist@): Any should be string. Handle type variables
        Truth.assertThat(builder.build())
            .containsExactly(
                "append: (Any, /) -> None",
                "clear: () -> None",
                "extend: (Collection[Any], /) -> None",
                "index: (Any, [int], [int], /) -> int",
                "insert: (int, Any, /) -> None",
                "pop: ([int], /) -> Any",
                "remove: (Any, /) -> None"
            )
    }

    private fun <T : Throwable?> assertExecThrows(
        expectedThrowable: java.lang.Class<T?>, vararg lines: String?
    ): StringSubject {
        val evalException: T? = org.junit.Assert.assertThrows<T?>(
            expectedThrowable,
            org.junit.function.ThrowingRunnable { ev.exec(*lines) })
        return Truth.assertThat(evalException).hasMessageThat()
    }
}
