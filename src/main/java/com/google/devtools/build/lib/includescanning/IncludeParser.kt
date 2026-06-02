// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning

import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.LoadingCache
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.collect.*
import com.google.common.io.CharStreams
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.concurrent.BlazeInterners
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.profiler.ProfilerTask
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * Scans a source file and extracts the literal inclusions it specifies. Does not store results --
 * repeated requests to the same file will result in repeated scans. Clients should implement a
 * caching layer in order to avoid unnecessary disk access when requesting an already scanned file.
 * 
 * 
 * Both this class and the static inner class [Hints] have lifetime of a single build (or a
 * single include scanning operation in the case of the [SwigIncludeParser]).
 */
@VisibleForTesting
internal open class IncludeParser
/**
 * Constructs a new FileParser.
 * 
 * @param hints regexps for converting computed includes into simple strings
 */(
    /** The externally-scoped immutable hints helper that is shared by all scanners.  */
    val hints: Hints?
) {
    /**
     * File types supported by the grep-includes binary. [.fileType] must be kept in sync with
     * //tools/cpp:grep-includes.
     */
    enum class GrepIncludesFileType(fileType: String) {
        CPP("c++"),
        SWIG("swig");

        val fileType: String?

        init {
            this.fileType = fileType
        }
    }

    /**
     * Immutable object representation of the four columns making up a single Rule in a Hints set. See
     * [Hints] for more details.
     */
    private class Rule(type: String, pattern: String?, findRoot: String, val findFilter: String?) {
        private enum class Type {
            PATH,
            FILE,
            INCLUDE_QUOTE,
            INCLUDE_ANGLE
        }

        val type: Type
        val pattern: Pattern
        val findRoot: String

        init {
            this.type = Type.valueOf(type.trim().toUpperCase())
            this.pattern = Pattern.compile("^" + pattern + "$")
            this.findRoot = findRoot.replace('\\', '$')
        }

        internal constructor(type: String, pattern: String?, findRoot: String) : this(type, pattern, findRoot, null) {
            Preconditions.checkArgument(
                (this.type == Type.INCLUDE_QUOTE) || (this.type == Type.INCLUDE_ANGLE), this
            )
        }

        override fun toString(): String {
            return type.toString() + " " + pattern + " " + findRoot + " " + findFilter
        }
    }

    /** [SkyValue] encapsulating the source-state-dependent part of [Hints].  */
    class HintsRules private constructor(private val rules: ImmutableList<Rule>) : SkyValue {
        companion object {
            val EMPTY: HintsRules = HintsRules(ImmutableList.of<Rule?>())
        }
    }

    /**
     * This class is a representation of the INCLUDE_HINTS file. The hints file contains regexp-based
     * rules to help this simple include scanner cope with computed includes, which would otherwise
     * require a full preprocessor with symbol support. Instead of actually processing symbols to
     * evaluate the computed includes, we instead apply rules to gather inclusions for matching paths.
     * 
     * 
     * The hints file is read, line by line, into a list of rules each of which encapsulates a line
     * of four columns. Each non-blank, non-comment line has the format:
     * 
     * <pre>
     * &quot;file&quot;|&quot;path&quot;  match-pattern  find-root  find-filter
    </pre> * 
     * 
     * 
     * The first column specifies whether the line is a rule based on matching source
     * *files* (passed directly to the compiler as inputs, or transitively #included by other
     * inputs) or include *paths* (passed to the compiler as -I, -iquote, or -isystem flags).
     * 
     * 
     * The second column is a regexp for files or paths. Whenever a compiler argument of the
     * specified type matches that regexp, the rule is taken. (All matching rules for every path and
     * file on a compiler command line are followed, and the results are combined.)
     * 
     * 
     * The third column is a point in the local filesystem from which to extract a recursive
     * listing. (This follows symlinks) Backrefs may be used to refer to the regexp or its capturing
     * groups. (This is mostly necessary because --package_path can cause input paths to carry
     * arbitrary prefixes.)
     * 
     * 
     * The fourth column is a regexp applied to each file found by the recursive listing. All
     * matching files are treated as dependencies.
     */
    class Hints internal constructor(
        hintsRules: HintsRules,
        syscallCache: SyscallCache?,
        artifactFactory: ArtifactFactory
    ) {
        private val rules: ImmutableList<Rule>
        private val artifactFactory: ArtifactFactory

        private val syscallCache: SyscallCache?

        private val fileLevelHintsCache: LoadingCache<Artifact?, ImmutableList<Artifact?>?> = Caffeine.newBuilder()
            .initialCapacity(HINTS_CACHE_CONCURRENCY)
            .build<Artifact?, ImmutableList<Artifact?>?>(CacheLoader { artifact: Artifact? ->
                this.getHintedInclusionsLegacy(
                    artifact
                )
            })

        /**
         * Constructs a hint set for a given INCLUDE_HINTS file to read.
         * 
         * @param hintsRules the [HintsRules] parsed from INCLUDE_HINTS
         */
        init {
            this.syscallCache = syscallCache
            this.artifactFactory = artifactFactory
            this.rules = hintsRules.rules
        }

        /** Returns the "file" type hinted inclusions for a given path, caching results by path.  */
        fun getFileLevelHintedInclusionsLegacy(path: Artifact): ImmutableList<Artifact?>? {
            if (!path.getExecPathString().startsWith(ALLOWED_PREFIX)) {
                return ImmutableList.of<Artifact?>()
            }
            return fileLevelHintsCache.get(path)
        }

        /**
         * Returns the "path" type hinted inclusions for the given paths. Callers are responsible for
         * caching.
         * 
         * 
         * Returns `null` when a skyframe restart is necessary.
         */
        @Throws(InterruptedException::class, IOException::class, NoSuchPackageException::class)
        fun getPathLevelHintedInclusions(
            paths: ImmutableList<PathFragment?>, env: SkyFunction.Environment
        ): ImmutableSet<Artifact?>? {
            val pathStrings =
                paths.stream()
                    .map<String?>(Function { obj: PathFragment? -> obj.getPathString() })
                    .filter(Predicate { p: String? -> p.startsWith(ALLOWED_PREFIX) })
                    .collect(ImmutableList.toImmutableList<String?>())
            if (pathStrings.isEmpty()) {
                return ImmutableSet.of<Artifact?>()
            }
            // Delay creation until we know we need one. Use a sorted set to make sure that the results
            // have a stable order and are unique.
            var hints: ImmutableSortedSet.Builder<Artifact?>? = null
            val rulePaths: MutableList<ContainingPackageLookupValue.Key> =
                ArrayList<ContainingPackageLookupValue.Key>(rules.size())
            val findFilters: MutableList<String> = ArrayList<String>(rules.size())
            for (rule in rules) {
                if (rule.type != Rule.Type.PATH) {
                    continue
                }
                var firstMatchPathString: String? = null
                var m: Matcher? = null
                for (pathString in pathStrings) {
                    m = rule.pattern.matcher(pathString)
                    if (m.matches()) {
                        firstMatchPathString = pathString
                        break
                    }
                }
                if (firstMatchPathString == null) {
                    continue
                }
                if (hints == null) {
                    hints = ImmutableSortedSet.orderedBy<Artifact?>(Artifact.EXEC_PATH_COMPARATOR)
                }
                val relativePath: PathFragment = PathFragment.create(m!!.replaceFirst(rule.findRoot))
                logger.atFine().log(
                    "hint for %s %s root: %s", rule.type, firstMatchPathString, relativePath
                )
                if (!relativePath.getPathString().startsWith(ALLOWED_PREFIX)) {
                    logger.atWarning().log(
                        "Path %s to search after substitution does not start with %s",
                        relativePath.getPathString(), ALLOWED_PREFIX
                    )
                    continue
                }
                rulePaths.add(
                    ContainingPackageLookupValue.key(PackageIdentifier.createInMainRepo(relativePath))
                )
                findFilters.add(rule.findFilter!!)
            }
            val containingPackageLookupValues: SkyframeLookupResult = env.getValuesAndExceptions(rulePaths)
            if (env.valuesMissing() && !env.inErrorBubbling()) {
                return null
            }
            val globKeys: MutableList<GlobDescriptor> = ArrayList<GlobDescriptor>(rulePaths.size())
            for (i in rulePaths.indices) {
                val containingPackageLookupValue: ContainingPackageLookupValue?
                val relativePathKey: ContainingPackageLookupValue.Key = rulePaths.get(i)
                val relativePath: PathFragment = relativePathKey.argument().getPackageFragment()
                try {
                    containingPackageLookupValue =
                        containingPackageLookupValues.getOrThrow<NoSuchPackageException?>(
                            relativePathKey, NoSuchPackageException::class.java
                        ) as ContainingPackageLookupValue?
                } catch (e: NoSuchPackageException) {
                    if (env.inErrorBubbling()) {
                        throw e
                    }
                    logger.atWarning().withCause(e).log(
                        "Unexpected exception when looking up containing package for %s"
                                + " (prodaccess expired?)",
                        relativePath
                    )
                    continue
                }
                if (!containingPackageLookupValue.hasContainingPackage()) {
                    logger.atWarning().log("%s not contained in any package: skipping", relativePath)
                    continue
                }
                val packageFragment: PathFragment? =
                    containingPackageLookupValue.getContainingPackageName().getPackageFragment()
                val pattern = findFilters.get(i)
                try {
                    // TODO: b/290998109#comment60 - Convert to create GLOBS node in IncludeParser.
                    globKeys.add(
                        GlobValue.key(
                            containingPackageLookupValue.getContainingPackageName(),
                            containingPackageLookupValue.getContainingPackageRoot(),
                            pattern,
                            Globber.Operation.FILES,
                            relativePath.relativeTo(packageFragment)
                        )
                    )
                } catch (e: InvalidGlobPatternException) {
                    env.getListener()
                        .handle(Event.Companion.warn("Error parsing pattern " + pattern + " for " + relativePath))
                }
            }
            if (env.valuesMissing()) {
                return null
            }
            val globResults: SkyframeLookupResult = env.getValuesAndExceptions(globKeys)
            if (env.valuesMissing() && !env.inErrorBubbling()) {
                return null
            }
            for (globKey in globKeys) {
                val packageFragment: PathFragment = globKey.getPackageId().getPackageFragment()
                val globValue: GlobValue?
                try {
                    globValue =
                        globResults.getOrThrow<IOException?, BuildFileNotFoundException?>(
                            globKey, IOException::class.java, BuildFileNotFoundException::class.java
                        ) as GlobValue?
                } catch (e: IOException) {
                    if (env.inErrorBubbling()) {
                        throw e
                    }
                    logger.atWarning().withCause(e).log(
                        "Unexpected exception when computing glob for %s" + " (prodaccess expired?)",
                        globKey
                    )
                    continue
                } catch (e: BuildFileNotFoundException) {
                    if (env.inErrorBubbling()) {
                        throw e
                    }
                    logger.atWarning().withCause(e).log(
                        "Unexpected exception when computing glob for %s" + " (prodaccess expired?)",
                        globKey
                    )
                    continue
                }
                for (file in globValue.getMatches()) {
                    hints!!.add(
                        artifactFactory.getSourceArtifact(
                            packageFragment.getRelative(file), globKey.getPackageRoot()
                        )
                    )
                }
            }
            if (env.valuesMissing()) {
                return null
            }
            return if (hints == null) ImmutableSet.of<Artifact?>() else hints.build()
        }

        /**
         * Performs the work of matching a given path against the hints and returns the expanded paths.
         * The above [.getHintedInclusions] should be used in preference, but if the performance
         * impact of Skyframe restarts is untenable, this can be used as a fallback.
         */
        private fun getHintedInclusionsLegacy(artifact: Artifact): ImmutableList<Artifact?> {
            val pathString: String? = artifact.getExecPath().getPathString()
            val sourceRoot: Root = artifact.getRoot().getRoot()
            // Delay creation until we know we need one. Use a TreeSet to make sure that the results are
            // sorted with a stable order and unique.
            var hints: MutableSet<Path?>? = null
            for (rule in rules) {
                if (rule.type != Rule.Type.FILE) {
                    continue
                }
                val m = rule.pattern.matcher(pathString)
                if (!m.matches()) {
                    continue
                }
                if (hints == null) {
                    hints = Sets.newTreeSet<Path?>()
                }
                val relativePath = m.replaceFirst(rule.findRoot)
                if (!relativePath.startsWith(ALLOWED_PREFIX)) {
                    logger.atWarning().log(
                        "Path %s to search after substitution does not start with %s",
                        relativePath, ALLOWED_PREFIX
                    )
                    continue
                }
                val root: Path? = sourceRoot.getRelative(relativePath)

                logger.atFine().log("hint for %s %s root: %s", rule.type, pathString, root)
                try {
                    // The assumption is made here that all files specified by this hint are under the same
                    // package path as the original file -- this filesystem tree traversal is completely
                    // ignorant of package paths. This could be violated if there were a hint that resolved to
                    // foo/**/*.h, there was a package foo/bar, and the packages foo and foo/bar were in
                    // different package paths. In that case, this traversal would fail to pick up
                    // foo/bar/**/*.h. No examples of this currently exist in the INCLUDE_HINTS
                    // file.
                    logger.atFine().log("Globbing: %s %s", root, rule.findFilter)
                    hints.addAll(UnixGlob.Builder(root, syscallCache).addPattern(rule.findFilter).glob())
                } catch (e: BadPattern) {
                    logger.atWarning().withCause(e).log("Error in hint expansion")
                } catch (e: IOException) {
                    logger.atWarning().withCause(e).log("Error in hint expansion")
                }
            }
            if (hints == null || hints.isEmpty()) {
                return ImmutableList.of<Artifact?>()
            }
            // Transform paths into source artifacts (all hints must be to source artifacts).
            val result: ImmutableList.Builder<Artifact?> =
                ImmutableList.builderWithExpectedSize<Artifact?>(hints.size())
            for (hint in hints) {
                result.add(
                    Preconditions.checkNotNull(
                        artifactFactory.getSourceArtifact(sourceRoot.relativize(hint), sourceRoot),
                        "Missing source artifact, hint=%s, sourceRoot=%s, pathString=%s",
                        hint,
                        sourceRoot,
                        pathString
                    )
                )
            }
            return result.build()
        }

        private fun getHintedInclusions(path: Artifact): MutableCollection<Inclusion?> {
            val pathString: String? = path.getExecPathString()
            // Delay creation until we know we need one. Use a LinkedHashSet to make sure that the results
            // are sorted with a stable order and unique.
            var hints: MutableSet<Inclusion?>? = null
            for (rule in rules) {
                if ((rule.type != Rule.Type.INCLUDE_ANGLE) && (rule.type != Rule.Type.INCLUDE_QUOTE)) {
                    continue
                }
                val m = rule.pattern.matcher(pathString)
                if (!m.matches()) {
                    continue
                }
                if (hints == null) {
                    hints = LinkedHashSet<Inclusion?>()
                }
                val inclusion: Inclusion =
                    Inclusion.Companion.create(
                        rule.findRoot,
                        if (rule.type == Rule.Type.INCLUDE_QUOTE) Inclusion.Kind.QUOTE else Inclusion.Kind.ANGLE
                    )
                hints!!.add(inclusion)
                logger.atFine().log("hint for %s %s root: %s", rule.type, pathString, inclusion)
            }
            if (hints != null && !hints.isEmpty()) {
                return ImmutableList.copyOf<Inclusion?>(hints)
            } else {
                return ImmutableList.of<Inclusion?>()
            }
        }

        companion object {
            private val WS_PAT: Pattern = Pattern.compile("\\s+")

            @VisibleForTesting
            const val ALLOWED_PREFIX: String = "third_party/"

            // Match regular expressions that can only match paths under ALLOWED_PREFIX .
            private val ALLOWED_PATTERN: Pattern = Pattern.compile("^\\(*" + ALLOWED_PREFIX + ".*")

            private const val HINTS_CACHE_CONCURRENCY = 100

            @Throws(IOException::class)
            fun getRules(hintsFile: Path): HintsRules {
                val rules = ImmutableList.builder<Rule?>()
                hintsFile.getInputStream().use { `is` ->
                    for (line in CharStreams.readLines(InputStreamReader(`is`, StandardCharsets.UTF_8))) {
                        var line = line
                        line = line.trim()
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue
                        }
                        val tokens = WS_PAT.split(line)
                        try {
                            if (tokens.length == 3) {
                                rules.add(IncludeParser.Rule(tokens[0]!!, tokens[1], tokens[2]!!))
                            } else if (tokens.length == 4) {
                                if (!ALLOWED_PATTERN.matcher(tokens[1]).matches()) {
                                    throw IOException(
                                        ("Illegal hint regex on: "
                                                + line
                                                + "\n"
                                                + tokens[1]
                                                + " does not match only paths in "
                                                + ALLOWED_PREFIX)
                                    )
                                }
                                rules.add(IncludeParser.Rule(tokens[0]!!, tokens[1], tokens[2]!!, tokens[3]))
                            } else {
                                throw IOException("Malformed hint line: " + line)
                            }
                        } catch (e: PatternSyntaxException) {
                            throw IOException("Malformed hint regex on: " + line + "\n  " + e.getMessage())
                        } catch (e: IllegalArgumentException) {
                            throw IOException("Invalid type on: " + line + "\n  " + e.getMessage())
                        }
                    }
                }
                return HintsRules(rules.build())
            }
        }
    }

    /**
     * An immutable inclusion tuple. This models an `#include` or `#include_next` line in
     * a file without the context how this file got included.
     */
    class Inclusion private constructor(
        pathFragment: PathFragment?,
        /** The kind of inclusion.  */
        val kind: Kind
    ) {
        /** The format of the #include in the source file -- quoted, angle bracket, etc.  */
        internal enum class Kind {
            /** Quote includes: `#include "name"`.  */
            QUOTE,

            /** Angle bracket includes: `#include <name>`.  */
            ANGLE,

            /** Quote next includes: `#include_next "name"`.  */
            NEXT_QUOTE,

            /** Angle next includes: `#include_next <name>`.  */
            NEXT_ANGLE;

            val isNext: Boolean
                /** Returns true if this is an `#include_next` inclusion,  */
                get() = this == Kind.NEXT_ANGLE || this == Kind.NEXT_QUOTE
        }

        /** The relative path of the inclusion.  */
        val pathFragment: PathFragment

        init {
            this.pathFragment = Preconditions.checkNotNull<PathFragment>(pathFragment)
        }

        val pathString: String?
            get() = pathFragment.getPathString()

        override fun toString(): String {
            return kind.toString() + ":" + pathFragment.getPathString()
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o !is Inclusion) {
                return false
            }
            return kind == o.kind && pathFragment == o.pathFragment
        }

        override fun hashCode(): Int {
            return pathFragment.hashCode() * 37 + kind.hashCode()
        }

        companion object {
            private val INCLUSIONS: Interner<Inclusion> = BlazeInterners.newWeakInterner<Inclusion?>()

            fun create(includeTarget: String?, kind: Kind): Inclusion {
                return INCLUSIONS.intern(Inclusion(PathFragment.create(includeTarget), kind))
            }

            fun create(pathFragment: PathFragment?, kind: Kind): Inclusion {
                return INCLUSIONS.intern(Inclusion(Preconditions.checkNotNull<PathFragment?>(pathFragment), kind))
            }
        }
    }

    @VisibleForTesting
    fun extractInclusion(line: String): Inclusion? {
        return extractInclusion(line.getBytes(StandardCharsets.ISO_8859_1), 0, line.length())
    }

    /**
     * Extracts a new, unresolved an Inclusion from a line of source.
     * 
     * @param chars the char array containing the line chars to parse
     * @param lineBegin the position of the first character in the line
     * @param lineEnd the position of the character after the last
     * @return the inclusion object if possible, null if none
     */
    private fun extractInclusion(chars: ByteArray, lineBegin: Int, lineEnd: Int): Inclusion? {
        // expect WS#WS(include|include_next|__has_include\(_next\)?)WS\(?("name"|<name>|<name>)\)?
        val data = expectIncludeKeyword(chars, lineBegin, lineEnd)
        var pos = data.pos
        if (pos == -1 || pos == lineEnd) {
            return null
        }
        var isNext = false
        if (data.canHaveNext) {
            val npos: Int = expect(chars, pos, lineEnd, "_next")
            if (npos >= 0) {
                isNext = true
                pos = npos
            }
        }
        if ((skipWhitespace(chars, pos, lineEnd).also { pos = it }) == lineEnd) {
            return null
        }
        if (data.hasParens) {
            if (chars[pos] != '('.code.toByte()) {
                return null
            }
            pos++
            if ((skipWhitespace(chars, pos, lineEnd).also { pos = it }) == lineEnd) {
                return null
            }
        }
        if (chars[pos] == '"'.code.toByte() || chars[pos] == '<'.code.toByte()) {
            val qchar = (chars[pos++].toInt() and 0xff).toChar()
            val spos = pos
            pos = indexOf(chars, pos + 1, lineEnd, if (qchar == '<') '>' else '"')
            if (pos < 0) {
                return null
            }
            if (chars[spos] == '/'.code.toByte()) {
                return null // disallow absolute paths
            }
            var name: String? = String(chars, spos, pos - spos)
            if (name.contains("\n")) { // strip any \+NL pairs within name
                name = BS_NL_PAT.matcher(name).replaceAll("")
            }
            if (isNext) {
                return Inclusion.Companion.create(
                    name,
                    if (qchar == '"') Inclusion.Kind.NEXT_QUOTE else Inclusion.Kind.NEXT_ANGLE
                )
            } else {
                return Inclusion.Companion.create(
                    name,
                    if (qchar == '"') Inclusion.Kind.QUOTE else Inclusion.Kind.ANGLE
                )
            }
        } else {
            return createOtherInclusion(String(chars, pos, lineEnd - pos))
        }
    }

    /**
     * Extracts all inclusions from characters of a file.
     * 
     * @param chars the file contents to parse & extract inclusions from
     * @return a new set of inclusions, normalized to the cache
     */
    @VisibleForTesting
    fun extractInclusions(chars: ByteArray): MutableList<Inclusion?> {
        val inclusions: MutableList<Inclusion?> = ArrayList<Inclusion?>()
        var lineBegin = 0 // the first char of each line
        val end: Int = chars.length // the file end
        while (lineBegin < end) {
            var lineEnd = lineBegin // the char after the last non-\n in each line
            // skip to the next \n or after end of buffer, ignoring continuations
            while (lineEnd < end) {
                if (chars[lineEnd] == '\n'.code.toByte()) {
                    break
                } else if (chars[lineEnd] == '\\'.code.toByte()) {
                    lineEnd++
                    if (chars[lineEnd] == '\n'.code.toByte()) {
                        lineEnd++
                    }
                } else {
                    lineEnd++
                }
            }

            // TODO(bazel-team) handle multiline block comments /* */ for the cases:
            //   /* blah blah blah
            //    lalala  */ #include "foo.h"
            // and:
            //   /* blah
            //   #include "foo.h"
            //   */

            // extract the inclusion, and save only the kind we care about.
            val inclusion = extractInclusion(chars, lineBegin, lineEnd)
            if (inclusion != null) {
                if (isValidInclusionKind(inclusion.kind)) {
                    inclusions.add(inclusion)
                }
            }
            lineBegin = lineEnd + 1 // next line starts after the previous line
        }
        return inclusions
    }

    /**
     * Extracts all inclusions from a given source file.
     * 
     * @param file the file to parse & extract inclusions from
     * @param actionExecutionContext Services in the scope of the action, like the stream to which
     * scanning messages are printed
     * @return a new set of inclusions, normalized to the cache
     */
    @Throws(IOException::class, ExecException::class, InterruptedException::class)
    fun extractInclusions(
        file: Artifact,
        actionExecutionMetadata: ActionExecutionMetadata?,
        actionExecutionContext: ActionExecutionContext,
        grepIncludes: Artifact?,
        grepIncludesExecutionPlatform: PlatformInfo?,
        remoteIncludeScanner: SpawnIncludeScanner?,
        isOutputFile: Boolean
    ): MutableCollection<Inclusion?> {
        var inclusions: MutableCollection<Inclusion?>
        if (remoteIncludeScanner != null && grepIncludes != null && remoteIncludeScanner.shouldParseRemotely(file)) {
            inclusions =
                remoteIncludeScanner.extractInclusions(
                    file,
                    actionExecutionMetadata,
                    actionExecutionContext,
                    grepIncludes,
                    grepIncludesExecutionPlatform,
                    this.fileType,
                    isOutputFile
                )
        } else {
            if (isOutputFile && !actionExecutionContext.fileSystemSupportsInputDiscovery()) {
                // Ensure that the file's metadata is available, which possibly requires a Skyframe restart.
                val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    actionExecutionContext.getInputMetadataProvider().getInputMetadataChecked(file)
            }
            try {
                Profiler.instance().profile(ProfilerTask.SCANNER, file.getExecPathString()).use { c ->
                    inclusions =
                        extractInclusions(
                            FileSystemUtils.readContent(actionExecutionContext.getInputPath(file))
                        )
                }
            } catch (e: IOException) {
                if (remoteIncludeScanner != null && grepIncludes != null) {
                    logger.atWarning().atMostEvery(1, TimeUnit.SECONDS).log(
                        "Falling back on remote parsing of %s (cause %s)",
                        actionExecutionContext.getInputPath(file), e.getMessage()
                    )
                    inclusions =
                        remoteIncludeScanner.extractInclusions(
                            file,
                            actionExecutionMetadata,
                            actionExecutionContext,
                            grepIncludes,
                            grepIncludesExecutionPlatform,
                            this.fileType,
                            isOutputFile
                        )
                } else {
                    throw e
                }
            }
        }
        if (hints != null) {
            inclusions.addAll(hints.getHintedInclusions(file))
        }
        return ImmutableList.copyOf<Inclusion?>(inclusions)
    }

    protected open val fileType: GrepIncludesFileType?
        /**
         * Returns type of the scanned file.
         * 
         * 
         * Supported values are "c++" for standard c/c++ headers and sources, and "swig" for .swig
         * files. Changes to this method must be synchronized with change to //tools/cpp:grep-includes.
         */
        get() = GrepIncludesFileType.CPP

    /**
     * Position of found include together with information about how to process the remaining include
     * line further.
     */
    protected class IncludesKeywordData private constructor(
        private val pos: Int,
        private val canHaveNext: Boolean,
        private val hasParens: Boolean
    ) {
        companion object {
            val NONE: IncludesKeywordData = IncludesKeywordData(-1, false, false)
            fun normal(pos: Int): IncludesKeywordData {
                return IncludesKeywordData(pos, true, false)
            }

            fun importOrSwig(pos: Int): IncludesKeywordData {
                return IncludesKeywordData(pos, false, false)
            }

            fun hasInclude(pos: Int): IncludesKeywordData {
                return IncludesKeywordData(pos, true, true)
            }
        }
    }

    /**
     * Parses include keyword in the provided char array and returns position immediately after
     * include keyword or -1 if keyword was not found, along with information to aid future parsing.
     * Can be overridden by subclasses.
     */
    protected open fun expectIncludeKeyword(chars: ByteArray, position: Int, end: Int): IncludesKeywordData {
        var pos: Int = expect(chars, skipWhitespace(chars, position, end), end, "#")
        if (pos > 0) {
            val npos: Int = skipWhitespace(chars, pos, end)
            if ((expect(chars, npos, end, "include").also { pos = it }) > 0) {
                return IncludesKeywordData.Companion.normal(pos)
            } else if ((expect(chars, npos, end, "import").also { pos = it }) > 0) {
                return IncludesKeywordData.Companion.importOrSwig(pos)
            } else if ((skipThroughHasInclude(chars, npos, end).also { pos = it }) > 0) {
                return IncludesKeywordData.Companion.hasInclude(pos)
            }
        }
        return IncludesKeywordData.Companion.NONE
    }

    /**
     * Returns true if we interested in the given inclusion kind. Can be overridden by the subclass.
     */
    protected open fun isValidInclusionKind(kind: Inclusion.Kind?): Boolean {
        return true
    }

    /**
     * Returns inclusion object for non-standard inclusion cases or null if inclusion should be
     * ignored.
     */
    protected open fun createOtherInclusion(inclusionContent: String?): Inclusion? {
        return null
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Skips whitespace, \+NL pairs, and block-style / * * / comments. Assumes line comments are
         * handled outside. Does not handle digraphs, trigraphs or decahexagraphs.
         * 
         * @param chars characters to scan
         * @param pos the starting position
         * @return the resulting position after skipping whitespace and comments.
         */
        fun skipWhitespace(chars: ByteArray, pos: Int, end: Int): Int {
            var pos = pos
            while (pos < end) {
                if (Character.isWhitespace(chars[pos].toInt() and 0xff)) {
                    pos++
                } else if (chars[pos] == '\\'.code.toByte() && pos + 1 < end && chars[pos + 1] == '\n'.code.toByte()) {
                    pos++
                } else if (chars[pos] == '/'.code.toByte() && pos + 1 < end && chars[pos + 1] == '*'.code.toByte()) {
                    pos += 2
                    while (pos < end - 1) {
                        if (chars[pos++] == '*'.code.toByte()) {
                            if (chars[pos] == '/'.code.toByte()) {
                                pos++
                                break // proper comment end
                            }
                        }
                    }
                } else { // not whitespace
                    return pos
                }
            }
            return pos // pos == len, meaning we fell off the end.
        }

        private const val HAS_INCLUDE = "__has_include"
        private val HAS_INCLUDE_LENGTH: Int = HAS_INCLUDE.length()
        private val NECESSARY_HAS_INCLUDE_LENGTH: Int = HAS_INCLUDE_LENGTH + 5

        /**
         * Returns the index of `chars` after the first occurrence of "__has_include" or -1 if no
         * such occurrence exists. Also requires that there be at least 5 characters after the
         * "__has_include", corresponding to a pair of parentheses and angle brackets/quotes and a
         * filename.
         * 
         * 
         * This code runs on every line that starts with " *# *", so it should be as fast as possible.
         */
        private fun skipThroughHasInclude(chars: ByteArray, pos: Int, end: Int): Int {
            var pos = pos
            val lastPos: Int = end - NECESSARY_HAS_INCLUDE_LENGTH
            while (pos <= lastPos) {
                var curPos = 0
                while (curPos < HAS_INCLUDE_LENGTH
                    && (chars[pos + curPos].toInt() and 0xff) == HAS_INCLUDE.charAt(curPos).code
                ) {
                    curPos++
                }
                if (curPos == HAS_INCLUDE_LENGTH) {
                    return pos + curPos
                }
                // We're looking for "__has_include" as a preprocessing token, which means that it cannot
                // start in the middle of any characters we've already processed, nor at the mismatching
                // character.
                pos += curPos + 1
            }
            return -1
        }

        /**
         * Checks for and skips a given token.
         * 
         * @param chars characters to scan
         * @param pos the starting position
         * @param expected the expected token
         * @return the resulting position if found, otherwise -1
         */
        protected fun expect(chars: ByteArray, pos: Int, end: Int, expected: String): Int {
            var pos = pos
            var si = 0
            val expectedLen: Int = expected.length()
            while (pos < end) {
                if (si == expectedLen) {
                    return pos
                }
                if ((chars[pos++].toInt() and 0xff) != expected.charAt(si++).code) {
                    return -1
                }
            }
            return -1
        }

        /**
         * Finds the index of a given character token from a starting pos.
         * 
         * @param chars characters to scan
         * @param pos the starting position
         * @param echar the character to find
         * @return the resulting position of echar if found, otherwise -1
         */
        private fun indexOf(chars: ByteArray, pos: Int, end: Int, echar: Char): Int {
            var pos = pos
            while (pos < end) {
                if (chars[pos] == echar.code.toByte()) {
                    return pos
                }
                pos++
            }
            return -1
        }

        private val BS_NL_PAT: Pattern = Pattern.compile("\\\\" + "\n")

        // Keep this in sync with the grep-includes binary's scanning output format.
        private val KIND_MAP: ImmutableMap<Char?, Inclusion.Kind?> = ImmutableMap.of<Char?, Inclusion.Kind?>(
            '"', Inclusion.Kind.QUOTE,
            '<', Inclusion.Kind.ANGLE,
            'q', Inclusion.Kind.NEXT_QUOTE,
            'a', Inclusion.Kind.NEXT_ANGLE
        )

        /**
         * Processes the output generated by an auxiliary include-scanning binary.
         * 
         * 
         * If a source file has the following include statements:
         * 
         * <pre>
         * #include &lt;string&gt;
         * #include "directory/header.h"
        </pre> * 
         * 
         * 
         * Then the output file has the following contents:
         * 
         * <pre>
         * "directory/header.h
         * &lt;string
        </pre> * 
         * 
         * 
         * Each line of the output is translated into an Inclusion object.
         */
        @Throws(IOException::class)
        private fun processIncludes(lines: MutableList<String>): MutableList<Inclusion?> {
            val inclusions: MutableList<Inclusion?> = ArrayList<Inclusion?>()
            for (line in lines) {
                if (line.isEmpty()) {
                    continue
                }
                val qchar: Char = line.charAt(0)
                val name: String = line.substring(1)
                val kind: Inclusion.Kind? = KIND_MAP.get(qchar)
                if (kind == null) {
                    throw IOException("Illegal inclusion kind '" + qchar + "'")
                }
                inclusions.add(Inclusion.Companion.create(name, kind))
            }
            return inclusions
        }

        /** Processes the output generated by an auxiliary include-scanning binary stored in a file.  */
        @Throws(IOException::class)
        fun processIncludes(file: Path?): MutableList<Inclusion?> {
            try {
                val data = FileSystemUtils.readContent(file)
                return processIncludes(Arrays.asList<String?>(*String(data, StandardCharsets.ISO_8859_1).split("\n")))
            } catch (e: IOException) {
                throw IOException("Error reading include file " + file + ": " + e.getMessage())
            }
        }

        /**
         * Processes the output generated by an auxiliary include-scanning binary read from a stream.
         * Closes the stream upon completion.
         */
        @Throws(IOException::class)
        fun processIncludes(streamName: Any?, `is`: InputStream): MutableList<Inclusion?> {
            try {
                InputStreamReader(`is`, StandardCharsets.ISO_8859_1).use { reader ->
                    return processIncludes(CharStreams.readLines(reader))
                }
            } catch (e: IOException) {
                throw IOException("Error reading include file " + streamName + ": " + e.getMessage())
            }
        }
    }
}
