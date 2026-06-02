// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.cmdline.LabelConstants
import com.google.devtools.build.lib.cmdline.LabelParser
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec
import com.google.devtools.build.lib.util.HashCodes
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.concurrent.CompletionException

/** The canonical name of an external repository.  */
class RepositoryName private constructor(
  /** Returns the bare repository name without the leading "@".  */
  @kotlin.jvm.JvmField val name: String,
  /**
     * Store the name of the context repository where this repository name is requested. If this field
     * is not null, it means this instance represents the requested repository name that is actually
     * not visible from the context repository and should fail in `RepositoryDelegatorFunction`
     * when fetching the repository.
     */
    private val contextRepoIfNotVisible: RepositoryName? = null,
  /**
     * If `contextRepoIfNotVisible` is not null, this field stores the suffix to be appended to
     * the error.
     */
    private val didYouMeanSuffix: String? = null
) {
    private val hashCode: Int

    init {
        this.hashCode = HashCodes.hashObjects(name, contextRepoIfNotVisible, didYouMeanSuffix)
    }

    val markerFileName: String
        /** Returns the marker file name for this repository.  */
        get() = "@" + name + ".marker"

    /**
     * Create a [RepositoryName] instance that indicates the requested repository name is
     * actually not visible from the context repository and should fail in `RepositoryDelegatorFunction` when fetching with this [RepositoryName] instance.
     */
    fun toNonVisible(contextRepo: RepositoryName?, didYouMeanSuffix: String?): RepositoryName {
        com.google.common.base.Preconditions.checkNotNull<RepositoryName?>(contextRepo)
        com.google.common.base.Preconditions.checkArgument(contextRepo!!.isVisible)
        com.google.common.base.Preconditions.checkNotNull<String?>(didYouMeanSuffix)
        return RepositoryName(name, contextRepo, didYouMeanSuffix)
    }

    @com.google.common.annotations.VisibleForTesting
    fun toNonVisible(contextRepo: RepositoryName?): RepositoryName {
        return toNonVisible(contextRepo, "")
    }

    val isVisible: Boolean
        get() = contextRepoIfNotVisible == null

    val isContextRepoMainRepo: Boolean
        get() = !this.isVisible && contextRepoIfNotVisible!!.isMain

    val contextRepoDisplayString: String?
        // Must only be called if isVisible() returns true.
        get() {
            com.google.common.base.Preconditions.checkNotNull<RepositoryName?>(contextRepoIfNotVisible)
            if (contextRepoIfNotVisible!!.isMain) {
                return "main repository"
            } else {
                return java.lang.String.format("repository '%s'", contextRepoIfNotVisible)
            }
        }

    val isMain: Boolean
        /** Returns if this is the main repository.  */
        get() = equals(MAIN)

    val nameWithAt: String?
        /**
         * Returns the repository name, with two leading "@"s, indicating that this is a
         * canonical repo name.
         */
        get() {
            if (!this.isVisible) {
                return java.lang.String.format(
                    "@@[unknown repo '%s' requested from %s%s]",
                    name, contextRepoIfNotVisible, didYouMeanSuffix
                )
            }
            return "@@" + name
        }

    val canonicalForm: String?
        /**
         * Returns the repository name with leading "@"s except for the main repo, which is
         * just the empty string.
         */
        get() = if (this.isMain) "" else this.nameWithAt

    /**
     * Returns the repository part of a [Label]'s string representation suitable for display.
     * The returned string is as simple as possible in the context of the main repo whose repository
     * mapping is provided: an empty string for the main repo, or a string prefixed with a leading
     * "@" or "@@" otherwise.
     * 
     * @param mainRepositoryMapping the [RepositoryMapping] of the main repository
     * @return
     * <dl>
     * <dt>the empty string
    </dt> * <dd>if this is the main repository
    </dd> * <dt>`@protobuf`
    </dt> * <dd>if this repository is a direct dependency of the main module and its apparent name is
     * "protobuf" (only if mainRepositoryMapping is not null)
    </dd> * <dt>`@@protobuf+`
    </dt> * <dd>if this a repository that is not visible from the main module
    </dd></dl> */
    fun getDisplayForm(mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?): String? {
        com.google.common.base.Preconditions.checkArgument(
            mainRepositoryMapping == null || mainRepositoryMapping.contextRepo().isMain()
        )
        if (!this.isVisible) {
            return this.nameWithAt
        }
        if (this.isMain) {
            // Packages in the main repository can always use repo-relative form.
            return ""
        }
        if (mainRepositoryMapping == null) {
            return this.nameWithAt
        }
        // If possible, represent the repository with a non-canonical label using the apparent name the
        // main repository has for it, otherwise fall back to a canonical label.
        return mainRepositoryMapping
            .getInverse(this)
            .map<String?>(java.util.function.Function { apparentName: String? -> "@" + apparentName })
            .orElse(this.nameWithAt)
    }

    /**
     * Returns the runfiles/execRoot path for this repository. If we don't know the name of this repo
     * (i.e., it is in the main repository), return an empty path fragment.
     * 
     * 
     * If --experimental_sibling_repository_layout is true, return "$execroot/../repo" (sibling of
     * __main__), instead of "$execroot/external/repo".
     */
    fun getExecPath(siblingRepositoryLayout: Boolean): PathFragment? {
        if (this.isMain) {
            return PathFragment.EMPTY_FRAGMENT
        }
        val prefix: PathFragment =
            if (siblingRepositoryLayout)
                LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
            else
                LabelConstants.EXTERNAL_PATH_PREFIX
        return prefix.getRelative(this.name)
    }

    val runfilesPath: PathFragment?
        /** Returns the runfiles path relative to the x.runfiles/main-repo directory.  */
        get() = if (this.isMain)
            PathFragment.EMPTY_FRAGMENT
        else
            PathFragment.create("..").getRelative(this.name)

    /** Same as [.getNameWithAt].  */
    override fun toString(): String {
        return this.nameWithAt!!
    }

    override fun equals(`object`: Any?): Boolean {
        if (this === `object`) {
            return true
        }
        if (`object` !is RepositoryName) {
            return false
        }
        return name == `object`.name
                && contextRepoIfNotVisible == `object`.contextRepoIfNotVisible
                && didYouMeanSuffix == `object`.didYouMeanSuffix
    }

    override fun hashCode(): Int {
        return hashCode
    }

    private class Codec : LeafObjectCodec<RepositoryName?>() {
        val encodedClass: java.lang.Class<RepositoryName?>
            get() = RepositoryName::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: LeafSerializationContext, obj: RepositoryName, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf<String?>(obj.name, UnsafeStringCodec.stringCodec(), codedOut)
            context.serializeLeaf<RepositoryName?>(obj.contextRepoIfNotVisible, this, codedOut)
            context.serializeLeaf<String?>(obj.didYouMeanSuffix, UnsafeStringCodec.stringCodec(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): RepositoryName {
            return RepositoryName(
                context.deserializeLeaf<String?>(codedIn, UnsafeStringCodec.stringCodec()),
                context.deserializeLeaf<RepositoryName?>(codedIn, this),
                context.deserializeLeaf<String?>(codedIn, UnsafeStringCodec.stringCodec())
            )
        }

        companion object {
            private val INSTANCE: Codec = com.google.devtools.build.lib.cmdline.RepositoryName.Codec()
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val BAZEL_TOOLS: RepositoryName = RepositoryName("bazel_tools")

        @kotlin.jvm.JvmField
        @SerializationConstant
        val MAIN: RepositoryName = RepositoryName("")

        @kotlin.jvm.JvmField
        @SerializationConstant
        val BUILTINS: RepositoryName = RepositoryName("_builtins")

        private val VALID_REPO_NAME: java.util.regex.Pattern = java.util.regex.Pattern.compile("[\\w\\-.+]*")

        // Must start with a letter. Can contain ASCII letters and digits, underscore, dash, and dot.
        private val VALID_USER_PROVIDED_NAME: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("[a-zA-Z0-9][-.\\w]*$")

        /**
         * A valid module name must: 1) begin with a lowercase letter; 2) end with a lowercase letter or a
         * digit; 3) contain only lowercase letters, digits, or one of * '._-'.
         */
        val VALID_MODULE_NAME: java.util.regex.Pattern = java.util.regex.Pattern.compile("[a-z]([a-z0-9._-]*[a-z0-9])?")

        private val repositoryNameCache: com.github.benmanes.caffeine.cache.LoadingCache<String?, RepositoryName?> =
            Caffeine.newBuilder()
                .weakValues()
                .build<String?, RepositoryName?>(
                    com.github.benmanes.caffeine.cache.CacheLoader { name: String? ->
                        Companion.validate(name!!)
                        RepositoryName(name.intern())
                    })

        /**
         * Makes sure that name is a valid repository name and creates a new RepositoryName using it.
         * 
         * @throws LabelSyntaxException if the name is invalid
         */
        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        fun create(name: String): RepositoryName? {
            if (name.isEmpty()) {
                return MAIN
            }
            if (name == BUILTINS.name) {
                return BUILTINS
            }
            try {
                return repositoryNameCache.get(name)
            } catch (e: CompletionException) {
                com.google.common.base.Throwables.throwIfInstanceOf<LabelSyntaxException?>(
                    e.getCause(),
                    LabelSyntaxException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
                throw e
            }
        }

        /** Creates a RepositoryName from a known-valid string.  */
        @kotlin.jvm.JvmStatic
        fun createUnvalidated(name: String): RepositoryName? {
            if (name.isEmpty()) {
                // NOTE(wyv): Without this `if` clause, a lot of Google-internal integration tests would start
                //   failing. This suggests to me that something is comparing RepositoryName objects using
                //   reference equality instead of #equals().
                return MAIN
            }
            if (name == BUILTINS.name) {
                return BUILTINS
            }
            return repositoryNameCache.get(name)
        }

        /**
         * Extracts the repository name from a PathFragment that was created with `PackageIdentifier.getSourceRoot`.
         * 
         * @return a `Pair` of the extracted repository name and the path fragment with stripped of
         * "external/"-prefix and repository name, or null if none was found or the repository name
         * was invalid.
         */
        fun fromPathFragment(
            path: PathFragment, siblingRepositoryLayout: Boolean
        ): com.google.devtools.build.lib.util.Pair<RepositoryName?, PathFragment?>? {
            if (!path.isMultiSegment()) {
                return null
            }

            val prefix: PathFragment? =
                if (siblingRepositoryLayout)
                    LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
                else
                    LabelConstants.EXTERNAL_PATH_PREFIX
            if (!path.startsWith(prefix)) {
                return null
            }

            try {
                val repoName = create(path.getSegment(1))
                val subPath: PathFragment? = path.subFragment(2)
                return com.google.devtools.build.lib.util.Pair.of<RepositoryName?, PathFragment?>(repoName, subPath)
            } catch (e: LabelSyntaxException) {
                return null
            }
        }

        /**
         * Performs validity checking, throwing an exception if the given name is invalid. The exception
         * message is sanitized.
         */
        @Throws(LabelSyntaxException::class)
        fun validate(name: String) {
            if (name.isEmpty() || name == BUILTINS.name) {
                return
            }

            // Some special cases for more user-friendly error messages.
            if (name == "." || name == "..") {
                throw LabelParser.syntaxErrorf(
                    "invalid repository name '%s': repo names are not allowed to be '%s'", name, name
                )
            }

            if (!VALID_REPO_NAME.matcher(name).matches()) {
                throw LabelParser.syntaxErrorf(
                    "invalid repository name '%s': repo names may contain only A-Z, a-z, 0-9, '-', '_', '.'"
                            + " and '+'",
                    com.google.devtools.build.lib.util.StringUtilities.sanitizeControlChars(name)
                )
            }
        }

        /**
         * Validates a repo name provided by the user. Such names have tighter restrictions; for example,
         * they can only start with a letter, and cannot contain a plus (+).
         */
        @kotlin.jvm.JvmStatic
        @Throws(net.starlark.java.eval.EvalException::class)
        fun validateUserProvidedRepoName(name: String?) {
            if (!VALID_USER_PROVIDED_NAME.matcher(name).matches()) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "invalid user-provided repo name '%s': valid names may contain only A-Z, a-z, 0-9, '-',"
                            + " '_', '.', and must start with a letter or a number",
                    com.google.devtools.build.lib.util.StringUtilities.sanitizeControlChars(name)
                )
            }
        }

        /** Returns true if the given name cannot possibly be a canonical repository name.  */
        fun isApparent(name: String): Boolean {
            return !name.isEmpty() && !name.contains("+")
        }

        fun repositoryNameCodec(): Codec {
            return com.google.devtools.build.lib.cmdline.RepositoryName.Codec.Companion.INSTANCE
        }
    }
}
