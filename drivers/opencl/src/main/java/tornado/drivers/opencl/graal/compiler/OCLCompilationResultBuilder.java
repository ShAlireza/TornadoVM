package tornado.drivers.opencl.graal.compiler;

import com.oracle.graal.asm.Assembler;
import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.compiler.common.cfg.Loop;
import com.oracle.graal.compiler.common.spi.ForeignCallsProvider;
import com.oracle.graal.lir.LIRInstruction.OperandFlag;
import com.oracle.graal.lir.LIRInstruction.OperandMode;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.lir.asm.DataBuilder;
import com.oracle.graal.lir.asm.FrameContext;
import com.oracle.graal.lir.framemap.FrameMap;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.LoopBeginNode;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.cfg.ControlFlowGraph;
import com.oracle.graal.nodes.extended.SwitchNode;
import java.util.*;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;
import tornado.common.exceptions.TornadoInternalError;
import tornado.drivers.opencl.graal.asm.OCLAssembler;
import tornado.drivers.opencl.graal.lir.OCLControlFlow;
import tornado.drivers.opencl.graal.lir.OCLControlFlow.LoopConditionOp;
import tornado.drivers.opencl.graal.lir.OCLControlFlow.LoopInitOp;
import tornado.drivers.opencl.graal.lir.OCLControlFlow.LoopPostOp;
import tornado.drivers.opencl.graal.lir.OCLControlFlow.SwitchOp;
import tornado.drivers.opencl.graal.lir.OCLLIRInstruction.AssignStmt;

import static tornado.common.exceptions.TornadoInternalError.shouldNotReachHere;
import static tornado.graal.TornadoLIRGenerator.trace;

public class OCLCompilationResultBuilder extends CompilationResultBuilder {

    protected LIR lir;
    protected int currentBlockIndex;
    protected final Set<ResolvedJavaMethod> nonInlinedMethods;
    protected boolean isKernel;

    public OCLCompilationResultBuilder(CodeCacheProvider codeCache,
            ForeignCallsProvider foreignCalls, FrameMap frameMap,
            Assembler asm, DataBuilder dataBuilder, FrameContext frameContext,
            OCLCompilationResult compilationResult) {
        super(codeCache, foreignCalls, frameMap, asm, dataBuilder, frameContext,
                compilationResult, new IdentityHashMap<>());
        nonInlinedMethods = new HashSet<>();
    }

    public void setKernel(boolean value) {
        isKernel = value;
    }

    public boolean isKernel() {
        return isKernel;
    }

    public OCLAssembler getAssembler() {
        return (OCLAssembler) asm;
    }

    public void addNonInlinedMethod(ResolvedJavaMethod method) {
        if (!nonInlinedMethods.contains(method)) {
            nonInlinedMethods.add(method);
        }
    }

    public Set<ResolvedJavaMethod> getNonInlinedMethods() {
        return nonInlinedMethods;
    }

    /**
     * Emits code for {@code lir} in its {@linkplain LIR#codeEmittingOrder()
     * code emitting order}.
     */
    @Override
    public void emit(@SuppressWarnings("hiding") LIR lir) {
        assert this.lir == null;
        assert currentBlockIndex == 0;
        this.lir = lir;
        this.currentBlockIndex = 0;
        frameContext.enter(this);

        // info("CFG: class=%s",lir.getControlFlowGraph().getClass().getName());
        ControlFlowGraph cfg = (ControlFlowGraph) lir.getControlFlowGraph();

        // Set<AbstractBlockBase<?>> blocks = new
        // HashSet<AbstractBlockBase<?>>();
        // Set<AbstractBlockBase<?>> pending = new
        // HashSet<AbstractBlockBase<?>>();
        // blocks.addAll(cfg.getBlocks());
        trace("Traversing CFG");

        Set<Block> floating = new HashSet<>();

        cfg.computePostdominators();
        traverseCFG(cfg, (OCLAssembler) asm, floating, cfg.getStartBlock());

        trace("Finished traversing CFG");
        this.lir = null;
        this.currentBlockIndex = 0;

    }

    @Override
    public void finish() {
        int position = asm.position();
        compilationResult.setTargetCode(asm.close(true), position);

        closeCompilationResult();
    }

