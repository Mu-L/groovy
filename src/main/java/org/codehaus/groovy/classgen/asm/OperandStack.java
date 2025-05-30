/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.classgen.asm;

import org.apache.groovy.ast.tools.ClassNodeUtils;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.tools.WideningCategories;
import org.codehaus.groovy.classgen.ClassGeneratorException;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveBoolean;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveByte;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveChar;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveDouble;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveFloat;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveInt;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveLong;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveShort;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveVoid;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.D2F;
import static org.objectweb.asm.Opcodes.D2I;
import static org.objectweb.asm.Opcodes.D2L;
import static org.objectweb.asm.Opcodes.DCONST_0;
import static org.objectweb.asm.Opcodes.DCONST_1;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.DUP2;
import static org.objectweb.asm.Opcodes.DUP2_X1;
import static org.objectweb.asm.Opcodes.DUP2_X2;
import static org.objectweb.asm.Opcodes.DUP_X2;
import static org.objectweb.asm.Opcodes.F2D;
import static org.objectweb.asm.Opcodes.F2I;
import static org.objectweb.asm.Opcodes.F2L;
import static org.objectweb.asm.Opcodes.FCONST_0;
import static org.objectweb.asm.Opcodes.FCONST_1;
import static org.objectweb.asm.Opcodes.FCONST_2;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.I2B;
import static org.objectweb.asm.Opcodes.I2C;
import static org.objectweb.asm.Opcodes.I2D;
import static org.objectweb.asm.Opcodes.I2F;
import static org.objectweb.asm.Opcodes.I2L;
import static org.objectweb.asm.Opcodes.I2S;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.L2D;
import static org.objectweb.asm.Opcodes.L2F;
import static org.objectweb.asm.Opcodes.L2I;
import static org.objectweb.asm.Opcodes.LCONST_0;
import static org.objectweb.asm.Opcodes.LCONST_1;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.POP2;
import static org.objectweb.asm.Opcodes.SWAP;

public class OperandStack {

    private final List<ClassNode> stack = new ArrayList<>();
    private final WriterController controller;

    public OperandStack(final WriterController controller) {
        this.controller = controller;
    }

    public int getStackLength() {
        return stack.size();
    }

    public void popDownTo(final int elements) {
        int last = stack.size();
        MethodVisitor mv = controller.getMethodVisitor();
        while (last > elements) {
            last -= 1;
            ClassNode element = popWithMessage(last);
            if (isTwoSlotType(element)) {
                mv.visitInsn(POP2);
            } else {
                mv.visitInsn(POP);
            }
        }
    }

    private ClassNode popWithMessage(final int last) {
        try {
            return stack.remove(last);
        } catch (IndexOutOfBoundsException e) { //GROOVY-10458
            String method = controller.getMethodNode() != null
                    ? controller.getMethodNode().getTypeDescriptor()
                    : controller.getConstructorNode().getTypeDescriptor();
            throw new GroovyBugError("Error while popping argument from operand stack tracker in class " + controller.getClassName() + " method " + method + ".");
        }
    }

    /**
     * returns true for long and double
     */
    private static boolean isTwoSlotType(final ClassNode type) {
        return isPrimitiveLong(type) || isPrimitiveDouble(type);
    }

    /**
     * ensure last marked parameter on the stack is a primitive boolean
     * if mark==stack size, we assume an empty expression or statement.
     * was used and we will use the value given in emptyDefault as boolean
     * if mark==stack.size()-1 the top element will be cast to boolean using
     * Groovy truth.
     * In other cases we throw a GroovyBugError
     */
    public void castToBool(final int mark, final boolean emptyDefault) {
        int size = stack.size();
        MethodVisitor mv = controller.getMethodVisitor();
        if (mark == size) {
            // no element, so use emptyDefault
            if (emptyDefault) {
                mv.visitIntInsn(BIPUSH, 1);
            } else {
                mv.visitIntInsn(BIPUSH, 0);
            }
            stack.add(null);
        } else if (mark == size - 1) {
            ClassNode last = stack.get(size - 1);
            // nothing to do in that case
            if (isPrimitiveBoolean(last)) return;
            if (ClassHelper.isPrimitiveType(last)) {
                BytecodeHelper.convertPrimitiveToBoolean(mv, last);
            } else {
                controller.getInvocationWriter().castNonPrimitiveToBool(last);
            }
        } else {
            throw new GroovyBugError("operand stack contains " + size + " elements, but we expected only " + mark);
        }
        stack.set(mark, ClassHelper.boolean_TYPE);
    }

