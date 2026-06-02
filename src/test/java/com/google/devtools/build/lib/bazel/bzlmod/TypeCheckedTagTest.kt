// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [TypeCheckedTag].  */
@RunWith(JUnit4::class)
class TypeCheckedTagTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basic() {
        val typeCheckedTag: TypeCheckedTag =
            TypeCheckedTag.create(
                createTagClass(attr("foo", Type.INTEGER).build()),
                BzlmodTestUtil.buildTag("tag_name").addAttr("foo", StarlarkInt.of(3)).setDevDependency()
                    .build(),  /* labelConverter= */
                null,
                "root module",  /* moduleIndex= */
                0,  /* tagIndex= */
                0
            )
        assertThat(typeCheckedTag.getFieldNames()).containsExactly("foo")
        Truth.assertThat(getattr(typeCheckedTag, "foo")).isEqualTo(StarlarkInt.of(3))
        assertThat(typeCheckedTag.isDevDependency).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun label() {
        val typeCheckedTag: TypeCheckedTag =
            TypeCheckedTag.create(
                createTagClass(
                    attr("foo", BuildType.LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE).build()
                ),
                BzlmodTestUtil.buildTag("tag_name")
                    .addAttr(
                        "foo", StarlarkList.immutableOf<String?>(":thing1", "//pkg:thing2", "@repo//pkg:thing3")
                    )
                    .build(),
                LabelConverter(
                    PackageIdentifier.parse("@myrepo//mypkg"),
                    BzlmodTestUtil.createRepositoryMapping(
                        BzlmodTestUtil.createModuleKey("test", "1.0"),
                        "repo",
                        "other_repo"
                    )
                ),
                "root module",  /* moduleIndex= */
                0,  /* tagIndex= */
                0
            )
        assertThat(typeCheckedTag.getFieldNames()).containsExactly("foo")
        Truth.assertThat(getattr(typeCheckedTag, "foo"))
            .isEqualTo(
                StarlarkList.immutableOf<T?>(
                    Label.parseCanonicalUnchecked("@myrepo//mypkg:thing1"),
                    Label.parseCanonicalUnchecked("@myrepo//pkg:thing2"),
                    Label.parseCanonicalUnchecked("@other_repo//pkg:thing3")
                )
            )
        assertThat(typeCheckedTag.isDevDependency).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun label_withoutDefaultValue() {
        val typeCheckedTag: TypeCheckedTag =
            TypeCheckedTag.create(
                createTagClass(
                    attr("foo", BuildType.LABEL).allowedFileTypes(FileTypeSet.ANY_FILE).build()
                ),
                BzlmodTestUtil.buildTag("tag_name").setDevDependency().build(),
                LabelConverter(
                    PackageIdentifier.parse("@myrepo//mypkg"),
                    BzlmodTestUtil.createRepositoryMapping(
                        BzlmodTestUtil.createModuleKey("test", "1.0"),
                        "repo",
                        "other_repo"
                    )
                ),
                "root module",  /* moduleIndex= */
                0,  /* tagIndex= */
                0
            )
        assertThat(typeCheckedTag.getFieldNames()).containsExactly("foo")
        Truth.assertThat(getattr(typeCheckedTag, "foo")).isEqualTo(Starlark.NONE)
        assertThat(typeCheckedTag.isDevDependency).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stringListDict_default() {
        val typeCheckedTag: TypeCheckedTag =
            TypeCheckedTag.create(
                createTagClass(
                    attr("foo", Types.STRING_LIST_DICT)
                        .value(
                            com.google.common.collect.ImmutableMap.of<K?, V?>(
                                "key",
                                com.google.common.collect.ImmutableList.of<E?>("value1", "value2")
                            )
                        )
                        .build()
                ),
                BzlmodTestUtil.buildTag("tag_name").build(),
                null,
                "root module",  /* moduleIndex= */
                0,  /* tagIndex= */
                0
            )
        assertThat(typeCheckedTag.getFieldNames()).containsExactly("foo")
        Truth.assertThat(getattr(typeCheckedTag, "foo"))
            .isEqualTo(
                Dict.builder<Any?, Any?>()
                    .put("key", StarlarkList.immutableOf<String?>("value1", "value2"))
                    .buildImmutable()
            )
        assertThat(typeCheckedTag.isDevDependency).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleAttributesAndDefaults() {
        val typeCheckedTag: TypeCheckedTag =
            TypeCheckedTag.create(
                BzlmodTestUtil.createTagClass(
                    attr("foo", Type.STRING).mandatory().build(),
                    attr("bar", Type.INTEGER).value(StarlarkInt.of(3)).build(),
                    attr("quux", Types.STRING_LIST).build()
                ),
                BzlmodTestUtil.buildTag("tag_name")
                    .addAttr("foo", "fooValue")
                    .addAttr("quux", StarlarkList.immutableOf<String?>("quuxValue1", "quuxValue2"))
                    .build(),  /* labelConverter= */
                null,
                "root module",  /* moduleIndex= */
                0,  /* tagIndex= */
                0
            )
        assertThat(typeCheckedTag.getFieldNames()).containsExactly("foo", "bar", "quux")
        Truth.assertThat(getattr(typeCheckedTag, "foo")).isEqualTo("fooValue")
        Truth.assertThat(getattr(typeCheckedTag, "bar")).isEqualTo(StarlarkInt.of(3))
        Truth.assertThat(getattr(typeCheckedTag, "quux"))
            .isEqualTo(StarlarkList.immutableOf<String?>("quuxValue1", "quuxValue2"))
        assertThat(typeCheckedTag.isDevDependency).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mandatory() {
        val e: ExternalDepsException? =
            org.junit.Assert.assertThrows<T?>(
                ExternalDepsException::class.java,
                org.junit.function.ThrowingRunnable {
                    TypeCheckedTag.create(
                        createTagClass(attr("foo", Type.STRING).mandatory().build()),
                        BzlmodTestUtil.buildTag("tag_name").build(),  /* labelConverter= */
                        null,
                        "root module",  /* moduleIndex= */
                        0,  /* tagIndex= */
                        0
                    )
                })
        assertThat(e).hasMessageThat().contains("mandatory attribute 'foo' isn't being specified")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowedValues() {
        val e: ExternalDepsException? =
            org.junit.Assert.assertThrows<T?>(
                ExternalDepsException::class.java,
                org.junit.function.ThrowingRunnable {
                    TypeCheckedTag.create(
                        createTagClass(
                            attr("foo", Type.STRING)
                                .allowedValues(AllowedValueSet("yes", "no"))
                                .build()
                        ),
                        BzlmodTestUtil.buildTag("tag_name").addAttr("foo", "maybe").build(),  /* labelConverter= */
                        null,
                        "root module",  /* moduleIndex= */
                        0,  /* tagIndex= */
                        0
                    )
                })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "the value for attribute 'foo' has to be one of 'yes' or 'no' instead of 'maybe'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownAttr() {
        val e: ExternalDepsException? =
            org.junit.Assert.assertThrows<T?>(
                ExternalDepsException::class.java,
                org.junit.function.ThrowingRunnable {
                    TypeCheckedTag.create(
                        createTagClass(attr("foo", Type.STRING).build()),
                        BzlmodTestUtil.buildTag("tag_name").addAttr("bar", "maybe").build(),  /* labelConverter= */
                        null,
                        "root module",  /* moduleIndex= */
                        0,  /* tagIndex= */
                        0
                    )
                })
        assertThat(e).hasMessageThat().contains("unknown attribute 'bar' provided")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sortKey() {
        val keyM1T1: TypeCheckedTag.SortKey = SortKey(1, 1)
        val keyM2T1: TypeCheckedTag.SortKey = SortKey(2, 1)
        val keyM1T2: TypeCheckedTag.SortKey = SortKey(1, 2)
        val keyM2T2: TypeCheckedTag.SortKey = SortKey(2, 2)

        assertThat(keyM1T1).isLessThan(keyM2T1)
        assertThat(keyM2T1).isGreaterThan(keyM1T1)
        assertThat(keyM1T1).isLessThan(keyM1T2)
        assertThat(keyM1T2).isGreaterThan(keyM1T1)
        assertThat(keyM2T1).isLessThan(keyM2T2)
        assertThat(keyM2T2).isGreaterThan(keyM2T1)
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun getattr(structure: Structure?, fieldName: String?): Any? {
            return Starlark.getattr(
                Mutability.IMMUTABLE,
                StarlarkSemantics.DEFAULT,
                structure,
                fieldName,  /* defaultValue= */
                null
            )
        }
    }
}
