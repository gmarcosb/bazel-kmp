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

/** A base class for FileType matchers.  */
@javax.annotation.concurrent.Immutable
abstract class FileType : com.google.common.base.Predicate<String?> {
    private class SingletonFileType(private val ext: String) : FileType() {
        override fun apply(path: String): Boolean {
            return com.google.devtools.build.lib.util.FileType.Companion.hasExtension(path, ext)
        }

        override fun getExtensions(): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<String?>(ext)
        }
    }

    private class ListFileType(extensions: com.google.common.collect.ImmutableList<String?>?) : FileType() {
        private val extensions: com.google.common.collect.ImmutableList<String?>

        init {
            this.extensions =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                    extensions
                )
        }

        override fun apply(path: String): Boolean {
            return com.google.devtools.build.lib.util.FileType.Companion.hasAnyExtension(path, extensions)
        }

        override fun getExtensions(): com.google.common.collect.ImmutableList<String?> {
            return extensions
        }

        override fun hashCode(): Int {
            return extensions.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is ListFileType
                    && this.extensions == obj.extensions
        }
    }

    override fun toString(): String {
        return this.extensions.toString()
    }

    /** Returns true if the file matches. Subclasses are expected to handle a full path.  */
    abstract override fun apply(path: String?): Boolean

    open val extensions: com.google.common.collect.ImmutableList<String?>
        /**
         * Get a list of filename extensions this matcher handles. The first entry in the list (if
         * available) is the primary extension that code can use to construct output file names. The list
         * can be empty for some matchers.
         * 
         * @return a list of filename extensions
         */
        get() = com.google.common.collect.ImmutableList.of<String?>()

    /** Return true if a file path is matched by this FileType  */
    @Deprecated("")
    fun matches(path: String?): Boolean {
        return apply(path)
    }

    /** Return true if the item is matched by this FileType  */
    fun matches(item: HasFileType): Boolean {
        return apply(item.filePathForFileTypeMatcher())
    }

    // Check FileTypes
    /** An interface for entities that have a file type.  */
    interface HasFileType {
        /**
         * Return a file path that ends with the file name.
         * 
         * 
         * The path will be used by [FileType] for matching. An example valid implementation
         * could return the full path of the file, or just the file name, depending on what can
         * efficiently be provided.
         */
        fun filePathForFileTypeMatcher(): String?
    }

    companion object {
        // A special file type
        @kotlin.jvm.JvmField
        @SerializationConstant
        @VisibleForSerialization
        val NO_EXTENSION: FileType = object : FileType() {
            override fun apply(path: String): Boolean {
                val lastSlashIndex: Int = path.lastIndexOf('/'.code)
                return path.indexOf('.'.code, lastSlashIndex + 1) == -1
            }
        }

        fun of(ext: String): FileType {
            return SingletonFileType(ext)
        }

        fun of(extensions: com.google.common.collect.ImmutableList<String?>?): FileType {
            return ListFileType(extensions)
        }

        @kotlin.jvm.JvmStatic
        fun of(vararg extensions: String?): FileType {
            return com.google.devtools.build.lib.util.FileType.Companion.of(
                com.google.common.collect.ImmutableList.copyOf<String?>(
                    extensions
                )
            )
        }

        /** Returns true if the given path has the given extension.  */ // TODO(bazel-team): When Starlarkifying this method, consider replacing with a mechanism that
        //  doesn't depend on the host OS. For example, ".lib" and ".LIB" could be accepted on all OSes
        //  for C++ rules by listing both variants explicitly without also allowind ".LiB".
        fun hasExtension(path: String, ext: String): Boolean {
            // TODO: This logic is flawed:
            //  * it applies to Windows but not macOS, even though both may have case-insensitive file
            // systems;
            //  * it doesn't take the actual file system case sensitivity into account;
            //  * it doesn't behave correctly with remote execution when host OS != exec OS.
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
                // No need to convert from internal String encoding to Unicode strings since all extensions
                // are ASCII.
                return path.regionMatches(true, path.length() - ext.length(), ext, 0, ext.length())
            } else {
                return path.endsWith(ext)
            }
        }

        /** Returns true if the given path has any of the given extensions.  */
        fun hasAnyExtension(path: String, extensions: com.google.common.collect.ImmutableList<String?>): Boolean {
            // Do not use an iterator-based for loop here as that creates excessive garbage.
            for (i in extensions.indices) {
                if (com.google.devtools.build.lib.util.FileType.Companion.hasExtension(path, extensions.get(i))) {
                    return true
                }
            }
            return false
        }

        /**
         * Checks whether an Iterable contains any of the specified file types.
         * 
         * 
         * At least one FileType must be specified.
         */
        fun <T : HasFileType?> contains(
            items: Iterable<T?>, vararg fileTypes: FileType?
        ): Boolean {
            com.google.common.base.Preconditions.checkState(fileTypes.size > 0, "Must specify at least one file type")
            val fileTypeSet: FileTypeSet = FileTypeSet.Companion.of(*fileTypes)
            for (item in items) {
                if (fileTypeSet.matches(item!!.filePathForFileTypeMatcher())) {
                    return true
                }
            }
            return false
        }

        /**
         * Checks whether a HasFileType is any of the specified file types.
         * 
         * 
         * At least one FileType must be specified.
         */
        fun <T : HasFileType?> contains(item: T?, vararg fileTypes: FileType?): Boolean {
            return FileTypeSet.Companion.of(*fileTypes).matches(item!!.filePathForFileTypeMatcher())
        }

        private fun <T : HasFileType?> typeMatchingPredicateFor(
            matchingType: FileType
        ): com.google.common.base.Predicate<T?> {
            return com.google.common.base.Predicate { item: T? -> matchingType.matches(item!!.filePathForFileTypeMatcher()) }
        }

        private fun <T : HasFileType?> typeMatchingPredicateFor(
            matchingTypes: FileTypeSet
        ): com.google.common.base.Predicate<T?> {
            return com.google.common.base.Predicate { item: T? -> matchingTypes.matches(item!!.filePathForFileTypeMatcher()) }
        }

        private fun <T : HasFileType?> typeMatchingPredicateFrom(
            fileTypePredicate: com.google.common.base.Predicate<String?>
        ): com.google.common.base.Predicate<T?> {
            return com.google.common.base.Predicate { item: T? -> fileTypePredicate.apply(item!!.filePathForFileTypeMatcher()) }
        }

        /**
         * A filter for Iterable that returns only those whose FileType matches the
         * specified Predicate.
         */
        fun <T : HasFileType?> filter(
            items: Iterable<T?>, predicate: com.google.common.base.Predicate<String?>
        ): Iterable<T?> {
            return com.google.common.collect.Iterables.filter<T?>(
                items,
                com.google.devtools.build.lib.util.FileType.Companion.typeMatchingPredicateFrom<T?>(predicate)
            )
        }

        /**
         * A filter for Iterable that returns only those of the specified file
         * types.
         */
        fun <T : HasFileType?> filter(
            items: Iterable<T?>, vararg fileTypes: FileType?
        ): Iterable<T?> {
            return com.google.devtools.build.lib.util.FileType.Companion.filter<T?>(
                items,
                FileTypeSet.Companion.of(*fileTypes)
            )
        }

        /**
         * A filter for Iterable that returns only those of the specified file
         * types.
         */
        fun <T : HasFileType?> filter(
            items: Iterable<T?>, fileTypes: FileTypeSet
        ): Iterable<T?> {
            return com.google.common.collect.Iterables.filter<T?>(
                items,
                com.google.devtools.build.lib.util.FileType.Companion.typeMatchingPredicateFor<T?>(fileTypes)
            )
        }

        /**
         * A filter for Iterable that returns only those of the specified file
         * type.
         */
        fun <T : HasFileType?> filter(
            items: Iterable<T?>, fileType: FileType
        ): Iterable<T?> {
            return com.google.common.collect.Iterables.filter<T?>(
                items,
                com.google.devtools.build.lib.util.FileType.Companion.typeMatchingPredicateFor<T?>(fileType)
            )
        }

        /**
         * A filter for Iterable that returns everything except the specified file
         * type.
         */
        fun <T : HasFileType?> except(
            items: Iterable<T?>, fileType: FileType
        ): Iterable<T?> {
            return com.google.common.collect.Iterables.filter<T?>(
                items,
                com.google.common.base.Predicates.not<T?>(
                    com.google.devtools.build.lib.util.FileType.Companion.typeMatchingPredicateFor<T?>(fileType)
                )
            )
        }

        /**
         * A filter for List that returns only those of the specified file types.
         * The result is a mutable list, computed eagerly; see [.filter] for a lazy variant.
         */
        fun <T : HasFileType?> filterList(
            items: Iterable<T?>, vararg fileTypes: FileType?
        ): MutableList<T?> {
            if (fileTypes.size > 0) {
                return com.google.devtools.build.lib.util.FileType.Companion.filterList<T?>(
                    items,
                    FileTypeSet.Companion.of(*fileTypes)
                )
            } else {
                return java.util.ArrayList<T?>()
            }
        }

        /**
         * A filter for List that returns only those of the specified file type.
         * The result is a mutable list, computed eagerly.
         */
        fun <T : HasFileType?> filterList(
            items: Iterable<T?>, fileType: FileType
        ): MutableList<T?> {
            val result: MutableList<T?> = java.util.ArrayList<T?>()
            for (item in items) {
                if (fileType.matches(item!!.filePathForFileTypeMatcher())) {
                    result.add(item)
                }
            }
            return result
        }

        /**
         * A filter for List that returns only those of the specified file types.
         * The result is a mutable list, computed eagerly.
         */
        fun <T : HasFileType?> filterList(
            items: Iterable<T?>, fileTypeSet: FileTypeSet
        ): MutableList<T?> {
            val result: MutableList<T?> = java.util.ArrayList<T?>()
            for (item in items) {
                if (fileTypeSet.matches(item!!.filePathForFileTypeMatcher())) {
                    result.add(item)
                }
            }
            return result
        }
    }
}
