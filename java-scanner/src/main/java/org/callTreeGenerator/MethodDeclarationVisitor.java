package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.*;

import java.util.*;


/**
 * 在调用树构建过程中，对首次被访问的MethodDeclaration进行处理的Visitor
 * 处理内容：
 *   Visit对象是文件级CompilationUnit，先寻找并进入指定的MethodDeclaration
 *   在记录try-catch嵌套情况的同时记录方法定义中的全部有Binding的方法调用，记录在bindingLeafs中
 *   在记录try-catch嵌套情况的同时记录方法定义中的全部由throw语句形成会向上抛出的RuntimeException，记录在throwExceptionInfos中
 *   在记录try-catch嵌套情况的同时记录方法定义中的全部由常规语句形成的5种RuntimeException，记录在exceptionThrows中 todo 未实现
 *   对遍历过程中遇到的每个Throwable子类，对其进行上溯，生成可记录至exceptionCharacteristicManager中的对应数据
 */
public class MethodDeclarationVisitor extends ASTVisitor {
    private IMethodBinding targetMethodBinding;
    private boolean inTargetMethodDeclarationFlag = false;
    private List<ThrowExceptionInfo> throwExceptionInfos = new ArrayList<>();
    private List<List<CatchClause>> catchClauses = new ArrayList<>();
    private int blockDepth = 0;
    private List<Integer> tryBodyStack = new ArrayList<>();
    private MethodDeclaration methodDeclarationNode = null;
    private Map<IMethodBinding, List<TryCatchNestingInfo>> bindingLeafs = new HashMap<>();
    // localExceptions<项目定义异常全名,对应父异常全名>
    private Map<String, String> localExceptions = new HashMap<>();
    private ThrowStatement inThrowStatementNode = null;
    private List<IfStatement> inIfSeatementStack = new ArrayList<>();
    private List<CatchClause> inCatchClauseStack = new ArrayList<>();

    public MethodDeclarationVisitor(IMethodBinding targetMethodBinding) {
        this.targetMethodBinding = targetMethodBinding;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        if (node.resolveBinding() != null) {
            if (Util.generateMethodQualifiedName(node.resolveBinding().getMethodDeclaration())
                    .equals(Util.generateMethodQualifiedName(targetMethodBinding))) {
                // 锁定目标方法的方法声明
                methodDeclarationNode = node;
                inTargetMethodDeclarationFlag = true;
            }
        }
        return true;
    }

    @Override
    public void endVisit(MethodDeclaration node) {
        if (node.resolveBinding() != null) {
            if (Util.generateMethodQualifiedName(node.resolveBinding().getMethodDeclaration())
                    .equals(Util.generateMethodQualifiedName(targetMethodBinding))) {
                inTargetMethodDeclarationFlag = false;
            }
        }
    }

    @Override
    public boolean visit(ThrowStatement node) {
        if (!inTargetMethodDeclarationFlag) return true;
        inThrowStatementNode = node;
        ITypeBinding typeBinding = node.getExpression().resolveTypeBinding();
        if (typeBinding != null) {
            String name = typeBinding.getQualifiedName();
        }
        return true;
    }

    @Override
    public void endVisit(ThrowStatement node) {
        if (!inTargetMethodDeclarationFlag) return;
        inThrowStatementNode = null;
    }

    @Override
    public boolean visit(IfStatement node) {
        if (!inTargetMethodDeclarationFlag) return true;
        inIfSeatementStack.add(node);
        return true;
    }

    @Override
    public void endVisit(IfStatement node) {
        if (!inTargetMethodDeclarationFlag) return;
        inIfSeatementStack.remove(inIfSeatementStack.size() - 1);
    }

    @Override
    public boolean visit(CatchClause node) {
        if (!inTargetMethodDeclarationFlag) return true;
        inCatchClauseStack.add(node);
        IVariableBinding variableBinding = node.getException().resolveBinding();
        if (variableBinding != null) {
            ITypeBinding exceptionTypeBinding = variableBinding.getType();
            if (exceptionTypeBinding != null) {
                if (!ExceptionCharacteristicManager.isArchived(exceptionTypeBinding.getQualifiedName())) {
                    // 对于新访问到的项目定义exception，记录其相关信息至localExceptions等待后续记录
                    recordLocalExceptionAndParent(exceptionTypeBinding);
                }
            }
        }
        return true;
    }
    private void recordLocalExceptionAndParent(ITypeBinding exceptionTypeBinding) {
        ITypeBinding exceptionParentTypeBinding = exceptionTypeBinding.getSuperclass();
        if (exceptionParentTypeBinding == null) {
            return;
        }
        localExceptions.put(exceptionTypeBinding.getQualifiedName(), exceptionParentTypeBinding.getQualifiedName());
        if (!ExceptionCharacteristicManager.isArchived(exceptionParentTypeBinding.getQualifiedName())) {
            recordLocalExceptionAndParent(exceptionParentTypeBinding);
        }
    }