    /**
     * remove operand stack top element using bytecode pop
     */
    public void pop() {
        popDownTo(stack.size() - 1);
    }

    public Label jump(final int ifIns) {
        Label label = new Label();
        jump(ifIns,label);
        return label;
    }

    public void jump(final int ifIns, final Label label) {
        controller.getMethodVisitor().visitJumpInsn(ifIns, label);
        // remove the boolean from the operand stack tracker
        remove(1);
    }

    /**
     * duplicate top element
     */
    public void dup() {
        ClassNode type = getTopOperand();
        stack.add(type);
        MethodVisitor mv = controller.getMethodVisitor();
        if (isTwoSlotType(type)) {
            mv.visitInsn(DUP2);
        } else {
            mv.visitInsn(DUP);
        }
    }

    public ClassNode box() {
        MethodVisitor mv = controller.getMethodVisitor();
        int size = stack.size();
        ClassNode type = stack.get(size - 1);
        if (ClassHelper.isPrimitiveType(type) && !isPrimitiveVoid(type)) {
            ClassNode wrapper = ClassHelper.getWrapper(type);
            BytecodeHelper.doCastToWrappedType(mv, type, wrapper);
            type = wrapper;
        } // else nothing to box
        stack.set(size - 1, type);
        return type;
    }

    /**
     * Remove amount elements from the operand stack, without using pop.
     * For example after a method invocation
     */
    public void remove(final int amount) {
        int size = stack.size();
        for (int i = size - 1, n = size - 1 - amount; i > n; i -= 1) {
            popWithMessage(i);
        }
    }

    /**
     * push operand on stack
     */
    public void push(final ClassNode type) {
        stack.add(type);
    }

    /**
     * swap two top level operands
     */
    public void swap() {
        MethodVisitor mv = controller.getMethodVisitor();
        int size = stack.size();
        ClassNode b = stack.get(size - 1);
        ClassNode a = stack.get(size - 2);
        //        dup_x1:     ---
        //        dup_x2:     aab  -> baab
        //        dup2_x1:    abb  -> bbabb
        //        dup2_x2:    aabb -> bbaabb
        //        b = top element, a = element under b
        //        top element at right
        if (isTwoSlotType(a)) { // aa
            if (isTwoSlotType(b)) { // aabb
                // aabb -> bbaa
                mv.visitInsn(DUP2_X2);   // bbaabb
                mv.visitInsn(POP2);      // bbaa
            } else {
                // aab -> baa
                mv.visitInsn(DUP_X2);   // baab
                mv.visitInsn(POP);      // baa
            }
        } else { // a
            if (isTwoSlotType(b)) { //abb
                // abb -> bba
                mv.visitInsn(DUP2_X1);   // bbabb
                mv.visitInsn(POP2);      // bba
            } else {
                // ab -> ba
                mv.visitInsn(SWAP);
            }
        }
        stack.set(size - 1, a);
        stack.set(size - 2, b);
    }

    /**
     * replace top level element with new element of given type
     */
    public void replace(final ClassNode type) {
        int size = ensureStackNotEmpty(stack);
        stack.set(size - 1, type);
    }

    private int ensureStackNotEmpty(final List<ClassNode> stack) {
        int size = stack.size();

        try {
            if (size == 0) throw new ArrayIndexOutOfBoundsException("size==0");
        } catch (ArrayIndexOutOfBoundsException ai) {
            System.err.println("index problem in " + controller.getSourceUnit().getName());
            throw ai;
        }

        return size;
    }

    /**
     * replace n top level elements with new element of given type
     */
    public void replace(final ClassNode type, final int n) {
        remove(n);
        push(type);
    }