    private String toString(Collection<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ ");
        for (Block b : blocks) {
            sb.append(b.getId() + " ");
        }
        sb.append("]");
        return sb.toString();
    }

    private void traverseCFG(ControlFlowGraph cfg, OCLAssembler asm,
            Set<Block> merges, Block b) {
        final List<Block> dominates = b.getDominated();

        final int numDominated = dominates.size();

        // System.out.printf("schedule: block=%s, succs=%s, dominates=%s, floating=%s, pdom=%s\n",b.getId(),toString(b.getSuccessors()),toString(dominates),toString(merges),b.getPostdominator());
        if (b.isLoopEnd()) {
            patchLoopEnd(b);
        }

        if (!b.isLoopHeader()) {
            emitBlock(b);
        }

        if (numDominated == 1) {
            traverseCFG(cfg, asm, merges, dominates.get(0));
        } else if (b.isLoopHeader()) {
            // TornadoInternalError.guarantee(b.getLoop().getExits().size() ==
            // 1,
            // "Loop has multiple exits! block=%d", b.getId());
            // TornadoInternalError.guarantee(dominates.size() == 2,
            // "Loop header dominates multiple blocks! block=%d", b.getId());

            final Loop<Block> loop = b.getLoop();

            // more than one exit means that there are break statements inside
            // the loopbody...
            Block exit = null;
            if (loop.getExits().size() > 1) {
                // multiple exists should converge at a merge node
                exit = loop.getExits().get(0);
                Set<Block> successors = new HashSet<>();
                successors.addAll(dominates);
                successors.removeAll(loop.getExits());
                successors.removeAll(loop.getBlocks());
                // System.out.printf("exit: block=%s, succ=%d, pred=%d\n",exit,exit.getSuccessorCount(),exit.getPredecessorCount());
                // System.out.printf("exit: dominates %s\n",toString(dominates));
                // System.out.printf("exit: loop blocks %s\n",toString(loop.getBlocks()));
                if (successors.size() == 1) {
                    exit = successors.iterator().next();
                }
                TornadoInternalError.guarantee(
                        exit.getBeginNode() instanceof AbstractMergeNode,
                        "loop exists do not converge: block=%d", b.getId());
            } else {
                exit = loop.getExits().get(0);
            }

            final boolean inverted = (dominates.get(0).equals(exit));
            final Block body = (inverted) ? dominates.get(1) : dominates.get(0);

            TornadoInternalError.guarantee(loop.numBackedges() == 1,
                    "loop at %s has %d backedges", b, loop.numBackedges());
            final Block backedge = cfg.blockFor(((LoopBeginNode) b
                    .getBeginNode()).loopEnds().first());

            patchLoopStms(b, body, backedge);
            emitBlock(b);

            asm.beginScope();
            traverseCFG(cfg, asm, merges, body);
            asm.endScope();

            // System.out.printf("backedge: %s\n",backedge);
            // System.out.printf("loop exit: exit=%s, exit count=%d\n",exit,b.getLoop().getExits().size());
            traverseCFG(cfg, asm, merges, exit);
        } else if (isIfBlock(b)) {
            // System.out.printf("branch: succ=%s, doms=%s\n",toString(b.getSuccessors()),toString(dominates));

            Block trueBranch = dominates.get(0);
            Block falseBranch = dominates.get(1);

            boolean emitMerge = false;
            Block mergeBlock = null;
            if (dominates.size() == 3) {
                mergeBlock = dominates.get(2);
                if (!merges.contains(mergeBlock)) {
                    merges.add(mergeBlock);
                    emitMerge = true;
                }
            }

            asm.beginScope();
            traverseCFG(cfg, asm, merges, trueBranch);
            asm.endScope();
            asm.indent();
            asm.elseStmt();
            asm.eol();
            asm.beginScope();
            traverseCFG(cfg, asm, merges, falseBranch);
            asm.endScope();

            if (emitMerge) {
                merges.remove(mergeBlock);
                traverseCFG(cfg, asm, merges, mergeBlock);
            }
        } else if (b.getSuccessorCount() == 1
                && !merges.contains(b.getSuccessors()[0])) {
            final Block successor = b.getSuccessors()[0];
            if (isMergeBlock(successor) && successor.isLoopEnd()) {
                emitBlock(successor);
            }
            // System.out.println("fallthrough");
            // TornadoInternalError.shouldNotReachHere();
        } else if (isSwitchBlock(b)) {

            final SwitchNode sw = (SwitchNode) b.getEndNode();

            final List<LIRInstruction> insns = lir.getLIRforBlock(b);
            final SwitchOp switchOp = (SwitchOp) insns.get(insns.size() - 1);

            asm.beginScope();

            for (int i = 0; i < switchOp.getKeyTargets().length; i++) {
                Block targetBlock = cfg.blockFor(sw.blockSuccessor(i));
                shouldNotReachHere();
                //patchCaseBlock(lir, switchOp.getKeyConstants()[i], targetBlock);
                emitBlock(targetBlock);
                // System.out.printf("target: %s\n",targetBlock);
            }

            if (sw.defaultSuccessor() != null) {
                Block targetBlock = cfg.blockFor(sw.defaultSuccessor());
                patchDefaultCaseBlock(lir, targetBlock);
                emitBlock(targetBlock);
                // System.out.printf("target: %s\n",targetBlock);
            }

            asm.popIndent();

            asm.endScope();

            Set<Block> successors = new HashSet<>();
            for (Block successor : b.getSuccessors()) {
                successors.add(successor);
            }
//            successors.addAll(b.getSuccessors());

            Block exit = null;
            if (successors.size() == 1) {
                exit = successors.iterator().next();
            } else {
                successors.removeAll(dominates);
                exit = successors.iterator().next();
            }

            traverseCFG(cfg, asm, merges, exit);

        }

    }

