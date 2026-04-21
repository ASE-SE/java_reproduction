package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

public class MethodTreeNode {
    // node不一定存在
    protected MethodDeclaration node = null;
    // binding保证非null
    protected IMethodBinding binding;
    protected List<ThrowExceptionInfo> throwExceptionInfo = new ArrayList<>();

    public MethodTreeNode() {}
    public MethodTreeNode(IMethodBinding binding) {
        this.binding = binding;
    }

    public MethodDeclaration getNode() {
        return node;
    }

    public IMethodBinding getBinding() {
        return binding;
    }

    public void setNode(MethodDeclaration node) {
        this.node = node;
    }

    public List<ThrowExceptionInfo> getThrowExceptionInfo() {
        return throwExceptionInfo;
    }

    public void setThrowExceptionInfo(List<ThrowExceptionInfo> throwExceptionInfo) {
        this.throwExceptionInfo = throwExceptionInfo;
    }

    public String getQualifiedName() {
        return Util.generateMethodQualifiedName(binding);
    }

    public String toString() {
        return getQualifiedName();
    }

    public String getSimpleName() {
        return binding.getName();
    }

    public String getCode() {
        if (node == null) {
            return "";
        }
        return node.toString();
    }

}
