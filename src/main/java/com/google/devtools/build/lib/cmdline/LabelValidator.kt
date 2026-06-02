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
package com.google.devtools.build.lib.cmdline

/**
 * The canonical place to parse and validate labels.
 * 
 * 
 * NOTE: The methods in this file are called both by Bazel and external programs, so they must
 * accept both internal and Unicode strings (see StringEncoding.java for more information). We can
 * get away with a single entry point for both encodings because all determinations we need to make
 * about the syntax of labels are based solely on ASCII code points, which are encoded identically
 * in both kinds of string.
 */
object LabelValidator {
    // Target names allow all ASCII characters except
    // 0-31 (control characters)
    // 58 ':' (colon)
    // 92 '\' (backslash) - directory separator (on Windows); may be allowed in the future
    // 127 (delete)
    /** Matches punctuation in target names which requires quoting in a blaze query.  */
    private val PUNCTUATION_REQUIRING_QUOTING: com.google.common.base.CharMatcher =
        com.google.common.base.CharMatcher.anyOf(" \"#$&'()*+,;<=>?[]{|}~")

    /**
     * Matches punctuation in target names which doesn't require quoting in a blaze query.
     * 
     * 
     * Note that . is also allowed in target names, and doesn't require quoting, but has
     * restrictions on its surrounding characters; see [.validateTargetName].
     */
    private val PUNCTUATION_NOT_REQUIRING_QUOTING: com.google.common.base.CharMatcher =
        com.google.common.base.CharMatcher.anyOf("!%-@^_`")

    // Package names allow all ASCII characters except
    // 0-31 (control characters)
    // 58 ':' (colon) - target name separator
    // 92 '\' (backslash) - directory separator (on Windows); may be allowed in the future
    // 127 (delete)
    /** Matches characters allowed in package name.  */
    private val ALLOWED_CHARACTERS_IN_PACKAGE_NAME: com.google.common.base.CharMatcher =
        com.google.common.base.CharMatcher.inRange('0', '9')
            .or(com.google.common.base.CharMatcher.inRange('a', 'z'))
            .or(com.google.common.base.CharMatcher.inRange('A', 'Z'))
            .or(com.google.common.base.CharMatcher.anyOf(" !\"#$%&'()*+,-./;<=>?@[]^_`{|}~"))
            .precomputed()

    /**
     * Matches characters allowed in target names regardless of context.
     * 
     * 
     * Note that the only other characters allowed in target names are / and . but they have
     * restrictions around surrounding characters; see [.validateTargetName].
     */
    private val ALWAYS_ALLOWED_TARGET_CHARACTERS: com.google.common.base.CharMatcher =
        com.google.common.base.CharMatcher.javaLetterOrDigit()
            .or(PUNCTUATION_REQUIRING_QUOTING)
            .or(PUNCTUATION_NOT_REQUIRING_QUOTING) // Accept any non-ASCII character, which is also guaranteed to be (part of) the encoding
            // of a non-ASCII code point, both for internal and Unicode strings.
            .or(com.google.common.base.CharMatcher.inRange(128.toChar(), 65535.toChar()))
            .precomputed()

    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val PACKAGE_NAME_ERROR: String =
        ("package names may contain A-Z, a-z, 0-9, or any of ' !\"#$%&'()*+,-./;<=>?[]^_`{|}~'"
                + " (any ASCII character except 0-31, 127, ':', or '\\')")

    @com.google.common.annotations.VisibleForTesting
    const val PACKAGE_NAME_DOT_ERROR: String = "package name component contains only '.' characters"

    /**
     * Performs validity checking of the specified package name. Returns null on success or an error
     * message otherwise.
     * 
     * @param packageName the name of the package
     * @return null if `name` is valid or an error string if any part
     * of the package name is invalid
     */
    @kotlin.jvm.JvmStatic
    fun validatePackageName(packageName: String): String? {
        val len: Int = packageName.length()
        if (len == 0) {
            // Empty package name (//:foo).
            return null
        }

        if (packageName.charAt(0) == '/') {
            return "package names may not start with '/'"
        }

        if (!ALLOWED_CHARACTERS_IN_PACKAGE_NAME.matchesAllOf(packageName)) {
            return PACKAGE_NAME_ERROR
        }

        if (packageName.charAt(packageName.length() - 1) == '/') {
            return "package names may not end with '/'"
        }
        // Check for empty or dot-only package segment
        var nonDot = false
        var lastSlash = true
        // Going backward and marking the last character as being a / so we detect
        // '.' only package segment.
        for (i in len - 1 downTo -1) {
            val c = if (i >= 0) packageName.charAt(i) else '/'
            if (c == '/') {
                if (lastSlash) {
                    return "package names may not contain '//' path separators"
                }
                if (!nonDot) {
                    return PACKAGE_NAME_DOT_ERROR
                }
                nonDot = false
                lastSlash = true
            } else {
                if (c != '.') {
                    nonDot = true
                }
                lastSlash = false
            }
        }

        return null // ok
    }

