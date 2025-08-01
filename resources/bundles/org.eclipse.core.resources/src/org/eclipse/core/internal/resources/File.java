/*******************************************************************************
 * Copyright (c) 2000, 2015 IBM Corporation and others.
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
 *     Serge Beauchamp (Freescale Semiconductor) - [229633] Group and Project Path Variable Support
 *     James Blackburn (Broadcom Corp.) - ongoing development
 *     Sergey Prigogin (Google) - [464072] Refresh on Access ignored during text search
 *******************************************************************************/
package org.eclipse.core.internal.resources;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.provider.FileInfo;
import org.eclipse.core.internal.preferences.EclipsePreferences;
import org.eclipse.core.internal.utils.BitMask;
import org.eclipse.core.internal.utils.Messages;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.osgi.util.NLS;

/**
 * The standard implementation of {@link IFile}.
 */
public class File extends Resource implements IFile {

	protected File(IPath path, Workspace container) {
		super(path, container);
	}

	@Override
	public void appendContents(InputStream content, int updateFlags, IProgressMonitor monitor) throws CoreException {
		String message = NLS.bind(Messages.resources_settingContents, getFullPath());
		SubMonitor subMonitor = SubMonitor.convert(monitor, message, 100);
		try (content) {
			Assert.isNotNull(content, "Content cannot be null."); //$NON-NLS-1$
			if (workspace.shouldValidate) {
				workspace.validateSave(this);
			}
			final ISchedulingRule rule = workspace.getRuleFactory().modifyRule(this);
			SubMonitor newChild = subMonitor.newChild(1);
			try {
				workspace.prepareOperation(rule, newChild);
				ResourceInfo info = getResourceInfo(false, false);
				checkAccessible(getFlags(info));
				workspace.beginOperation(true);
				IFileInfo fileInfo = getStore().fetchInfo();
				internalSetContents(content, fileInfo, updateFlags, true, subMonitor.newChild(99));
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true);
			}
		} catch (IOException streamCloseIgnored) {
			// ignore;
		} finally {
			subMonitor.done();
		}
	}

	@Override
	public void appendContents(InputStream content, boolean force, boolean keepHistory, IProgressMonitor monitor) throws CoreException {
		// funnel all operations to central method
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		updateFlags |= keepHistory ? IResource.KEEP_HISTORY : IResource.NONE;
		appendContents(content, updateFlags, monitor);
	}

	/**
	 * Changes this file to be a folder in the resource tree and returns
	 * the newly created folder.  All related
	 * properties are deleted.  It is assumed that on disk the resource is
	 * already a folder/directory so no action is taken to delete the disk
	 * contents.
	 * <p>
	 * <b>This method is for the exclusive use of the local resource manager</b>
	 */
	public IFolder changeToFolder() throws CoreException {
		getPropertyManager().deleteProperties(this, IResource.DEPTH_ZERO);
		IFolder result = workspace.getRoot().getFolder(path);
		if (isLinked()) {
			IPath location = getRawLocation();
			delete(IResource.NONE, null);
			result.createLink(location, IResource.ALLOW_MISSING_LOCAL, null);
		} else {
			workspace.deleteResource(this);
			workspace.createResource(result, false);
		}
		return result;
	}

	@Override
	public void create(InputStream content, int updateFlags, IProgressMonitor monitor) throws CoreException {
		String message = NLS.bind(Messages.resources_creating, getFullPath());
		SubMonitor subMonitor = SubMonitor.convert(monitor, message, 100);
		try (content) {
			checkValidPath(path, FILE, true);
			final ISchedulingRule rule = workspace.getRuleFactory().createRule(this);
			try {
				workspace.prepareOperation(rule, subMonitor.newChild(1));
				checkCreatable();
				workspace.beginOperation(true);
				IFileStore store = getStore();
				IFileInfo localInfo = create(updateFlags, subMonitor.newChild(40), store);
				boolean local = content != null;
				if (local) {
					try {
						internalSetContents(content, localInfo, updateFlags, false, subMonitor.newChild(59));
					} catch (CoreException | OperationCanceledException e) {
						// CoreException when a problem happened creating the file on disk
						// OperationCanceledException when the operation of setting contents has been
						// canceled
						// In either case delete from the workspace and disk
						workspace.deleteResource(this);
						store.delete(EFS.NONE, null);
						throw e;
					}
				}
				setLocal(local);
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true);
			}
		} catch (IOException streamCloseIgnored) {
			// ignore;
		} finally {
			subMonitor.done();
		}
	}

	@Override
	public void create(InputStream content, boolean force, IProgressMonitor monitor) throws CoreException {
		// funnel all operations to central method
		create(content, (force ? IResource.FORCE : IResource.NONE), monitor);
	}

	@Override
	public void create(byte[] content, int updateFlags, IProgressMonitor monitor) throws CoreException {
		SubMonitor subMonitor = SubMonitor.convert(monitor, NLS.bind(Messages.resources_creating, getFullPath()), 100);
		try {
			checkValidPath(path, FILE, true);
			final ISchedulingRule rule = workspace.getRuleFactory().createRule(this);
			try {
				workspace.prepareOperation(rule, subMonitor.newChild(1));
				checkCreatable();
				workspace.beginOperation(true);
				IFileStore store = getStore();
				IFileInfo localInfo = create(updateFlags, subMonitor.newChild(40), store);
				boolean local = content != null;
				if (local) {
					try {
						internalSetContents(content, localInfo, updateFlags, false, subMonitor.newChild(59));
					} catch (CoreException | OperationCanceledException e) {
						// CoreException when a problem happened creating the file on disk
						// OperationCanceledException when the operation of setting contents has been
						// canceled
						// In either case delete from the workspace and disk
						workspace.deleteResource(this);
						store.delete(EFS.NONE, null);
						throw e;
					}
				}
				setLocal(local);
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true);
			}
		} finally {
			subMonitor.done();
		}
	}

	void checkCreatable() throws CoreException {
		checkDoesNotExist();
		Container parent = (Container) getParent();
		ResourceInfo info = parent.getResourceInfo(false, false);
		parent.checkAccessible(getFlags(info));
		checkValidGroupContainer(parent, false, false);
	}

	IFileInfo create(int updateFlags, IProgressMonitor subMonitor, IFileStore store)
			throws CoreException, ResourceException {
		String message;
		IFileInfo localInfo;
		if (BitMask.isSet(updateFlags, IResource.FORCE)) {
			// Assume  the file does not exist - otherwise implementation fails later
			// during actual write
			localInfo = new FileInfo(getName()); // with exists==false
		} else {
			localInfo=store.fetchInfo();
			if (localInfo.exists()) {
				// return an appropriate error message for case variant collisions
				if (!Workspace.caseSensitive) {
					String name = getLocalManager().getLocalName(store);
					if (name != null && !localInfo.getName().equals(name)) {
						message = NLS.bind(Messages.resources_existsLocalDifferentCase,
								IPath.fromOSString(store.toString()).removeLastSegments(1).append(name).toOSString());
						throw new ResourceException(IResourceStatus.CASE_VARIANT_EXISTS, getFullPath(), message, null);
					}
				}
				message = NLS.bind(Messages.resources_fileExists, store.toString());
				throw new ResourceException(IResourceStatus.FAILED_WRITE_LOCAL, getFullPath(), message, null);
			}
		}
		subMonitor.done();

		workspace.createResource(this, updateFlags);
		return localInfo;
	}

	private void setLocal(boolean local) throws CoreException {
		internalSetLocal(local, DEPTH_ZERO);
		if (!local) {
			getResourceInfo(true, true).clearModificationStamp();
		}
	}

	@Override
	public String getCharset() throws CoreException {
		return getCharset(true);
	}

	@Override
	public String getCharset(boolean checkImplicit) throws CoreException {
		// non-existing resources default to parent's charset
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		if (!exists(flags, false)) {
			return checkImplicit ? workspace.getCharsetManager().getCharsetFor(getFullPath().removeLastSegments(1), true) : null;
		}
		checkLocal(flags, DEPTH_ZERO);
		try {
			return internalGetCharset(checkImplicit, info);
		} catch (CoreException e) {
			if (e.getStatus().getCode() == IResourceStatus.RESOURCE_NOT_FOUND) {
				return checkImplicit ? workspace.getCharsetManager().getCharsetFor(getFullPath().removeLastSegments(1), true) : null;
			}
			throw e;
		}
	}

	@Override
	public String getCharsetFor(Reader contents) throws CoreException {
		String charset;
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		if (exists(flags, true)) {
			// the file exists, look for user setting
			if ((charset = workspace.getCharsetManager().getCharsetFor(getFullPath(), false)) != null) {
				// if there is a file-specific user setting, use it
				return charset;
			}
		}
		// tries to obtain a description from the contents provided
		IContentDescription description;
		try {
			// TODO need to take project specific settings into account
			IContentTypeManager contentTypeManager = Platform.getContentTypeManager();
			description = contentTypeManager.getDescriptionFor(contents, getName(), new QualifiedName[] {IContentDescription.CHARSET});
		} catch (IOException e) {
			String message = NLS.bind(Messages.resources_errorContentDescription, getFullPath());
			throw new ResourceException(IResourceStatus.FAILED_DESCRIBING_CONTENTS, getFullPath(), message, e);
		}
		if (description != null) {
			if ((charset = description.getCharset()) != null) {
				// the description contained charset info, we are done
				return charset;
			}
		}
		// could not find out the encoding based on the contents... default to parent's
		return workspace.getCharsetManager().getCharsetFor(getFullPath().removeLastSegments(1), true);
	}

	private String internalGetCharset(boolean checkImplicit, ResourceInfo info) throws CoreException {
		// if there is a file-specific user setting, use it
		String charset = workspace.getCharsetManager().getCharsetFor(getFullPath(), false);
		if (charset != null || !checkImplicit) {
			return charset;
		}
		// tries to obtain a description for the file contents
		IContentDescription description = workspace.getContentDescriptionManager().getDescriptionFor(this, info, true);
		if (description != null) {
			String contentCharset = description.getCharset();
			if (contentCharset != null) {
				return contentCharset;
			}
		}
		// could not find out the encoding based on the contents... default to parent's
		return workspace.getCharsetManager().getCharsetFor(getFullPath().removeLastSegments(1), true);
	}

	@Override
	public IContentDescription getContentDescription() throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		boolean isSynchronized = isSynchronized(IResource.DEPTH_ZERO);
		// Throw an exception if out-of-sync and not auto-refresh enabled
		if (!isSynchronized && !getLocalManager().isLightweightAutoRefreshEnabled()) {
			String message = NLS.bind(Messages.localstore_resourceIsOutOfSync, getFullPath());
			throw new ResourceException(IResourceStatus.OUT_OF_SYNC_LOCAL, getFullPath(), message, null);
		}
		return workspace.getContentDescriptionManager().getDescriptionFor(this, info, isSynchronized);
	}

	@Override
	public InputStream getContents() throws CoreException {
		return getContents(getLocalManager().isLightweightAutoRefreshEnabled());
	}

	/** like {@link #readAllBytes()} **/
	@Override
	public InputStream getContents(boolean force) throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		return getLocalManager().read(this, force, null);
	}

	/** like {@link #getContents(boolean)} with parameter force=true **/
	@Override
	public byte[] readAllBytes() throws CoreException {
		boolean force = true;
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		return getLocalManager().readAllBytes(this, force, null);
	}

	@Deprecated
	@Override
	public int getEncoding() throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		return getLocalManager().getEncoding(this);
	}

	@Override
	public IFileState[] getHistory(IProgressMonitor monitor) {
		return getLocalManager().getHistoryStore().getStates(getFullPath(), monitor);
	}

	@Override
	public int getType() {
		return FILE;
	}

	protected void internalSetContents(InputStream content, IFileInfo fileInfo, int updateFlags, boolean append, IProgressMonitor monitor) throws CoreException {
		if (content == null) {
			content = new ByteArrayInputStream(new byte[0]);
		}
		getLocalManager().write(this, content, fileInfo, updateFlags, append, monitor);
		updateMetadataFiles();
		workspace.getAliasManager().updateAliases(this, getStore(), IResource.DEPTH_ZERO, monitor);
	}

	protected void internalSetContents(byte[] content, IFileInfo fileInfo, int updateFlags, boolean append,
			IProgressMonitor monitor) throws CoreException {
		if (content == null) {
			content = new byte[0];
		}
		getLocalManager().write(this, content, fileInfo, updateFlags, append, monitor);
		updateMetadataFiles();
		workspace.getAliasManager().updateAliases(this, getStore(), IResource.DEPTH_ZERO, monitor);
	}

	static void internalSetMultipleContents(ConcurrentMap<File, byte[]> filesToCreate, int updateFlags, boolean append,
			IProgressMonitor monitor, ExecutorService executorService) throws CoreException {
		SubMonitor subMonitor = SubMonitor.convert(monitor, filesToCreate.size());
		List<Future<CoreException>> futures = new ArrayList<>(filesToCreate.size());
		for (Entry<File, byte[]> e : filesToCreate.entrySet()) {
			Future<CoreException> future = executorService.submit(() -> {
				try {
					File file = e.getKey();
					byte[] content = e.getValue();
					writeSingle(updateFlags, append, subMonitor.slice(1), file, content);
				} catch (CoreException ce) {
					return ce;
				}
				return null;
			});
			futures.add(future);
		}
		CoreException ex = null;
		for (Future<CoreException> f : futures) {
			CoreException ce;
			try {
				ce = f.get();
			} catch (InterruptedException | ExecutionException e) {
				ce = new CoreException(Status.error("Error during parallel IO", e)); //$NON-NLS-1$
			}
			if (ce != null) {
				if (ex == null) {
					ex = ce;
				} else {
					ex.addSuppressed(ce);
				}
			}
		}
		if (ex != null) {
			ex.addSuppressed(new IllegalStateException("Stacktrace of invoking parallel IO")); //$NON-NLS-1$
			throw ex;
		}
		NullProgressMonitor npm = new NullProgressMonitor();
		for (File file : filesToCreate.keySet()) {
			file.updateMetadataFiles();
			file.workspace.getAliasManager().updateAliases(file, file.getStore(), IResource.DEPTH_ZERO, npm);
			file.setLocal(true);
		}
	}

	private static void writeSingle(int updateFlags, boolean append, IProgressMonitor monitor, File file,
			byte[] content) throws CoreException, ResourceException {
		IFileStore store = file.getStore();
		NullProgressMonitor npm = new NullProgressMonitor();
		IFileInfo localInfo = file.create(updateFlags, npm, store);
		file.getLocalManager().write(file, content, localInfo, updateFlags, append, monitor);
	}

	/**
	 * Optimized refreshLocal for files.  This implementation does not block the workspace
	 * for the common case where the file exists both locally and on the file system, and
	 * is in sync.  For all other cases, it defers to the super implementation.
	 */
	@Override
	public void refreshLocal(int depth, IProgressMonitor monitor) throws CoreException {
		if (!getLocalManager().fastIsSynchronized(this)) {
			super.refreshLocal(IResource.DEPTH_ZERO, monitor);
		}
	}

	@Override
	public void setContents(IFileState content, int updateFlags, IProgressMonitor monitor) throws CoreException {
		setContents(content.getContents(), updateFlags, monitor);
	}

	@Override
	public void setContents(InputStream content, int updateFlags, IProgressMonitor monitor) throws CoreException {
		String message = NLS.bind(Messages.resources_settingContents, getFullPath());
		SubMonitor subMonitor = SubMonitor.convert(monitor, message, 100);
		try (content) {
			if (workspace.shouldValidate) {
				workspace.validateSave(this);
			}
			final ISchedulingRule rule = workspace.getRuleFactory().modifyRule(this);
			SubMonitor newChild = subMonitor.newChild(1);
			try {
				workspace.prepareOperation(rule, newChild);
				ResourceInfo info = getResourceInfo(false, false);
				checkAccessible(getFlags(info));
				workspace.beginOperation(true);
				IFileInfo fileInfo = getStore().fetchInfo();
				if (BitMask.isSet(updateFlags, IResource.DERIVED)) {
					// update of derived flag during IFile.write:
					info.set(ICoreConstants.M_DERIVED);
				}
				internalSetContents(content, fileInfo, updateFlags, false, subMonitor.newChild(99));
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true);
			}
		} catch (IOException streamCloseIgnored) {
			// ignore;
		} finally {
			subMonitor.done();
		}
	}
	@Override
	public void setContents(byte[] content, int updateFlags, IProgressMonitor monitor) throws CoreException {
		String message = NLS.bind(Messages.resources_settingContents, getFullPath());
		SubMonitor subMonitor = SubMonitor.convert(monitor, message, 100);
		try {
			if (workspace.shouldValidate) {
				workspace.validateSave(this);
			}
			final ISchedulingRule rule = workspace.getRuleFactory().modifyRule(this);
			SubMonitor newChild = subMonitor.newChild(1);
			try {
				workspace.prepareOperation(rule, newChild);
				ResourceInfo info = getResourceInfo(false, false);
				checkAccessible(getFlags(info));
				workspace.beginOperation(true);
				IFileInfo fileInfo = getStore().fetchInfo();
				if (BitMask.isSet(updateFlags, IResource.DERIVED)) {
					// update of derived flag during IFile.write:
					info.set(ICoreConstants.M_DERIVED);
				}
				internalSetContents(content, fileInfo, updateFlags, false, subMonitor.newChild(99));
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true);
			}
		} finally {
			subMonitor.done();
		}
	}

	@Override
	public long setLocalTimeStamp(long value) throws CoreException {
		//override to handle changing timestamp on project description file
		long result = super.setLocalTimeStamp(value);
		if (path.segmentCount() == 2 && path.segment(1).equals(IProjectDescription.DESCRIPTION_FILE_NAME)) {
			//handle concurrent project deletion
			ResourceInfo projectInfo = ((Project) getProject()).getResourceInfo(false, false);
			if (projectInfo != null) {
				getLocalManager().updateLocalSync(projectInfo, result);
			}
		}
		return result;
	}

	/**
	 * Treat the file specially if it represents a metadata file, which includes:
	 * - project description file (.project)
	 * - project preferences files (*.prefs)
	 *
	 * This method is called whenever it is discovered that a file has
	 * been modified (added, removed, or changed).
	 */
	public void updateMetadataFiles() throws CoreException {
		int count = path.segmentCount();
		String name = path.segment(1);
		// is this a project description file?
		if (count == 2 && name.equals(IProjectDescription.DESCRIPTION_FILE_NAME)) {
			Project project = (Project) getProject();
			project.updateDescription();
			// Discard stale project natures on ProjectInfo
			ProjectInfo projectInfo = (ProjectInfo) project.getResourceInfo(false, true);
			projectInfo.discardNatures();
			return;
		}
		// check to see if we are in the .settings directory
		if (count == 3 && EclipsePreferences.DEFAULT_PREFERENCES_DIRNAME.equals(name)) {
			ProjectPreferences.updatePreferences(this);
			return;
		}
	}

	@Deprecated
	@Override
	public void setCharset(String newCharset) throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		checkAccessible(getFlags(info));
		workspace.getCharsetManager().setCharsetFor(getFullPath(), newCharset);
	}

	@Override
	public void setCharset(String newCharset, IProgressMonitor monitor) throws CoreException {
		String message = NLS.bind(Messages.resources_settingCharset, getFullPath());
		SubMonitor subMonitor = SubMonitor.convert(monitor, message, 100);
		// need to get the project as a scheduling rule because we might be creating a new folder/file to
		// hold the project settings
		final ISchedulingRule rule = workspace.getRuleFactory().charsetRule(this);
		SubMonitor newChild = subMonitor.newChild(1);
		try {
			workspace.prepareOperation(rule, newChild);
			ResourceInfo info = getResourceInfo(false, false);
			checkAccessible(getFlags(info));
			workspace.beginOperation(true);
			workspace.getCharsetManager().setCharsetFor(getFullPath(), newCharset);
			info = getResourceInfo(false, true);
			info.incrementCharsetGenerationCount();
			subMonitor.worked(99);
		} catch (OperationCanceledException e) {
			workspace.getWorkManager().operationCanceled();
			throw e;
		} finally {
			subMonitor.done();
			workspace.endOperation(rule, true);
		}
	}

	@Override
	public void setContents(InputStream content, boolean force, boolean keepHistory, IProgressMonitor monitor) throws CoreException {
		// funnel all operations to central method
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		updateFlags |= keepHistory ? IResource.KEEP_HISTORY : IResource.NONE;
		setContents(content, updateFlags, monitor);
	}

	@Override
	public void setContents(IFileState source, boolean force, boolean keepHistory, IProgressMonitor monitor) throws CoreException {
		// funnel all operations to central method
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		updateFlags |= keepHistory ? IResource.KEEP_HISTORY : IResource.NONE;
		setContents(source.getContents(), updateFlags, monitor);
	}

	@Override
	public String getLineSeparator(boolean checkParent) throws CoreException {
		if (exists()) {
			try (
					// for performance reasons the buffer size should
					// reflect the average length of the first Line:
					InputStream input = new BufferedInputStream(getContents(), 128);) {
				int c = input.read();
				while (c != -1 && c != '\r' && c != '\n') {
					c = input.read();
				}
				if (c == '\n') {
					return "\n"; //$NON-NLS-1$
				}
				if (c == '\r') {
					if (input.read() == '\n') {
						return "\r\n"; //$NON-NLS-1$
					}
					return "\r"; //$NON-NLS-1$
				}
			} catch (CoreException core) {
				if (!checkParent) {
					throw core;
				}
			} catch (IOException io) {
				if (!checkParent) {
					throw new CoreException(Status.error(io.getMessage(), io));
				}
			}
		}
		return checkParent ? getProject().getDefaultLineSeparator() : null;
	}

}
