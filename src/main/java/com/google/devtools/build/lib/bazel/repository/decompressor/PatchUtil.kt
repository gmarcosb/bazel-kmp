// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.patch.AbstractDelta
import com.github.difflib.patch.Patch
import com.github.difflib.patch.PatchFailedException
import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import java.io.IOException
import java.lang.String
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import kotlin.Boolean
import kotlin.Char
import kotlin.IndexOutOfBoundsException
import kotlin.Int
import kotlin.arrayOf
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/** Implementation of native patch.  */
object PatchUtil {
    private val CHUNK_HEADER_RE: Pattern =
        Pattern.compile("^@@\\s+-(?:(\\d+)(?:,(\\d+))?)\\s+\\+(?:(\\d+)(?:,(\\d+))?)\\s+@@$")

    private val GIT_LINE_PREFIXES = arrayOf<String?>(
        "old mode ",
        "new mode ",
        "deleted file mode ",
        "new file mode ",
        "copy from ",
        "copy to ",
        "rename old ",
        "rename new ",
        "similarity index ",
        "dissimilarity index ",
        "index "
    )

    private fun getLineType(line: String, isReadingChunk: Boolean, isGitDiff: Boolean): LineType {
        if (isReadingChunk) {
            if (line.startsWith("+")) {
                return LineType.CHUNK_ADD
            }
            if (line.startsWith("-")) {
                return LineType.CHUNK_DEL
            }
            if (line.startsWith(" ") || line.isEmpty()) {
                return LineType.CHUNK_EQL
            }
        } else {
            if (line.startsWith("--- ")) {
                return LineType.OLD_FILE
            }
            if (line.startsWith("+++ ")) {
                return LineType.NEW_FILE
            }
            if (line.startsWith("diff --git ")) {
                return LineType.GIT_HEADER
            }
            if (isGitDiff) {
                // Only recognize the following when we saw "diff --git " before.
                if (line.startsWith("rename from ")) {
                    return LineType.RENAME_FROM
                }
                if (line.startsWith("rename to ")) {
                    return LineType.RENAME_TO
                }
                if (line.startsWith("new mode ")) {
                    return LineType.NEW_MODE
                }
                if (line.startsWith("new file mode ")) {
                    return LineType.NEW_FILE_MODE
                }
                for (prefix in GIT_LINE_PREFIXES) {
                    if (line.startsWith(prefix)) {
                        return LineType.OTHER_GIT_LINE
                    }
                }
            }
        }
        if (line.startsWith("@@") && line.lastIndexOf("@@") != 0) {
            val pos: Int = line.indexOf("@@", 2)
            val m = CHUNK_HEADER_RE.matcher(line.substring(0, pos + 2))
            if (m.find()) {
                return LineType.CHUNK_HEAD
            }
        }
        return LineType.UNKNOWN
    }

