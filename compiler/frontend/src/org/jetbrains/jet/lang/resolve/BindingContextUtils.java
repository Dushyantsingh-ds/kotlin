/*
 * Copyright 2010-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve;

import com.google.common.collect.Lists;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.bindingContextUtil.BindingContextUtilPackage;
import org.jetbrains.jet.lang.resolve.calls.autocasts.DataFlowInfo;
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall;
import org.jetbrains.jet.lang.resolve.calls.model.VariableAsFunctionResolvedCall;
import org.jetbrains.jet.lang.resolve.source.SourcePackage;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.JetTypeInfo;
import org.jetbrains.jet.lang.types.TypeUtils;
import org.jetbrains.jet.util.slicedmap.ReadOnlySlice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.jetbrains.jet.lang.descriptors.CallableMemberDescriptor.Kind.DECLARATION;
import static org.jetbrains.jet.lang.descriptors.CallableMemberDescriptor.Kind.SYNTHESIZED;
import static org.jetbrains.jet.lang.diagnostics.Errors.AMBIGUOUS_LABEL;
import static org.jetbrains.jet.lang.resolve.BindingContext.*;

public class BindingContextUtils {
    private BindingContextUtils() {
    }

    @Nullable
    public static VariableDescriptor extractVariableDescriptorIfAny(@NotNull BindingContext bindingContext, @Nullable JetElement element, boolean onlyReference) {
        DeclarationDescriptor descriptor = null;
        if (!onlyReference && (element instanceof JetVariableDeclaration || element instanceof JetParameter)) {
            descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element);
        }
        else if (element instanceof JetSimpleNameExpression) {
            descriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, (JetSimpleNameExpression) element);
        }
        else if (element instanceof JetQualifiedExpression) {
            descriptor = extractVariableDescriptorIfAny(bindingContext, ((JetQualifiedExpression) element).getSelectorExpression(), onlyReference);
        }
        if (descriptor instanceof VariableDescriptor) {
            return (VariableDescriptor) descriptor;
        }
        return null;
    }

    @Nullable
    public static JetFile getContainingFile(@NotNull BindingContext context, @NotNull DeclarationDescriptor declarationDescriptor) {
        // declarationDescriptor may describe a synthesized element which doesn't have PSI
        // To workaround that, we find a top-level parent (which is inside a PackageFragmentDescriptor), which is guaranteed to have PSI
        DeclarationDescriptor descriptor = findTopLevelParent(declarationDescriptor);
        if (descriptor == null) return null;

        PsiElement declaration = descriptorToDeclaration(context, descriptor);
        if (declaration == null) return null;

        PsiFile containingFile = declaration.getContainingFile();
        if (!(containingFile instanceof JetFile)) return null;
        return (JetFile) containingFile;
    }

    @Nullable
    private static DeclarationDescriptor findTopLevelParent(@NotNull DeclarationDescriptor declarationDescriptor) {
        DeclarationDescriptor descriptor = declarationDescriptor;
        if (declarationDescriptor instanceof PropertyAccessorDescriptor) {
            descriptor = ((PropertyAccessorDescriptor) descriptor).getCorrespondingProperty();
        }
        while (!(descriptor == null || DescriptorUtils.isTopLevelDeclaration(descriptor))) {
            descriptor = descriptor.getContainingDeclaration();
        }
        return descriptor;
    }

    @Nullable
    private static PsiElement doGetDescriptorToDeclaration(@NotNull DeclarationDescriptor descriptor) {
        DeclarationDescriptor original = descriptor.getOriginal();
        if (!(original instanceof DeclarationDescriptorWithSource)) {
            return null;
        }
        return SourcePackage.getPsi(((DeclarationDescriptorWithSource) original).getSource());
    }

    // NOTE this is also used by KDoc
    @Nullable
    public static PsiElement descriptorToDeclaration(@Nullable BindingContext context, @NotNull DeclarationDescriptor descriptor) {
        if (descriptor instanceof CallableMemberDescriptor) {
            return callableDescriptorToDeclaration(context, (CallableMemberDescriptor) descriptor);
        }
        else if (descriptor instanceof ClassDescriptor) {
            return classDescriptorToDeclaration(context, (ClassDescriptor) descriptor);
        }
        else {
            return doGetDescriptorToDeclaration(descriptor);
        }
    }

    @NotNull
    public static List<PsiElement> descriptorToDeclarations(@NotNull BindingContext context, @NotNull DeclarationDescriptor descriptor) {
        if (descriptor instanceof CallableMemberDescriptor) {
            return callableDescriptorToDeclarations(context, (CallableMemberDescriptor) descriptor);
        }
        else {
            PsiElement psiElement = descriptorToDeclaration(context, descriptor);
            if (psiElement != null) {
                return Lists.newArrayList(psiElement);
            } else {
                return Lists.newArrayList();
            }
        }
    }

    @Nullable
    public static PsiElement callableDescriptorToDeclaration(@Nullable BindingContext context, @NotNull CallableMemberDescriptor callable) {
        if (callable.getKind() == DECLARATION || callable.getKind() == SYNTHESIZED) {
            return doGetDescriptorToDeclaration(callable);
        }
        //TODO: should not use this method for fake_override and delegation
        Set<? extends CallableMemberDescriptor> overriddenDescriptors = callable.getOverriddenDescriptors();
        if (overriddenDescriptors.size() == 1) {
            return callableDescriptorToDeclaration(overriddenDescriptors.iterator().next());
        }
        return null;
    }

    @NotNull
    public static List<PsiElement> callableDescriptorToDeclarations(
            @NotNull BindingContext context,
            @NotNull CallableMemberDescriptor callable
    ) {
        if (callable.getKind() == DECLARATION || callable.getKind() == SYNTHESIZED) {
            PsiElement psiElement = doGetDescriptorToDeclaration(callable);
            return psiElement != null ? Lists.newArrayList(psiElement) : Lists.<PsiElement>newArrayList();
        }

        List<PsiElement> r = new ArrayList<PsiElement>();
        Set<? extends CallableMemberDescriptor> overriddenDescriptors = callable.getOverriddenDescriptors();
        for (CallableMemberDescriptor overridden : overriddenDescriptors) {
            r.addAll(callableDescriptorToDeclarations(context, overridden));
        }
        return r;
    }

    @Nullable
    public static PsiElement classDescriptorToDeclaration(@NotNull BindingContext context, @NotNull ClassDescriptor clazz) {
        return doGetDescriptorToDeclaration(clazz);
    }

    public static void recordFunctionDeclarationToDescriptor(@NotNull BindingTrace trace,
            @NotNull PsiElement psiElement, @NotNull SimpleFunctionDescriptor function) {

        if (function.getKind() != DECLARATION) {
            throw new IllegalArgumentException("function of kind " + function.getKind() + " cannot have declaration");
        }

        trace.record(BindingContext.FUNCTION, psiElement, function);
    }

    @NotNull
    public static <K, V> V getNotNull(
        @NotNull BindingContext bindingContext,
        @NotNull ReadOnlySlice<K, V> slice,
        @NotNull K key
    ) {
        return getNotNull(bindingContext, slice, key, "Value at " + slice + " must not be null for " + key);
    }

    @NotNull
    public static <K, V> V getNotNull(
            @NotNull BindingContext bindingContext,
            @NotNull ReadOnlySlice<K, V> slice,
            @NotNull K key,
            @NotNull String messageIfNull
    ) {
        V value = bindingContext.get(slice, key);
        if (value == null) {
            throw new IllegalStateException(messageIfNull);
        }
        return value;
    }

    @NotNull
    public static DeclarationDescriptor getEnclosingDescriptor(@NotNull BindingContext context, @NotNull JetElement element) {
        JetNamedDeclaration declaration = PsiTreeUtil.getParentOfType(element, JetNamedDeclaration.class);
        if (declaration instanceof JetFunctionLiteral) {
            return getEnclosingDescriptor(context, declaration);
        }
        DeclarationDescriptor descriptor = context.get(DECLARATION_TO_DESCRIPTOR, declaration);
        assert descriptor != null : "No descriptor for named declaration: " + declaration.getText() + "\n(of type " + declaration.getClass() + ")";
        return descriptor;
    }

    public static FunctionDescriptor getEnclosingFunctionDescriptor(@NotNull BindingContext context, @NotNull JetElement element) {
        JetFunction function = PsiTreeUtil.getParentOfType(element, JetFunction.class);
        return (FunctionDescriptor)context.get(DECLARATION_TO_DESCRIPTOR, function);
    }

    public static void reportAmbiguousLabel(
            @NotNull BindingTrace trace,
            @NotNull JetSimpleNameExpression targetLabel,
            @NotNull Collection<DeclarationDescriptor> declarationsByLabel
    ) {
        Collection<PsiElement> targets = Lists.newArrayList();
        for (DeclarationDescriptor descriptor : declarationsByLabel) {
            PsiElement element = descriptorToDeclaration(trace.getBindingContext(), descriptor);
            assert element != null : "Label can only point to something in the same lexical scope";
            targets.add(element);
        }
        if (!targets.isEmpty()) {
            trace.record(AMBIGUOUS_LABEL_TARGET, targetLabel, targets);
        }
        trace.report(AMBIGUOUS_LABEL.on(targetLabel));
    }

    @Nullable
    public static JetType updateRecordedType(
            @Nullable JetType type,
            @NotNull JetExpression expression,
            @NotNull BindingTrace trace,
            boolean shouldBeMadeNullable
    ) {
        if (type == null) return null;
        if (shouldBeMadeNullable) {
            type = TypeUtils.makeNullable(type);
        }
        trace.record(BindingContext.EXPRESSION_TYPE, expression, type);
        return type;
    }

    @Nullable
    public static JetTypeInfo getRecordedTypeInfo(@NotNull JetExpression expression, @NotNull BindingContext context) {
        if (!context.get(BindingContext.PROCESSED, expression)) return null;
        DataFlowInfo dataFlowInfo = context.get(BindingContext.EXPRESSION_DATA_FLOW_INFO, expression);
        if (dataFlowInfo == null) {
            dataFlowInfo = DataFlowInfo.EMPTY;
        }
        JetType type = context.get(BindingContext.EXPRESSION_TYPE, expression);
        return JetTypeInfo.create(type, dataFlowInfo);
    }

    public static boolean isExpressionWithValidReference(
            @NotNull JetExpression expression,
            @NotNull BindingContext context
    ) {
        if (expression instanceof JetCallExpression) {
            ResolvedCall<?> resolvedCall = BindingContextUtilPackage.getResolvedCall(expression, context);
            return resolvedCall instanceof VariableAsFunctionResolvedCall;
        }
        return expression instanceof JetReferenceExpression;
    }

    public static boolean isVarCapturedInClosure(BindingContext bindingContext, DeclarationDescriptor descriptor) {
        if (!(descriptor instanceof VariableDescriptor) || descriptor instanceof PropertyDescriptor) return false;
        VariableDescriptor variableDescriptor = (VariableDescriptor) descriptor;
        return bindingContext.get(CAPTURED_IN_CLOSURE, variableDescriptor) != null && variableDescriptor.isVar();
    }

    @NotNull
    public static Pair<FunctionDescriptor, PsiElement> getContainingFunctionSkipFunctionLiterals(
            @NotNull BindingContext context,
            @Nullable DeclarationDescriptor startDescriptor,
            boolean strict
    ) {
        FunctionDescriptor containingFunctionDescriptor = DescriptorUtils.getParentOfType(startDescriptor, FunctionDescriptor.class, strict);
        PsiElement containingFunction = containingFunctionDescriptor != null ? callableDescriptorToDeclaration(context, containingFunctionDescriptor) : null;
        while (containingFunction instanceof JetFunctionLiteral) {
            containingFunctionDescriptor = DescriptorUtils.getParentOfType(containingFunctionDescriptor, FunctionDescriptor.class);
            containingFunction = containingFunctionDescriptor != null ? callableDescriptorToDeclaration(context,
                                                                                                        containingFunctionDescriptor) : null;
        }

        return new Pair<FunctionDescriptor, PsiElement>(containingFunctionDescriptor, containingFunction);
    }

}
