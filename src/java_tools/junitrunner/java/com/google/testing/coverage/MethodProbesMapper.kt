// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.coverage

import com.google.testing.coverage.BranchExp
import com.google.testing.coverage.CovExp
import com.google.testing.coverage.MethodProbesMapper
import com.google.testing.coverage.ProbeExp
import org.jacoco.core.internal.analysis.filter.IFilter
import org.jacoco.core.internal.analysis.filter.IFilterContext
import org.jacoco.core.internal.analysis.filter.IFilterOutput
import java.util.HashMap
import java.util.HashSet
import java.util.TreeMap


/**
 * The mapper is a probes visitor that will cache control flow information as well as keeping track
 * of the probes as the main driver generates the probe ids. Upon finishing the method it uses the
 * information collected to generate the mapping information between probes and the instructions.
 */
open class MethodProbesMapper(filterContext: IFilterContext?, filter: IFilter?) :
    org.jacoco.core.internal.flow.MethodProbesVisitor(), IFilterOutput {
    /*
       * The implementation roughly follows the same pattern of the Analyzer class of Jacoco.
       *
       * The mapper has a few states:
       *
       * - lineMappings: a mapping between line number and labels
       *
       * - a sequence of "instructions", where each instruction has one or more predecessors. The
       * predecessor field has a sole purpose of propagating probe id. The 'merge' nodes in the CFG has
       * no predecessors, since the branch stops at theses points.
       *
       * - The instructions each has states that keep track of the probes that are associated with the
       * instruction.
       *
       * Initially the probe ids are assigned to the instructions that immediately precede the probe. At
       * the end of visiting the methods, the probe ids are propagated through the predecessor chains.
       */
    // States
    //
    // These are state variables that needs to be updated in the visitor methods.
    // The values usually changes as we traverse the byte code.
    private var lastInstruction: Instruction? = null
    private var currentLine = -1
    private val currentLabels: MutableList<org.objectweb.asm.Label?> = java.util.ArrayList<org.objectweb.asm.Label?>()
    private var currentInstructionNode: org.objectweb.asm.tree.AbstractInsnNode? = null
    private val instructionMap: MutableMap<org.objectweb.asm.tree.AbstractInsnNode?, Instruction> =
        HashMap<org.objectweb.asm.tree.AbstractInsnNode?, Instruction>()

    // Filtering
    private val filter: IFilter?
    private val filterContext: IFilterContext?
    private val ignored: HashSet<org.objectweb.asm.tree.AbstractInsnNode?> =
        HashSet<org.objectweb.asm.tree.AbstractInsnNode?>()
    private val unioned: MutableMap<org.objectweb.asm.tree.AbstractInsnNode?, org.objectweb.asm.tree.AbstractInsnNode?> =
        HashMap<org.objectweb.asm.tree.AbstractInsnNode?, org.objectweb.asm.tree.AbstractInsnNode?>()
    private val branchReplacements: MutableMap<org.objectweb.asm.tree.AbstractInsnNode?, org.jacoco.core.internal.analysis.filter.Replacements?> =
        HashMap<org.objectweb.asm.tree.AbstractInsnNode?, org.jacoco.core.internal.analysis.filter.Replacements?>()

    // Result
    private val lineToBranchExp: MutableMap<Int?, BranchExp?> = TreeMap<Int?, BranchExp?>()

    fun result(): MutableMap<Int?, BranchExp?> {
        return lineToBranchExp
    }

    // Intermediate results
    //
    // These values are built up during the visitor methods. They will be used to compute
    // the final results.
    private val instructions = InstructionSet()
    private val jumps: MutableList<Jump> = java.util.ArrayList<Jump>()
    private val probedInstructions: MutableList<Instruction> = java.util.ArrayList<Instruction>()
    private val labelToInsn: MutableMap<org.objectweb.asm.Label?, Instruction> =
        HashMap<org.objectweb.asm.Label?, Instruction>()

    init {
        this.filterContext = filterContext
        this.filter = filter
    }

    override fun accept(methodNode: org.objectweb.asm.tree.MethodNode, methodVisitor: org.objectweb.asm.MethodVisitor) {
        methodVisitor.visitCode()
        for (i in methodNode.instructions) {
            currentInstructionNode = i
            i.accept(methodVisitor)
        }
        if (filter != null) {
            filter.filter(methodNode, filterContext, this)
        }
        methodVisitor.visitEnd()
    }

    /** Visitor method to append a new Instruction  */
    private fun visitInsn() {
        val instruction: Instruction = com.google.testing.coverage.MethodProbesMapper.Instruction(currentLine)
        instructions.add(instruction)
        if (lastInstruction != null) {
            lastInstruction!!.addBranch(instruction,  /* branchIndex= */0)
        }

        for (label in currentLabels) {
            labelToInsn.put(label, instruction)
        }
        currentLabels.clear() // Update states
        lastInstruction = instruction
        instructionMap.put(currentInstructionNode, instruction)
    }

    // Plain visitors: called from adapter when no probe is needed
    override fun visitInsn(opcode: Int) {
        visitInsn()
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        visitInsn()
    }

    override fun visitVarInsn(opcode: Int, variable: Int) {
        visitInsn()
    }

    override fun visitTypeInsn(opcode: Int, type: String?) {
        visitInsn()
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, desc: String?) {
        visitInsn()
    }

    override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, desc: String?, itf: Boolean) {
        visitInsn()
    }

    override fun visitInvokeDynamicInsn(
        name: String?,
        desc: String?,
        handle: org.objectweb.asm.Handle?,
        vararg args: Any?
    ) {
        visitInsn()
    }

    override fun visitLdcInsn(cst: Any?) {
        visitInsn()
    }

    override fun visitIincInsn(`var`: Int, inc: Int) {
        visitInsn()
    }

    override fun visitMultiANewArrayInsn(desc: String?, dims: Int) {
        visitInsn()
    }

    // Methods that need to update the states
    override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label?) {
        visitInsn()
        jumps.add(com.google.testing.coverage.MethodProbesMapper.Jump(lastInstruction, label, 1))
    }

    override fun visitLabel(label: org.objectweb.asm.Label) {
        currentLabels.add(label)
        if (!org.jacoco.core.internal.flow.LabelInfo.isSuccessor(label)) {
            lastInstruction = null
        }
    }

    override fun visitLineNumber(line: Int, start: org.objectweb.asm.Label?) {
        currentLine = line
    }

    /** Visit a switch instruction with no probes  */
    private fun visitSwitchInsn(dflt: org.objectweb.asm.Label, labels: Array<org.objectweb.asm.Label>) {
        visitInsn()

        // Handle default transition
        org.jacoco.core.internal.flow.LabelInfo.resetDone(dflt)
        var branch = 0
        jumps.add(com.google.testing.coverage.MethodProbesMapper.Jump(lastInstruction, dflt, branch))
        org.jacoco.core.internal.flow.LabelInfo.setDone(dflt)

        // Handle other transitions
        org.jacoco.core.internal.flow.LabelInfo.resetDone(labels)
        for (label in labels) {
            if (!org.jacoco.core.internal.flow.LabelInfo.isDone(label)) {
                branch++
                jumps.add(com.google.testing.coverage.MethodProbesMapper.Jump(lastInstruction, label, branch))
                org.jacoco.core.internal.flow.LabelInfo.setDone(label)
            }
        }
    }

    override fun visitTableSwitchInsn(
        min: Int,
        max: Int,
        dflt: org.objectweb.asm.Label,
        vararg labels: org.objectweb.asm.Label
    ) {
        visitSwitchInsn(dflt, labels)
    }

    override fun visitLookupSwitchInsn(
        dflt: org.objectweb.asm.Label,
        keys: IntArray?,
        labels: Array<org.objectweb.asm.Label>
    ) {
        visitSwitchInsn(dflt, labels)
    }

    private fun addProbe(probeId: Int, branchIdx: Int) {
        // We do not add probes to the flow graph, but we need to update
        // the branch count of the predecessor of the probe
        lastInstruction.addBranch(ProbeExp(probeId), branchIdx)
        probedInstructions.add(lastInstruction!!)
    }

    // Probe visit methods
    override fun visitProbe(probeId: Int) {
        // This function is only called when visiting a merge node which
        // is a successor.
        // It adds a probe point to the last instruction
        checkNotNull(lastInstruction)

        addProbe(probeId,  /* branchIdx= */0)
        lastInstruction = null // Merge point should have no predecessor.
    }

    override fun visitJumpInsnWithProbe(
        opcode: Int,
        label: org.objectweb.asm.Label?,
        probeId: Int,
        frame: org.jacoco.core.internal.flow.IFrame?
    ) {
        visitInsn()
        addProbe(probeId,  /* branchIdx= */1)
    }

    override fun visitInsnWithProbe(opcode: Int, probeId: Int) {
        visitInsn()
        addProbe(probeId,  /* branchIdx= */0)
    }

    override fun visitTableSwitchInsnWithProbes(
        min: Int,
        max: Int,
        dflt: org.objectweb.asm.Label,
        labels: Array<org.objectweb.asm.Label>,
        frame: org.jacoco.core.internal.flow.IFrame?
    ) {
        visitSwitchInsnWithProbes(dflt, labels)
    }

    override fun visitLookupSwitchInsnWithProbes(
        dflt: org.objectweb.asm.Label,
        keys: IntArray?,
        labels: Array<org.objectweb.asm.Label>,
        frame: org.jacoco.core.internal.flow.IFrame?
    ) {
        visitSwitchInsnWithProbes(dflt, labels)
    }

    private fun visitSwitchInsnWithProbes(dflt: org.objectweb.asm.Label, labels: Array<org.objectweb.asm.Label>) {
        visitInsn()
        org.jacoco.core.internal.flow.LabelInfo.resetDone(dflt)
        org.jacoco.core.internal.flow.LabelInfo.resetDone(labels)
        var branch = 0
        visitTargetWithProbe(dflt, branch)
        for (l in labels) {
            branch++
            visitTargetWithProbe(l, branch)
        }
    }

    private fun visitTargetWithProbe(label: org.objectweb.asm.Label, branch: Int) {
        if (!org.jacoco.core.internal.flow.LabelInfo.isDone(label)) {
            val id: Int = org.jacoco.core.internal.flow.LabelInfo.getProbeId(label)
            if (id == org.jacoco.core.internal.flow.LabelInfo.NO_PROBE) {
                jumps.add(com.google.testing.coverage.MethodProbesMapper.Jump(lastInstruction, label, branch))
            } else {
                // Note, in this case the instrumenter should insert intermediate labels
                // for the probes. These probes will be added for the switch instruction.
                //
                // There is no direct jump between lastInstruction and the label either.
                addProbe(id, branch)
            }
            org.jacoco.core.internal.flow.LabelInfo.setDone(label)
        }
    }

    /** Finishing the method  */
    override fun visitEnd() {
        for (jump in jumps) {
            val insn: Instruction = labelToInsn.get(jump.target)!!
            jump.source.addBranch(insn, jump.branch)
        }

        for (insn in probedInstructions) {
            com.google.testing.coverage.MethodProbesMapper.Instruction.Companion.wireBranchPredecessors(insn)
        }

        // Handle merged instructions
        for (node in unioned.keys) {
            val rep: org.objectweb.asm.tree.AbstractInsnNode? = findRepresentative(node)
            val insn: Instruction = instructionMap.get(node)!!
            val repInsn: Instruction = instructionMap.get(rep)!!
            val branch: BranchExp = BranchExp.Companion.ensureIsBranchExp(insn.branchExp)
            val repBranch: BranchExp = BranchExp.Companion.ensureIsBranchExp(repInsn.branchExp)
            repInsn.branchExp = BranchExp.Companion.zip(repBranch, branch)
            ignored.add(node)
        }

        // Handle branch replacements
        for (entry in branchReplacements.entries) {
            val newBranchExp: BranchExp = BranchExp.Companion.initializeEmptyBranches()
            var branchIndex = 0
            for (replacements in entry.value.values()) {
                val subExp: BranchExp = BranchExp.Companion.initializeEmptyBranches()
                var subBranchIndex = 0
                for (replacement in replacements) {
                    val branchExp: BranchExp = instructionMap.get(replacement.instruction)!!.branchExp
                    subExp.setBranchAtIndex(subBranchIndex, branchExp.getBranchAtIndex(replacement.branch))
                    subBranchIndex++
                }
                newBranchExp.setBranchAtIndex(branchIndex, subExp)
                branchIndex++
            }
            val oldInsn: Instruction = instructionMap.get(entry.key)!!
            val newInsn: Instruction = com.google.testing.coverage.MethodProbesMapper.Instruction(oldInsn.line)
            newInsn.logicalBranches = branchIndex
            newInsn.branchExp = newBranchExp
            instructionMap.put(entry.key, newInsn)
            instructions.replace(oldInsn, newInsn)
        }

        val ignoredInstructions: HashSet<Instruction?> = HashSet<Instruction?>()
        for (entry in instructionMap.entries) {
            if (ignored.contains(entry.key)) {
                ignoredInstructions.add(entry.value)
            }
        }

        // Merge branches in the instructions on the same line
        for (insn in instructions) {
            if (ignoredInstructions.contains(insn)) {
                continue
            }
            if (insn!!.logicalBranches > 1) {
                val insnExp: CovExp? = insn.branchExp
                if (insnExp != null && (insnExp is BranchExp)) {
                    val exp: BranchExp = insnExp as BranchExp
                    val lineExp: BranchExp? = lineToBranchExp.get(insn.line)
                    if (lineExp == null) {
                        lineToBranchExp.put(insn.line, exp)
                    } else {
                        lineToBranchExp.put(insn.line, BranchExp.Companion.concatenate(lineExp, exp))
                    }
                } else {
                    // If we reach here, the internal data of the mapping is inconsistent, either
                    // 1) An instruction has branches but we do not create BranchExp for it.
                    // 2) An instruction has branches but it does not have an associated CovExp.
                }
            }
        }
    }

    /** IFilterOutput  */ // Handle only ignore for now; most filters only use this.
    override fun ignore(
        fromInclusive: org.objectweb.asm.tree.AbstractInsnNode,
        toInclusive: org.objectweb.asm.tree.AbstractInsnNode?
    ) {
        var n: org.objectweb.asm.tree.AbstractInsnNode = fromInclusive
        while (n !== toInclusive) {
            ignored.add(n)
            n = n.getNext()
        }
        ignored.add(toInclusive)
    }

    override fun merge(i1: org.objectweb.asm.tree.AbstractInsnNode?, i2: org.objectweb.asm.tree.AbstractInsnNode?) {
        // Track nodes to be merged using a union-find algorithm.
        var i1: org.objectweb.asm.tree.AbstractInsnNode? = i1
        var i2: org.objectweb.asm.tree.AbstractInsnNode? = i2
        i1 = findRepresentative(i1)
        i2 = findRepresentative(i2)
        if (i1 !== i2) {
            unioned.put(i1, i2)
        }
    }

    override fun replaceBranches(
        source: org.objectweb.asm.tree.AbstractInsnNode?,
        replacements: org.jacoco.core.internal.analysis.filter.Replacements?
    ) {
        branchReplacements.put(source, replacements)
    }

    private fun findRepresentative(node: org.objectweb.asm.tree.AbstractInsnNode?): org.objectweb.asm.tree.AbstractInsnNode? {
        // The "find" part of union-find. Walk the chain of nodes to find the representative node
        // (at the root), flattening the tree a little as we go.
        var node: org.objectweb.asm.tree.AbstractInsnNode? = node
        var parent: org.objectweb.asm.tree.AbstractInsnNode?
        var grandParent: org.objectweb.asm.tree.AbstractInsnNode?
        while ((unioned.get(node).also { parent = it }) != null) {
            if ((unioned.get(parent).also { grandParent = it }) != null) {
                unioned.put(node, grandParent)
            }
            node = parent
        }
        return node
    }

    /** Jumps between instructions and labels  */
    private class Jump(val source: Instruction, l: org.objectweb.asm.Label?, b: Int) {
        val target: org.objectweb.asm.Label?
        val branch: Int

        init {
            target = l
            branch = b
        }
    }

    /** Associate an instruction with a CovExp and its predecessor.  */
    private class Instruction(val line: Int) {
        var branchExp: BranchExp = BranchExp.Companion.initializeEmptyBranches()

        var predecessor: Instruction? = null

        var predecessorBranchIndex: Int = -1

        var logicalBranches: Int = 0

        fun addBranch(target: Instruction, branchIndex: Int) {
            logicalBranches++
            target.predecessor = this
            target.predecessorBranchIndex = branchIndex
        }

        fun addBranch(probeExp: ProbeExp?, branchIndex: Int) {
            logicalBranches++
            branchExp.setBranchAtIndex(branchIndex, probeExp)
        }

        /** Sets the target for a given branch.  */
        fun setBranchTarget(targetExp: CovExp?, branchIndex: Int) {
            branchExp.setBranchAtIndex(branchIndex, targetExp)
        }

        companion object {
            fun wireBranchPredecessors(root: Instruction) {
                // This is not a recursive method because some of these chains can be quite long
                var current: Instruction? = root
                var predecessor = root.predecessor
                while (predecessor != null) {
                    val alreadyHasBranches: Boolean = predecessor.branchExp.hasBranches()
                    predecessor.setBranchTarget(current!!.branchExp, current.predecessorBranchIndex)
                    if (alreadyHasBranches) {
                        // if the predecessor already had a configured branchExp we don't need to continue the
                        // walk; it should already have wired up its predecessors.
                        break
                    }
                    current = predecessor
                    predecessor = current.predecessor
                }
            }
        }
    }

    /**
     * Permit efficient replacement of one instruction with another while preserving original
     * insertion order. A replacement instruction takes the place of the old instruction for iteration
     * order.
     */
    private class InstructionSet : Iterable<Instruction> {
        private val instructions: MutableList<Instruction?> = java.util.ArrayList<Instruction?>()

        private val instructionIndex: MutableMap<Instruction?, Int?> = HashMap<Instruction?, Int?>()

        fun add(instruction: Instruction?) {
            instructionIndex.put(instruction, instructions.size)
            instructions.add(instruction)
        }

        fun replace(oldInstruction: Instruction?, newInstruction: Instruction?) {
            val index: Int = instructionIndex.get(oldInstruction)!!
            instructions.set(index, newInstruction)
            instructionIndex.put(newInstruction, index)
            instructionIndex.remove(oldInstruction)
        }

        override fun iterator(): MutableIterator<Instruction?>? {
            return instructions.iterator()
        }
    }
}