    @Override
    public void endVisit(CatchClause node) {
        if (!inTargetMethodDeclarationFlag) return;
        inCatchClauseStack.remove(inCatchClauseStack.size() - 1);
    }

    public boolean visit(ClassInstanceCreation node) {
        if (!inTargetMethodDeclarationFlag) return true;
        if (inThrowStatementNode == null) return true;
        // 在抛出异常的语句中，对抛出行为进行记录
        IMethodBinding methodBinding = node.resolveConstructorBinding();
        if (methodBinding == null) return true;
        ITypeBinding typeBinding = methodBinding.getDeclaringClass();
        if (typeBinding == null) return true;
        // 对于新访问到的项目定义exception，记录其相关信息至localExceptions等待后续记录
        recordLocalExceptionAndParent(typeBinding);
        // 记录throw的new Exception信息
        IfStatement ifStatement = null;
        if (!inIfSeatementStack.isEmpty()) ifStatement = inIfSeatementStack.get(inIfSeatementStack.size() - 1);
        CatchClause catchClause = null;
        if (!inCatchClauseStack.isEmpty()) catchClause = inCatchClauseStack.get(inCatchClauseStack.size() - 1);
        throwExceptionInfos.add(new ThrowExceptionInfo(
                inThrowStatementNode,
                node,
                ifStatement,
                catchClause
        ));
        return true;
    }

    @Override
    public boolean visit(MethodInvocation node) {
        if (!inTargetMethodDeclarationFlag) return true;
        IMethodBinding methodBinding = node.resolveMethodBinding();
        IMethodBinding methodDeclarationBinding;
        if (methodBinding == null) {
            return true;
        } else {
            methodDeclarationBinding = methodBinding.getMethodDeclaration();
            if (methodDeclarationBinding == null) {
                return true;
            }
        }
        // 记录所有方法调用及调用时的try嵌套情况
        Set<CatchClause> nowCatchClauses = getCatchClauseSet();
        if (nowCatchClauses.isEmpty()) {
            if (bindingLeafs.containsKey(methodDeclarationBinding)) {
                bindingLeafs.get(methodDeclarationBinding).add(new TryCatchNestingInfo());
            } else {
                List<TryCatchNestingInfo> tryCatchNestingInfos = new ArrayList<>();
                tryCatchNestingInfos.add(new TryCatchNestingInfo());
                bindingLeafs.put(methodDeclarationBinding, tryCatchNestingInfos);
            }
        } else {
            if (bindingLeafs.containsKey(methodDeclarationBinding)) {
                bindingLeafs.get(methodDeclarationBinding).add(new TryCatchNestingInfo(nowCatchClauses));
            } else {
                List<TryCatchNestingInfo> tryCatchNestingInfos = new ArrayList<>();
                tryCatchNestingInfos.add(new TryCatchNestingInfo(nowCatchClauses));
                bindingLeafs.put(methodDeclarationBinding, tryCatchNestingInfos);
            }

        }
        return true;
    }
    private Set<CatchClause> getCatchClauseSet() {
        Set<CatchClause> nowCatchClauses = new HashSet<>();
        for (List<CatchClause> ccs: catchClauses) {
            nowCatchClauses.addAll(ccs);
        }
        return nowCatchClauses;
    }

    @Override
    public boolean visit(TryStatement node) {
        if (!inTargetMethodDeclarationFlag) return true;
        // 进入tryStatement时进行记录，后续判断交由visit Block进行处理
        catchClauses.add(node.catchClauses());
        tryBodyStack.add(Integer.valueOf(blockDepth));
        return true;
    }

    @Override
    public boolean visit(Block node) {
        if (!inTargetMethodDeclarationFlag) return true;
        blockDepth += 1;
        return true;
    }

    @Override
    public void endVisit(Block node) {
        if (!inTargetMethodDeclarationFlag) return;
        blockDepth -= 1;
        if (!tryBodyStack.isEmpty() && tryBodyStack.get(tryBodyStack.size() - 1).equals(blockDepth)) {
            tryBodyStack.remove(tryBodyStack.size() - 1);
            catchClauses.remove(catchClauses.size() - 1);
        }
    }

    public MethodDeclaration getMethodDeclarationNode() {
        return methodDeclarationNode;
    }

    public Map<IMethodBinding, List<TryCatchNestingInfo>> getBindingLeafs() {
        return bindingLeafs;
    }

    public Map<String, String> getLocalExceptions() {
        return localExceptions;
    }

    public List<ThrowExceptionInfo> getThrowExceptionInfos() {
        return throwExceptionInfos;
    }
}
