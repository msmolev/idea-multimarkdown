/*
 * Copyright (c) 2015-2015 Vladimir Schneider <vladimir.schneider@gmail.com>
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.vladsch.idea.multimarkdown.language;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import com.vladsch.idea.multimarkdown.psi.MultiMarkdownNamedElement;
import org.jetbrains.annotations.NotNull;

public class MultiMarkdownRefactoringSupportProvider extends RefactoringSupportProvider {
    @Override
    public boolean isAvailable(@NotNull PsiElement context) {
        return true; //context instanceof MultiMarkdownNamedElement && ((MultiMarkdownNamedElement) context).isAvailable(context);
    }

    @Override
    public boolean isInplaceRenameAvailable(@NotNull PsiElement element, PsiElement context) {
        return element instanceof MultiMarkdownNamedElement && ((MultiMarkdownNamedElement) element).isInplaceRenameAvailable(context);
    }

    @Override
    public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement element, PsiElement context) {
        return element instanceof MultiMarkdownNamedElement && ((MultiMarkdownNamedElement) element).isMemberInplaceRenameAvailable(context);
    }
}
