/**
 * Copyright (c) 2010 MATSUFUJI Hideharu <matsufuji2008@gmail.com>,
 *               2010-2012 KUBO Atsuhiro <kubo@iteman.jp>,
 * All rights reserved.
 *
 * This file is part of MakeGood.
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License v1.0 which accompanies this
 * distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package com.piece_framework.makegood.ui.launch;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.IType;
import org.eclipse.dltk.core.search.IDLTKSearchConstants;
import org.eclipse.dltk.core.search.SearchEngine;
import org.eclipse.dltk.core.search.SearchMatch;
import org.eclipse.dltk.core.search.SearchParticipant;
import org.eclipse.dltk.core.search.SearchPattern;
import org.eclipse.dltk.core.search.SearchRequestor;
import org.eclipse.php.internal.core.PHPLanguageToolkit;
import org.eclipse.php.internal.core.typeinference.PHPClassType;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import com.piece_framework.makegood.core.PHPSourceModule;
import com.piece_framework.makegood.core.PHPType;
import com.piece_framework.makegood.core.TestingFramework;
import com.piece_framework.makegood.launch.TestTargetRepository;
import com.piece_framework.makegood.ui.Activator;
import com.piece_framework.makegood.ui.EditorParser;
import com.piece_framework.makegood.ui.MakeGoodContext;
import com.piece_framework.makegood.ui.MakeGoodStatus;

public class RelatedTestsLaunchShortcut extends MakeGoodLaunchShortcut {
    @Override
    public void launch(IEditorPart editor, String mode) {
        clearTestTargets();
        if (!(editor instanceof ITextEditor)) throw new TestLaunchException();

        EditorParser editorParser = new EditorParser(editor);
        List<IType> types = editorParser.getTypes();
        if (types == null || types.size() == 0) {
            MakeGoodContext.getInstance().updateStatus(MakeGoodStatus.TypesNotFound);
            throw new TestLaunchException();
        }

        ISourceModule source = editorParser.getSourceModule();
        if (source != null) {
            PHPSourceModule phpSourceModule = new PHPSourceModule(source);

            try {
                if (phpSourceModule.isTest()) {
                    addTestTarget(source.getResource());
                }
            } catch (CoreException e) {
                Activator.getDefault().getLog().log(new Status(Status.ERROR, Activator.PLUGIN_ID, e.getMessage(), e));
                throw new TestLaunchException();
            }

            collectRelatedTests(types, phpSourceModule.getTestingFramework());
        }

        if (TestTargetRepository.getInstance().getCount() == 0) {
            MakeGoodContext.getInstance().updateStatus(MakeGoodStatus.RelatedTestsNotFound);
            throw new TestLaunchException();
        }

        super.launch(editor, mode);
    }

    private void collectRelatedTests(List<IType> types, TestingFramework testingFramework) {
        SearchPattern pattern = null;
        for (IType type: types) {
            PHPType phpType = new PHPType(type, testingFramework);
            try {
                if (!phpType.isClass()) continue;
            } catch (CoreException e) {
                Activator.getDefault().getLog().log(new Status(Status.WARNING, Activator.PLUGIN_ID, e.getMessage(), e));
                continue;
            }

            SearchPattern patternForType =
                SearchPattern.createPattern(
                    PHPClassType.fromIType(type).getTypeName(),
                    IDLTKSearchConstants.TYPE,
                    IDLTKSearchConstants.REFERENCES,
                    SearchPattern.R_FULL_MATCH,
                    PHPLanguageToolkit.getDefault()
                );
            if (pattern == null) {
                pattern = patternForType;
            } else {
                pattern = SearchPattern.createOrPattern(pattern, patternForType);
            }
        }

        try {
            new SearchEngine().search(
                pattern,
                new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                SearchEngine.createSearchScope(types.get(0).getScriptProject()),
                new TestSearchRequestor(),
                null
            );
        } catch (CoreException e) {
            Activator.getDefault().getLog().log(new Status(Status.ERROR, Activator.PLUGIN_ID, e.getMessage(), e));
            throw new TestLaunchException();
        }
    }

    /**
     * @since 1.3.0
     */
    private class TestSearchRequestor extends SearchRequestor {
        private Set<IResource> searchMatches = new HashSet<IResource>();

        @Override
        public void acceptSearchMatch(SearchMatch match) throws CoreException {
            IResource resource = match.getResource();
            if (searchMatches.contains(resource)) return;
            searchMatches.add(resource);

            IModelElement element = DLTKCore.create(resource);
            if (element == null) return;
            if (!(element instanceof ISourceModule)) return;
            if (new PHPSourceModule((ISourceModule) element).isTest() == false) return;
            addTestTarget(resource);
        }

        @Override
        public void endReporting() {
        }
    }
}