    /**
     * do Groovy cast for top level element
     */
    public void doGroovyCast(final ClassNode targetType) {
        doConvertAndCast(targetType, false);
    }

    public void doGroovyCast(final Variable v) {
        doConvertAndCast(v.getOriginType(), false);
    }

    public void doAsType(final ClassNode targetType) {
        doConvertAndCast(targetType,true);
    }

    private void throwExceptionForNoStackElement(final int size, final ClassNode targetType, final boolean coerce) {
        if (size > 0) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Internal compiler error while compiling ").append(controller.getSourceUnit().getName()).append("\n");
        MethodNode methodNode = controller.getMethodNode();
        if (methodNode!=null) {
            sb.append("Method: ");
            sb.append(methodNode);
            sb.append("\n");
        }
        ConstructorNode constructorNode = controller.getConstructorNode();
        if (constructorNode!=null) {
            sb.append("Constructor: ");
            sb.append(methodNode);
            sb.append("\n");
        }
        sb.append("Line ").append(controller.getLineNumber()).append(",");
        sb.append(" expecting ").append(coerce ? "coercion" : "casting").append(" to ").append(targetType.toString(false));
        sb.append(" but operand stack is empty");
        throw new ArrayIndexOutOfBoundsException(sb.toString());
    }

    private void doConvertAndCast(ClassNode targetType, final boolean coerce) {
        int size = stack.size();
        throwExceptionForNoStackElement(size, targetType, coerce);

        ClassNode top = stack.get(size - 1);
        targetType = targetType.redirect();
        if (top == targetType /* for better performance */
                || ClassNodeUtils.isCompatibleWith(top, targetType)) return;

        if (coerce) {
            controller.getInvocationWriter().coerce(top, targetType);
            return;
        }

        boolean primTarget = ClassHelper.isPrimitiveType(targetType);
        boolean primTop = ClassHelper.isPrimitiveType(top);

        if (primTop && primTarget) {
            // here we box and unbox to get the goal type
            if (convertPrimitive(top, targetType)) {
                replace(targetType);
                return;
            }
            box();
        } else if (primTarget) {
            // top is not primitive so unbox
            // leave that BH#doCast later
        } else {
            // top might be primitive, target is not
            // so let invocation writer box if needed and do groovy cast otherwise
            controller.getInvocationWriter().castToNonPrimitiveIfNecessary(top, targetType);
        }

        MethodVisitor mv = controller.getMethodVisitor();
        if (primTarget && !isPrimitiveBoolean(targetType)
                && !primTop && ClassHelper.getWrapper(targetType).equals(top)) {
            BytecodeHelper.doCastToPrimitive(mv, top, targetType);
        } else {
            top = stack.get(size - 1);
            if (!WideningCategories.implementsInterfaceOrSubclassOf(top, targetType)) {
                BytecodeHelper.doCast(mv,targetType);
            }
        }
        replace(targetType);
    }

    private boolean convertFromInt(final ClassNode target) {
        int convertCode;
        if (isPrimitiveChar(target)) {
            convertCode = I2C;
        } else if (isPrimitiveByte(target)) {
            convertCode = I2B;
        } else if (isPrimitiveShort(target)) {
            convertCode = I2S;
        } else if (isPrimitiveLong(target)) {
            convertCode = I2L;
        } else if (isPrimitiveFloat(target)) {
            convertCode = I2F;
        } else if (isPrimitiveDouble(target)) {
            convertCode = I2D;
        } else {
            return false;
        }
        controller.getMethodVisitor().visitInsn(convertCode);
        return true;
    }

    private boolean convertFromLong(final ClassNode target) {
        MethodVisitor mv = controller.getMethodVisitor();
        if (isPrimitiveInt(target)) {
            mv.visitInsn(L2I);
            return true;
        } else if (isPrimitiveChar(target)
                || isPrimitiveByte(target)
                || isPrimitiveShort(target)) {
            mv.visitInsn(L2I);
            return convertFromInt(target);
        } else if (isPrimitiveDouble(target)) {
            mv.visitInsn(L2D);
            return true;
        } else if (isPrimitiveFloat(target)) {
            mv.visitInsn(L2F);
            return true;
        }
        return false;
    }

