// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext

/**
 * Codec implementations for [Artifact] subclasses.
 * 
 * 
 * Each Artifact's codec implementation is split into two codecs: the main codec that handles the
 * individual fields, and a value-sharing codec.
 */
object ArtifactCodecs {
    // TODO: b/359437873 - generate with @AutoCodec.
    val VALUE_SHARING_CODECS: com.google.common.collect.ImmutableList<ObjectCodec<out Artifact?>?> =
        com.google.common.collect.ImmutableList.of<E?>(
            DerivedArtifactValueSharingCodec(),
            SourceArtifactValueSharingCodec(),
            SpecialArtifactValueSharingCodec()
        )

    @Throws(IOException::class, SerializationException::class)
    private fun serializeOrOmitGeneratingActionKey(
        context: SerializationContext, obj: DerivedArtifact, codedOut: CodedOutputStream
    ) {
        val include: Boolean =
            context
                .getDependency(ArtifactSerializationContext::class.java)
                .includeGeneratingActionKey(obj, context)
        codedOut.writeBoolNoTag(include)
        if (include) {
            context.serialize(obj.getGeneratingActionKey(), codedOut)
        }
    }

    @Throws(IOException::class, SerializationException::class)
    private fun <T> deserializeOrGetGeneratingActionKey(
        context: AsyncDeserializationContext,
        codedIn: CodedInputStream,
        builder: T?,
        setter: FieldSetter<T?>
    ) {
        val included: Boolean = codedIn.readBool()
        if (included) {
            context.deserialize(codedIn, builder, setter)
        } else {
            val generatingActionKey: ActionLookupData? =
                context
                    .getDependency(ArtifactSerializationContext::class.java)
                    .getOmittedGeneratingActionKey(context)
            setter.set(builder, generatingActionKey)
        }
    }

    private fun getExecPathForDeserialization(
        root: ArtifactRoot, rootRelativePath: PathFragment, generatingActionKey: Any?
    ): PathFragment {
        com.google.common.base.Preconditions.checkArgument(
            !root.isSourceRoot(),
            "Root not derived: %s (rootRelativePath=%s, generatingActionKey=%s)",
            root,
            rootRelativePath,
            generatingActionKey
        )
        com.google.common.base.Preconditions.checkArgument(
            root.getRoot().isAbsolute() === rootRelativePath.isAbsolute(),
            "Illegal root relative path: %s (root=%s, generatingActionKey=%s)",
            rootRelativePath,
            root,
            generatingActionKey
        )
        return root.getExecPath().getRelative(rootRelativePath)
    }

    @com.google.errorprone.annotations.Keep
    private class DerivedArtifactValueSharingCodec

        : DeferredObjectCodec<DerivedArtifact?>() {
        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun getEncodedClass(): java.lang.Class<DerivedArtifact?> {
            return DerivedArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: DerivedArtifact?, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                obj,  /* distinguisher= */null, DerivedArtifactCodec.Companion.INSTANCE, codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<DerivedArtifact?>? {
            val value: SimpleDeferredValue<DerivedArtifact?>? = SimpleDeferredValue.create()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                DerivedArtifactCodec.Companion.INSTANCE,
                value,
                SimpleDeferredValue::set
            )
            return value
        }
    }

    /**
     * [ObjectCodec] for [DerivedArtifact].
     * 
     * 
     * To be kept in sync with [SpecialArtifactCodec].
     */
    @com.google.errorprone.annotations.Keep // Used by reflection.
    private class DerivedArtifactCodec : DeferredObjectCodec<DerivedArtifact?>() {
        public override fun getEncodedClass(): java.lang.Class<DerivedArtifact?> {
            return DerivedArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: DerivedArtifact, codedOut: CodedOutputStream
        ) {
            context.serialize(obj.getRoot(), codedOut)
            context.serialize(obj.getRootRelativePath(), codedOut)
            serializeOrOmitGeneratingActionKey(context, obj, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<DerivedArtifact?> {
            val builder = DeserializedDerivedArtifactBuilder(context)
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedDerivedArtifactBuilder, value: Any? ->
                    DeserializedDerivedArtifactBuilder.Companion.setRoot(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedDerivedArtifactBuilder, value: Any? ->
                    DeserializedDerivedArtifactBuilder.Companion.setRootRelativePath(
                        builder,
                        value
                    )
                })
            ArtifactCodecs.deserializeOrGetGeneratingActionKey<T?>(
                context,
                codedIn,
                builder,
                FieldSetter { builder: DeserializedDerivedArtifactBuilder, value: Any? ->
                    DeserializedDerivedArtifactBuilder.Companion.setGeneratingActionKey(
                        builder,
                        value
                    )
                })
            return builder
        }

        companion object {
            private val INSTANCE = DerivedArtifactCodec()
        }
    }

