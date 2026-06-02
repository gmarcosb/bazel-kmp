// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec

/**
 * A path segment representing a path fragment using the host machine's path style. That is; If you
 * are running on a Unix machine, the path style will be unix, on Windows it is the windows path
 * style.
 * 
 * 
 * Path fragments are either absolute or relative.
 * 
 * 
 * Strings are normalized with '.' and '..' removed and resolved (if possible), any multiple
 * slashes ('/') removed, and any trailing slash also removed. Windows drive letters are uppercased.
 * The current implementation does not touch the incoming path string unless the string actually
 * needs to be normalized.
 * 
 * 
 * There is some limited support for Windows-style paths. Most importantly, drive identifiers in
 * front of a path (c:/abc) are supported and such paths are correctly recognized as absolute, as
 * are paths with backslash separators (C:\\foo\\bar). However, advanced Windows-style features like
 * \\\\network\\paths and \\\\?\\unc\\paths are not supported. We are currently using forward
 * slashes ('/') even on Windows.
 * 
 * 
 * All paths are case-sensitive.
 */
@com.google.errorprone.annotations.Immutable
abstract class PathFragment
private constructor(normalizedPath: String?) : Comparable<PathFragment?>, FileType.HasFileType, PathStrippable {
    val pathString: String

    /** This method expects path to already be normalized.  */
    init {
        this.pathString = com.google.common.base.Preconditions.checkNotNull<String>(normalizedPath)
    }

    val isEmpty: Boolean
        get() = pathString.isEmpty()

    /**
     * Returns 0 for relative paths (e.g. "a/b"), 1 for Unix-style absolute paths (e.g. "/a/b"), and 3
     * for Windows-style absolute paths (e.g. "a:/b").
     */
    abstract val driveStrLength: Int

    private class RelativePathFragment  // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a RelativePathFragment instance beyond the current value of 16
    // bytes. Our heap typically has many instances.
        (normalizedPath: String?) : PathFragment(normalizedPath) {
        override fun getDriveStrLength(): Int {
            return 0
        }
    }

    private class UnixStyleAbsolutePathFragment  // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a UnixStyleAbsolutePathFragment instance beyond the current
    // value of 16 bytes. Our heap typically has many instances.
        (normalizedPath: String?) : PathFragment(normalizedPath) {
        override fun getDriveStrLength(): Int {
            return 1
        }
    }

    private class WindowsStyleAbsolutePathFragment  // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a WindowsStyleAbsolutePathFragment instance beyond the current
    // value of 16 bytes. Our heap typically has many instances (when Bazel is run on Windows).
        (normalizedPath: String?) : PathFragment(normalizedPath) {
        override fun getDriveStrLength(): Int {
            return 3
        }
    }

    val baseName: String
        /**
         * If called on a [PathFragment] instance for a mount name (eg. '/' or 'C:/'), the empty
         * string is returned.
         * 
         * 
         * This operation allocates a new string.
         */
        get() {
            val lastSeparator: Int = pathString.lastIndexOf(SEPARATOR_CHAR.code)
            return if (lastSeparator < this.driveStrLength)
                pathString.substring(this.driveStrLength)
            else
                pathString.substring(lastSeparator + 1)
        }

    /**
     * Returns a [PathFragment] instance formed by resolving `other` relative to this
     * path. For example, if this path is "a" and other is "b", returns "a/b".
     * 
     * 
     * If the passed path is absolute it is returned untouched. This can be useful to resolve
     * symlinks.
     */
    fun getRelative(other: PathFragment?): PathFragment? {
        com.google.common.base.Preconditions.checkNotNull<PathFragment?>(other)
        if (this.isEmpty || other!!.isAbsolute) {
            return other
        }
        // The path fragment is already normal, use cheaper normalization check.
        val otherStr = other.pathString
        return getRelative(otherStr, other.driveStrLength, OS.needsToNormalizeSuffix(otherStr))
    }

    /**
     * Returns a [PathFragment] instance formed by resolving `other` relative to this
     * path. For example, if this path is "a" and other is "b", returns "a/b".
     * 
     * 
     * See [.getRelative] for details.
     */
    fun getRelative(other: String?): PathFragment? {
        com.google.common.base.Preconditions.checkNotNull<String?>(other)
        return getRelative(other!!, OS.getDriveStrLength(other), OS.needsToNormalize(other))
    }

    private fun getRelative(other: String, otherDriveStrLength: Int, normalizationLevel: Int): PathFragment? {
        if (this.isEmpty) {
            return create(other)
        }
        if (other.isEmpty()) {
            return this
        }
        // This is an absolute path, simply return it
        if (otherDriveStrLength > 0) {
            val normalizedPath: String? =
                if (normalizationLevel != OsPathPolicy.Companion.NORMALIZED)
                    OS.normalize(other, normalizationLevel)
                else
                    other
            return makePathFragment(normalizedPath, otherDriveStrLength)
        }
        var newPath: String?
        if (pathString.length() == this.driveStrLength) {
            newPath = this.pathString + other
        } else {
            newPath = this.pathString + '/' + other
        }
        newPath =
            if (normalizationLevel != OsPathPolicy.Companion.NORMALIZED)
                OS.normalize(newPath, normalizationLevel)
            else
                newPath
        return makePathFragment(newPath, this.driveStrLength)
    }

    fun getChild(baseName: String): PathFragment {
        checkBaseName(baseName)
        val newPath: String?
        if (pathString.length() == this.driveStrLength) {
            newPath = this.pathString + baseName
        } else {
            newPath = this.pathString + '/' + baseName
        }
        return makePathFragment(newPath, this.driveStrLength)
    }

    val parentDirectory: PathFragment?
        /**
         * Returns the parent directory of this [PathFragment].
         * 
         * 
         * If this is called on an single directory for a relative path, this returns an empty relative
         * path. If it's called on a root (like '/') or the empty string, it returns null.
         */
        get() {
            val lastSeparator: Int = pathString.lastIndexOf(SEPARATOR_CHAR.code)

            // For absolute paths we need to specially handle when we hit root
            // Relative paths can't hit this path as driveStrLength == 0
            if (this.driveStrLength > 0) {
                if (lastSeparator < this.driveStrLength) {
                    if (pathString.length() > this.driveStrLength) {
                        val newPath: String = pathString.substring(0, this.driveStrLength)
                        return makePathFragment(newPath, this.driveStrLength)
                    } else {
                        return null
                    }
                }
            } else {
                if (lastSeparator == -1) {
                    if (!this.isEmpty) {
                        return EMPTY_FRAGMENT
                    } else {
                        return null
                    }
                }
            }
            val newPath: String = pathString.substring(0, lastSeparator)
            return makePathFragment(newPath, this.driveStrLength)
        }

    /**
     * Returns the [PathFragment] relative to the base [PathFragment].
     * 
     * 
     * For example, `
     * [PathFragment].create("foo/bar/wiz").relativeTo([PathFragment].create("foo"))
    ` *  returns `"bar/wiz"`.
     * 
     * 
     * If the [PathFragment] is not a child of the passed [PathFragment] an [ ] is thrown. In particular, this will happen whenever the two [ ] instances aren't both absolute or both relative.
     */
    fun relativeTo(base: PathFragment?): PathFragment {
        com.google.common.base.Preconditions.checkNotNull<PathFragment?>(base)
        require(this.isAbsolute == base!!.isAbsolute) { "Cannot relativize an absolute and a non-absolute path pair" }
        val basePath: String? = base.pathString
        require(pathString.startsWith(basePath)) {
            java.lang.String.format(
                "Path '%s' is not under '%s', cannot relativize",
                this,
                base
            )
        }
        val bn: Int = basePath.length()
        if (bn == 0) {
            return this
        }
        if (pathString.length() == bn) {
            return EMPTY_FRAGMENT
        }
        val lastSlashIndex: Int
        if (basePath.charAt(bn - 1) == '/') {
            lastSlashIndex = bn - 1
        } else {
            lastSlashIndex = bn
        }
        require(pathString.charAt(lastSlashIndex) == '/') {
            java.lang.String.format(
                "Path '%s' is not under '%s', cannot relativize",
                this,
                base
            )
        }
        val newPath: String = pathString.substring(lastSlashIndex + 1)
        return RelativePathFragment(newPath)
    }

    fun relativeTo(base: String): PathFragment {
        return relativeTo(create(base))
    }

    /**
     * Returns true iff `other` is an ancestor of this path.
     * 
     * 
     * If this == other, true is returned.
     * 
     * 
     * An absolute path can never be an ancestor of a relative path, and vice versa.
     */
    fun startsWith(other: PathFragment?): Boolean {
        com.google.common.base.Preconditions.checkNotNull<PathFragment?>(other)
        if (other!!.pathString.length() > pathString.length()) {
            return false
        }
        if (this.driveStrLength != other.driveStrLength) {
            return false
        }
        if (!pathString.startsWith(other.pathString)) {
            return false
        }
        return pathString.length() == other.pathString.length() || other.pathString.length() == this.driveStrLength || pathString.charAt(
            other.pathString.length()
        ) == SEPARATOR_CHAR
    }

    /**
     * Returns true iff `other` is an ancestor of this path, ignoring case.
     * 
     * 
     * If this == other, true is returned.
     * 
     * 
     * An absolute path can never be an ancestor of a relative path, and vice versa.
     */
    fun startsWithIgnoringCase(other: PathFragment?): Boolean {
        com.google.common.base.Preconditions.checkNotNull<PathFragment?>(other)
        // Drive strings are ASCII only and hence can be checked without conversion to Unicode.
        if (this.driveStrLength != other!!.driveStrLength) {
            return false
        }
        // Convert to regular Unicode Java strings so that Unicode case mappings are applied correctly.
        val normalizedPathUnicode: String = StringEncoding.internalToUnicode(this.pathString)
        val otherNormalizedPathUnicode: String = StringEncoding.internalToUnicode(other.pathString)
        if (otherNormalizedPathUnicode.length() > normalizedPathUnicode.length()) {
            return false
        }
        if (!normalizedPathUnicode.regionMatches(
                true, 0, otherNormalizedPathUnicode, 0, otherNormalizedPathUnicode.length()
            )
        ) {
            return false
        }
        return normalizedPathUnicode.length() == otherNormalizedPathUnicode.length() || other.pathString.length() == this.driveStrLength || normalizedPathUnicode.charAt(
            otherNormalizedPathUnicode.length()
        ) == SEPARATOR_CHAR
    }

    /**
     * Returns true iff `other`, considered as a list of path segments, is relative and a suffix
     * of `this`, or both are absolute and equal.
     * 
     * 
     * This is a reflexive, transitive, anti-symmetric relation (i.e. a partial order)
     */
    fun endsWith(other: PathFragment?): Boolean {
        com.google.common.base.Preconditions.checkNotNull<PathFragment?>(other)
        if (other!!.pathString.length() > pathString.length()) {
            return false
        }
        if (other.isAbsolute) {
            return this == other
        }
        if (!pathString.endsWith(other.pathString)) {
            return false
        }
        return pathString.length() == other.pathString.length() || other.isEmpty
                || (pathString.charAt(pathString.length() - other.pathString.length() - 1)
                == SEPARATOR_CHAR)
    }

    val isAbsolute: Boolean
        get() = this.driveStrLength > 0

    override fun toString(): String {
        return this.pathString
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        return o is PathFragment && this.pathString == o.pathString
    }

    override fun hashCode(): Int {
        return this.pathString.hashCode()
    }

    /**
     * Compares this path fragment to another path fragment as normalized strings, possibly ignoring
     * casing based on the host OS.
     * 
     * 
     * `dir/foo, dir/foo-bar/data.txt, dir/foo/data.txt` is sorted according to this method,
     * which is not consistent with viewing a path as a sequence of segments. See [ ][.HIERARCHICAL_COMPARATOR] for an alternative comparator.
     */
    override fun compareTo(o: PathFragment): Int {
        return this.pathString.compareTo(o.pathString)
    }

    /**///////////////////////////////////////////////////////////////////// */ /**
    * Returns the number of segments in this path, excluding the drive string for absolute paths.
    *
    *
    * This operation is O(N) on the length of the string.
    */
    fun segmentCount(): Int {
        val n: Int = pathString.length()
        var segmentCount = 0
        var i: Int
        i = this.driveStrLength
        while (i < n) {
            if (pathString.charAt(i) == SEPARATOR_CHAR) {
                ++segmentCount
            }
            ++i
        }
        // Add last segment if one exists.
        if (i > this.driveStrLength) {
            ++segmentCount
        }
        return segmentCount
    }

    val isSingleSegment: Boolean
        /**
         * Determines whether this path consists of a single segment, excluding the drive string for
         * absolute paths.
         * 
         * 
         * Prefer this method over [.segmentCount] when it is not necessary to know the exact
         * number of segments, as this short-circuits as soon as [.SEPARATOR_CHAR] is found.
         */
        get() = pathString.length() > this.driveStrLength && !this.isMultiSegment

    val isMultiSegment: Boolean
        /**
         * Determines whether this path consists of multiple segments, excluding the drive string for
         * absolute paths.
         * 
         * 
         * Prefer this method over [.segmentCount] when it is not necessary to know the exact
         * number of segments, as this short-circuits as soon as [.SEPARATOR_CHAR] is found.
         */
        get() = pathString.indexOf(SEPARATOR_CHAR.code, this.driveStrLength) >= 0

    /**
     * Returns the specified segment of this path; index must be non-negative and less than `segmentCount()`.
     * 
     * 
     * This operation is O(N) on the length of the string.
     */
    fun getSegment(index: Int): String {
        val n: Int = pathString.length()
        var segmentCount = 0
        var i: Int
        i = this.driveStrLength
        while (i < n && segmentCount < index) {
            if (pathString.charAt(i) == SEPARATOR_CHAR) {
                ++segmentCount
            }
            ++i
        }
        val starti = i
        while (i < n) {
            if (pathString.charAt(i) == SEPARATOR_CHAR) {
                break
            }
            ++i
        }
        // Add last segment if one exists.
        if (i > this.driveStrLength) {
            ++segmentCount
        }
        val endi = i
        require(!(index < 0 || index >= segmentCount)) { "Illegal segment index: " + index }
        return pathString.substring(starti, endi)
    }

    /**
     * Returns a new path fragment that is a sub fragment of this one. The sub fragment begins at the
     * specified `beginIndex` segment and ends at the segment at index `endIndex - 1
    ` * . Thus the number of segments in the new PathFragment is `endIndex - beginIndex
    ` * .
     * 
     * 
     * If the path is absolute and `beginIndex` is zero, the returned path is absolute.
     * Otherwise, if the path is relative or `beginIndex> is greater than zero, the returned path
     * is relative.
     * 
     * 
     * This operation is O(N) on the length of the string.
     * 
     * @param beginIndex the beginning index, inclusive.
     * @param endIndex the ending index, exclusive.
     * @return the specified sub fragment, never null.
     * @throws IndexOutOfBoundsException if the `beginIndex` is negative, or `
     * endIndex` is larger than the length of this `String` object, or `
     * beginIndex` is larger than `endIndex`.
    ` */
    fun subFragment(beginIndex: Int, endIndex: Int): PathFragment {
        if (beginIndex < 0 || beginIndex > endIndex) {
            throw java.lang.IndexOutOfBoundsException(
                java.lang.String.format("path: %s, beginIndex: %d endIndex: %d", toString(), beginIndex, endIndex)
            )
        }
        return subFragmentImpl(beginIndex, endIndex)
    }

    fun subFragment(beginIndex: Int): PathFragment {
        if (beginIndex < 0) {
            throw java.lang.IndexOutOfBoundsException(
                java.lang.String.format("path: %s, beginIndex: %d", toString(), beginIndex)
            )
        }
        return subFragmentImpl(beginIndex, -1)
    }

    private fun subFragmentImpl(beginIndex: Int, endIndex: Int): PathFragment {
        val n: Int = pathString.length()
        var segmentIndex = 0
        var i: Int
        i = this.driveStrLength
        while (i < n && segmentIndex < beginIndex) {
            if (pathString.charAt(i) == SEPARATOR_CHAR) {
                ++segmentIndex
            }
            ++i
        }
        var starti = i
        if (segmentIndex < endIndex) {
            while (i < n) {
                if (pathString.charAt(i) == SEPARATOR_CHAR) {
                    ++segmentIndex
                    if (segmentIndex == endIndex) {
                        break
                    }
                }
                ++i
            }
        } else if (endIndex == -1) {
            i = pathString.length()
        }
        var endi = i
        // Add last segment if one exists for verification
        if (i == n && i > this.driveStrLength) {
            ++segmentIndex
        }
        if (beginIndex > segmentIndex || endIndex > segmentIndex) {
            throw java.lang.IndexOutOfBoundsException(
                java.lang.String.format("path: %s, beginIndex: %d endIndex: %d", toString(), beginIndex, endIndex)
            )
        }
        // If beginIndex is 0, we include the drive string.
        var driveStrLength = 0
        if (beginIndex == 0) {
            starti = 0
            driveStrLength = this.driveStrLength
            endi = java.lang.Math.max(endi, driveStrLength)
        }
        return makePathFragment(pathString.substring(starti, endi), driveStrLength)
    }

    /** Strip `numComponents` leading components from file names on extraction.  */
    fun stripComponents(numComponents: Int): PathFragment? {
        if (numComponents == 0) {
            return this
        }
        require(numComponents >= 0) { java.lang.String.format("Invalid number of components (%d)", numComponents) }
        if (numComponents >= this.segmentCount()) {
            return EMPTY_FRAGMENT
        }
        return this.subFragment(numComponents)
    }

    /**
     * Returns an [Iterable] that lazily yields the segments of this path.
     * 
     * 
     * When iterating over the segments of a path fragment, prefer this method to [ ][.splitToListOfSegments] as it performs a single, lazy traversal over the path string without
     * the overhead of creating a list.
     */
    fun segments(): Iterable<String?> {
        return Iterable { PathSegmentIterator.Companion.create(this.pathString, this.driveStrLength) }
    }

    /**
     * Splits this path fragment into a list of segments.
     * 
     * 
     * This operation is O(N) on the length of the string. If it is not necessary to store the
     * segments in list form, consider using [.segments].
     */
    fun splitToListOfSegments(): com.google.common.collect.ImmutableList<String?> {
        val segments: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(segmentCount())
        var nexti = this.driveStrLength
        val n: Int = pathString.length()
        for (i in this.driveStrLength..<n) {
            if (pathString.charAt(i) == SEPARATOR_CHAR) {
                segments.add(pathString.substring(nexti, i))
                nexti = i + 1
            }
        }
        // Add last segment if one exists.
        if (nexti < n) {
            segments.add(pathString.substring(nexti))
        }
        return segments.build()
    }

    val safePathString: String?
        /** Returns the path string, or '.' if the path is empty.  */
        get() = if (!this.isEmpty) this.pathString else "."

    val callablePathString: String?
        /**
         * Returns the path string using '/' as the name-separator character, but do so in a way
         * unambiguously recognizable as path. In other words, return "." for relative and empty paths,
         * and prefix relative paths with one segment by "./".
         * 
         * 
         * In this way, a shell will always interpret such a string as path (absolute or relative to
         * the working directory) and not as command to be searched for in the search path.
         * 
         * 
         * Prefer [.getCallablePathStringForOs] if the execution OS is available.
         */
        get() {
            if (this.isAbsolute) {
                return this.pathString
            } else if (this.isEmpty) {
                return "."
            } else if (pathString.indexOf(SEPARATOR_CHAR.code) == -1) {
                return "." + SEPARATOR_CHAR + this.pathString
            } else {
                return this.pathString
            }
        }

    /**
     * Returns the path string using the native name-separator for the given OS, but does so in a way
     * unambiguously recognizable as path. In other words, return "." for relative and empty paths,
     * and prefix relative paths with an additional "." segment.
     * 
     * 
     * In this way, a shell will always interpret such a string as path (absolute or relative to
     * the working directory) and not as command to be searched for in the search path.
     */
    fun getCallablePathStringForOs(executionOs: com.google.devtools.build.lib.util.OS?): String? {
        return OsPathPolicy.Companion.of(executionOs).postProcessPathStringForExecution(this.callablePathString)
    }

    val fileExtension: String
        /**
         * Returns the file extension of this path, excluding the period, or "" if there is no extension.
         */
        get() {
            val n: Int = pathString.length()
            for (i in n - 1 downTo this.driveStrLength + 1) {
                val c: Char = pathString.charAt(i)
                if (c == '.') {
                    return pathString.substring(i + 1, n)
                } else if (c == SEPARATOR_CHAR) {
                    break
                }
            }
            return ""
        }

    /**
     * Returns a [PathFragment] formed by appending `newName` to this [ ]'s parent directory. If this [PathFragment] has zero segments, returns
     * `null`. If `newName` is absolute, the value of `this` will be ignored and a
     * [PathFragment] corresponding to `newName` will be returned. This is consistent with
     * the behavior of [.getRelative].
     */
    fun replaceName(newName: String?): PathFragment? {
        val parent = this.parentDirectory
        return if (parent != null) parent.getRelative(newName) else null
    }

    val driveStr: String
        /**
         * Returns the drive for an absolute path fragment.
         * 
         * 
         * On unix, this will return "/". On Windows it will return the drive letter, like "C:/".
         */
        get() {
            com.google.common.base.Preconditions.checkArgument(this.isAbsolute)
            return pathString.substring(0, this.driveStrLength)
        }

    /**
     * Returns a relative PathFragment created from this absolute PathFragment using the same segments
     * and drive letter.
     */
    fun toRelative(): PathFragment {
        com.google.common.base.Preconditions.checkArgument(this.isAbsolute)
        return makePathFragment(pathString.substring(this.driveStrLength), 0)
    }

    /**
     * Returns true if this path contains uplevel references "..".
     * 
     * 
     * Since path fragments are normalized, this implies that the uplevel reference is at the start
     * of the path fragment.
     */
    fun containsUplevelReferences(): Boolean {
        // Path is normalized, so any ".." would have to be the first segment.
        return pathString.startsWith("..")
                && (pathString.length() == 2 || pathString.charAt(2) == SEPARATOR_CHAR)
    }

    private enum class NormalizedImplState {
        Base,  /* No particular state, eg. an 'a' or 'L' character */
        Separator,  /* We just saw a separator */
        Dot,  /* We just saw a dot after a separator */
        DotDot,  /* We just saw two dots after a separator */
    }

    public override fun filePathForFileTypeMatcher(): String {
        return this.pathString
    }

    override fun expand(stripPaths: UnaryOperator<PathFragment?>): String {
        return stripPaths.apply(this).normalizedPath
    }

    /** Indicates that a path fragment's base name had invalid characters.  */
    class InvalidBaseNameException private constructor(message: String?) : java.lang.Exception(message)

    private class Codec : LeafObjectCodec<PathFragment?>() {
        val encodedClass: java.lang.Class<PathFragment?>
            get() = PathFragment::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: LeafSerializationContext, obj: PathFragment, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf(obj.pathString, stringCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): PathFragment {
            return createAlreadyNormalized(context.deserializeLeaf(codedIn, stringCodec()))
        }

        companion object {
            private val INSTANCE: Codec = com.google.devtools.build.lib.vfs.PathFragment.Codec()
        }
    }

    companion object {
        private val OS: OsPathPolicy = OsPathPolicy.Companion.getFilePathOs()

        @kotlin.jvm.JvmField
        @SerializationConstant
        val EMPTY_FRAGMENT: PathFragment = RelativePathFragment("")

        const val SEPARATOR_CHAR: Char = '/'

        /**
         * Compares two path fragments lexicographically as sequences of case-sensitive path segments. The
         * relative ordering of relative and absolute paths is unspecified.
         * 
         * 
         * The ordering imposed by this comparator differs from that of [ ][.compareTo] as it sorts `foo/bar-baz/quz` after `foo/bar/quz` - it
         * has the property that the children of a path are sorted directly after their parent.
         * 
         * 
         * Note that the ordering imposed by this comparator is *not* consistent with equals if
         * applied to paths that differ only in case on Windows. Paths of artifacts in a single build are
         * known to not be affected by this as Bazel ensures that there is only a single artifact per
         * equivalence class of [PathFragment].
         */
        // TODO(bazel-team): Consider making this the default comparator for PathFragment and revisit the
        //  choice to assume case sensitivity based on the host OS. Windows case sensitivity is
        //  configurable on a per-directory basis:
        //  https://learn.microsoft.com/en-us/windows/wsl/case-sensitivity
        @kotlin.jvm.JvmField
        val HIERARCHICAL_COMPARATOR: java.util.Comparator<PathFragment?> =
            java.util.Comparator { p1: PathFragment?, p2: PathFragment? ->
                // Bazel's Strings contain raw UTF-8 bytes (see StringEncoding), which can be compared
                // byte-by-byte.
                val b1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    StringUnsafe.getInternalStringBytes(
                        p1!!.pathString
                    )
                val b2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    StringUnsafe.getInternalStringBytes(
                        p2!!.pathString
                    )
                // This is based on String.compareTo for the case of two Latin-1 coders.
                val k: Int = java.util.Arrays.mismatch(b1, b2)
                if (k == -1) {
                    return@Comparator 0
                }
                if (k >= b1.length) {
                    // b1 is a prefix of b2.
                    return@Comparator -1
                }
                if (k >= b2.length) {
                    // b2 is a prefix of b1.
                    return@Comparator 1
                }
                val c1: Byte = b1[k]
                val c2: Byte = b2[k]
                if (c1 == '/'.code.toByte()) {
                    // Sort a/b/c before a/b-c.
                    return@Comparator -1
                }
                if (c2 == '/'.code.toByte()) {
                    // Sort a/b-c after a/b/c.
                    return@Comparator 1
                }
                java.lang.Byte.compareUnsigned(c1, c2)
            }

        private val ADDITIONAL_SEPARATOR_CHAR: Char = OS.additionalSeparator()

        // DON'T add more fields here unless you know what you are doing. Adding another field will
        // increase the shallow heap of a PathFragment instance beyond the current value of 16 bytes.
        // Blaze's heap typically has many instances.
        /** Creates a new normalized path fragment.  */
        @kotlin.jvm.JvmStatic
        fun create(path: String): PathFragment {
            return createInternal(path, OS)
        }

        fun createForOs(path: String, os: com.google.devtools.build.lib.util.OS?): PathFragment {
            return createInternal(path, OsPathPolicy.Companion.getFilePathOs(os))
        }

        private fun createInternal(path: String, osPathPolicy: OsPathPolicy): PathFragment {
            if (path.isEmpty()) {
                return EMPTY_FRAGMENT
            }
            val normalizationLevel: Int = osPathPolicy.needsToNormalize(path)
            val normalizedPath: String? =
                if (normalizationLevel != OsPathPolicy.Companion.NORMALIZED)
                    osPathPolicy.normalize(path, normalizationLevel)
                else
                    path
            val driveStrLength: Int = osPathPolicy.getDriveStrLength(normalizedPath)
            return makePathFragment(normalizedPath, driveStrLength)
        }

        private fun makePathFragment(normalizedPath: String?, driveStrLength: Int): PathFragment {
            return when (driveStrLength) {
                0 -> RelativePathFragment(normalizedPath)
                1 -> UnixStyleAbsolutePathFragment(normalizedPath)
                3 -> WindowsStyleAbsolutePathFragment(normalizedPath)
                else -> throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "normalizedPath: %s, driveStrLength: %s", normalizedPath, driveStrLength
                    )
                )
            }
        }

        /**
         * Creates a new path fragment, where the caller promises that the path is normalized.
         * 
         * 
         * WARNING! Make sure the path fragment is in fact already normalized. The rest of the code
         * assumes this is the case.
         */
        fun createAlreadyNormalized(normalizedPath: String): PathFragment {
            return if (normalizedPath.isEmpty())
                EMPTY_FRAGMENT
            else
                makePathFragment(normalizedPath, OS.getDriveStrLength(normalizedPath))
        }

        @kotlin.jvm.JvmStatic
        fun isNormalizedRelativePath(path: String?): Boolean {
            val driveStrLength: Int = OS.getDriveStrLength(path)
            val normalizationLevel: Int = OS.needsToNormalize(path)
            return driveStrLength == 0 && normalizationLevel == OsPathPolicy.Companion.NORMALIZED
        }

        @kotlin.jvm.JvmStatic
        fun containsSeparator(path: String): Boolean {
            return path.lastIndexOf(SEPARATOR_CHAR.code) != -1
        }

        fun isAbsolute(path: String?): Boolean {
            return OS.getDriveStrLength(path) > 0
        }

        /**
         * Returns true if the passed path contains uplevel references "..".
         * 
         * 
         * This is useful to check a string for '..' segments before constructing a PathFragment, since
         * these are always normalized and will throw uplevel references away.
         */
        @kotlin.jvm.JvmStatic
        fun containsUplevelReferences(path: String): Boolean {
            return !isNormalizedImpl(path,  /* lookForSameLevelReferences= */false)
        }

        /**
         * Returns true if the passed path does not contain uplevel references ("..") or single-dot
         * references (".").
         * 
         * 
         * This is useful to check a string for normalization before constructing a PathFragment, since
         * these are always normalized and will throw uplevel references away.
         */
        @kotlin.jvm.JvmStatic
        fun isNormalized(path: String): Boolean {
            return isNormalizedImpl(path,  /* lookForSameLevelReferences= */true)
        }

        private fun isNormalizedImpl(path: String, lookForSameLevelReferences: Boolean): Boolean {
            // Starting state is equivalent to having just seen a separator
            var state = NormalizedImplState.Separator
            val n: Int = path.length()
            for (i in 0..<n) {
                val c: Char = path.charAt(i)
                val isSeparator: Boolean = OS.isSeparator(c)
                when (state) {
                    NormalizedImplState.Base -> {
                        if (isSeparator) {
                            state = NormalizedImplState.Separator
                        } else {
                            state = NormalizedImplState.Base
                        }
                    }

                    NormalizedImplState.Separator -> {
                        if (isSeparator) {
                            state = NormalizedImplState.Separator
                        } else if (c == '.') {
                            state = NormalizedImplState.Dot
                        } else {
                            state = NormalizedImplState.Base
                        }
                    }

                    NormalizedImplState.Dot -> {
                        if (isSeparator) {
                            if (lookForSameLevelReferences) {
                                // "." segment found
                                return false
                            }
                            state = NormalizedImplState.Separator
                        } else if (c == '.') {
                            state = NormalizedImplState.DotDot
                        } else {
                            state = NormalizedImplState.Base
                        }
                    }

                    NormalizedImplState.DotDot -> {
                        if (isSeparator) {
                            // ".." segment found
                            return false
                        } else {
                            state = NormalizedImplState.Base
                        }
                    }
                }
            }
            // The character just after the string is equivalent to a separator
            when (state) {
                NormalizedImplState.Dot -> if (lookForSameLevelReferences) {
                    // "." segment found
                    return false
                }

                NormalizedImplState.DotDot -> return false
                else -> {}
            }
            return true
        }

        /**
         * Throws [IllegalArgumentException] if `paths` contains any paths that are equal to
         * `startingWithPath` or that are not beneath `startingWithPath`.
         */
        fun checkAllPathsAreUnder(
            paths: Iterable<PathFragment>, startingWithPath: PathFragment?
        ) {
            for (path in paths) {
                com.google.common.base.Preconditions.checkArgument(
                    path != startingWithPath && path.startsWith(startingWithPath),
                    "%s is not beneath %s",
                    path,
                    startingWithPath
                )
            }
        }

        private fun checkBaseName(baseName: String) {
            require(!baseName.isEmpty()) { "Child must not be empty string ('')" }
            require(!(baseName == "." || baseName == "..")) { "baseName must not be '" + baseName + "'" }
            try {
                checkSeparators(baseName)
            } catch (e: InvalidBaseNameException) {
                throw java.lang.IllegalArgumentException("baseName " + e.getMessage() + ": '" + baseName + "'", e)
            }
        }

        @Throws(InvalidBaseNameException::class)
        fun checkSeparators(baseName: String) {
            if (baseName.indexOf(SEPARATOR_CHAR.code) != -1) {
                throw InvalidBaseNameException("must not contain " + SEPARATOR_CHAR)
            }
            if (ADDITIONAL_SEPARATOR_CHAR.code != 0) {
                if (baseName.indexOf(ADDITIONAL_SEPARATOR_CHAR.code) != -1) {
                    throw InvalidBaseNameException("must not contain " + ADDITIONAL_SEPARATOR_CHAR)
                }
            }
        }

        fun pathFragmentCodec(): Codec {
            return com.google.devtools.build.lib.vfs.PathFragment.Codec.Companion.INSTANCE
        }
    }
}