    private boolean convertFromDouble(final ClassNode target) {
        MethodVisitor mv = controller.getMethodVisitor();
        if (isPrimitiveInt(target)) {
            mv.visitInsn(D2I);
            return true;
        } else if (isPrimitiveChar(target)
                || isPrimitiveByte(target)
                || isPrimitiveShort(target)) {
            mv.visitInsn(D2I);
            return convertFromInt(target);
        } else if (isPrimitiveLong(target)) {
            mv.visitInsn(D2L);
            return true;
        } else if (isPrimitiveFloat(target)) {
            mv.visitInsn(D2F);
            return true;
        }
        return false;
    }

    private boolean convertFromFloat(final ClassNode target) {
        MethodVisitor mv = controller.getMethodVisitor();
        if (isPrimitiveInt(target)) {
            mv.visitInsn(F2I);
            return true;
        } else if (isPrimitiveChar(target)
                || isPrimitiveByte(target)
                || isPrimitiveShort(target)) {
            mv.visitInsn(F2I);
            return convertFromInt(target);
        } else if (isPrimitiveLong(target)) {
            mv.visitInsn(F2L);
            return true;
        } else if (isPrimitiveDouble(target)) {
            mv.visitInsn(F2D);
            return true;
        }
        return false;
    }

    private boolean convertPrimitive(final ClassNode top, final ClassNode target) {
        if (top == target)
            return true;
        if (isPrimitiveInt(top)) {
            return convertFromInt(target);
        } else if (isPrimitiveChar(top)
                || isPrimitiveByte(top)
                || isPrimitiveShort(top)) {
            return isPrimitiveInt(target) || convertFromInt(target);
        } else if (isPrimitiveFloat(top)) {
            return convertFromFloat(target);
        } else if (isPrimitiveDouble(top)) {
            return convertFromDouble(target);
        } else if (isPrimitiveLong(top)) {
            return convertFromLong(target);
        }
        return false;
    }

    /**
     * load the constant on the operand stack.
     */
    public void pushConstant(final ConstantExpression expression) {
        MethodVisitor mv = controller.getMethodVisitor();
        Object value = expression.getValue();
        ClassNode exprType = expression.getType();
        ClassNode type = ClassHelper.getUnwrapper(exprType);
        boolean boxing = !exprType.equals(type);
        boolean primitive = boxing || ClassHelper.isPrimitiveType(type);

        if (value == null) {
            mv.visitInsn(ACONST_NULL);
            type = ClassHelper.OBJECT_TYPE;
        } else if (boxing && value instanceof Boolean) { // load static value
            String text = (Boolean) value ? "TRUE" : "FALSE";
            mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", text, "Ljava/lang/Boolean;");
            boxing = false;
            type = exprType;
        } else if (primitive) {
            pushPrimitiveConstant(mv, value, type);
        } else if (value instanceof BigDecimal) {
            newInstance(mv, value);
        } else if (value instanceof BigInteger) {
            newInstance(mv, value);
        } else if (value instanceof String) {
            mv.visitLdcInsn(value);
        } else {
            throw new ClassGeneratorException(
                    "Cannot generate bytecode for constant: " + value + " of type: " + type.getName());
        }

        push(type);
        if (boxing) box();
    }

