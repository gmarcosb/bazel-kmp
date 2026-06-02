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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.common.base.Strings
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.analysis.BlazeDirectories
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.util.StringUtilities
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import net.starlark.java.annot.Param
import net.starlark.java.annot.ParamType
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.*
import java.nio.charset.StandardCharsets
import java.util.function.Predicate

/** Starlark API for the repository_rule's context.  */
@StarlarkBuiltin(
    name = "repository_ctx", category = DocCategory.BUILTIN, doc = """
        The context of the repository rule containing helper functions and information about attributes. You get a repository_ctx object as an argument to the <code>implementation</code> function when you create a repository rule.
        
        """.trimIndent()
)
class StarlarkRepositoryContext(
    repoDefinition: RepoDefinition,
    packageLocator: PathPackageLocator,
    outputDirectory: Path?,
    ignoredSubdirectories: IgnoredSubdirectories,
    environment: SkyFunction.Environment?,
    repoEnv: ImmutableMap<String?, String?>?,
    nonstrictRepoEnv: ImmutableMap<String?, String?>?,
    downloadManager: DownloadManager?,
    timeoutScaling: Double,
    processWrapper: ProcessWrapper?,
    starlarkSemantics: StarlarkSemantics?,
    remoteExecutor: RepositoryRemoteExecutor?,
    syscallCache: SyscallCache?,
    directories: BlazeDirectories?
) : StarlarkBaseExternalContext(
    outputDirectory,
    directories,
    environment,
    repoEnv,
    nonstrictRepoEnv,
    downloadManager,
    timeoutScaling,
    processWrapper,
    starlarkSemantics,
    RepositoryFetchProgress.repositoryFetchContextString(
        RepositoryName.Companion.createUnvalidated(repoDefinition.name)
    ),
    remoteExecutor,  /* allowWatchingPathsOutsideWorkspace= */
    true
) {
    private val repoDefinition: RepoDefinition
    private val packageLocator: PathPackageLocator
    private val ignoredSubdirectories: IgnoredSubdirectories
    private val syscallCache: SyscallCache?

    /**
     * Create a new context (repository_ctx) object for a Starlark repository rule (`rule`
     * argument).
     */
    init {
        this.repoDefinition = repoDefinition
        this.packageLocator = packageLocator
        this.ignoredSubdirectories = ignoredSubdirectories
        this.syscallCache = syscallCache
    }

    override fun shouldDeleteWorkingDirectoryOnClose(successful: Boolean): Boolean {
        return !successful
    }

    @get:StarlarkMethod(
        name = "name",
        structField = true,
        doc = ("The canonical name of the external repository created by this rule. This name is"
                + " guaranteed to be unique among all external repositories, but its exact format is"
                + " not specified. Use <a href='#original_name'><code>original_name</code></a>"
                + " instead to get the name that was originally specified as the <code>name</code>"
                + " when this repository rule was instantiated.")
    )
    val name: String
        get() = repoDefinition.name

    @get:StarlarkMethod(
        name = "original_name",
        structField = true,
        doc = ("The name that was originally specified as the <code>name</code> attribute when this"
                + " repository rule was instantiated. This name is not necessarily unique among"
                + " external repositories. Use <a href='#name'><code>name</code></a> instead to get"
                + " the canonical name of the external repository.")
    )
    val originalName: String?
        get() =// The original name isn't set for repositories backing Bazel modules, in which case the
        // original name doesn't matter as the restricted set of rules that can back
            // Bazel modules do not use the name.
            if (Strings.isNullOrEmpty(repoDefinition.originalName))
                repoDefinition.name
            else
                repoDefinition.originalName

    @get:StarlarkMethod(
        name = "workspace_root",
        structField = true,
        doc = "The path to the root workspace of the bazel invocation."
    )
    val workspaceRoot: StarlarkPath
        get() = StarlarkPath(this, directories.getWorkspace())

    @get:StarlarkMethod(
        name = "attr", structField = true, doc = """
          A struct to access the values of the attributes. The values are provided by the user (if not, a default value is used).
          
          """.trimIndent()
    )
    val attr: Structure
        get() = repoDefinition

    @Throws(EvalException::class, InterruptedException::class)
    private fun externalPath(method: String?, pathObject: Any): StarlarkPath {
        val starlarkPath = getPath(pathObject)
        val path = starlarkPath.getPath()
        if (packageLocator.getPathEntries().stream()
                .noneMatch(Predicate { root: Root? -> path.startsWith(root.asPath()) })
            || path.startsWith(workingDirectory)
        ) {
            return starlarkPath
        }
        val workspaceRoot = packageLocator.getWorkspaceFile(syscallCache).getParentDirectory()
        val relativePath: PathFragment? = path.relativeTo(workspaceRoot)
        if (ignoredSubdirectories.matchingEntry(relativePath) != null) {
            return starlarkPath
        }
        throw Starlark.errorf(
            "%s can only be applied to external paths (that is, outside the workspace or ignored in"
                    + " .bazelignore)",
            method
        )
    }

    // This method must not be moved to ModuleExtensionContext as the FS-specific watch in its
    // implementation would cause lock files to differ across platforms. If this is ever needed, add
    // a `copy` method instead.
    @StarlarkMethod(
        name = "symlink",
        doc = "Creates a symlink on the filesystem.",
        useStarlarkThread = true,
        parameters = [Param(
            name = "target",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "The path that the symlink should point to."
        ), Param(
            name = "link_name",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "The path of the symlink to create."
        )]
    )
    @Throws(RepositoryFunctionException::class, EvalException::class, InterruptedException::class)
    fun symlink(target: Any, linkName: Any, thread: StarlarkThread) {
        val targetPath = getPath(target)
        val linkPath = getPath(linkName)
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newSymlinkEvent(
                targetPath.toString(),
                linkPath.toString(),
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)
        try {
            checkInOutputDirectory("write", linkPath)
            StarlarkBaseExternalContext.Companion.makeDirectories(linkPath.getPath())
            linkPath.getPath().createSymbolicLink(targetPath.getPath())
            if (!linkPath
                    .getPath()
                    .getFileSystem()
                    .supportsSymbolicLinksNatively(linkPath.getPath().asFragment())
            ) {
                // The symlink may be emulated as a copy, which would need to be tracked for invalidation.
                maybeWatch(targetPath, ShouldWatch.AUTO)
            }
        } catch (e: IOException) {
            throw RepositoryFunctionException(
                IOException(
                    ("Could not create symlink from "
                            + targetPath
                            + " to "
                            + linkPath
                            + ": "
                            + e.getMessage()),
                    e
                ),
                Transience.TRANSIENT
            )
        } catch (e: InvalidPathException) {
            throw RepositoryFunctionException(
                Starlark.errorf("Could not create %s: %s", linkPath, e.getMessage()),
                Transience.PERSISTENT
            )
        }
    }

    @StarlarkMethod(
        name = "template",
        doc = """
          Generates a new file using a <code>template</code>. Every occurrence in <code>template</code> of a key of <code>substitutions</code> will be replaced by the corresponding value. The result is written in <code>path</code>. An optional <code>executable</code> argument (default to true) can be set to turn on or off the executable bit.
          
          """.trimIndent(),
        useStarlarkThread = true,
        parameters = [Param(
            name = "path",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "Path of the file to create, relative to the repository directory."
        ), Param(
            name = "template",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "Path to the template file."
        ), Param(
            name = "substitutions",
            defaultValue = "{}",
            named = true,
            doc = "Substitutions to make when expanding the template."
        ), Param(
            name = "executable",
            defaultValue = "True",
            named = true,
            doc = "Set the executable flag on the created file, true by default."
        ), Param(
            name = "watch_template", defaultValue = "'auto'", positional = false, named = true, doc = """
                Whether to <a href="#watch">watch</a> the template file. Can be the string 'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking the <a href="#watch"><code>watch()</code></a> method; passing 'no' does not attempt to watch the file; passing 'auto' will only attempt to watch the file when it is legal to do so (see <code>watch()</code> docs for more information.
                
                """.trimIndent()
        )]
    )
    @Throws(RepositoryFunctionException::class, EvalException::class, InterruptedException::class)
    fun createFileFromTemplate(
        path: Any,
        template: Any,
        substitutions: Dict<*, *>?,  // <String, String> expected
        executable: Boolean,
        watchTemplate: String,
        thread: StarlarkThread
    ) {
        val p = getPath(path)
        val t = getPath(template)
        val substitutionMap: MutableMap<String?, String?> =
            Dict.cast<String?, String?>(substitutions, String::class.java, String::class.java, "substitutions")
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newTemplateEvent(
                p.toString(),
                t.toString(),
                substitutionMap,
                executable,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)
        if (t.isDir()) {
            throw Starlark.errorf("attempting to use a directory as template: %s", t)
        }
        maybeWatch(t, ShouldWatch.Companion.fromString(watchTemplate))
        try {
            checkInOutputDirectory("write", p)
            StarlarkBaseExternalContext.Companion.makeDirectories(p.getPath())
            // Read and write files as raw bytes by using the Latin-1 encoding, which matches the encoding
            // used by Bazel for strings.
            var tpl = FileSystemUtils.readContent(t.getPath(), StandardCharsets.ISO_8859_1)
            for (substitution in substitutionMap.entrySet()) {
                tpl =
                    StringUtilities.replaceAllLiteral(tpl, substitution.getKey(), substitution.getValue())
            }
            p.getPath().delete()
            p.getPath().getOutputStream().use { stream ->
                stream.write(tpl.getBytes(StandardCharsets.ISO_8859_1))
            }
            if (executable) {
                p.getPath().setExecutable(true)
            }
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        } catch (e: InvalidPathException) {
            throw RepositoryFunctionException(
                Starlark.errorf("Could not create %s: %s", p, e.getMessage()), Transience.PERSISTENT
            )
        }
    }

    override fun isRemotable(): Boolean {
        return repoDefinition.repoRule.remotable
    }

    @Throws(EvalException::class)
    override fun getRemoteExecProperties(): ImmutableMap<String?, String?> {
        return ImmutableMap.copyOf<String?, String?>(
            Dict.cast<String?, String?>(
                this.attr.getValue("exec_properties"), String::class.java, String::class.java, "exec_properties"
            )
        )
    }

    @StarlarkMethod(
        name = "delete", doc = """
          Deletes a file or a directory. Returns a bool, indicating whether the file or directory was actually deleted by this call.
          
          """.trimIndent(), useStarlarkThread = true, parameters = [Param(
            name = "path",
            allowedTypes = [ParamType(type = String::class), ParamType(type = StarlarkPath::class)],
            doc = """
                Path of the file to delete, relative to the repository directory, or absolute. Can be a path or a string.
                
                """.trimIndent()
        )]
    )
    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    fun delete(pathObject: Any, thread: StarlarkThread): Boolean {
        val starlarkPath = externalPath("delete()", pathObject)
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newDeleteEvent(
                starlarkPath.toString(), identifyingStringForLogging, thread.getCallerLocation()
            )
        env.getListener().post(w)
        try {
            val path = starlarkPath.getPath()
            path.deleteTreesBelow()
            return path.delete()
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @StarlarkMethod(
        name = "rename", doc = """
          Renames the file or directory from <code>src</code> to <code>dst</code>. Parent directories are created as needed. Fails if the destination path
          already exists. Both paths must be located within the repository.
          
          """.trimIndent(), useStarlarkThread = true, parameters = [Param(
            name = "src",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = """
                The path of the existing file or directory to rename, relative
                to the repository directory.
                
                """.trimIndent()
        ), Param(
            name = "dst",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = """
                The new name to which the file or directory will be renamed to,
                relative to the repository directory.
                
                """.trimIndent()
        )]
    )
    @Throws(RepositoryFunctionException::class, EvalException::class, InterruptedException::class)
    fun rename(srcName: Any, dstName: Any, thread: StarlarkThread) {
        val srcPath = getPath(srcName)
        val dstPath = getPath(dstName)
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newRenameEvent(
                srcPath.toString(),
                dstPath.toString(),
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)
        try {
            checkInOutputDirectory("write", srcPath)
            checkInOutputDirectory("write", dstPath)
            if (dstPath.exists()) {
                throw RepositoryFunctionException(
                    IOException("Could not rename " + srcPath + " to " + dstPath + ": already exists"),
                    Transience.TRANSIENT
                )
            }
            StarlarkBaseExternalContext.Companion.makeDirectories(dstPath.getPath())
            srcPath.getPath().renameTo(dstPath.getPath())
        } catch (e: IOException) {
            throw RepositoryFunctionException(
                IOException(
                    "Could not rename " + srcPath + " to " + dstPath + ": " + e.getMessage(), e
                ),
                Transience.TRANSIENT
            )
        } catch (e: InvalidPathException) {
            throw RepositoryFunctionException(
                Starlark.errorf("Could not rename %s to %s: %s", srcPath, dstPath, e.getMessage()),
                Transience.PERSISTENT
            )
        }
    }

    @StarlarkMethod(
        name = "patch", doc = """
          Apply a patch file to the root directory of external repository. The patch file should be a standard <a href="https://en.wikipedia.org/wiki/Diff#Unified_format"> unified diff format</a> file. The Bazel-native patch implementation doesn't support binary patch like the patch command line tool.
          
          """.trimIndent(), useStarlarkThread = true, parameters = [Param(
            name = "patch_file",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = """
                The patch file to apply, it can be label, relative path or absolute path. If it's a relative path, it will resolve to the repository directory.
                
                """.trimIndent()
        ), Param(
            name = "strip",
            named = true,
            defaultValue = "0",
            doc = "Strip the specified number of leading components from file names."
        ), Param(
            name = "watch_patch", defaultValue = "'auto'", positional = false, named = true, doc = """
                Whether to <a href="#watch">watch</a> the patch file. Can be the string 'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking the <a href="#watch"><code>watch()</code></a> method; passing 'no' does not attempt to watch the file; passing 'auto' will only attempt to watch the file when it is legal to do so (see <code>watch()</code> docs for more information.
                
                """.trimIndent()
        )]
    )
    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    fun patch(patchFile: Any, stripI: StarlarkInt?, watchPatch: String, thread: StarlarkThread) {
        val strip = Starlark.toInt(stripI, "strip")
        val starlarkPath = getPath(patchFile)
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newPatchEvent(
                starlarkPath.toString(),
                strip,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)
        if (starlarkPath.isDir()) {
            throw Starlark.errorf("attempting to use a directory as patch file: %s", starlarkPath)
        }
        maybeWatch(starlarkPath, ShouldWatch.Companion.fromString(watchPatch))
        try {
            PatchUtil.apply(starlarkPath.getPath(), strip, workingDirectory)
        } catch (e: PatchFailedException) {
            throw RepositoryFunctionException(
                Starlark.errorf("Error applying patch %s: %s", starlarkPath, e.getMessage()),
                Transience.TRANSIENT
            )
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @StarlarkMethod(
        name = "watch_tree",
        doc = """
          Tells Bazel to watch for changes to any files or directories transitively under the given path. Any changes to the contents of files, the existence of files or directories, file names or directory names, will cause this repo to be refetched.<p>Note that attempting to watch paths inside the repo currently being fetched will result in an error.
          
          """.trimIndent(),
        parameters = [Param(
            name = "path",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "Path of the directory tree to watch."
        ), Param(
            name = "exclude",
            positional = false,
            allowedTypes = [ParamType(type = Sequence::class, generic1 = String::class)],
            defaultValue = "[]",
            named = true,
            doc = """
                Glob patterns to exclude from watching. The patterns provided here will be joined with <code>path</code> to create the full glob pattern that will be matched against.  Eg. if <code>path</code> was <code>/example/path</code> and <code>exclude</code> was <code>[".ignore/**", "scratchFile"]</code>, then the glob pattern that would be excluded would be: <code>/example/path/.ignore/**</code> and <code>/example/patch/scratchFile</code>.
                
                """.trimIndent()
        )]
    )
    @Throws(EvalException::class, InterruptedException::class, RepositoryFunctionException::class)
    fun watchTree(path: Any, exclude: Sequence<*>?) {
        val p = getPath(path)
        if (!p.isDir()) {
            throw Starlark.errorf("can't call watch_tree() on non-directory %s", p)
        }
        val excludes =
            Sequence.cast<String?>(exclude, String::class.java, "excludes").getImmutableList()
        val repoCacheFriendlyPath: RepoCacheFriendlyPath? =
            toRepoCacheFriendlyPath(p.getPath(), ShouldWatch.YES)
        if (repoCacheFriendlyPath == null) {
            return
        }
        try {
            getValueAndRecordInput(DirTree(repoCacheFriendlyPath, excludes))
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @StarlarkMethod(
        name = "repo_metadata", doc = """
          Constructs an opaque object that can be returned from the repo rule's implementation function to provide metadata about its reproducibility.
          
          """.trimIndent(), parameters = [Param(
            name = "reproducible", defaultValue = "False", doc = """
                States that this repo can be reproducibly refetched; that is, if it were fetched another time with exactly the same input attributes, repo rule definition, watched files and environment variables, etc., then exactly the same output would be produced. This property needs to hold even if other untracked conditions change, such as information from the internet, the path of the workspace root, output from running arbitrary executables, etc. If set to True, this allows the fetched repo contents to be cached across workspaces. <p>Note that setting this to True does not guarantee caching in the repo contents cache; for example, local repo rules are never cached.
                
                """.trimIndent(), positional = false, named = true
        ), Param(
            name = "attrs_for_reproducibility", defaultValue = "{}", doc = """
                If <code>reproducible</code> is False, this can be specified to tell Bazel which attributes of the original repo rule to change to make it reproducible.
                
                """.trimIndent(), positional = false, named = true
        )]
    )
    @Throws(EvalException::class)
    fun repoMetadata(reproducible: Boolean, attrs: Dict<*, *>): RepoMetadata {
        if (reproducible && !attrs.isEmpty()) {
            throw Starlark.errorf(
                "attrs_for_reproducibility can only be specified if reproducible is False"
            )
        }
        return RepoMetadata(
            if (reproducible) Reproducibility.YES else Reproducibility.NO,
            Dict.cast<String?, Any?>(attrs, String::class.java, Any::class.java, "attrs_for_reproducibility")
        )
    }

    override fun toString(): String {
        return "repository_ctx[" + repoDefinition.name + "]"
    }
}