    /**
     * Performs validity checking of the specified target name. Returns null on success or an error
     * message otherwise.
     */
    @kotlin.jvm.JvmStatic
    fun validateTargetName(targetName: String): String? {
        // We allow labels equaling '.' or ending in '/.' for now. If we ever
        // actually configure the target we will report an error, but they are permitted for
        // data directories.

        // Code optimized for the common case: success.

        val len: Int = targetName.length()
        if (len == 0) {
            return "empty target name"
        }
        // Forbidden start chars:
        var c: Char = targetName.charAt(0)
        if (c == '/') {
            return "target names may not start with '/'"
        } else if (c == '.') {
            if (targetName.startsWith("../") || targetName == "..") {
                return "target names may not contain up-level references '..'"
            } else if (targetName == ".") {
                return null // See comment above; ideally should be an error.
            } else if (targetName.startsWith("./")) {
                return "target names may not contain '.' as a path segment"
            }
        }

        // Give a friendly error message on CRs in target names
        if (targetName.endsWith("\r")) {
            return "target names may not end with carriage returns " +
                    "(perhaps the input source is CRLF-terminated)"
        }

        for (ii in 0..<len) {
            c = targetName.charAt(ii)
            if (ALWAYS_ALLOWED_TARGET_CHARACTERS.matches(c)) {
                continue
            }
            if (c == '.') {
                continue
            }
            if (c == '/') {
                if (stringRegionMatch(targetName, "/../", ii)) {
                    return "target names may not contain up-level references '..'"
                } else if (stringRegionMatch(targetName, "/./", ii)) {
                    return "target names may not contain '.' as a path segment"
                } else if (stringRegionMatch(targetName, "//", ii)) {
                    return "target names may not contain '//' path separators"
                }
                continue
            }
            if (c <= '\u001f' || c == '\u007f') {
                return "target names may not contain non-printable characters: '" +
                        java.lang.String.format("\\x%02X", c.code) + "'"
            }
            return "target names may not contain '" + c + "'"
        }
        // Forbidden end chars:
        if (c == '.') {
            if (targetName.endsWith("/..")) {
                return "target names may not contain up-level references '..'"
            } else if (targetName.endsWith("/.")) {
                return null // See comment above; ideally should be an error.
            }
        }
        if (c == '/') {
            return "target names may not end with '/'"
        }
        return null // ok
    }

    // Prefer this implementation over calls to String#subString(), as the latter implies copying
    // the subregion.
    private fun stringRegionMatch(fullString: String, possibleMatch: String, offset: Int): Boolean {
        return fullString.regionMatches(offset, possibleMatch, 0, possibleMatch.length())
    }

    /**
     * Validate the label and parse it into a pair of package name and target name. If the label is
     * not valid, it throws an [BadLabelException].
     * 
     * 
     * It accepts these forms of labels:
     * <pre>
     * //foo/bar
     * //foo/bar:quux
     * //foo/bar:      (undocumented, but accepted)
    </pre> * 
     */
    @kotlin.jvm.JvmStatic
    @Throws(BadLabelException::class)
    fun validateAbsoluteLabel(absName: String): PackageAndTarget {
        val result = parseAbsoluteLabel(absName)
        val packageName = result.packageName
        val targetName = result.targetName
        var error = validatePackageName(packageName)
        if (error != null) {
            error = "invalid package name '" + packageName + "': " + error
            // This check is just for a more helpful error message,
            // i.e. valid target name, invalid package name, colon-free label form
            // used => probably they meant "//foo:bar.c" not "//foo/bar.c".
            if (packageName.endsWith("/" + targetName)) {
                error += " (perhaps you meant \":" + targetName + "\"?)"
            }
            throw BadLabelException(error)
        }
        error = validateTargetName(targetName)
        if (error != null) {
            error = "invalid target name '" + targetName + "': " + error
            throw BadLabelException(error)
        }
        return result
    }

    /**
     * Returns if the label starts with a repository (@whatever) or a package (//whatever).
     */
    @kotlin.jvm.JvmStatic
    fun isAbsolute(label: String): Boolean {
        return label.startsWith("//") || label.startsWith("@")
    }

    /**
     * Parses the given absolute label by verifying that it starts with "//". If it contains a ':',
     * then the part after that is the target name within the package, and the part before that (but
     * without the leading "//") is the package name. However, it performs no validation on these two
     * pieces.
     * 
     * 
     * Use of this method is generally not recommended.
     * 
     * @throws NullPointerException if `absName` is `null`
     * @throws BadLabelException if `absName` starts with "//"
     */
    @kotlin.jvm.JvmStatic
    @Throws(BadLabelException::class)
    fun parseAbsoluteLabel(absName: String): PackageAndTarget {
        var absName = absName
        if (!isAbsolute(absName)) {
            throw BadLabelException("invalid label: " + absName)
        }
        if (absName.startsWith("@")) {
            val endOfRepo: Int = absName.indexOf("//")
            if (endOfRepo < 0) {
                return PackageAndTarget("", absName.substring(1))
            }
            absName = absName.substring(endOfRepo)
        }
        // Find the package/suffix separation:
        val colonIndex: Int = absName.indexOf(':'.code)
        val splitAt = if (colonIndex >= 0) colonIndex else absName.length()
        val packageName: String = absName.substring("//".length(), splitAt)
        val suffix: String = absName.substring(splitAt)

        // ('suffix' is empty, or starts with a colon.)

        // "If packagename and version are elided, the colon is not necessary."
        val targetName: String =
            if (suffix.isEmpty() // Target name is last package segment: (works in slash-free case too.)
            )
                packageName.substring(packageName.lastIndexOf('/'.code) + 1) // Target name is what's after colon:
            else
                suffix.substring(1)

        return PackageAndTarget(packageName, targetName)
    }

    /**
     * A pair of package and target names. Note that having an instance of this does not imply that
     * the package or target names are actually valid.
     */
    class PackageAndTarget(val packageName: String, val targetName: String) {
        override fun toString(): String {
            return "//" + packageName + ":" + targetName
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(packageName, targetName)
        }

        override fun equals(o: Any?): Boolean {
            if (o == null || o.getClass() != getClass()) {
                return false
            }
            val otherTarget = o as PackageAndTarget
            return otherTarget.targetName == targetName
                    && otherTarget.packageName == packageName
        }
    }

    /**
     * An exception to notify the caller that a label could not be parsed.
     */
    class BadLabelException(msg: String?) : java.lang.Exception(msg)
}
