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
package com.google.devtools.build.lib.bazel.repository.decompressor

import com.google.auto.value.AutoBuilder
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.vfs.Path
import java.util.*

/**
 * Description of an archive to be decompressed.
 * 
 * @param context The context in which this decompression is happening. Should only be used for
 * reporting.
 */
@kotlin.jvm.JvmRecord
data class DecompressorDescriptor(
    val context: String?,
    val archivePath: Path?,
    val destinationPath: Path?,
    val prefix: Optional<String?>?,
    val stripComponents: Int,
    val renameFiles: ImmutableMap<String?, String?>?
) {
    /** Builder for describing the file to be decompressed.  */
    @AutoBuilder
    abstract class Builder {
        abstract fun setContext(context: String?): Builder?

        abstract fun setArchivePath(archivePath: Path?): Builder?

        abstract fun setDestinationPath(destinationPath: Path?): Builder?

        abstract fun setPrefix(prefix: String?): Builder?

        abstract fun setStripComponents(stripComponents: Int): Builder?

        abstract fun setRenameFiles(renameFiles: MutableMap<String?, String?>?): Builder?

        abstract fun autoBuild(): DecompressorDescriptor

        fun build(): DecompressorDescriptor {
            val d = autoBuild()
            require(d.stripComponents >= 0) { "'strip_components' must be non-negative" }
            require(
                !(d.stripComponents != 0 && d.prefix!!.isPresent() && !d.prefix.get().isEmpty())
            ) { "Only one of 'strip_prefix' or 'strip_components' can be set" }
            return d
        }
    }

    init {
        String > Objects.requireNonNull<String?>(context, "context")
        Path > Objects.requireNonNull<Path?>(archivePath, "archivePath")
        Path > Objects.requireNonNull<Path?>(destinationPath, "destinationPath")
        Objects.requireNonNull<Optional<String?>?>(prefix, "prefix")
        Objects.requireNonNull<ImmutableMap<String?, String?>?>(
            renameFiles, "renameFiles"
        )
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_DecompressorDescriptor_Builder()
                .setContext("")
                .setStripComponents(0)
                .setRenameFiles(ImmutableMap.of<K?, V?>())
        }
    }
}
