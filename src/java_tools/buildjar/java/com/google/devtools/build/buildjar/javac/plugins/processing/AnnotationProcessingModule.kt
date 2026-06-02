// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.buildjar.javac.plugins.processing

import com.google.common.collect.ImmutableList
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** A module for information about the compilation's annotation processing.  */
class AnnotationProcessingModule private constructor(
    private val sourceGenDir: Path?,
    private val manifestProto: Path?
) {
    /** A builder for [AnnotationProcessingModule]s.  */
    class Builder private constructor() {
        private var sourceGenDir: Path? = null
        private var manifestProto: Path? = null

        fun build(): AnnotationProcessingModule {
            return AnnotationProcessingModule(sourceGenDir, manifestProto)
        }

        fun setSourceGenDir(sourceGenDir: Path?) {
            this.sourceGenDir = sourceGenDir
        }

        fun setManifestProtoPath(manifestProto: Path) {
            this.manifestProto = manifestProto.toAbsolutePath()
        }
    }

    private val enabled: Boolean

    fun isGenerated(path: Path): Boolean {
        return path.startsWith(sourceGenDir)
    }

    fun stripSourceRoot(path: Path): Path? {
        return if (path.startsWith(sourceGenDir)) sourceGenDir!!.relativize(path) else path
    }

    fun registerPlugin(builder: ImmutableList.Builder<BlazeJavaCompilerPlugin?>) {
        if (enabled) {
            builder.add(AnnotationProcessingPlugin(this))
        }
    }

    private val units: MutableMap<String?, CompilationUnit?> = HashMap<String?, CompilationUnit?>()

    init {
        this.enabled = sourceGenDir != null && manifestProto != null
    }

    fun recordUnit(unit: CompilationUnit) {
        units.put(unit.getPath(), unit)
    }

    private fun buildManifestProto(): Manifest {
        val builder: Manifest.Builder = Manifest.newBuilder()

        val keys: MutableList<String?> = ArrayList<String?>(units.keys)
        Collections.sort<String?>(keys)
        for (key in keys) {
            val unit: CompilationUnit? = units.get(key)
            builder.addCompilationUnit(unit)
        }

        return builder.build()
    }

    @Throws(IOException::class)
    fun emitManifestProto() {
        if (!enabled) {
            return
        }
        try {
            Files.newOutputStream(manifestProto).use { out ->
                buildManifestProto().writeTo(out)
            }
        } catch (ex: IOException) {
            throw IOException("Cannot write manifest to " + manifestProto, ex)
        }
    }

    companion object {
        fun builder(): Builder {
            return AnnotationProcessingModule.Builder()
        }
    }
}
