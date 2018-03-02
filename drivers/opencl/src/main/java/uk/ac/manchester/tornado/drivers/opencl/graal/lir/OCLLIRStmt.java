/*
 * Copyright (c) 2018, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.opencl.graal.lir;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLTernaryIntrinsic;
import uk.ac.manchester.tornado.drivers.opencl.graal.compiler.OCLCompilationResultBuilder;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLUnary.MemoryAccess;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLUnary.OCLAddressCast;

public class OCLLIRStmt {

    protected static abstract class AbstractInstruction extends LIRInstruction {

        protected AbstractInstruction(LIRInstructionClass<? extends AbstractInstruction> c) {
            super(c);
        }

        @Override
        public final void emitCode(CompilationResultBuilder crb) {
            emitCode((OCLCompilationResultBuilder) crb, (OCLAssembler) crb.asm);
        }

        public abstract void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm);

    }

    @Opcode("ASSIGN")
    public static class AssignStmt extends AbstractInstruction {

        public static final LIRInstructionClass<AssignStmt> TYPE = LIRInstructionClass.create(AssignStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected Value rhs;

        public AssignStmt(AllocatableValue lhs, Value rhs) {
            super(TYPE);
            this.lhs = lhs;
            this.rhs = rhs;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            if (rhs instanceof OCLLIROp) {
                ((OCLLIROp) rhs).emit(crb, asm);
            } else {
                asm.emitValue(crb, rhs);
            }
            asm.delimiter();
            asm.eol();
        }

        public AllocatableValue getResult() {
            return lhs;
        }

        public Value getExpr() {
            return rhs;
        }
    }

    @Opcode("MOVE")
    public static class MoveStmt extends AbstractInstruction {

        public static final LIRInstructionClass<MoveStmt> TYPE = LIRInstructionClass.create(MoveStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected Value rhs;

        public MoveStmt(AllocatableValue lhs, Value rhs) {
            super(TYPE);
            this.lhs = lhs;
            this.rhs = rhs;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        public AllocatableValue getResult() {
            return lhs;
        }

        public Value getExpr() {
            return rhs;
        }
    }

    @Opcode("LOAD")
    public static class LoadStmt extends AbstractInstruction {

        public static final LIRInstructionClass<LoadStmt> TYPE = LIRInstructionClass.create(LoadStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;

        public LoadStmt(AllocatableValue lhs, OCLAddressCast cast, MemoryAccess address) {
            super(TYPE);
            this.lhs = lhs;
            this.cast = cast;
            this.address = address;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        public AllocatableValue getResult() {
            return lhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("VLOAD")
    public static class VectorLoadStmt extends AbstractInstruction {

        public static final LIRInstructionClass<VectorLoadStmt> TYPE = LIRInstructionClass.create(VectorLoadStmt.class);

        @Def
        protected AllocatableValue lhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;

        @Use
        protected Value index;

        protected OCLBinaryIntrinsic op;

        public VectorLoadStmt(AllocatableValue lhs, OCLBinaryIntrinsic op, Value index, OCLAddressCast cast, MemoryAccess address) {
            super(TYPE);
            this.lhs = lhs;
            this.cast = cast;
            this.address = address;
            this.op = op;
            this.index = index;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            asm.emitValue(crb, lhs);
            asm.space();
            asm.assign();
            asm.space();
            asm.emit(op.toString());
            asm.emit("(");
            asm.emitValue(crb, index);
            asm.emit(", ");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        public Value getResult() {
            return lhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }

        public OCLBinaryIntrinsic getOp() {
            return op;
        }
    }

    @Opcode("STORE")
    public static class StoreStmt extends AbstractInstruction {

        public static final LIRInstructionClass<StoreStmt> TYPE = LIRInstructionClass.create(StoreStmt.class);

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;

        public StoreStmt(OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();

            //asm.space();
            asm.emit("*(");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
            asm.emit(")");
            asm.space();
            asm.assign();
            asm.space();
            asm.emitValue(crb, rhs);
            asm.delimiter();
            asm.eol();
        }

        public Value getRhs() {
            return rhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }
    }

    @Opcode("VSTORE")
    public static class VectorStoreStmt extends AbstractInstruction {

        public static final LIRInstructionClass<VectorStoreStmt> TYPE = LIRInstructionClass.create(VectorStoreStmt.class);

        @Use
        protected Value rhs;
        @Use
        protected OCLAddressCast cast;
        @Use
        protected MemoryAccess address;
        @Use
        protected Value index;

        protected OCLTernaryIntrinsic op;

        public VectorStoreStmt(OCLTernaryIntrinsic op, Value index, OCLAddressCast cast, MemoryAccess address, Value rhs) {
            super(TYPE);
            this.rhs = rhs;
            this.cast = cast;
            this.address = address;
            this.op = op;
            this.index = index;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();

            //asm.space();
            asm.emit(op.toString());
            asm.emit("(");
            asm.emitValue(crb, rhs);
            asm.emit(", ");
            asm.emitValue(crb, index);
            asm.emit(", ");
            cast.emit(crb, asm);
            asm.space();
            address.emit(crb, asm);
//            if (address instanceof MemoryAccess) {
//                ((MemoryAccess) address).emit(crb);
//            } else if (address instanceof Variable) {
//                asm.emitValue(crb, address);
//            }
            asm.emit(")");
            asm.delimiter();
            asm.eol();
        }

        public Value getRhs() {
            return rhs;
        }

        public OCLAddressCast getCast() {
            return cast;
        }

        public MemoryAccess getAddress() {
            return address;
        }

        public Value getIndex() {
            return index;
        }

        public OCLTernaryIntrinsic getOp() {
            return op;
        }
    }

    @Opcode("EXPR")
    public static class ExprStmt extends AbstractInstruction {

        public static final LIRInstructionClass<ExprStmt> TYPE = LIRInstructionClass.create(ExprStmt.class);

        @Use
        protected Value expr;

        public ExprStmt(OCLLIROp expr) {
            super(TYPE);
            this.expr = expr;
        }

        @Override
        public void emitCode(OCLCompilationResultBuilder crb, OCLAssembler asm) {
            asm.indent();
            if (expr instanceof OCLLIROp) {
                ((OCLLIROp) expr).emit(crb, asm);
            } else {
                asm.emitValue(crb, expr);
            }
            asm.delimiter();
            asm.eol();
        }

        public Value getExpr() {
            return expr;
        }
    }

}