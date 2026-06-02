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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * The result of BzlCompileFunction, which compiles a .bzl file. There are two subclasses: `Success`, for when the file is compiled successfully, and `Failure`, for when the file does
 * not exist, or had parser/resolver errors.
 */
// In practice, almost any change to a .bzl causes the BzlCompileValue to be recomputed.
// We could do better with a finer-grained notion of equality than "the source
// files differ". In particular, a trivial change such as fixing a typo in a comment should not
// cause invalidation. (Changes that are only slightly more substantial may be semantically
// significant. For example, inserting a blank line affects subsequent line numbers, which appear
// in error messages and query output.)
//
// Comparing syntax trees for equality is complex and expensive, so the most practical
// implementation of this optimization will have to wait until Starlark files are compiled,
// at which point byte-equality of the compiled representation (which is simple to compute)
// will serve.
//
// TODO(adonovan): actually compile the code. The name is a step ahead of the implementation.
abstract class BzlCompileValue : NotComparableSkyValue {
    abstract fun lookupSuccessful(): Boolean

    // on success
    @kotlin.jvm.JvmField
    abstract val program: net.starlark.java.syntax.Program?

    // on success
    @kotlin.jvm.JvmField
    abstract val digest: ByteArray?

    // on success
    @kotlin.jvm.JvmField
    abstract val typeOptions: TypeOptions?

    // on failure
    @kotlin.jvm.JvmField
    abstract val error: String?

    /** If the file is compiled successfully, this class encapsulates the compiled program.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    class Success private constructor(
        prog: net.starlark.java.syntax.Program?,
        digest: ByteArray?,
        private val typeOptions: TypeOptions?
    ) : BzlCompileValue() {
        private val prog: net.starlark.java.syntax.Program
        private val digest: ByteArray

        init {
            this.prog = com.google.common.base.Preconditions.checkNotNull<net.starlark.java.syntax.Program>(prog)
            this.digest = com.google.common.base.Preconditions.checkNotNull<ByteArray?>(digest)
        }

        override fun lookupSuccessful(): Boolean {
            return true
        }

        override fun getProgram(): net.starlark.java.syntax.Program {
            return this.prog
        }

        override fun getDigest(): ByteArray {
            return this.digest
        }

        override fun getTypeOptions(): TypeOptions? {
            return this.typeOptions
        }

        override fun getError(): String? {
            throw java.lang.IllegalStateException(
                "attempted to retrieve unsuccessful lookup reason for successful lookup"
            )
        }
    }

    /** If the file isn't found or has errors, this class encapsulates a message with the reason.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    class Failure private constructor(errorMsg: String?) : BzlCompileValue() {
        private val errorMsg: String

        init {
            this.errorMsg = com.google.common.base.Preconditions.checkNotNull<String>(errorMsg)
        }

        override fun lookupSuccessful(): Boolean {
            return false
        }

        override fun getProgram(): net.starlark.java.syntax.Program? {
            throw java.lang.IllegalStateException(
                "attempted to retrieve .bzl program from an unsuccessful lookup"
            )
        }

        override fun getDigest(): ByteArray? {
            throw java.lang.IllegalStateException("attempted to retrieve digest for unsuccessful lookup")
        }

        override fun getError(): String {
            return this.errorMsg
        }

        override fun getTypeOptions(): TypeOptions? {
            throw java.lang.IllegalStateException("attempted to retrieve type options for unsuccessful lookup")
        }
    }

    /** Types of bzl files we may encounter.  */
    internal enum class Kind {
        /** A regular .bzl file loaded on behalf of a BUILD or WORKSPACE file.  */ // The reason we can share a single key type for these environments is that they have the same
        // symbol names, even though their symbol definitions (particularly for the "native" object)
        // differ. (See also #11954, which aims to make even the symbol definitions the same.)
        NORMAL,

        /** A .bzl file loaded during evaluation of the `@_builtins` pseudo-repository.  */
        BUILTINS,

        /** The prelude file, whose declarations are implicitly loaded by all BUILD files.  */
        PRELUDE,

        /**
         * A virtual empty file that does not correspond to a lookup in the filesystem. This is used for
         * the default prelude contents, when the real prelude's contents should be ignored (in
         * particular, when its package is missing).
         */
        EMPTY_PRELUDE,
    }

