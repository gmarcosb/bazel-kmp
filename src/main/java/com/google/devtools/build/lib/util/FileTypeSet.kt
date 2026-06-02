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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization

/** A set of FileTypes for grouped matching.  */
@javax.annotation.concurrent.Immutable
open class FileTypeSet : com.google.common.base.Predicate<String?> {
    private val fileTypes: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.util.FileType>?

    private constructor() {
        this.fileTypes = null
    }

    private constructor(vararg fileTypes: com.google.devtools.build.lib.util.FileType?) {
        this.fileTypes =
            com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.util.FileType?>(fileTypes)
    }

    private constructor(fileTypes: Iterable<com.google.devtools.build.lib.util.FileType?>) {
        this.fileTypes =
            com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.util.FileType?>(fileTypes)
    }

    /** Returns a copy of this [FileTypeSet] including the specified `fileTypes`.  */
    fun including(vararg fileTypes: com.google.devtools.build.lib.util.FileType?): FileTypeSet {
        return FileTypeSet(
            com.google.common.collect.Iterables.concat<com.google.devtools.build.lib.util.FileType?>(
                this.fileTypes,
                java.util.Arrays.asList<com.google.devtools.build.lib.util.FileType?>(*fileTypes)
            )
        )
    }

    /** Returns true if the filename can be matched by any FileType in this set.  */
    open fun matches(path: String?): Boolean {
        for (type in fileTypes) {
            if (type.apply(path)) {
                return true
            }
        }
        return false
    }

    @VisibleForSerialization
    fun getFileTypes(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.util.FileType>? {
        return fileTypes
    }

    val isNone: Boolean
        /** Returns true if this predicate matches nothing.  */
        get() = this === NO_FILE

    override fun apply(path: String?): Boolean {
        return matches(path)
    }

    open val extensions: MutableList<String?>
        /** Returns the list of possible file extensions for this file type. Can be empty.  */
        get() {
            val extensions: MutableList<String?> = java.util.ArrayList<String?>()
            for (type in fileTypes) {
                extensions.addAll(type.getExtensions())
            }
            return extensions
        }

    override fun toString(): String {
        return com.google.devtools.build.lib.util.StringUtil.joinEnglishList(this.extensions)
    }

    companion object {
        /** A set that matches all files.  */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val ANY_FILE: FileTypeSet = object : FileTypeSet() {
            override fun toString(): String {
                return "any files"
            }

            override fun matches(filename: String?): Boolean {
                return true
            }

            override fun getExtensions(): MutableList<String?> {
                return com.google.common.collect.ImmutableList.of<String?>()
            }
        }

        /** A predicate that matches no files.  */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val NO_FILE: FileTypeSet = object :
            FileTypeSet(com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.util.FileType?>()) {
            override fun toString(): String {
                return "no files"
            }

            override fun matches(filename: String?): Boolean {
                return false
            }
        }

        /**
         * Returns a set that matches only the provided `fileTypes`.
         * 
         * 
         * If `fileTypes` is empty, the returned predicate will match no files.
         */
        fun of(vararg fileTypes: com.google.devtools.build.lib.util.FileType?): FileTypeSet? {
            if (fileTypes.size == 0) {
                return NO_FILE
            } else {
                return FileTypeSet(*fileTypes)
            }
        }

        /**
         * Returns a set that matches only the provided `fileTypes`.
         * 
         * 
         * If `fileTypes` is empty, the returned predicate will match no files.
         */
        fun of(fileTypes: Iterable<com.google.devtools.build.lib.util.FileType?>): FileTypeSet? {
            if (com.google.common.collect.Iterables.isEmpty(fileTypes)) {
                return NO_FILE
            } else {
                return FileTypeSet(fileTypes)
            }
        }
    }
}