    private void patchLoopEnd(Block b) {
        // final List<LIRInstruction> insns = lir.getLIRforBlock(b);
        // int index = insns.size() - 1;
        // LIRInstruction current = insns.get(index);
        // while(!(current instanceof LoopEndOp)){
        // index --;
        // current = insns.get(index);
        // }
        //
        // if(index != insns.size() - 1){
        // insns.add(insns.remove(index));
        // }

    }

    private void patchDefaultCaseBlock(LIR lir, Block block) {
        final List<LIRInstruction> insns = lir.getLIRforBlock(block);
        if (insns.get(1) instanceof OCLControlFlow.CaseOp) {
            insns.remove(1);
            insns.remove(insns.size() - 1);
        }

        insns.add(1, new OCLControlFlow.DefaultCaseOp());
    }

    private void patchCaseBlock(LIR lir, ConstantValue key, Block block) {
        final List<LIRInstruction> insns = lir.getLIRforBlock(block);
        insns.add(1, new OCLControlFlow.CaseOp(key));
        insns.add(new OCLControlFlow.CaseBreakOp());

    }

    private static boolean isIfBlock(Block block) {
        return block.getEndNode() instanceof IfNode;
    }

    private static boolean isSwitchBlock(Block block) {
        return block.getEndNode() instanceof SwitchNode;
    }

    private static SwitchNode getSwitchNode(Block block) {
        return (SwitchNode) block.getEndNode();
    }

    private static boolean isMergeBlock(Block block) {
        return block.getBeginNode() instanceof AbstractMergeNode;
    }

    private void patchLoopStms(Block header, Block body, Block backedge) {

        final List<LIRInstruction> headerInsns = lir.getLIRforBlock(header);
        final List<LIRInstruction> bodyInsns = lir.getLIRforBlock(backedge);

        formatLoopHeader(headerInsns);

        migrateInsnToBody(headerInsns, bodyInsns);

    }

    private void migrateInsnToBody(List<LIRInstruction> header,
            List<LIRInstruction> body) {

        // build up set of IVs
        // final Set<AllocatableValue> ivs = new HashSet<AllocatableValue>();
        //
        // for (int i = 1; i < header.size(); i++) {
        // final LIRInstruction insn = header.get(i);
        // if (insn instanceof LoopInitOp) {
        // break;
        // } else if (insn instanceof OCLLIRInstruction.AssignStmt) {
        // final OCLLIRInstruction.AssignStmt assign =
        // (OCLLIRInstruction.AssignStmt) insn;
        // ivs.add(assign.getResult());
        // }
        // }
        // move all insns past the loop expression into the loop body
        int index = header.size() - 1;
        int insertAt = body.size() - 1;

        LIRInstruction current = header.get(index);
        while (!(current instanceof LoopConditionOp)) {
            // System.out.printf("moving: %s from blokc %s to block %s...\n",current,
            // header, body);
            if (!(current instanceof LoopPostOp)) {
                body.add(insertAt, header.remove(index));
            }

            index--;
            current = header.get(index);
        }

        // move all insns which do not update ivs into the loop body
        // current = header.get(index);
        // while(!(current instanceof LoopConditionOp)){
        // if(current instanceof OCLLIRInstruction.AssignStmt){
        // final OCLLIRInstruction.AssignStmt assign =
        // (OCLLIRInstruction.AssignStmt) current;
        // if(!ivs.contains(assign.getResult())){
        // body.add(insertAt, header.remove(index));
        // }
        // }
        // index--;
        // current = header.get(index);
        // }
    }