    private class DeserializedDerivedArtifactBuilder
        (context: AsyncDeserializationContext) : DeferredValue<DerivedArtifact?> {
        private val context: AsyncDeserializationContext
        private var root: ArtifactRoot? = null
        private var rootRelativePath: PathFragment? = null
        private var generatingActionKey: ActionLookupData? = null

        init {
            this.context = context
        }

        public override fun call(): DerivedArtifact {
            return context
                .getDependency(ArtifactSerializationContext::class.java)
                .intern(
                    DerivedArtifact(
                        root,
                        getExecPathForDeserialization(root, rootRelativePath, generatingActionKey),
                        generatingActionKey
                    ),
                    context
                )
        }

        companion object {
            private fun setRoot(builder: DeserializedDerivedArtifactBuilder, value: Any?) {
                builder.root = value as ArtifactRoot
            }

            private fun setRootRelativePath(
                builder: DeserializedDerivedArtifactBuilder, value: Any?
            ) {
                builder.rootRelativePath = value as PathFragment
            }

            private fun setGeneratingActionKey(
                builder: DeserializedDerivedArtifactBuilder, value: Any?
            ) {
                builder.generatingActionKey = value as ActionLookupData?
            }
        }
    }

    @com.google.errorprone.annotations.Keep
    private class SourceArtifactValueSharingCodec

        : DeferredObjectCodec<SourceArtifact?>() {
        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun getEncodedClass(): java.lang.Class<SourceArtifact?> {
            return SourceArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: SourceArtifact?, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                obj,  /* distinguisher= */null, SourceArtifactCodec.Companion.INSTANCE, codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<SourceArtifact?>? {
            val value: SimpleDeferredValue<SourceArtifact?>? = SimpleDeferredValue.create()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                SourceArtifactCodec.Companion.INSTANCE,
                value,
                SimpleDeferredValue::set
            )
            return value
        }
    }

