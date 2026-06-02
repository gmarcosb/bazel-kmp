// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * Wrapper around a map of bitcode files for purposes of caching its fingerprint.
 * 
 * 
 * Each instance is potentially shared by many [LtoBackendAction] instances.
 */
internal class BitcodeFiles(files: NestedSet<Artifact?>) {
    private val files: NestedSet<Artifact?>

    @kotlin.concurrent.Volatile
    @Transient
    private var fingerprint: ByteArray? = null

    @kotlin.concurrent.Volatile
    private var filesArtifactPathMapReference: java.lang.ref.WeakReference<MutableMap<PathFragment?, Artifact?>?> =
        java.lang.ref.WeakReference<MutableMap<PathFragment?, Artifact?>?>(null)

    init {
        this.files = files
    }

    fun getFiles(): NestedSet<Artifact?> {
        return files
    }

    val filesArtifactPathMap: MutableMap<PathFragment, Artifact>?
        /** Helper function to get a map from path to artifact  */
        get() {
            // This method is called once per LtoBackendAction instance that shares this BitcodeFiles
            // instance. Therefore we weakly cache the result.
            //
            // It's a garbage hotspot, so we deliberately use a presized CompactHashMap instead of
            // streams and ImmutableMap. In a build with many LtoBackendAction instances, this approach
            // reduced garbage allocated by this method by ~65%. The approach of caching the result further
            // reduced garbage up to a total reduction of >99%.

            var result: MutableMap<PathFragment?, Artifact?>? = filesArtifactPathMapReference.get()
            if (result != null) {
                return result
            }

            synchronized(this) {
                result = filesArtifactPathMapReference.get()
                if (result != null) {
                    return result
                }
                val filesList: com.google.common.collect.ImmutableList<Artifact> = getFiles().toList()
                result = com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap.createWithExpectedSize(
                    filesList.size
                )
                for (file in filesList) {
                    result!!.put(file.getExecPath(), file)
                }
                filesArtifactPathMapReference =
                    java.lang.ref.WeakReference<MutableMap<PathFragment?, Artifact?>?>(result)
                return result
            }
        }

    fun addToFingerprint(fp: Fingerprint) {
        if (fingerprint == null) {
            synchronized(this) {
                if (fingerprint == null) {
                    fingerprint = computeFingerprint()
                }
            }
        }
        fp.addBytes(fingerprint)
    }

    private fun computeFingerprint(): ByteArray? {
        val fp: Fingerprint = Fingerprint()
        for (path in files.toList()) {
            fp.addPath(path.getExecPath())
        }
        return fp.digestAndReset()
    }
}