    /**
     * Type-checking options.
     * 
     * @param useTypeSyntax If true, permit type annotation syntax in the file.
     * @param wantStaticTypeChecking If true, BzlLoadFunction should perform static type checking.
     * @param wantDynamicTypeChecking If true, BzlLoadFunction should enable dynamic type checking
     * during evaluation.
     */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class TypeOptions(
      val useTypeSyntax: Boolean,
      @kotlin.jvm.JvmField val wantStaticTypeChecking: Boolean,
      @kotlin.jvm.JvmField val wantDynamicTypeChecking: Boolean
    )

    /** SkyKey for retrieving a compiled .bzl program.  */
    @AutoCodec
    class Key private constructor(root: Root?, label: Label?, kind: Kind?) : SkyKey {
        /** The root in which the .bzl file is to be found. Null for EMPTY_PRELUDE.  */
        val root: Root?

        /** The label of the .bzl to be retrieved. Null for EMPTY_PRELUDE.  */
        val label: Label?

        val kind: Kind

        init {
            this.root = root
            this.label = label
            this.kind = com.google.common.base.Preconditions.checkNotNull<Kind>(kind)
            if (kind != com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.EMPTY_PRELUDE) {
                com.google.common.base.Preconditions.checkNotNull<Root?>(root)
                com.google.common.base.Preconditions.checkNotNull<Any?>(label)
            }
        }

        val isBuiltins: Boolean
            /** Returns whether this key is for a `@_builtins` .bzl file.  */
            get() = kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.BUILTINS

        val isSclDialect: Boolean
            /** Returns true if the requested file follows the .scl dialect.  */
            get() = label != null && label.name.endsWith(".scl")

        val isBuildPrelude: Boolean
            get() = kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.PRELUDE || kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.EMPTY_PRELUDE

        fun getLabel(): Label? {
            return label
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                com.google.devtools.build.lib.skyframe.BzlCompileValue.Key::class.java,
                root,
                label,
                kind
            )
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other is Key) {
                // Compare roots last since that's the more expensive step.
                return this.kind == other.kind && this.label == other.label
                        && this.root == other.root
            }
            return false
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.BZL_COMPILE
        }

        override fun toString(): String {
            return java.lang.String.format("%s:[%s]%s", functionName(), root, label)
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.BzlCompileValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(root: Root?, label: Label?, kind: Kind?): Key {
                return com.google.devtools.build.lib.skyframe.BzlCompileValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.BzlCompileValue.Key(
                        root,
                        label,
                        kind
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.BzlCompileValue.Key.Companion.interner.intern(key)
            }
        }
    }

    companion object {
        /** Constructs a value from a failure before parsing a file.  */
        @com.google.errorprone.annotations.FormatMethod
        fun noFile(format: String, vararg args: Any?): BzlCompileValue {
            return com.google.devtools.build.lib.skyframe.BzlCompileValue.Failure(
                java.lang.String.format(
                    format,
                    *args
                )
            )
        }

        /** Constructs a value from a compiled .bzl program.  */
        fun withProgram(
            prog: net.starlark.java.syntax.Program?,
            digest: ByteArray?,
            typeOptions: TypeOptions?
        ): BzlCompileValue {
            return com.google.devtools.build.lib.skyframe.BzlCompileValue.Success(prog, digest, typeOptions)
        }

        /** Constructs a key for loading a regular (non-prelude) .bzl.  */
        fun key(root: Root?, label: Label?): Key {
            return com.google.devtools.build.lib.skyframe.BzlCompileValue.Key.Companion.create(
                root,
                label,
                com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.NORMAL
            )
        }

        /** Constructs a key for loading a builtins .bzl.  */
        fun keyForBuiltins(root: Root?, label: Label?): Key {
            return com.google.devtools.build.lib.skyframe.BzlCompileValue.Key.Companion.create(
                root,
                label,
                com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.BUILTINS
            )
        }

        /** Constructs a key for loading the prelude .bzl.  */
        @com.google.common.annotations.VisibleForTesting
        fun keyForBuildPrelude(root: Root?, label: Label?): Key {
            return com.google.devtools.build.lib.skyframe.BzlCompileValue.Key.Companion.create(
                root,
                label,
                com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.PRELUDE
            )
        }

        /** The unique SkyKey of EMPTY_PRELUDE kind.  */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val EMPTY_PRELUDE_KEY: Key =
            com.google.devtools.build.lib.skyframe.BzlCompileValue.Key( /*root=*/null,  /*label=*/
                null,
                com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.EMPTY_PRELUDE
            )
    }
}