    /** [ObjectCodec] for [SourceArtifact]  */
    @com.google.errorprone.annotations.Keep // Used by reflection.
    private class SourceArtifactCodec : DeferredObjectCodec<SourceArtifact?>() {
        public override fun getEncodedClass(): java.lang.Class<SourceArtifact?> {
            return SourceArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: SourceArtifact, codedOut: CodedOutputStream?
        ) {
            context.serialize(obj.getExecPath(), codedOut)
            context.serialize(obj.getRoot(), codedOut)
            context.serialize(obj.getArtifactOwner(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<SourceArtifact?> {
            val builder =
                DeserializedSourceArtifactBuilder(
                    context.getDependency(ArtifactSerializationContext::class.java)
                )
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSourceArtifactBuilder, value: Any? ->
                    DeserializedSourceArtifactBuilder.Companion.setExecPath(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSourceArtifactBuilder, value: Any? ->
                    DeserializedSourceArtifactBuilder.Companion.setRoot(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSourceArtifactBuilder, value: Any? ->
                    DeserializedSourceArtifactBuilder.Companion.setOwner(
                        builder,
                        value
                    )
                })
            return builder
        }

        companion object {
            private val INSTANCE = SourceArtifactCodec()
        }
    }

    private class DeserializedSourceArtifactBuilder(context: ArtifactSerializationContext) :
        DeferredValue<SourceArtifact?> {
        private val context: ArtifactSerializationContext
        private var execPath: PathFragment? = null
        private var root: ArtifactRoot? = null
        private var owner: ArtifactOwner? = null

        init {
            this.context = context
        }

        public override fun call(): SourceArtifact? {
            return context.getSourceArtifact(execPath, root, owner)
        }

        companion object {
            private fun setExecPath(builder: DeserializedSourceArtifactBuilder, value: Any?) {
                builder.execPath = value as PathFragment?
            }

            private fun setRoot(builder: DeserializedSourceArtifactBuilder, value: Any?) {
                builder.root = value as ArtifactRoot?
            }

            private fun setOwner(builder: DeserializedSourceArtifactBuilder, value: Any?) {
                builder.owner = value as ArtifactOwner?
            }
        }
    }

    @com.google.errorprone.annotations.Keep
    private class SpecialArtifactValueSharingCodec

        : DeferredObjectCodec<SpecialArtifact?>() {
        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun getEncodedClass(): java.lang.Class<SpecialArtifact?> {
            return SpecialArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: SpecialArtifact?, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                obj,  /* distinguisher= */null, SpecialArtifactCodec.Companion.INSTANCE, codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<SpecialArtifact?>? {
            val value: SimpleDeferredValue<SpecialArtifact?>? = SimpleDeferredValue.create()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                SpecialArtifactCodec.Companion.INSTANCE,
                value,
                SimpleDeferredValue::set
            )
            return value
        }
    }

    /**
     * [ObjectCodec] for [SpecialArtifact].
     * 
     * 
     * To be kept in sync with [DerivedArtifactCodec].
     */
    @com.google.errorprone.annotations.Keep // Used by reflection.
    private class SpecialArtifactCodec : DeferredObjectCodec<SpecialArtifact?>() {
        public override fun getEncodedClass(): java.lang.Class<SpecialArtifact?> {
            return SpecialArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: SpecialArtifact, codedOut: CodedOutputStream
        ) {
            context.serialize(obj.getRoot(), codedOut)
            context.serialize(obj.getRootRelativePath(), codedOut)
            serializeOrOmitGeneratingActionKey(context, obj, codedOut)
            context.serialize(obj.getSpecialArtifactType(), codedOut)
            context.serialize(obj.getParent(), codedOut)
            context.serialize(obj.getParentRelativePath(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<SpecialArtifact?> {
            val builder = DeserializedSpecialArtifactBuilder(context)
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSpecialArtifactBuilder, value: Any? ->
                    DeserializedSpecialArtifactBuilder.Companion.setRoot(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSpecialArtifactBuilder, value: Any? ->
                    DeserializedSpecialArtifactBuilder.Companion.setRootRelativePath(
                        builder,
                        value
                    )
                })
            ArtifactCodecs.deserializeOrGetGeneratingActionKey<T?>(
                context,
                codedIn,
                builder,
                FieldSetter { builder: DeserializedSpecialArtifactBuilder, value: Any? ->
                    DeserializedSpecialArtifactBuilder.Companion.setGeneratingActionKey(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSpecialArtifactBuilder, value: Any? ->
                    DeserializedSpecialArtifactBuilder.Companion.setType(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSpecialArtifactBuilder, value: Any? ->
                    DeserializedSpecialArtifactBuilder.Companion.setParent(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedSpecialArtifactBuilder, value: Any? ->
                    DeserializedSpecialArtifactBuilder.Companion.setParentRelativePath(
                        builder,
                        value
                    )
                })
            return builder
        }

        companion object {
            private val INSTANCE = SpecialArtifactCodec()
        }
    }

    private class DeserializedSpecialArtifactBuilder
        (context: AsyncDeserializationContext) : DeferredValue<SpecialArtifact?> {
        private val context: AsyncDeserializationContext
        private var root: ArtifactRoot? = null
        private var rootRelativePath: PathFragment? = null
        private var generatingActionKey: ActionLookupData? = null
        private var type: SpecialArtifactType? = null
        private var parent: SpecialArtifact? = null
        private var parentRelativePath: PathFragment? = null

        init {
            this.context = context
        }

        public override fun call(): SpecialArtifact? {
            return context
                .getDependency(ArtifactSerializationContext::class.java)
                .intern(
                    SpecialArtifact(
                        root,
                        getExecPathForDeserialization(root, rootRelativePath, generatingActionKey),
                        generatingActionKey,
                        type,
                        parent,
                        parentRelativePath
                    ),
                    context
                ) as SpecialArtifact?
        }

        companion object {
            private fun setRoot(builder: DeserializedSpecialArtifactBuilder, value: Any?) {
                builder.root = value as ArtifactRoot
            }

            private fun setRootRelativePath(
                builder: DeserializedSpecialArtifactBuilder, value: Any?
            ) {
                builder.rootRelativePath = value as PathFragment
            }

            private fun setGeneratingActionKey(
                builder: DeserializedSpecialArtifactBuilder, value: Any?
            ) {
                builder.generatingActionKey = value as ActionLookupData?
            }

            private fun setType(builder: DeserializedSpecialArtifactBuilder, value: Any?) {
                builder.type = value as SpecialArtifactType?
            }

            private fun setParent(builder: DeserializedSpecialArtifactBuilder, value: Any?) {
                builder.parent = value as SpecialArtifact?
            }

            private fun setParentRelativePath(
                builder: DeserializedSpecialArtifactBuilder, value: Any?
            ) {
                builder.parentRelativePath = value as PathFragment?
            }
        }
    }

    @Suppress("unused") // Codec used by reflection.
    private class ArchivedTreeArtifactCodec

        : DeferredObjectCodec<ArchivedTreeArtifact?>() {
        public override fun getEncodedClass(): java.lang.Class<ArchivedTreeArtifact?> {
            return ArchivedTreeArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: ArchivedTreeArtifact, codedOut: CodedOutputStream?
        ) {
            val derivedTreeRoot: PathFragment? = obj.getRoot().getExecPath().subFragment(1, 2)

            context.serialize(obj.getParent(), codedOut)
            context.serialize(derivedTreeRoot, codedOut)
            context.serialize(obj.getRootRelativePath(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<ArchivedTreeArtifact?> {
            val builder =
                DeserializedArchivedTreeArtifactBuilder()
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedArchivedTreeArtifactBuilder, value: Any? ->
                    DeserializedArchivedTreeArtifactBuilder.Companion.setTreeArtifact(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedArchivedTreeArtifactBuilder, value: Any? ->
                    DeserializedArchivedTreeArtifactBuilder.Companion.setDerivedTreeRoot(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedArchivedTreeArtifactBuilder, value: Any? ->
                    DeserializedArchivedTreeArtifactBuilder.Companion.setRootRelativePath(
                        builder,
                        value
                    )
                })
            return builder
        }
    }

    private class DeserializedArchivedTreeArtifactBuilder

        : DeferredValue<ArchivedTreeArtifact?> {
        private var treeArtifact: SpecialArtifact? = null
        private var derivedTreeRoot: PathFragment? = null
        private var rootRelativePath: PathFragment? = null

        public override fun call(): ArchivedTreeArtifact {
            return ArchivedTreeArtifact.Companion.createWithCustomDerivedTreeRoot(
                treeArtifact, derivedTreeRoot, rootRelativePath
            )
        }

        companion object {
            private fun setTreeArtifact(
                builder: DeserializedArchivedTreeArtifactBuilder, value: Any?
            ) {
                builder.treeArtifact = value as SpecialArtifact
            }

            private fun setDerivedTreeRoot(
                builder: DeserializedArchivedTreeArtifactBuilder, value: Any?
            ) {
                builder.derivedTreeRoot = value as PathFragment?
            }

            private fun setRootRelativePath(
                builder: DeserializedArchivedTreeArtifactBuilder, value: Any?
            ) {
                builder.rootRelativePath = value as PathFragment?
            }
        }
    }

    @Suppress("unused") // Used by reflection.
    private class TreeFileArtifactCodec : DeferredObjectCodec<TreeFileArtifact?>() {
        public override fun getEncodedClass(): java.lang.Class<TreeFileArtifact?> {
            return TreeFileArtifact::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: TreeFileArtifact, codedOut: CodedOutputStream
        ) {
            context.serialize(obj.getParent(), codedOut)
            context.serialize(obj.getParentRelativePath(), codedOut)
            serializeOrOmitGeneratingActionKey(context, obj, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<TreeFileArtifact?> {
            val builder = DeserializedTreeFileArtifactBuilder()
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedTreeFileArtifactBuilder, value: Any? ->
                    DeserializedTreeFileArtifactBuilder.Companion.setParent(
                        builder,
                        value
                    )
                })
            context.deserialize(
                codedIn,
                builder,
                { builder: DeserializedTreeFileArtifactBuilder, value: Any? ->
                    DeserializedTreeFileArtifactBuilder.Companion.setParentRelativePath(
                        builder,
                        value
                    )
                })
            ArtifactCodecs.deserializeOrGetGeneratingActionKey<T?>(
                context,
                codedIn,
                builder,
                FieldSetter { builder: DeserializedTreeFileArtifactBuilder, value: Any? ->
                    DeserializedTreeFileArtifactBuilder.Companion.setGeneratingActionKey(
                        builder,
                        value
                    )
                })
            return builder
        }
    }

    private class DeserializedTreeFileArtifactBuilder

        : DeferredValue<TreeFileArtifact?> {
        private var parent: SpecialArtifact? = null
        private var parentRelativePath: PathFragment? = null
        private var generatingActionKey: ActionLookupData? = null

        public override fun call(): TreeFileArtifact {
            return TreeFileArtifact(parent, parentRelativePath, generatingActionKey)
        }

        companion object {
            private fun setParent(builder: DeserializedTreeFileArtifactBuilder, value: Any?) {
                builder.parent = value as SpecialArtifact
            }

            private fun setParentRelativePath(
                builder: DeserializedTreeFileArtifactBuilder, value: Any?
            ) {
                builder.parentRelativePath = value as PathFragment?
            }

            private fun setGeneratingActionKey(
                builder: DeserializedTreeFileArtifactBuilder, value: Any?
            ) {
                builder.generatingActionKey = value as ActionLookupData?
            }
        }
    }
}