    @Throws(IOException::class)
    private fun readFile(file: Path?): ImmutableList<String> {
        return FileSystemUtils.readLines(file, StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun writeFile(file: Path, content: MutableList<String?>?) {
        FileSystemUtils.writeLinesAs(file, StandardCharsets.UTF_8, content)
    }

    private fun getReadPermission(permission: Int): Boolean {
        // Parse read permission from posix file permission notation
        return (permission and 4) == 4
    }

    private fun getWritePermission(permission: Int): Boolean {
        // Parse write permission from posix file permission notation
        return (permission and 2) == 2
    }

    private fun getExecutablePermission(permission: Int): Boolean {
        // Parse executable permission from posix file permission notation
        return (permission and 1) == 1
    }

    @Throws(IOException::class)
    private fun getFilePermissionValue(file: Path): Int {
        return ((if (file.isReadable()) 4 else 0)
                + (if (file.isWritable()) 2 else 0)
                + (if (file.isExecutable()) 1 else 0))
    }

    @Throws(IOException::class)
    private fun setFilePermission(file: Path, permission: Int) {
        file.setReadable(getReadPermission(permission))
        file.setWritable(getWritePermission(permission))
        file.setExecutable(getExecutablePermission(permission))
    }

    @Throws(IOException::class, PatchFailedException::class)
    private fun applyPatchToFile(
        patch: Patch<String?>, oldFile: Path?, newFile: Path?, isRenaming: Boolean, filePermission: Int
    ) {
        // The file we should read oldContent from.
        var filePermission = filePermission
        var inputFile: Path? = null
        if (oldFile != null && oldFile.exists()) {
            inputFile = oldFile
        } else if (newFile != null && newFile.exists()) {
            inputFile = newFile
        }

        val oldContent: ImmutableList<String>?
        if (inputFile == null) {
            oldContent = ImmutableList.of<String?>()
        } else {
            oldContent = readFile(inputFile)
            // Preserve old file permission if no explicit permission is set.
            if (filePermission == -1) {
                filePermission = getFilePermissionValue(inputFile)
            }
        }

        var newContent: MutableList<String?> = ArrayList<String?>(oldContent)
        // Apply the chunks separately and in reverse order to workaround an issue in applyFuzzy.
        // See https://github.com/java-diff-utils/java-diff-utils/pull/125#issuecomment-1749385825
        for (delta in Lists.reverse<AbstractDelta<String?>>(patch.getDeltas())) {
            val tmpPatch = Patch<String?>()
            tmpPatch.addDelta(delta)
            try {
                newContent = tmpPatch.applyFuzzy(newContent, 2)
            } catch (e: PatchFailedException) {
                throw PatchFailedException(
                    String.format(
                        "in patch applied to %s: %s, error applying change near line %s",
                        oldFile, e.getMessage(), delta.getSource().getPosition() + 1
                    )
                )
            } catch (e: IndexOutOfBoundsException) {
                throw PatchFailedException(
                    String.format(
                        "in patch applied to %s: %s, error applying change near line %s",
                        oldFile, e.getMessage(), delta.getSource().getPosition() + 1
                    )
                )
            }
        }

        // The file we should write newContent to.
        val outputFile: Path?
        if (oldFile != null && oldFile.exists() && !isRenaming) {
            outputFile = oldFile
        } else {
            outputFile = newFile
        }

        // The old file should always change, therefore we can just delete the original file.
        // If the output file name is the same as the old file, we'll just recreate it later.
        if (oldFile != null) {
            oldFile.delete()
        }

        // Does this patch look like deleting a file.
        val isDeleteFile = newFile == null && newContent.isEmpty()

        if (outputFile != null && !isDeleteFile) {
            writeFile(outputFile, newContent)
            if (filePermission != -1) {
                setFilePermission(outputFile, filePermission)
            }
        }
    }

    /**
     * Strip a number of leading components from a path
     * 
     * @param path the original path
     * @param strip the number of leading components to strip
     * @return The stripped path
     */
    private fun stripPath(path: kotlin.String, strip: Int): kotlin.String {
        var strip = strip
        var pos = 0
        while (pos < path.length() && strip > 0) {
            if (path.charAt(pos) == '/') {
                strip--
            }
            pos++
        }
        return path.substring(pos)
    }

    /**
     * Extract the file path from a patch line starting with "--- " or "+++ " Returns null if the path
     * is /dev/null, otherwise returns the extracted path if succeeded or throw an exception if
     * failed.
     */
    @Throws(PatchFailedException::class)
    private fun extractPath(line: kotlin.String, strip: Int, loc: Int): kotlin.String? {
        // The line could look like:
        // --- a/foo/bar.txt   2019-05-27 17:19:37.054593200 +0200
        // +++ b/foo/bar.txt   2019-05-27 17:19:37.054593200 +0200
        // If strip is 1, we want extract the file path as foo/bar.txt
        var line = line
        Preconditions.checkArgument(line.startsWith("+++ ") || line.startsWith("--- "))
        line = Iterables.get<kotlin.String?>(Splitter.on('\t').split(line), 0)!!
        if (line.length() > 4) {
            var path: kotlin.String = line.substring(4).trim()
            if (path == "/dev/null") {
                return null
            }
            path = stripPath(path, strip)
            if (!path.isEmpty()) {
                return path
            }
        }
        throw PatchFailedException(
            String.format(
                "Cannot determine file name with strip = %d at line %d:\n%s", strip, loc, line
            )
        )
    }

    @Throws(PatchFailedException::class)
    private fun getFilePath(path: kotlin.String?, outputDirectory: Path, loc: Int): Path? {
        if (path == null) {
            return null
        }
        val filePath = outputDirectory.getRelative(path)
        if (!filePath.startsWith(outputDirectory)) {
            throw PatchFailedException(
                String.format(
                    "Cannot patch file outside of external repository (%s), file path = \"%s\" at line"
                            + " %d",
                    outputDirectory.getPathString(), path, loc
                )
            )
        }
        return filePath
    }

    @Throws(PatchFailedException::class)
    private fun checkPatchContentIsComplete(
        patchContent: MutableList<kotlin.String?>, header: ChunkHeader, oldLineCount: Int, newLineCount: Int, loc: Int
    ) {
        // If the patchContent is not empty, it should have correct format.
        if (!patchContent.isEmpty()) {
            if (patchContent.size() < 2 || !patchContent.get(0).startsWith("---") || !patchContent.get(1)
                    .startsWith("+++")
            ) {
                throw PatchFailedException(
                    String.format(
                        "The patch content must start with ---/+++ prelude lines at line %d.", loc
                    )
                )
            }
            if (header == null) {
                throw PatchFailedException(
                    String.format(
                        "Looks like a unified diff at line %d, but no patch chunk was found.", loc
                    )
                )
            }
            val result = header.check(oldLineCount, newLineCount)
            // result will never be Result.Error here because it would have been throw in previous
            // line already.
            if (result == Result.CONTINUE) {
                throw PatchFailedException(
                    String.format("Expecting more chunk line at line %d", loc + patchContent.size())
                )
            }
        }
    }

    @Throws(PatchFailedException::class)
    private fun checkFilesStatusForRenaming(
        oldFile: Path?, newFile: Path?, oldFileStr: kotlin.String?, newFileStr: kotlin.String?, loc: Int
    ) {
        // If we're doing a renaming,
        // old file should be specified and exists,
        // new file should be specified but doesn't exist yet.
        var oldFileError = ""
        var newFileError = ""
        if (oldFile == null) {
            oldFileError = ", old file name is not specified"
        } else if (!oldFile.exists()) {
            oldFileError = String.format(", old file name (%s) doesn't exist", oldFileStr)
        }
        if (newFile == null) {
            newFileError = ", new file name is not specified"
        } else if (newFile.exists()) {
            newFileError = String.format(", new file name (%s) already exists", newFileStr)
        }
        if (!oldFileError.isEmpty() || !newFileError.isEmpty()) {
            throw PatchFailedException(
                String.format("Cannot rename file (near line %d)%s%s.", loc, oldFileError, newFileError)
            )
        }
    }

    @Throws(PatchFailedException::class)
    private fun checkFilesStatusForPatching(
        patch: Patch<kotlin.String?>,
        oldFile: Path?,
        newFile: Path?,
        oldFileStr: kotlin.String?,
        newFileStr: kotlin.String?,
        loc: Int
    ) {
        // At least one of oldFile or newFile should be specified.
        if (oldFile == null && newFile == null) {
            throw PatchFailedException(
                String.format(
                    "Wrong patch format near line %d, neither new file or old file are specified.", loc
                )
            )
        }

        // Does this patch look like adding a new file.
        val isAddFile =
            patch.getDeltas().size() == 1 && patch.getDeltas().get(0).getSource().getLines().isEmpty()

        // If this patch is not adding a new file,
        // then either old file or new file should be specified and exists,
        // if not we throw an error.
        if (!isAddFile && (oldFile == null || !oldFile.exists())
            && (newFile == null || !newFile.exists())
        ) {
            val oldFileError: kotlin.String?
            val newFileError: kotlin.String?
            if (oldFile == null) {
                oldFileError = ", old file name is not specified"
            } else {
                oldFileError = String.format(", old file name (%s) doesn't exist", oldFileStr)
            }
            if (newFile == null) {
                newFileError = ", new file name is not specified"
            } else {
                newFileError = String.format(", new file name (%s) doesn't exist", newFileStr)
            }
            throw PatchFailedException(
                String.format(
                    "Cannot find file to patch (near line %d)%s%s.", loc, oldFileError, newFileError
                )
            )
        }
    }

    /**
     * Apply a patch file under a directory
     * 
     * @param patchFile the patch file to apply
     * @param strip the number of leading components to strip from file path in the patch file
     * @param outputDirectory the directory to apply the patch file to
     */
    @kotlin.jvm.JvmStatic
    @Throws(IOException::class, PatchFailedException::class)
    fun apply(patchFile: Path, strip: Int, outputDirectory: Path) {
        applyInternal(patchFile, strip, outputDirectory,  /* singleFile= */null)
    }

    /**
     * Apply a patch file under a directory, skipping all parts of the patch file that do not apply to
     * the given single file.
     * 
     * @param patchFile the patch file to apply
     * @param strip the number of leading components to strip from file path in the patch file
     * @param outputDirectory the directory to apply the patch file to
     * @param singleFile only apply the parts of the patch file that apply to this file. Renaming the
     * file is not supported in this case.
     */
    @Throws(IOException::class, PatchFailedException::class)
    fun applyToSingleFile(
        patchFile: Path, strip: Int, outputDirectory: Path, singleFile: Path?
    ) {
        applyInternal(patchFile, strip, outputDirectory, singleFile)
    }

    @Throws(IOException::class, PatchFailedException::class)
    private fun applyInternal(
        patchFile: Path, strip: Int, outputDirectory: Path, singleFile: Path?
    ) {
        if (!patchFile.exists()) {
            throw PatchFailedException("Cannot find patch file: " + patchFile.getPathString())
        }

        val singleFileStr =
            if (singleFile != null) singleFile.relativeTo(outputDirectory).getPathString() else null
        var isGitDiff = false
        var hasRenameFrom = false
        var hasRenameTo = false
        var isReadingChunk = false
        val patchContent: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
        var header: ChunkHeader? = null
        var oldFileStr: kotlin.String? = null
        var newFileStr: kotlin.String? = null
        var oldFile: Path? = null
        var newFile: Path? = null
        var oldLineCount = 0
        var newLineCount = 0
        var filePermission = -1
        var result: Result?

        val patchFileLines = readFile(patchFile)
        for (i in 0..patchFileLines.size()) {
            // Adding an extra line to make sure last chunk also gets applied.
            val line = if (i < patchFileLines.size()) patchFileLines.get(i) else "$"
            val type: LineType?
            when (getLineType(line, isReadingChunk, isGitDiff).also { type = it }) {
                LineType.OLD_FILE -> {
                    patchContent.add(line)
                    oldFileStr = extractPath(line, strip, i + 1)
                    oldFile = getFilePath(oldFileStr, outputDirectory, i + 1)
                }

                LineType.NEW_FILE -> {
                    patchContent.add(line)
                    newFileStr = extractPath(line, strip, i + 1)
                    newFile = getFilePath(newFileStr, outputDirectory, i + 1)
                }

                LineType.NEW_MODE, LineType.NEW_FILE_MODE -> {
                    // The line should look like: "new mode 100755" or "new file mode 100755"
                    // 7 is the file permission for owner, which is at index 12 or 17
                    val index = if (type == LineType.NEW_MODE) 12 else 17
                    if (line.length() <= index) {
                        throw PatchFailedException("Truncated file mode at line " + (i + 1) + ": " + line)
                    }
                    val c: Char = line.charAt(index)
                    if (c < '0' || c > '7') {
                        throw PatchFailedException(
                            "Wrong file mode format at line " + (i + 1) + ": " + line
                        )
                    }
                    filePermission = c.code - '0'.code
                }

                LineType.CHUNK_HEAD -> {
                    val pos: Int = line.indexOf("@@", 2)
                    val headerStr: kotlin.String = line.substring(0, pos + 2)
                    patchContent.add(headerStr)
                    header = ChunkHeader(headerStr)
                    oldLineCount = 0
                    newLineCount = 0
                    isReadingChunk = true
                }

                LineType.CHUNK_ADD -> {
                    newLineCount++
                    patchContent.add(line)
                    result = header!!.check(oldLineCount, newLineCount)
                    if (result == Result.COMPLETE) {
                        isReadingChunk = false
                    } else if (result == Result.ERROR) {
                        throw PatchFailedException(
                            ("Wrong chunk detected near line "
                                    + (i + 1)
                                    + ": "
                                    + line
                                    + ", does not expect an added line here.")
                        )
                    }
                }

                LineType.CHUNK_DEL -> {
                    oldLineCount++
                    patchContent.add(line)
                    result = header!!.check(oldLineCount, newLineCount)
                    if (result == Result.COMPLETE) {
                        isReadingChunk = false
                    } else if (result == Result.ERROR) {
                        throw PatchFailedException(
                            ("Wrong chunk detected near line "
                                    + (i + 1)
                                    + ": "
                                    + line
                                    + ", does not expect a deleted line here.")
                        )
                    }
                }

                LineType.CHUNK_EQL -> {
                    oldLineCount++
                    newLineCount++
                    patchContent.add(line)
                    result = header!!.check(oldLineCount, newLineCount)
                    if (result == Result.COMPLETE) {
                        isReadingChunk = false
                    } else if (result == Result.ERROR) {
                        throw PatchFailedException(
                            ("Wrong chunk detected near line "
                                    + (i + 1)
                                    + ": "
                                    + line
                                    + ", does not expect a context line here.")
                        )
                    }
                }

                LineType.RENAME_FROM -> {
                    hasRenameFrom = true
                    if (oldFileStr == null) {
                        // len("rename from ") == 12
                        oldFileStr = line.substring(12).trim()
                        if (oldFileStr.isEmpty()) {
                            throw PatchFailedException(
                                String.format("Cannot determine file name from line %d:\n%s", i + 1, line)
                            )
                        }
                        oldFile = getFilePath(oldFileStr, outputDirectory, i + 1)
                    }
                }

                LineType.RENAME_TO -> {
                    hasRenameTo = true
                    if (newFileStr == null) {
                        // len("rename to ") == 10
                        newFileStr = line.substring(10).trim()
                        if (newFileStr.isEmpty()) {
                            throw PatchFailedException(
                                String.format("Cannot determine file name from line %d:\n%s", i + 1, line)
                            )
                        }
                        newFile = getFilePath(newFileStr, outputDirectory, i + 1)
                    }
                }

                LineType.OTHER_GIT_LINE -> {}
                LineType.GIT_HEADER, LineType.UNKNOWN -> {
                    // A git header line or an unknown line should trigger an action to apply collected
                    // patch content to a file.
                    // Renaming is a git only format
                    val isRenaming = isGitDiff && hasRenameFrom && hasRenameTo

                    if (!patchContent.isEmpty() || isRenaming || filePermission != -1) {
                        // We collected something useful, let's do some checks before applying the patch.
                        val patchStartLocation: Int = i + 1 - patchContent.size()

                        PatchUtil.checkPatchContentIsComplete(
                            patchContent, header!!, oldLineCount, newLineCount, patchStartLocation
                        )

                        if (isRenaming) {
                            if (singleFile != null) {
                                if (singleFile == newFile || singleFile == oldFile) {
                                    throw PatchFailedException(
                                        "Renaming %s while applying patches to it as a single file is not supported."
                                            .formatted(singleFile)
                                    )
                                }
                            } else {
                                checkFilesStatusForRenaming(
                                    oldFile, newFile, oldFileStr, newFileStr, patchStartLocation
                                )
                            }
                        }
                        if (singleFileStr != null && strip == 0 && ("a/" + singleFileStr) == oldFileStr
                            && ("b/" + singleFileStr) == newFileStr
                        ) {
                            throw PatchFailedException(
                                String.format(
                                    "error at line %d: the patch file contains a/b prefixes, did you forget to"
                                            + " set patch_strip = 1?",
                                    patchStartLocation
                                )
                            )
                        }

                        if (singleFile == null || (singleFile == newFile && singleFile == oldFile)) {
                            val patch = UnifiedDiffUtils.parseUnifiedDiff(patchContent)
                            checkFilesStatusForPatching(
                                patch, oldFile, newFile, oldFileStr, newFileStr, patchStartLocation
                            )

                            applyPatchToFile(patch, oldFile, newFile, isRenaming, filePermission)
                        }
                    }

                    patchContent.clear()
                    header = null
                    oldFileStr = null
                    newFileStr = null
                    oldFile = null
                    newFile = null
                    filePermission = -1
                    oldLineCount = 0
                    newLineCount = 0
                    isReadingChunk = false
                    // If the new patch starts with "diff --git " then it's a git diff.
                    isGitDiff = type == LineType.GIT_HEADER
                    if (isGitDiff) {
                        // In case there is no line starting with +++ and --- (file permission change),
                        // try to parse the file names from the line starting with "diff --git"
                        val args = Splitter.on(' ').splitToList(line)
                        if (args.size() >= 4) {
                            oldFileStr = PatchUtil.stripPath(args.get(2)!!, strip)
                            if (!oldFileStr.isEmpty()) {
                                oldFile = getFilePath(oldFileStr, outputDirectory, i + 1)
                            }
                            newFileStr = PatchUtil.stripPath(args.get(3)!!, strip)
                            if (!newFileStr.isEmpty()) {
                                newFile = getFilePath(newFileStr, outputDirectory, i + 1)
                            }
                        }
                    }
                    hasRenameFrom = false
                    hasRenameTo = false
                }
            }
        }
    }

    /** The possible results of ChunkHeader.check.  */
    enum class Result {
        COMPLETE,  // The entire chunk is read
        CONTINUE,  // Should continue reading the chunk
        ERROR,  // The chunk body doesn't match the chunk header's size description
    }

    private class ChunkHeader(header: kotlin.String?) {
        private val oldSize: Int
        private val newSize: Int

        fun check(oldLineCnt: Int, newLineCnt: Int): Result {
            if (oldLineCnt == oldSize && newLineCnt == newSize) {
                return Result.COMPLETE
            }
            if (oldLineCnt <= oldSize && newLineCnt <= newSize) {
                return Result.CONTINUE
            }
            return Result.ERROR
        }

        init {
            val m = CHUNK_HEADER_RE.matcher(header)
            if (m.find()) {
                var size: kotlin.String?
                size = m.group(2)
                oldSize = if (size == null) 1 else Integer.parseInt(size)
                size = m.group(4)
                newSize = if (size == null) 1 else Integer.parseInt(size)
            } else {
                throw PatchFailedException("Wrong chunk header: " + header)
            }
        }
    }

    private enum class LineType {
        OLD_FILE,
        NEW_FILE,
        CHUNK_HEAD,
        CHUNK_ADD,
        CHUNK_DEL,
        CHUNK_EQL,
        GIT_HEADER,
        RENAME_FROM,
        RENAME_TO,
        NEW_MODE,
        NEW_FILE_MODE,
        OTHER_GIT_LINE,
        UNKNOWN
    }
}