    private static class DepFinder implements InstructionValueProcedure {

        private final Set<Value> dependencies;

        public DepFinder(final Set<Value> dependencies) {
            this.dependencies = dependencies;
        }

        @Override
        public Value doValue(LIRInstruction instruction, Value value,
                OperandMode mode, EnumSet<OperandFlag> flags) {
            // System.out.printf("dep: insn=%s, value=%s\n",instruction,value);
            if (value instanceof Variable) {
                dependencies.add(value);
            }

            return value;
        }

        public Set<Value> getDependencies() {
            return dependencies;
        }

    }

    private static void formatLoopHeader(List<LIRInstruction> instructions) {
        int index = instructions.size() - 1;

        LIRInstruction condition = instructions.get(index);
        while (!(condition instanceof LoopConditionOp)) {
            index--;
            condition = instructions.get(index);
        }

        instructions.remove(index);

        final Set<Value> dependencies = new HashSet<>();
        DepFinder df = new DepFinder(dependencies);
        condition.forEachInput(df);

        // for(Value insn : dependencies){
        // System.out.printf("dep: %s\n",insn);
        // }
        index--;
        final List<LIRInstruction> moved = new ArrayList<>();
        LIRInstruction insn = instructions.get(index);
        while (!(insn instanceof LoopPostOp)) {
            if (insn instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) insn;
                if (assign.getResult() instanceof Variable) {
                    Variable var = (Variable) assign.getResult();
                    if (dependencies.contains(var)) {
                        moved.add(instructions.remove(index));
                    }
                }
            }
            index--;
            insn = instructions.get(index);
        }

        LIRInstruction loopInit = instructions.get(instructions.size() - 1);
        while (!(loopInit instanceof LoopInitOp)) {
            index--;
            loopInit = instructions.get(index);
        }

        instructions.add(index + 1, condition);
        instructions.addAll(index - 1, moved);

    }

    public void emitBlock(AbstractBlockBase<?> block) {
        if (block == null) {
            return;
        }

        ((OCLAssembler) asm).emitLine("// BLOCK %d", block.getId());

        if (PrintLIRWithAssembly.getValue()) {
            blockComment(String.format("block B%d %s", block.getId(),
                    block.getLoop()));
        }

        LIRInstruction breakInst = null;
        for (LIRInstruction op : lir.getLIRforBlock(block)) {
            if (op == null) {
                continue;
            } else if (op instanceof OCLControlFlow.LoopBreakOp) {
                breakInst = op;
                continue;
            }

            if (PrintLIRWithAssembly.getValue()) {
                blockComment(String.format("%d %s", op.id(), op));
            }

            try {
                emitOp(this, op);
            } catch (TornadoInternalError e) {
                throw e.addContext("lir instruction", block + "@" + op.id()
                        + " " + op + "\n");
            }
        }

        // because of the way Graal handles Phi nodes, we generate the break
        // instruction
        // before any phi nodes are updated, therefore we need to ensure that
        // the break
        // is emitted as the end of the block.
        if (breakInst != null) {
            try {
                emitOp(this, breakInst);
            } catch (TornadoInternalError e) {
                throw e.addContext("lir instruction",
                        block + "@" + breakInst.id() + " " + breakInst + "\n");
            }
        }

    }

    private static void emitOp(CompilationResultBuilder crb, LIRInstruction op) {
        try {
            trace("op: " + op);
            op.emitCode(crb);
        } catch (AssertionError | RuntimeException t) {
            throw new TornadoInternalError(t);
        }
    }

}
