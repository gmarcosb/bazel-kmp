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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.rules.cpp.ArtifactCategory
import com.google.devtools.build.lib.rules.cpp.CppActionNames
import com.google.devtools.build.lib.rules.cpp.CppFileTypes
import com.google.devtools.build.lib.util.FileTypeSet

/**
 * Utility types and methods for generating command lines for the linker, given
 * a CppLinkAction or LinkConfiguration.
 * 
 * 
 * The linker commands, e.g. "ar", may not be functional, i.e.
 * they may mutate the output file rather than overwriting it.
 * To avoid this, we need to delete the output file before invoking the
 * command.  But that is not done by this class; deleting the output
 * file is the responsibility of the classes implementing CppLinkActionContext.
 */
object Link {
    /**
     * These file are supposed to be added using `addLibrary()` calls to [CppLinkAction]
     * but will never be expanded to their constituent `.o` files. [CppLinkAction] checks
     * that these files are never added as non-libraries.
     */
    @kotlin.jvm.JvmField
    val SHARED_LIBRARY_FILETYPES: FileTypeSet? = FileTypeSet.of(
        CppFileTypes.SHARED_LIBRARY,
        CppFileTypes.VERSIONED_SHARED_LIBRARY,
        CppFileTypes.INTERFACE_SHARED_LIBRARY,
        com.google.devtools.build.lib.util.FileType.NO_EXTENSION
    )

    @kotlin.jvm.JvmField
    val ONLY_SHARED_LIBRARY_FILETYPES: FileTypeSet? = FileTypeSet.of(
        CppFileTypes.SHARED_LIBRARY,
        CppFileTypes.VERSIONED_SHARED_LIBRARY,
        com.google.devtools.build.lib.util.FileType.NO_EXTENSION
    )

    val ONLY_INTERFACE_LIBRARY_FILETYPES: FileTypeSet? = FileTypeSet.of(CppFileTypes.INTERFACE_SHARED_LIBRARY)

    @kotlin.jvm.JvmField
    val ARCHIVE_LIBRARY_FILETYPES: FileTypeSet? = FileTypeSet.of(
        CppFileTypes.ARCHIVE,
        CppFileTypes.PIC_ARCHIVE,
        CppFileTypes.ALWAYS_LINK_LIBRARY,
        CppFileTypes.ALWAYS_LINK_PIC_LIBRARY,
        CppFileTypes.RUST_RLIB,
        com.google.devtools.build.lib.util.FileType.NO_EXTENSION
    )

    @kotlin.jvm.JvmField
    val ARCHIVE_FILETYPES: FileTypeSet? = FileTypeSet.of(
        CppFileTypes.ARCHIVE,
        CppFileTypes.PIC_ARCHIVE,
        CppFileTypes.RUST_RLIB,
        com.google.devtools.build.lib.util.FileType.NO_EXTENSION
    )

    /** The set of object files  */
    @kotlin.jvm.JvmField
    val OBJECT_FILETYPES: FileTypeSet? = FileTypeSet.of(
        CppFileTypes.OBJECT_FILE,
        CppFileTypes.PIC_OBJECT_FILE,
        CppFileTypes.CLIF_OUTPUT_PROTO,
        CppFileTypes.BC_SOURCE
    )

    // LINT.IfChange
    /** Whether a particular link target requires PIC code.  */
    enum class Picness {
        PIC,
        NOPIC
    }

    /** Whether a particular link target linked in statically or dynamically.  */
    enum class LinkerOrArchiver {
        ARCHIVER,
        LINKER
    }

    /**
     * Whether a particular link target is executable.
     */
    enum class Executable {
        EXECUTABLE,
        NOT_EXECUTABLE
    }

