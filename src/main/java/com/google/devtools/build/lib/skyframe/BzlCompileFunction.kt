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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileValue

/**
 * A Skyframe function that compiles the .bzl file denoted by a Label.
 * 
 * 
 * Given a [Label] referencing a Starlark file, BzlCompileFunction loads, parses, resolves,
 * and compiles it. The Label must be absolute, and must not reference the special `external`
 * package. If the file (or the package containing it) doesn't exist, the function doesn't fail, but
 * instead returns a specific `NO_FILE` [BzlCompileValue].
 */
// TODO(adonovan): actually compile. The name is a step ahead of the implementation.
class BzlCompileFunction(
    bazelStarlarkEnvironment: BazelStarlarkEnvironment,
    hashFunction: com.google.common.hash.HashFunction,
    packageLoadingListener: PackageLoadingListener
) : SkyFunction {
    private val bazelStarlarkEnvironment: BazelStarlarkEnvironment
    private val hashFunction: com.google.common.hash.HashFunction
    private val packageLoadingListener: PackageLoadingListener

    init {
        this.bazelStarlarkEnvironment = bazelStarlarkEnvironment
        this.hashFunction = hashFunction
        this.packageLoadingListener = packageLoadingListener
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        try {
            return computeInline(
                skyKey.argument() as com.google.devtools.build.lib.skyframe.BzlCompileValue.Key?,
                env,
                bazelStarlarkEnvironment,
                hashFunction,
                packageLoadingListener
            )
        } catch (e: FailedIOException) {
            throw FunctionException(e)
        }
    }

    internal class FailedIOException private constructor(cause: IOException, transience: Transience?) :
        java.lang.Exception(cause.getMessage(), cause) {
        private val transience: Transience?

        init {
            this.transience = transience
        }

        fun getTransience(): Transience? {
            return transience
        }
    }

    private class FunctionException(cause: FailedIOException) : SkyFunctionException(cause, cause.transience)
    companion object {
        @Throws(FailedIOException::class, java.lang.InterruptedException::class)
        fun computeInline(
            key: com.google.devtools.build.lib.skyframe.BzlCompileValue.Key,
            env: SkyFunction.Environment,
            bazelStarlarkEnvironment: BazelStarlarkEnvironment,
            hashFunction: com.google.common.hash.HashFunction,
            packageLoadingListener: PackageLoadingListener
        ): BzlCompileValue? {
            val bytes: ByteArray?
            var digest: ByteArray?
            val inputName: String?
            var rootedPath: RootedPath? = null

            if (key.kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.EMPTY_PRELUDE) {
                // Default prelude is empty.
                bytes = byteArrayOf()
                digest = null
                inputName = "<default prelude>"
            } else {
                // Obtain the file.
                rootedPath = RootedPath.toRootedPath(key.root, key.label.toPathFragment())
                val fileSkyKey: SkyKey? = FileValue.key(rootedPath)
                var fileValue: FileValue? = null
                try {
                    fileValue = env.getValueOrThrow<IOException?>(fileSkyKey, IOException::class.java) as FileValue?
                } catch (e: IOException) {
                    throw FailedIOException(e, Transience.PERSISTENT)
                }
                if (fileValue == null) {
                    return null
                }

                if (fileValue.exists()) {
                    if (!fileValue.isFile()) {
                        return if (fileValue.isDirectory())
                            BzlCompileValue.Companion.noFile("cannot load '%s': is a directory", key.label)
                        else
                            BzlCompileValue.Companion.noFile(
                                "cannot load '%s': not a regular file (dangling link?)", key.label
                            )
                    }

                    // Read the file.
                    val path: com.google.devtools.build.lib.vfs.Path = rootedPath.asPath()
                    try {
                        bytes =
                            if (fileValue.isSpecialFile())
                                com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(path)
                            else
                                com.google.devtools.build.lib.vfs.FileSystemUtils.readWithKnownFileSize(
                                    path,
                                    fileValue.getSize()
                                )
                    } catch (e: IOException) {
                        throw FailedIOException(e, Transience.TRANSIENT)
                    }
                    digest = fileValue.getDigest() // may be null
                    inputName = path.toString()
                } else {
                    if (key.kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.PRELUDE) {
                        // A non-existent prelude is fine.
                        bytes = byteArrayOf()
                        digest = null
                        inputName = "<default prelude>"
                    } else {
                        return BzlCompileValue.Companion.noFile("cannot load '%s': no such file", key.label)
                    }
                }
            }

            // Compute digest if we didn't already get it from a fileValue.
            if (digest == null) {
                digest = hashFunction.hashBytes(bytes).asBytes()
            }

            val semantics: net.starlark.java.eval.StarlarkSemantics? = PrecomputedValue.STARLARK_SEMANTICS.get(env)
            if (semantics == null) {
                return null
            }

            val predeclared: com.google.common.collect.ImmutableMap<String?, Any?>?
            if (key.isSclDialect()) {
                predeclared = bazelStarlarkEnvironment.getStarlarkGlobals().getSclToplevels()
            } else if (key.kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.BUILTINS) {
                predeclared = bazelStarlarkEnvironment.getBuiltinsBzlEnv()
            } else {
                // Use the predeclared environment for BUILD-loaded bzl files, ignoring injection. It is not
                // the right env for the actual evaluation of BUILD-loaded bzl files because it doesn't
                // map to the injected symbols. But the names of the symbols are the same, and the names are
                // all we need to do symbol resolution.
                //
                // For WORKSPACE-loaded bzl files, the env isn't quite right not because of injection but
                // because the "native" object is different. But A) that will be fixed with #11954, and B) we
                // don't care for the same reason as above.

                predeclared = bazelStarlarkEnvironment.getUninjectedBuildBzlEnv()
            }

            // We have all deps. Parse, resolve, and return.
            val input: net.starlark.java.syntax.ParserInput?
            try {
                input =
                    com.google.devtools.build.lib.skyframe.StarlarkUtil.createParserInput(
                        bytes,
                        inputName,
                        semantics.get<Utf8EnforcementMode?>(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
                        env.getListener()
                    )
            } catch (e: InvalidUtf8Exception) {
                return BzlCompileValue.Companion.noFile("compilation of '%s' failed", inputName)
            }

            val optionsBuilder: net.starlark.java.syntax.FileOptions.Builder =
                net.starlark.java.syntax.FileOptions.builder() // By default, Starlark load statements create file-local bindings.
                    // However, the BUILD prelude typically contains nothing but load
                    // statements whose bindings are intended to be visible in all BUILD
                    // files. The loadBindsGlobally flag allows us to retrieve them.
                    .loadBindsGlobally(key.isBuildPrelude()) // .scl files should be ASCII-only in string literals.
                    // TODO(bazel-team): It'd be nice if we could intercept non-ASCII errors from the lexer,
                    // and modify the displayed message to clarify to the user that the string would be
                    // permitted in a .bzl file. But there's no easy way to do that short of either string
                    // matching the error message or reworking the interpreter API to put more structured
                    // detail in errors (i.e. new fields or error subclasses).
                    .stringLiteralsAreAsciiOnly(key.isSclDialect())
            val typeOptions: TypeOptions = getTypeOptions(semantics, key)
            updateFileOptions(optionsBuilder, typeOptions)
            val file: net.starlark.java.syntax.StarlarkFile =
                net.starlark.java.syntax.StarlarkFile.parse(input, optionsBuilder.build())

            // compile
            val module: net.starlark.java.eval.Module

            if (key.kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.EMPTY_PRELUDE) {
                // The empty prelude has no label, so we can't use it to filter the predeclareds.
                // This doesn't matter since the empty prelude doesn't attempt to access any predeclareds
                // anyway.
                module = net.starlark.java.eval.Module.withPredeclared(semantics, predeclared)
            } else {
                // The BazelCompileContext holds additional contextual info to be associated with the Module
                // The information is used to filter predeclareds
                val bazelCompileContext: BazelCompileContext? =
                    BazelCompileContext.create(key.label, file.getName())
                module =
                    net.starlark.java.eval.Module.withPredeclaredAndData(semantics, predeclared, bazelCompileContext)
            }
            try {
                val prog: net.starlark.java.syntax.Program = net.starlark.java.syntax.Program.compileFile(file, module)
                if (key.kind == com.google.devtools.build.lib.skyframe.BzlCompileValue.Kind.NORMAL) {
                    packageLoadingListener.onBzlCompileCompleteAndSuccessful(rootedPath, bytes.length)
                }
                return BzlCompileValue.Companion.withProgram(prog, digest, typeOptions)
            } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
                addSyntaxErrorsToListener(env.getListener(), ex.errors(), key)
                return BzlCompileValue.Companion.noFile(
                    "compilation of module '%s'%s failed",
                    key.label.toPathFragment(),
                    if (StarlarkBuiltinsValue.isBuiltinsRepo(key.label.getRepository())) " (internal)" else ""
                )
            }
        }

        /**
         * Whether the file should permit type syntax (annotations, etc.) based on flags and the type of
         * file.
         */
        private fun getTypeOptions(
            semantics: net.starlark.java.eval.StarlarkSemantics,
            key: com.google.devtools.build.lib.skyframe.BzlCompileValue.Key
        ): TypeOptions {
            val typeSyntaxFlag: Boolean =
                semantics.getBool(BuildLanguageOptions.EXPERIMENTAL_STARLARK_TYPE_SYNTAX)
            val allowlist: MutableList<String?>? =
                semantics.get<MutableList<String?>?>(BuildLanguageOptions.EXPERIMENTAL_STARLARK_TYPES_ALLOWED_PATHS)

            val okFiletype =  // annotations in prelude not allowed (it has null key.label)
                !key.isBuildPrelude() // annotations in SCL not allowed (not yet compatible with Go-Starlark interpreter)
                        && !key.isSclDialect() // TODO: #27370 - At the moment we haven't implemented the distinction between typed and
                        // untyped code, so we need this special casing to prevent type checking from applying
                        // to arbitrary @_builtins code. Same for @bazel_tools.
                        && !key.isBuiltins() && !key.label.getRepository().equals(RepositoryName.BAZEL_TOOLS)

            var useTypeSyntax = false
            if (typeSyntaxFlag && okFiletype) {
                if (allowlist!!.isEmpty()
                    || allowlist.stream().anyMatch(java.util.function.Predicate { s: String? ->
                        key.label.getCanonicalForm().startsWith(s)
                    })
                ) {
                    useTypeSyntax = true
                }
            }
            val doStaticTypeChecking =
                useTypeSyntax
                        && semantics.getBool(net.starlark.java.eval.StarlarkSemantics.EXPERIMENTAL_STARLARK_STATIC_TYPE_CHECKING)
            val doDynamicTypeChecking =
                useTypeSyntax
                        && semantics.getBool(net.starlark.java.eval.StarlarkSemantics.EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING)

            return TypeOptions(useTypeSyntax, doStaticTypeChecking, doDynamicTypeChecking)
        }

        private fun updateFileOptions(builder: net.starlark.java.syntax.FileOptions.Builder, typeOptions: TypeOptions) {
            val needsTypeInfo =
                typeOptions.wantStaticTypeChecking || typeOptions.wantDynamicTypeChecking
            builder
                .allowTypeSyntax(typeOptions.useTypeSyntax)
                .resolveTypeSyntax(needsTypeInfo)
                .tolerateInvalidTypeExpressions(!needsTypeInfo)
        }

        /**
         * Replays the syntax errors from a file onto an event handler, adding more context if necessary.
         */
        private fun addSyntaxErrorsToListener(
            handler: EventHandler,
            errors: MutableList<net.starlark.java.syntax.SyntaxError>,
            key: com.google.devtools.build.lib.skyframe.BzlCompileValue.Key
        ) {
            Event.replayEventsOn(handler, errors)
            // If type annotations are disallowed, it could either be because the required flags aren't
            // enabled or because the filetype disallows it.
            for (err in errors) {
                if (err.message().contains(": type annotations are disallowed")) {
                    val fileLoc: net.starlark.java.syntax.Location =
                        net.starlark.java.syntax.Location.fromFile(err.location().file())
                    val explanation =
                        if (key.isSclDialect())
                            "Type annotations are not permitted in .scl files."
                        else
                            """
                Type annotations syntax can be enabled with --experimental_starlark_type_syntax and/or --experimental_starlark_types_allowed_paths.
                """.trimIndent()
                    handler.handle(Event.error(fileLoc, explanation))
                }
            }
        }
    }
}
