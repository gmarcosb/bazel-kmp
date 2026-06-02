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

import com.google.devtools.build.lib.cmdline.Label.labelCodec

/**
 * A value that represents the .bzl (or .scl) module loaded by a Starlark `load()` statement.
 * 
 * 
 * Note: Historically, all modules had the .bzl suffix, but this is no longer true now that Bazel
 * supports the .scl dialect. In identifiers, code comments, and documentation, you should generally
 * assume any "bzl" term could mean a .scl file as well.
 * 
 * 
 * The key consists of an absolute [Label] and the context in which the load occurs. The
 * Label should not reference the special `external` package.
 * 
 * 
 * This value is also used to represent the special prelude file that may be implicitly loaded
 * and sourced by BUILD files. The prelude file need not end in ".bzl".
 */
class BzlLoadValue @com.google.common.annotations.VisibleForTesting constructor(
    module: net.starlark.java.eval.Module?,
    transitiveDigest: ByteArray?,
    bzlVisibility: BzlVisibility?,
    recordedRepoMappings: com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?>?
) : SkyValue {
    private val module: net.starlark.java.eval.Module? // .bzl module (and indirectly, the entire load DAG)

    /** Returns the digest of the .bzl module and its transitive load dependencies.  */
    // TODO(brandjon): Is this field redundant with BazelModuleContext#bzlTransitiveDigest, accessible
    // from the Module as client data?
    @kotlin.jvm.JvmField
    val transitiveDigest: ByteArray? // of .bzl file and load dependencies
    private val bzlVisibility: BzlVisibility?

    /**
     * Returns the repo mapping entries used to laod this bzl file. Stored for correctness across
     * Bazel server restarts.
     */
    @kotlin.jvm.JvmField
    val recordedRepoMappings: com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?>? =
        null

    /** Returns the .bzl module.  */
    fun getModule(): net.starlark.java.eval.Module? {
        return module
    }

    /** Returns the visibility of this module for the purpose of `load()` statements.  */
    fun getBzlVisibility(): BzlVisibility? {
        return bzlVisibility
    }

    init {
        .also {
            this.module = it
        }<Module> com . google . common . base . Preconditions . checkNotNull < net . starlark . java . eval . Module ? > (module)
            .also { this.transitiveDigest = it } <
                com.google.common.base.Preconditions.checkNotNull<ByteArray?>(transitiveDigest)
                    .also {
                        this.bzlVisibility = it
                    }<BzlVisibility> com . google . common . base . Preconditions . checkNotNull < kotlin . Any ? > (bzlVisibility)
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.recordedRepoMappings = <ImmutableTable<RepositoryName, String,RepositoryName>>checkNotNull(recordedRepoMappings);
            """.trimMargin()
        )
    }

    private abstract class KeyForLocalEval : Key()


    /** SkyKey for a Starlark load.  */
    abstract class Key  // Closed, for class-based equals()/hashCode().
    private constructor() : BazelModuleKey {
        /**
         * Returns the absolute label of the .bzl file to be loaded.
         * 
         * 
         * For [KeyForBuiltins], it must begin with `@_builtins//:`. (It is legal for
         * other keys to use `@_builtins`, but since no real repo by that name may be defined,
         * they won't evaluate to a successful result.)
         */
        @kotlin.jvm.JvmField
        abstract val label: Label?

        open val isBuildPrelude: Boolean
            /** Returns true if this is a request for the special BUILD prelude file.  */
            get() = false

        open val isBuiltins: Boolean
            /** Returns true if this is a request for a builtins bzl file.  */
            get() = false

        val isSclDialect: Boolean
            /** Returns true if the requested file follows the .scl dialect.  */
            get() = this.label.name.endsWith(".scl")

        /**
         * Constructs a new key suitable for evaluating a `load()` dependency of this key's .bzl
         * file.
         * 
         * 
         * The new key uses the given label but the same contextual information -- whether the
         * top-level requesting value is a BUILD or WORKSPACE file, and if it's a WORKSPACE, its
         * chunking info.
         */
        abstract fun getKeyForLoad(loadLabel: Label?): Key?

        /**
         * Constructs a BzlCompileValue key suitable for retrieving the Starlark code for this .bzl,
         * given the Root in which to find its file.
         */
        abstract fun getCompileKey(root: Root?): com.google.devtools.build.lib.skyframe.BzlCompileValue.Key?

        public override fun valueIsShareable(): Boolean {
            // We don't guarantee that all constructs implement equality, meaning we can't correctly
            // compare deserialized instances. This is currently the case for attribute descriptors.
            return false
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj == null) {
                return false
            }
            if (this.getClass() != obj.getClass()) {
                return false
            }
            val that = obj as Key
            return this.label.equals(that.label)
                    && (this.isBuildPrelude == that.isBuildPrelude)
                    && (this.isBuiltins == that.isBuiltins)
        }

        override fun hashCode(): Int {
            var result: Int = HashCodes.hashObjects(getClass(), this.label)
            result = 31 * result + java.lang.Boolean.hashCode(this.isBuildPrelude)
            result = 31 * result + java.lang.Boolean.hashCode(this.isBuiltins)
            return result
        }

        protected fun toStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("label", this.label)
                .add("isBuildPrelude", this.isBuildPrelude)
        }

        override fun toString(): String {
            return toStringHelper().toString()
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = keyInterner
    }

    /** A key for loading a .bzl during package loading (BUILD evaluation).  */
    @Immutable
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class KeyForBuild private constructor(
        label: Label?,
        /**
         * True if this is the special prelude file, whose declarations are implicitly loaded by all
         * BUILD files.
         */
        private val isBuildPrelude: Boolean
    ) : KeyForLocalEval() {
        private val label: Label

        init {
            this.label = com.google.common.base.Preconditions.checkNotNull<Label>(label)
        }

        override fun getLabel(): Label {
            return label
        }

        override fun isBuildPrelude(): Boolean {
            return isBuildPrelude
        }

        override fun getKeyForLoad(loadLabel: Label?): Key {
            // Note that the returned key always has !isBuildPrelude. I.e., if the prelude file loads
            // another .bzl, the loaded .bzl is processed as normal with no special prelude magic. This is
            // because 1) only the prelude file, not its dependencies, should automatically re-export its
            // loaded symbols; and 2) we don't want prelude-loaded modules to end up cloned if they're
            // also loaded through normal means.
            return keyForBuild(loadLabel)
        }

        override fun getCompileKey(root: Root?): com.google.devtools.build.lib.skyframe.BzlCompileValue.Key? {
            if (isBuildPrelude) {
                return BzlCompileValue.Companion.keyForBuildPrelude(root, label)
            } else {
                return BzlCompileValue.Companion.key(root, label)
            }
        }
    }

    /**
     * A key for loading a .bzl during `@_builtins` evaluation.
     * 
     * 
     * This kind of key is only requested by [StarlarkBuiltinsFunction] and its transitively
     * loaded [BzlLoadFunction] calls.
     * 
     * 
     * The label must have [RepositoryName.BUILTINS] as its repository component. (It is
     * valid for other key types to use that repo name, but since it is not a real repository and
     * cannot be fetched, any attempt to resolve such a key would fail.)
     */
    @Immutable
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class KeyForBuiltins private constructor(label: Label?) : KeyForLocalEval() {
        private val label: Label

        init {
            this.label = com.google.common.base.Preconditions.checkNotNull<Label>(label)
            require(StarlarkBuiltinsValue.isBuiltinsRepo(label.getRepository())) { "repository name for builtins key must be '@_builtins'" }
        }

        override fun getLabel(): Label {
            return label
        }

        override fun isBuiltins(): Boolean {
            return true
        }

        override fun getKeyForLoad(label: Label?): Key {
            return keyForBuiltins(label)
        }

        override fun getCompileKey(root: Root?): com.google.devtools.build.lib.skyframe.BzlCompileValue.Key? {
            return BzlCompileValue.Companion.keyForBuiltins(root, label)
        }
    }

    /** A key for loading a .bzl to get the repo rule required by Bzlmod generated repositories.  */
    @Immutable
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal open class KeyForBzlmod private constructor(label: Label?) : Key() {
        private val label: Label

        init {
            this.label = com.google.common.base.Preconditions.checkNotNull<Label>(label)
        }

        override fun getLabel(): Label {
            return label
        }

        override fun getKeyForLoad(loadLabel: Label?): Key {
            return keyForBzlmod(loadLabel)
        }

        override fun getCompileKey(root: Root?): com.google.devtools.build.lib.skyframe.BzlCompileValue.Key? {
            return BzlCompileValue.Companion.key(root, label)
        }
    }

    @Immutable
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class KeyForBzlmodBootstrap private constructor(label: Label?) : KeyForBzlmod(label) {
        override fun getKeyForLoad(loadLabel: Label): Key {
            return keyForBzlmodBootstrap(loadLabel)
        }
    }

    @com.google.errorprone.annotations.Keep
    private class KeyCodec : LeafObjectCodec<Key?>() {
        val encodedClass: java.lang.Class<KeyForLocalEval?>
            get() = KeyForLocalEval::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(context: LeafSerializationContext, obj: Key, codedOut: CodedOutputStream) {
            context.serializeLeaf<T?>(obj.label, labelCodec(), codedOut)

            when (obj) {
                -> {
                    codedOut.writeInt32NoTag(0)
                    codedOut.writeBoolNoTag(forBuild.isBuildPrelude())
                }

                -> {
                    codedOut.writeInt32NoTag(1)
                }

                -> {
                    codedOut.writeInt32NoTag(2)
                }

                -> {
                    codedOut.writeInt32NoTag(3)
                }
            }
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream): Key? {
            val label: Label = context.deserializeLeaf<T>(codedIn, labelCodec())
            val discriminant: Int = codedIn.readInt32()
            return when (discriminant) {
                0 -> if (codedIn.readBool()) keyForBuildPrelude(label) else keyForBuild(label)
                1 -> keyForBuiltins(label)
                2 -> keyForBzlmodBootstrap(label)
                3 -> keyForBzlmod(label)
                else -> {
                    throw com.google.devtools.build.lib.skyframe.serialization.SerializationException("unexpected discriminant: " + discriminant)
                }
            }
        }

        companion object {
            private val INSTANCE = KeyCodec()
        }
    }

    companion object {
        private val keyInterner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

        /** Constructs a key for loading a regular .bzl file from BUILD files.  */
        fun keyForBuild(label: Label?): Key {
            return keyInterner.intern(KeyForBuild(label,  /* isBuildPrelude= */false))
        }

        /** Constructs a key for loading a .bzl file within the `@_builtins` pseudo-repository.  */
        fun keyForBuiltins(label: Label?): Key {
            return keyInterner.intern(KeyForBuiltins(label))
        }

        /** Constructs a key for loading the special prelude .bzl.  */
        fun keyForBuildPrelude(label: Label?): Key {
            return keyInterner.intern(KeyForBuild(label,  /* isBuildPrelude= */true))
        }

        /** Constructs a key for loading a .bzl for Bzlmod repos  */
        fun keyForBzlmod(label: Label?): Key {
            return keyInterner.intern(KeyForBzlmod(label))
        }

        fun keyForBzlmodBootstrap(label: Label): Key {
            com.google.common.base.Preconditions.checkArgument(
                label.getRepository().equals(RepositoryName.BAZEL_TOOLS),
                "keyForBzlmodBootstrap must be called with a label in the bazel_tools repository"
            )
            return keyInterner.intern(KeyForBzlmodBootstrap(label))
        }

        @kotlin.jvm.JvmStatic
        fun bzlLoadKeyCodec(): KeyCodec {
            return KeyCodec.Companion.INSTANCE
        }
    }
}