    private static void newInstance(final MethodVisitor mv, final Object value) {
        String className = BytecodeHelper.getClassInternalName(value.getClass().getName());
        mv.visitTypeInsn(NEW, className);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(value.toString());
        mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "(Ljava/lang/String;)V", false);
    }

    private static void pushPrimitiveConstant(final MethodVisitor mv, final Object value, final ClassNode type) {
        boolean isInt = isPrimitiveInt(type);
        boolean isShort = isPrimitiveShort(type);
        boolean isByte = isPrimitiveByte(type);
        boolean isChar = isPrimitiveChar(type);
        if (isInt || isShort || isByte || isChar) {
            int val = isInt ? (Integer) value : isShort ? (Short) value : isChar ? (Character) value : (Byte) value;
            BytecodeHelper.pushConstant(mv, val);
        } else if (isPrimitiveLong(type)) {
            if ((Long) value == 0L) {
                mv.visitInsn(LCONST_0);
            } else if ((Long) value == 1L) {
                mv.visitInsn(LCONST_1);
            } else {
                mv.visitLdcInsn(value);
            }
        } else if (isPrimitiveFloat(type)) {
            // GROOVY-9797: Use Float.equals to differentiate between positive and negative zero
            if (value.equals(0f)) {
                mv.visitInsn(FCONST_0);
            } else if ((Float) value == 1f) {
                mv.visitInsn(FCONST_1);
            } else if ((Float) value == 2f) {
                mv.visitInsn(FCONST_2);
            } else {
                mv.visitLdcInsn(value);
            }
        } else if (isPrimitiveDouble(type)) {
            // GROOVY-9797: Use Double.equals to differentiate between positive and negative zero
            if (value.equals(0d)) {
                mv.visitInsn(DCONST_0);
            } else if ((Double) value == 1d) {
                mv.visitInsn(DCONST_1);
            } else {
                mv.visitLdcInsn(value);
            }
        } else if (isPrimitiveBoolean(type)) {
            boolean b = (Boolean) value;
            if (b) {
                mv.visitInsn(ICONST_1);
            } else {
                mv.visitInsn(ICONST_0);
            }
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public void pushDynamicName(final Expression name) {
        if (name instanceof ConstantExpression) {
            ConstantExpression ce = (ConstantExpression) name;
            Object value = ce.getValue();
            if (value instanceof String) {
                pushConstant(ce);
                return;
            }
        }
        new CastExpression(ClassHelper.STRING_TYPE, name).visit(controller.getAcg());
    }

    public void loadOrStoreVariable(final BytecodeVariable variable, final boolean useReferenceDirectly) {
        CompileStack compileStack = controller.getCompileStack();
        if (compileStack.isLHS()) {
            storeVar(variable);
        } else {
            MethodVisitor mv = controller.getMethodVisitor();
            int idx = variable.getIndex();
            ClassNode type = variable.getType();

            if (variable.isHolder()) {
                mv.visitVarInsn(ALOAD, idx);
                if (!useReferenceDirectly) {
                    mv.visitMethodInsn(INVOKEVIRTUAL, "groovy/lang/Reference", "get", "()Ljava/lang/Object;", false);
                    BytecodeHelper.doCast(mv, type);
                    push(type);
                } else {
                    push(ClassHelper.REFERENCE_TYPE);
                }
            } else {
                load(type, idx);
            }
        }
    }

    public void storeVar(final BytecodeVariable variable) {
        MethodVisitor mv = controller.getMethodVisitor();
        int idx = variable.getIndex();
        ClassNode type = variable.getType();
        // value is on stack
        if (variable.isHolder()) {
            doGroovyCast(type);
            box();
            mv.visitVarInsn(ALOAD, idx);
            mv.visitTypeInsn(CHECKCAST, "groovy/lang/Reference");
            mv.visitInsn(SWAP);
            mv.visitMethodInsn(INVOKEVIRTUAL, "groovy/lang/Reference", "set", "(Ljava/lang/Object;)V", false);
        } else {
            doGroovyCast(type);
            BytecodeHelper.store(mv, type, idx);
        }
        // remove RHS value from operand stack
        remove(1);
    }

    public void load(final ClassNode type, final int idx) {
        MethodVisitor mv = controller.getMethodVisitor();
        BytecodeHelper.load(mv, type, idx);
        push(type);
    }

    public void pushBool(final boolean value) {
        MethodVisitor mv = controller.getMethodVisitor();
        mv.visitInsn(value ? ICONST_1 : ICONST_0);
        push(ClassHelper.boolean_TYPE);
    }

    @Override
    public String toString() {
        return "OperandStack(size=" + stack.size() + ":" + stack.toString() + ")";
    }

    public ClassNode getTopOperand() {
        int size = ensureStackNotEmpty(stack);
        return stack.get(size - 1);
    }
}