    /**
     * Types of ELF files that can be created by the linker (.a, .so, .lo,
     * executable).
     */
    enum class LinkTargetType(
        linkerOrArchiver: LinkerOrArchiver,
        actionName: String,
        picness: Picness,
        linkerOutput: ArtifactCategory,
        executable: Executable
    ) {
        /** A normal static archive.  */
        STATIC_LIBRARY(
            LinkerOrArchiver.ARCHIVER,
            CppActionNames.CPP_LINK_STATIC_LIBRARY,
            Picness.NOPIC,
            ArtifactCategory.STATIC_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** An objc fully linked static archive.  */
        OBJC_FULLY_LINKED_ARCHIVE(
            LinkerOrArchiver.ARCHIVER,
            CppActionNames.OBJC_FULLY_LINK,
            Picness.NOPIC,
            ArtifactCategory.STATIC_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** An objc executable.  */
        OBJC_EXECUTABLE(
            LinkerOrArchiver.LINKER,
            CppActionNames.OBJC_EXECUTABLE,
            Picness.NOPIC,
            ArtifactCategory.EXECUTABLE,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.EXECUTABLE
        ),

        /** A static archive with .pic.o object files (compiled with -fPIC).  */
        PIC_STATIC_LIBRARY(
            LinkerOrArchiver.ARCHIVER,
            CppActionNames.CPP_LINK_STATIC_LIBRARY,
            Picness.PIC,
            ArtifactCategory.STATIC_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** An interface dynamic library.  */
        INTERFACE_DYNAMIC_LIBRARY(
            LinkerOrArchiver.LINKER,
            CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
            Picness.NOPIC,  // Actually PIC but it's not indicated in the file name
            ArtifactCategory.INTERFACE_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** A dynamic library built from cc_library srcs.  */
        NODEPS_DYNAMIC_LIBRARY(
            LinkerOrArchiver.LINKER,
            CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY,
            Picness.NOPIC,  // Actually PIC but it's not indicated in the file name
            ArtifactCategory.DYNAMIC_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** A transitive dynamic library used for distribution.  */
        DYNAMIC_LIBRARY(
            LinkerOrArchiver.LINKER,
            CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
            Picness.NOPIC,  // Actually PIC but it's not indicated in the file name
            ArtifactCategory.DYNAMIC_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** A static archive without removal of unused object files.  */
        ALWAYS_LINK_STATIC_LIBRARY(
            LinkerOrArchiver.ARCHIVER,
            CppActionNames.CPP_LINK_STATIC_LIBRARY,
            Picness.NOPIC,
            ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** A PIC static archive without removal of unused object files.  */
        ALWAYS_LINK_PIC_STATIC_LIBRARY(
            LinkerOrArchiver.ARCHIVER,
            CppActionNames.CPP_LINK_STATIC_LIBRARY,
            Picness.PIC,
            ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.NOT_EXECUTABLE
        ),

        /** An executable binary.  */
        EXECUTABLE(
            LinkerOrArchiver.LINKER,
            CppActionNames.CPP_LINK_EXECUTABLE,
            Picness.NOPIC,  // Picness is not indicate in the file name
            ArtifactCategory.EXECUTABLE,
            com.google.devtools.build.lib.rules.cpp.Link.Executable.EXECUTABLE
        ); // LINT.ThenChange(@rules_cc//cc/private/link/target_types.bzl)

        private val linkerOrArchiver: LinkerOrArchiver?

        /**
         * The name of a link action with this LinkTargetType, for the purpose of crosstool feature
         * selection.
         */
        @kotlin.jvm.JvmField
        val actionName: String?
        private val linkerOutput: ArtifactCategory
        private val picness: Picness?
        private val executable: Executable?

        init {
            this.linkerOrArchiver = linkerOrArchiver
            this.actionName = actionName
            this.linkerOutput = linkerOutput
            this.picness = picness
            this.executable = executable
        }

        /**
         * Returns whether the name of the output file should denote that the code in the file is PIC.
         */
        fun picness(): Picness? {
            return picness
        }

        val picExtensionWhenApplicable: String
            get() = if (picness == Picness.PIC) ".pic" else ""

        val defaultExtension: String
            get() = linkerOutput.getDefaultExtension()

        fun linkerOrArchiver(): LinkerOrArchiver? {
            return linkerOrArchiver
        }

        /** Returns an `ArtifactCategory` identifying the artifact type this link action emits.  */
        fun getLinkerOutput(): ArtifactCategory {
            return linkerOutput
        }

        /** Returns true iff this link type is executable.  */
        fun isExecutable(): Boolean {
            return (executable == com.google.devtools.build.lib.rules.cpp.Link.Executable.EXECUTABLE)
        }

        val isTransitiveDynamicLibrary: Boolean
            /** Returns true iff this link type is a transitive dynamic library.  */
            get() = this == LinkTargetType.DYNAMIC_LIBRARY

        val isDynamicLibrary: Boolean
            /** Returns true iff this link type is a dynamic library or transitive dynamic library.  */
            get() = this == LinkTargetType.NODEPS_DYNAMIC_LIBRARY || this == LinkTargetType.DYNAMIC_LIBRARY
    }

    /** The degree of "staticness" of symbol resolution during linking.  */
    enum class LinkingMode {
        /**
         * Link binaries statically except for system libraries (e.g. `gcc x.o libfoo.a libbar.a -lm`).
         */
        STATIC,

        /**
         * All libraries are linked dynamically (if a dynamic version is available), e.g. `gcc x.o libfoo.so libbar.so -lm`.
         */
        DYNAMIC,
    }

    /**
     * How to pass archives to the linker on the command line.
     */
    enum class ArchiveType {
        REGULAR,  // Put the archive itself on the linker command line.
        START_END_LIB // Put the object files enclosed by --start-lib / --end-lib on the command line
    }
}
