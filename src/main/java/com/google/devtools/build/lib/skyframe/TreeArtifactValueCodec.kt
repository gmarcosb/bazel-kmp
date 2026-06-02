// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.cache.MetadataDigestUtils

/**
 * A wrapper around the AutoCodec-generated codec for [TreeArtifactValue] that makes sure the
 * [TreeArtifactValue.empty] constant is deserialized into a different constant ([ ][.EMPTY_DESERIALIZED]) that implements the [DeserializedSkyValue] marker interface.
 */
@com.google.errorprone.annotations.Keep
internal class TreeArtifactValueCodec : DeferredObjectCodec<TreeArtifactValue?>() {
    val encodedClass: java.lang.Class<TreeArtifactValue?>
        get() = TreeArtifactValue::class.java

    public override fun additionalEncodedClasses(): com.google.common.collect.ImmutableSet<java.lang.Class<out TreeArtifactValue?>?> {
        return com.google.common.collect.ImmutableSet.of<E?>(TreeArtifactValue_AutoCodec.Deserialized::class.java)
    }

    @Throws(SerializationException::class, IOException::class)
    public override fun serialize(
        context: SerializationContext?, obj: TreeArtifactValue, codedOut: CodedOutputStream
    ) {
        if (obj == TreeArtifactValue.Companion.empty()) {
            codedOut.writeBoolNoTag(true)
        } else {
            codedOut.writeBoolNoTag(false)
            AUTOCODEC.serialize(context, obj, codedOut)
        }
    }

    @Throws(SerializationException::class, IOException::class)
    public override fun deserializeDeferred(
        context: AsyncDeserializationContext?, codedIn: CodedInputStream
    ): DeferredValue<out TreeArtifactValue?>? {
        if (codedIn.readBool()) {
            return DeferredValue { EMPTY_DESERIALIZED }
        }
        return AUTOCODEC.deserializeDeferred(context, codedIn)
    }

    companion object {
        private val EMPTY_DESERIALIZED: TreeArtifactValue = Deserialized(
            MetadataDigestUtils.fromMetadata(com.google.common.collect.ImmutableMap.of<K?, V?>()),
            TreeArtifactValue.Companion.EMPTY_MAP,
            0L,  /* archivedRepresentation= */
            null,  /* resolvedPath= */
            null,  /* entirelyRemote= */
            false
        )

        private val AUTOCODEC: DeferredObjectCodec<TreeArtifactValue?> = TreeArtifactValue_AutoCodec()
    }
}
