/*******************************************************************************
 * Copyright (c) 2000, 2017 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Max Weninger (max.weninger@windriver.com) - Bug 131895 [Edit] Undo in compare
 *     Max Weninger (max.weninger@windriver.com) - Bug 72936 [Viewers] Show line numbers in comparision
 *     Stefan Dirix (sdirix@eclipsesource.com) - Bug 473847: Minimum E4 Compatibility of Compare
 *******************************************************************************/
package org.eclipse.compare.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;

import org.eclipse.compare.ICompareContainer;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IOperationHistoryListener;
import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.OperationHistoryEvent;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.GroupMarker;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.IUndoManagerExtension;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISharedTextColors;
import org.eclipse.jface.text.source.LineNumberRulerColumn;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.ui.texteditor.ChangeEncodingAction;
import org.eclipse.ui.texteditor.FindReplaceAction;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IElementStateListener;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Wraps a JFace SourceViewer and add some convenience methods.
 */
public class MergeSourceViewer implements ISelectionChangedListener,
		ITextListener, IMenuListener, IOperationHistoryListener, IAdaptable {

	public static final String UNDO_ID= "undo"; //$NON-NLS-1$
	public static final String REDO_ID= "redo"; //$NON-NLS-1$
	public static final String CUT_ID= "cut"; //$NON-NLS-1$
	public static final String COPY_ID= "copy"; //$NON-NLS-1$
	public static final String PASTE_ID= "paste"; //$NON-NLS-1$
	public static final String DELETE_ID= "delete"; //$NON-NLS-1$
	public static final String SELECT_ALL_ID= "selectAll"; //$NON-NLS-1$
	public static final String FIND_ID= "find"; //$NON-NLS-1$
	public static final String GOTO_LINE_ID= "gotoLine"; //$NON-NLS-1$
	public static final String CHANGE_ENCODING_ID= "changeEncoding"; //$NON-NLS-1$

	class TextOperationAction extends MergeViewerAction {

		private final int fOperationCode;

		TextOperationAction(int operationCode, boolean mutable, boolean selection, boolean content) {
			this(operationCode, null, mutable, selection, content);
		}

		public TextOperationAction(int operationCode, String actionDefinitionId, boolean mutable, boolean selection, boolean content) {
			super(mutable, selection, content);
			if (actionDefinitionId != null) {
				setActionDefinitionId(actionDefinitionId);
			}
			fOperationCode= operationCode;
			update();
		}

		@Override
		public void run() {
			if (isEnabled()) {
				getSourceViewer().doOperation(fOperationCode);
			}
		}

		@Override
		public boolean isEnabled() {
			return fOperationCode != -1 && getSourceViewer().canDoOperation(fOperationCode);
		}

		@Override
		public void update() {
			setEnabled(isEnabled());
		}
	}

	/**
	 * TODO: The only purpose of this class is to provide "Go to Line" action in
	 * TextMergeViewer. The adapter should be removed as soon as we implement
	 * embedded TextEditor in a similar way JDT has it done for Java compare.
	 */
	class TextEditorAdapter implements ITextEditor {

		@Override
		public void close(boolean save) {
			// defining interface method
		}

		@Override
		public void doRevertToSaved() {
			// defining interface method
		}

		@Override
		public IAction getAction(String actionId) {
			// defining interface method
			return null;
		}

		@Override
		public IDocumentProvider getDocumentProvider() {
			return new IDocumentProvider(){

				@Override
				public void aboutToChange(Object element) {
					// defining interface method
				}

				@Override
				public void addElementStateListener(
						IElementStateListener listener) {
					// defining interface method
				}

				@Override
				public boolean canSaveDocument(Object element) {
					// defining interface method
					return false;
				}

				@Override
				public void changed(Object element) {
					// defining interface method
				}

				@Override
				public void connect(Object element) throws CoreException {
					// defining interface method
				}

				@Override
				public void disconnect(Object element) {
					// defining interface method
				}

				@Override
				public IAnnotationModel getAnnotationModel(Object element) {
					// defining interface method
					return null;
				}

				@Override
				public IDocument getDocument(Object element) {
					return MergeSourceViewer.this.getSourceViewer().getDocument();
				}

				@Override
				public long getModificationStamp(Object element) {
					// defining interface method
					return 0;
				}

				@Override
				public long getSynchronizationStamp(Object element) {
					// defining interface method
					return 0;
				}

				@Override
				public boolean isDeleted(Object element) {
					// defining interface method
					return false;
				}

				@Override
				public boolean mustSaveDocument(Object element) {
					// defining interface method
					return false;
				}

				@Override
				public void removeElementStateListener(
						IElementStateListener listener) {
					// defining interface method
				}

				@Override
				public void resetDocument(Object element) throws CoreException {
					// defining interface method
				}

				@Override
				public void saveDocument(IProgressMonitor monitor,
						Object element, IDocument document, boolean overwrite)
						throws CoreException {
					// defining interface method
				}};
		}

		@Override
		public IRegion getHighlightRange() {
			// defining interface method
			return null;
		}

		@Override
		public ISelectionProvider getSelectionProvider() {
			return MergeSourceViewer.this.getSourceViewer().getSelectionProvider();
		}

		@Override
		public boolean isEditable() {
			// defining interface method
			return false;
		}

		@Override
		public void removeActionActivationCode(String actionId) {
			// defining interface method
		}

		@Override
		public void resetHighlightRange() {
			// defining interface method
		}

		@Override
		public void selectAndReveal(int start, int length) {
			selectAndReveal(start, length, start, length);
		}

		private void selectAndReveal(int selectionStart, int selectionLength, int revealStart, int revealLength) {

			ISelection selection = getSelectionProvider().getSelection();
			if (selection instanceof ITextSelection textSelection) {
				if (textSelection.getOffset() != 0	|| textSelection.getLength() != 0) {
					markInNavigationHistory();
				}
			}

			StyledText widget= MergeSourceViewer.this.getSourceViewer().getTextWidget();
			widget.setRedraw(false);
			adjustHighlightRange(revealStart, revealLength);
			MergeSourceViewer.this.getSourceViewer().revealRange(revealStart, revealLength);

			MergeSourceViewer.this.getSourceViewer().setSelectedRange(selectionStart, selectionLength);

			markInNavigationHistory();
			widget.setRedraw(true);
		}

		private void markInNavigationHistory() {
			getSite().getPage().getNavigationHistory().markLocation(this);
		}

		private void adjustHighlightRange(int offset, int length) {

			if (MergeSourceViewer.this instanceof ITextViewerExtension5 extension) {
				extension.exposeModelRange(new Region(offset, length));
			} else if (!isVisible(MergeSourceViewer.this.getSourceViewer(), offset, length)) {
				MergeSourceViewer.this.getSourceViewer().resetVisibleRegion();
			}
		}

		private /*static*/ final boolean isVisible(ITextViewer viewer, int offset, int length) {
			if (viewer instanceof ITextViewerExtension5 extension) {
				IRegion overlap= extension.modelRange2WidgetRange(new Region(offset, length));
				return overlap != null;
			}
			return viewer.overlapsWithVisibleRegion(offset, length);
		}

		@Override
		public void setAction(String actionID, IAction action) {
			// defining interface method
		}

		@Override
		public void setActionActivationCode(String actionId,
				char activationCharacter, int activationKeyCode,
				int activationStateMask) {
			// defining interface method
		}

		@Override
		public void setHighlightRange(int offset, int length, boolean moveCursor) {
			// defining interface method
		}

		@Override
		public void showHighlightRangeOnly(boolean showHighlightRangeOnly) {
			// defining interface method
		}

		@Override
		public boolean showsHighlightRangeOnly() {
			// defining interface method
			return false;
		}

		@Override
		public IEditorInput getEditorInput() {
			if (MergeSourceViewer.this.fContainer.getWorkbenchPart() instanceof IEditorPart) {
				return ((IEditorPart) MergeSourceViewer.this.fContainer.getWorkbenchPart()).getEditorInput();
			}
			return null;
		}

		@Override
		public IEditorSite getEditorSite() {
			// defining interface method
			return null;
		}

		@Override
		public void init(IEditorSite site, IEditorInput input)
				throws PartInitException {
			// defining interface method
		}

		@Override
		public void addPropertyListener(IPropertyListener listener) {
			// defining interface method
		}

		@Override
		public void createPartControl(Composite parent) {
			// defining interface method
		}

		@Override
		public void dispose() {
			// defining interface method
		}

		@Override
		public IWorkbenchPartSite getSite() {
			return MergeSourceViewer.this.fContainer.getWorkbenchPart().getSite();
		}

		@Override
		public String getTitle() {
			// defining interface method
			return null;
		}

		@Override
		public Image getTitleImage() {
			// defining interface method
			return null;
		}

		@Override
		public String getTitleToolTip() {
			// defining interface method
			return null;
		}

		@Override
		public void removePropertyListener(IPropertyListener listener) {
			// defining interface method
		}

		@Override
		public void setFocus() {
			// defining interface method
		}

		@Override
		public <T> T getAdapter(Class<T> adapter) {
			// defining interface method
			return null;
		}

		@Override
		public void doSave(IProgressMonitor monitor) {
			// defining interface method
		}

		@Override
		public void doSaveAs() {
			// defining interface method
		}

		@Override
		public boolean isDirty() {
			// defining interface method
			return false;
		}

		@Override
		public boolean isSaveAsAllowed() {
			// defining interface method
			return false;
		}

		@Override
		public boolean isSaveOnCloseNeeded() {
			// defining interface method
			return false;
		}
	}

	private final ResourceBundle fResourceBundle;
	private final ICompareContainer fContainer;
	private final SourceViewer fSourceViewer;
	private Position fRegion;
	private boolean fEnabled= true;
	private final HashMap<String, IAction> fActions= new HashMap<>();
	private IDocument fRememberedDocument;

	private boolean fAddSaveAction= true;
	private boolean isConfigured = false;

	// line number ruler support
	private final IPropertyChangeListener fPreferenceChangeListener;
	private boolean fShowLineNumber=false;
	private LineNumberRulerColumn fLineNumberColumn;
	private final List<IAction> textActions = new ArrayList<>();
	private CommandContributionItem fSaveContributionItem;

	public MergeSourceViewer(SourceViewer sourceViewer,	ResourceBundle bundle, ICompareContainer container) {
		Assert.isNotNull(sourceViewer);
		fSourceViewer= sourceViewer;
		fResourceBundle= bundle;
		fContainer = container;

		MenuManager menu= new MenuManager();
		menu.setRemoveAllWhenShown(true);
		menu.addMenuListener(this);
		StyledText te= getSourceViewer().getTextWidget();
		te.setMenu(menu.createContextMenu(te));
		fContainer.registerContextMenu(menu, getSourceViewer());

		// for listening to editor show/hide line number preference value
		fPreferenceChangeListener= MergeSourceViewer.this::handlePropertyChangeEvent;
		EditorsUI.getPreferenceStore().addPropertyChangeListener(fPreferenceChangeListener);
		fShowLineNumber= EditorsUI.getPreferenceStore().getBoolean(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER);
		if(fShowLineNumber){
			updateLineNumberRuler();
		}

		IOperationHistory history = getHistory();
		if (history != null) {
			history.addOperationHistoryListener(this);
		}

		// don't add save when in a dialog, IWorkbenchPart is null in dialog containers
		fAddSaveAction = fContainer.getWorkbenchPart() != null;
	}

	public void rememberDocument(IDocument doc) {
//		if (doc != null && fRememberedDocument != null) {
//			System.err.println("MergeSourceViewer.rememberDocument: fRememberedDocument != null: shouldn't happen"); //$NON-NLS-1$
//		}
		fRememberedDocument= doc;
	}

	public IDocument getRememberedDocument() {
		return fRememberedDocument;
	}

	public void hideSaveAction() {
		fAddSaveAction= false;
	}

	public void setFont(Font font) {
		StyledText te= getSourceViewer().getTextWidget();
		if (te != null) {
			te.setFont(font);
		}
		if (fLineNumberColumn != null) {
			fLineNumberColumn.setFont(font);
			layoutViewer();
		}
	}

	public void setBackgroundColor(Color color) {
		StyledText te= getSourceViewer().getTextWidget();
		if (te != null) {
			te.setBackground(color);
		}
		if (fLineNumberColumn != null) {
			fLineNumberColumn.setBackground(color);
		}
	}

	public void setForegroundColor(Color color) {
		StyledText te= getSourceViewer().getTextWidget();
		if (te != null) {
			te.setForeground(color);
		}
	}

	public void setEnabled(boolean enabled) {
		if (enabled != fEnabled) {
			fEnabled= enabled;
			StyledText c= getSourceViewer().getTextWidget();
			if (c != null) {
				c.setEnabled(enabled);
			}
		}
	}

	public boolean getEnabled() {
		return fEnabled;
	}

	public void setRegion(Position region) {
		fRegion= region;
	}

	public Position getRegion() {
		return fRegion;
	}

	public boolean isControlOkToUse() {
		StyledText t= getSourceViewer().getTextWidget();
		return t != null && !t.isDisposed();
	}

	public void setSelection(Position position) {
		if (position != null) {
			getSourceViewer().setSelectedRange(position.getOffset(), position.getLength());
		}
	}

	public void setLineBackground(Position position, Color c) {
		StyledText t= getSourceViewer().getTextWidget();
		if (t != null && !t.isDisposed()) {
			Point region= new Point(0, 0);
			getLineRange(position, region);

			region.x-= getDocumentRegionOffset();

			try {
				t.setLineBackground(region.x, region.y, c);
			} catch (IllegalArgumentException ex) {
				// silently ignored
			}
		}
	}

	public void resetLineBackground() {
		StyledText t= getSourceViewer().getTextWidget();
		if (t != null && !t.isDisposed()) {
			int lines= getLineCount();
			t.setLineBackground(0, lines, null);
		}
	}

	/*
	 * Returns number of lines in document region.
	 */
	public int getLineCount() {
		IRegion region= getSourceViewer().getVisibleRegion();

		int length= region.getLength();
		if (length == 0) {
			return 0;
		}

		IDocument doc= getSourceViewer().getDocument();
		int startLine= 0;
		int endLine= 0;

		int start= region.getOffset();
		try {
			startLine= doc.getLineOfOffset(start);
		} catch(BadLocationException ex) {
			// silently ignored
		}
		try {
			endLine= doc.getLineOfOffset(start+length);
		} catch(BadLocationException ex) {
			// silently ignored
		}

		return endLine-startLine+1;
	}

	public int getViewportLines() {
		StyledText te= getSourceViewer().getTextWidget();
		Rectangle clArea= te.getClientArea();
		if (!clArea.isEmpty()) {
			return clArea.height / te.getLineHeight();
		}
		return 0;
	}

	public int getViewportHeight() {
		StyledText te= getSourceViewer().getTextWidget();
		Rectangle clArea= te.getClientArea();
		if (!clArea.isEmpty()) {
			return clArea.height;
		}
		return 0;
	}

	/*
	 * Returns lines
	 */
	public int getDocumentRegionOffset() {
		int start= getSourceViewer().getVisibleRegion().getOffset();
		IDocument doc= getSourceViewer().getDocument();
		if (doc != null) {
			try {
				return doc.getLineOfOffset(start);
			} catch(BadLocationException ex) {
				// silently ignored
			}
		}
		return 0;
	}

	public int getVerticalScrollOffset() {
		StyledText st= getSourceViewer().getTextWidget();
		int lineHeight= st.getLineHeight();
		return getSourceViewer().getTopInset() - ((getDocumentRegionOffset()*lineHeight) + st.getTopPixel());
	}

	/*
	 * Returns the start line and the number of lines which correspond to the given position.
	 * Starting line number is 0 based.
	 */
	public Point getLineRange(Position p, Point region) {

		IDocument doc= getSourceViewer().getDocument();

		if (p == null || doc == null) {
			region.x= 0;
			region.y= 0;
			return region;
		}

		int start= p.getOffset();
		int length= p.getLength();

		int startLine= 0;
		try {
			startLine= doc.getLineOfOffset(start);
		} catch (BadLocationException e) {
			// silently ignored
		}

		int lineCount= 0;

		if (length == 0) {
//			// if range length is 0 and if range starts a new line
//			try {
//				if (start == doc.getLineStartOffset(startLine)) {
//					lines--;
//				}
//			} catch (BadLocationException e) {
//				lines--;
//			}

		} else {
			int endLine= 0;
			try {
				endLine= doc.getLineOfOffset(start + length - 1);	// why -1?
			} catch (BadLocationException e) {
				// silently ignored
			}
			lineCount= endLine-startLine+1;
		}

		region.x= startLine;
		region.y= lineCount;
		return region;
	}

	/*
	 * Scroll TextPart to the given line.
	 */
	public void vscroll(int line) {

		int srcViewSize= getLineCount();
		int srcExtentSize= getViewportLines();

		if (srcViewSize > srcExtentSize) {

			if (line < 0) {
				line= 0;
			}

			int cp= getSourceViewer().getTopIndex();
			if (cp != line) {
				getSourceViewer().setTopIndex(line + getDocumentRegionOffset());
			}
		}
	}

	public void addAction(String actionId, MergeViewerAction action) {
		fActions.put(actionId, action);
	}

	public IAction getAction(String actionId) {
		IAction action= fActions.get(actionId);
		if (action == null) {
			action= createAction(actionId);
			if (action == null) {
				return null;
			}
			if (action instanceof MergeViewerAction mva) {
				if (mva.isContentDependent()) {
					getSourceViewer().addTextListener(this);
				}
				if (mva.isSelectionDependent()) {
					getSourceViewer().addSelectionChangedListener(this);
				}

				Utilities.initAction(action, fResourceBundle, "action." + actionId + ".");			 //$NON-NLS-1$ //$NON-NLS-2$
			}
			addAction(actionId, action);

		}
		if (action instanceof MergeViewerAction mva) {
			if (mva.isEditableDependent() && !getSourceViewer().isEditable()) {
				return null;
			}
		}
		return action;
	}

	protected IAction createAction(String actionId) {
		if (UNDO_ID.equals(actionId)) {
			return new TextOperationAction(ITextOperationTarget.UNDO, IWorkbenchCommandConstants.EDIT_UNDO, true, false, true);
		}
		if (REDO_ID.equals(actionId)) {
			return new TextOperationAction(ITextOperationTarget.REDO, IWorkbenchCommandConstants.EDIT_REDO, true, false, true);
		}
		if (CUT_ID.equals(actionId)) {
			return new TextOperationAction(ITextOperationTarget.CUT, IWorkbenchCommandConstants.EDIT_CUT, true, true, false);
		}
		if (COPY_ID.equals(actionId)) {
			return new TextOperationAction(ITextOperationTarget.COPY, IWorkbenchCommandConstants.EDIT_COPY, false, true, false);
		}
		if (PASTE_ID.equals(actionId)) {
			return new TextOperationAction(ITextOperationTarget.PASTE, IWorkbenchCommandConstants.EDIT_PASTE, true, false, false);
		}
		if (DELETE_ID.equals(actionId)) {
			return new TextOperationAction(ITextOperationTarget.DELETE, IWorkbenchCommandConstants.EDIT_DELETE, true, false, false);
		}
		if (SELECT_ALL_ID.equals(actionId)) {
			return new TextOperationAction(ITextOperationTarget.SELECT_ALL, IWorkbenchCommandConstants.EDIT_SELECT_ALL, false, false, false);
		}
		return null;
	}

	@Override
	public void selectionChanged(SelectionChangedEvent event) {
		Iterator<IAction> e= fActions.values().iterator();
		while (e.hasNext()) {
			IAction next = e.next();
			if (next instanceof MergeViewerAction action) {
				if (action.isSelectionDependent()) {
					action.update();
				}
			}
		}
	}

	@Override
	public void textChanged(TextEvent event) {
		updateContentDependantActions();
	}

	void updateContentDependantActions() {
		Iterator<IAction> e= fActions.values().iterator();
		while (e.hasNext()) {
			IAction next = e.next();
			if (next instanceof MergeViewerAction action) {
				if (action.isContentDependent()) {
					action.update();
				}
			}
		}
	}

	/*
	 * Allows the viewer to add menus and/or tools to the context menu.
	 */
	@Override
	public void menuAboutToShow(IMenuManager menu) {

		menu.add(new Separator("undo")); //$NON-NLS-1$
		addMenu(menu, UNDO_ID);
		addMenu(menu, REDO_ID);
		menu.add(new GroupMarker("save")); //$NON-NLS-1$
		if (fAddSaveAction) {
			addSave(menu);
		}
		menu.add(new Separator("file")); //$NON-NLS-1$

		menu.add(new Separator("ccp")); //$NON-NLS-1$
		addMenu(menu, CUT_ID);
		addMenu(menu, COPY_ID);
		addMenu(menu, PASTE_ID);
		addMenu(menu, DELETE_ID);
		addMenu(menu, SELECT_ALL_ID);

		menu.add(new Separator("edit")); //$NON-NLS-1$
		addMenu(menu, CHANGE_ENCODING_ID);
		menu.add(new Separator("find")); //$NON-NLS-1$
		addMenu(menu, FIND_ID);

		menu.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));

		menu.add(new Separator("text")); //$NON-NLS-1$
		for (IAction action : textActions) {
			menu.add(action);
		}

		menu.add(new Separator("rest")); //$NON-NLS-1$

		// update all actions
		// to get undo redo right
		updateActions();
	}

	private void addMenu(IMenuManager menu, String actionId) {
		IAction action= getAction(actionId);
		if (action != null) {
			menu.add(action);
		}
	}

	private void addSave(IMenuManager menu) {
		ICommandService commandService = fContainer.getWorkbenchPart().getSite().getService(ICommandService.class);
		final Command command= commandService.getCommand(IWorkbenchCommandConstants.FILE_SAVE);

		final IHandler handler = command.getHandler();
		if (handler != null) {
			if (fSaveContributionItem == null) {
				fSaveContributionItem = new CommandContributionItem(
						new CommandContributionItemParameter(fContainer
								.getWorkbenchPart().getSite(), null, command
								.getId(), CommandContributionItem.STYLE_PUSH));
			}
			// save is an editable dependent action, ie add only when edit
			// is possible
			if (handler.isHandled() && getSourceViewer().isEditable()) {
				menu.add(fSaveContributionItem);
			}
		}
	}

	/**
	 * The viewer is no longer part of the UI, it's a wrapper only. The disposal
	 * doesn't take place while releasing the editor pane's children. The method
	 * have to be called it manually. The wrapped viewer is disposed as a
	 * regular viewer, while disposing the UI.
	 */
	public void dispose() {
		getSourceViewer().removeTextListener(this);
		getSourceViewer().removeSelectionChangedListener(this);
		EditorsUI.getPreferenceStore().removePropertyChangeListener(fPreferenceChangeListener);

		IOperationHistory history = getHistory();
		if (history != null) {
			history.removeOperationHistoryListener(this);
		}
	}

	/**
	 * update all actions independent of their type
	 */
	public void updateActions() {
		Iterator<IAction> e= fActions.values().iterator();
		while (e.hasNext()) {
			IAction next = e.next();
			if (next instanceof MergeViewerAction action) {
				action.update();
			} else if (next instanceof FindReplaceAction action) {
				action.update();
			} else if (next instanceof ChangeEncodingAction action) {
				action.update();
			}
		}
	}

	public void configure(SourceViewerConfiguration configuration) {
		if (isConfigured ) {
			getSourceViewer().unconfigure();
		}
		isConfigured = true;
		getSourceViewer().configure(configuration);
	}

	/**
	 * specific implementation to support a vertical ruler
	 */
	public void setBounds (int x, int y, int width, int height) {
		if(getSourceViewer().getControl() instanceof Composite){
			getSourceViewer().getControl().setBounds(x, y, width, height);
		} else {
			getSourceViewer().getTextWidget().setBounds(x, y, width, height);
		}
	}

	/**
	 * handle show/hide line numbers from editor preferences
	 */
	protected void handlePropertyChangeEvent(PropertyChangeEvent event) {

		String key= event.getProperty();

		if(key.equals(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER)){
			boolean b= EditorsUI.getPreferenceStore().getBoolean(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER);
			if (b != fShowLineNumber){
				toggleLineNumberRuler();
			}
		} else if (key.equals(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER_COLOR)) {
			updateLineNumberColumnPresentation(true);
		}
	}

	/**
	 * Hides or shows line number ruler column based of preference setting
	 */
	private void updateLineNumberRuler() {
		if (!fShowLineNumber) {
			if (fLineNumberColumn != null) {
				getSourceViewer().removeVerticalRulerColumn(fLineNumberColumn);
			}
		} else {
			if (fLineNumberColumn == null) {
				fLineNumberColumn = new LineNumberRulerColumn();
				updateLineNumberColumnPresentation(false);
			}
			getSourceViewer().addVerticalRulerColumn(fLineNumberColumn);
		}
	}

	private void updateLineNumberColumnPresentation(boolean refresh) {
		if (fLineNumberColumn == null) {
			return;
		}
		RGB rgb=  getColorFromStore(EditorsUI.getPreferenceStore(), AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER_COLOR);
		if (rgb == null) {
			rgb= new RGB(0, 0, 0);
		}
		ISharedTextColors sharedColors= getSharedColors();
		fLineNumberColumn.setForeground(sharedColors.getColor(rgb));
		if (refresh) {
			fLineNumberColumn.redraw();
		}
	}

	private void layoutViewer() {
		Control parent= getSourceViewer().getControl();
		if (parent instanceof Composite && !parent.isDisposed()) {
			((Composite) parent).layout(true);
		}
	}

	private ISharedTextColors getSharedColors() {
		return EditorsUI.getSharedTextColors();
	}

	private RGB getColorFromStore(IPreferenceStore store, String key) {
		RGB rgb= null;
		if (store.contains(key)) {
			if (store.isDefault(key)) {
				rgb= PreferenceConverter.getDefaultColor(store, key);
			} else {
				rgb= PreferenceConverter.getColor(store, key);
			}
		}
		return rgb;
	}

	/**
	 * Toggles line number ruler column.
	 */
	private void toggleLineNumberRuler()
	{
		fShowLineNumber=!fShowLineNumber;

		updateLineNumberRuler();
	}

	public void addTextAction(IAction textEditorPropertyAction) {
		textActions.add(textEditorPropertyAction);
	}

	public boolean removeTextAction(IAction textEditorPropertyAction) {
		return textActions.remove(textEditorPropertyAction);
	}

	public void addAction(String id, IAction action) {
		fActions.put(id, action);
	}

	private IOperationHistory getHistory() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		return PlatformUI.getWorkbench().getOperationSupport()
				.getOperationHistory();
	}

	@Override
	public void historyNotification(OperationHistoryEvent event) {
		// This method updates the enablement of all content operations
		// when the undo history changes. It could be localized to UNDO and REDO.
		IUndoContext context = getUndoContext();
		if (context != null && event.getOperation().hasContext(context)) {
			Display.getDefault().asyncExec(this::updateContentDependantActions);
		}
	}

	private IUndoContext getUndoContext() {
		IUndoManager undoManager = getSourceViewer().getUndoManager();
		if (undoManager instanceof IUndoManagerExtension) {
			return ((IUndoManagerExtension)undoManager).getUndoContext();
		}
		return null;
	}

	/**
	 * @return the wrapped viewer
	 */
	public SourceViewer getSourceViewer() {
		return fSourceViewer;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getAdapter(Class<T> adapter) {
		if (adapter == ITextEditor.class) {
			return (T) new TextEditorAdapter();
		}
		return null;
	}
}
