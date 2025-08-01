/*******************************************************************************
 * Copyright (c) 2004, 2022 IBM Corporation and others.
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
 *     James Blackburn (Broadcom Corp.) - ongoing development
 *     Sergey Prigogin (Google) - [464072] Refresh on Access ignored during text search
 *     Lars Vogel <Lars.Vogel@vogella.com> - Bug 473427
 *     Christoph Läubrich - Issue  #82 - ContentDescriptionManager access ResourcesPlugin.getWorkspace in the init phase
 *******************************************************************************/
package org.eclipse.core.internal.resources;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.internal.events.ILifecycleListener;
import org.eclipse.core.internal.events.LifecycleEvent;
import org.eclipse.core.internal.utils.Cache;
import org.eclipse.core.internal.utils.Cache.Entry;
import org.eclipse.core.internal.utils.Messages;
import org.eclipse.core.internal.utils.Policy;
import org.eclipse.core.internal.watson.ElementTreeIterator;
import org.eclipse.core.internal.watson.IElementContentVisitor;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IRegistryChangeEvent;
import org.eclipse.core.runtime.IRegistryChangeListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.content.IContentTypeManager.ContentTypeChangeEvent;
import org.eclipse.core.runtime.content.IContentTypeMatcher;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * Keeps a cache of recently read content descriptions.
 *
 * @since 3.0
 * @see IFile#getContentDescription()
 */
public class ContentDescriptionManager implements IManager, IRegistryChangeListener, IContentTypeManager.IContentTypeChangeListener, ILifecycleListener {

	/**
	 * This job causes the content description cache and the related flags
	 * in the resource tree to be flushed.
	 */
	private class FlushJob extends InternalWorkspaceJob {
		private final Set<IPath> toFlush;
		private boolean fullFlush;

		public FlushJob(Workspace workspace) {
			super(Messages.resources_flushingContentDescriptionCache, workspace);
			setSystem(true);
			setUser(false);
			setPriority(LONG);
			setRule(workspace.getRoot());
			toFlush = new LinkedHashSet<>(5);
		}

		@Override
		public boolean belongsTo(Object family) {
			return FAMILY_DESCRIPTION_CACHE_FLUSH.equals(family);
		}

		@Override
		public IStatus runInWorkspace(final IProgressMonitor monitor) {
			if (monitor.isCanceled()) {
				return Status.CANCEL_STATUS;
			}
			try {
				monitor.beginTask("", Policy.opWork); //$NON-NLS-1$
				//note that even though we are running in a workspace job, we
				//must do a begin/endOperation to re-acquire the workspace lock
				final ISchedulingRule rule = workspace.getRoot();
				try {
					workspace.prepareOperation(rule, monitor);
					workspace.beginOperation(true);
					//don't do anything if the system is shutting down or has been shut down
					//it is too late to change the workspace at this point anyway
					if (systemBundle.getState() != Bundle.STOPPING) {
						doFlushCache(monitor, getPathsToFlush());
					}
				} finally {
					workspace.endOperation(rule, false);
				}
			} catch (OperationCanceledException e) {
				return Status.CANCEL_STATUS;
			} catch (CoreException e) {
				return e.getStatus();
			} finally {
				monitor.done();
			}
			return Status.OK_STATUS;
		}

		private Set<IPath> getPathsToFlush() {
			synchronized (toFlush) {
				try {
					if (fullFlush) {
						return Collections.EMPTY_SET;
					}
					return new LinkedHashSet<>(toFlush);
				} finally {
					fullFlush = false;
					toFlush.clear();
				}
			}
		}

		/**
		 * @param project project to flush, or null for a full flush
		 */
		void flush(IProject project) {
			if (Policy.DEBUG_CONTENT_TYPE_CACHE) {
				Policy.debug("Scheduling flushing of content type cache for " //$NON-NLS-1$
						+ (project == null ? IPath.ROOT : project.getFullPath()));
			}
			synchronized (toFlush) {
				if (!fullFlush) {
					if (project == null) {
						fullFlush = true;
					} else {
						toFlush.add(project.getFullPath());
					}
				}
			}
			schedule(1000);
		}

	}

	/**
	 * An input stream that only opens the file if bytes are actually requested.
	 * @see #readDescription(File)
	 */
	static class LazyFileInputStream extends InputStream {
		private InputStream actual;
		private final IFileStore target;

		LazyFileInputStream(IFileStore target) {
			this.target = target;
		}

		@Override
		public int available() throws IOException {
			if (actual == null) {
				return 0;
			}
			return actual.available();
		}

		@Override
		public void close() throws IOException {
			if (actual == null) {
				return;
			}
			actual.close();
		}

		private void ensureOpened() throws IOException {
			if (actual != null) {
				return;
			}
			if (target == null) {
				throw new FileNotFoundException();
			}
			try {
				actual = target.openInputStream(EFS.NONE, null);
			} catch (CoreException e) {
				if (e.getCause() instanceof IOException) {
					throw (IOException) e.getCause();
				}
				throw new IOException(e.getMessage());
			}
		}

		@Override
		public int read() throws IOException {
			ensureOpened();
			return actual.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			ensureOpened();
			return actual.read(b, off, len);
		}

		@Override
		public long skip(long n) throws IOException {
			ensureOpened();
			return actual.skip(n);
		}
	}

	private static final QualifiedName CACHE_STATE = new QualifiedName(ResourcesPlugin.PI_RESOURCES, "contentCacheState"); //$NON-NLS-1$
	private static final QualifiedName CACHE_TIMESTAMP = new QualifiedName(ResourcesPlugin.PI_RESOURCES, "contentCacheTimestamp"); //$NON-NLS-1$\

	public static final String FAMILY_DESCRIPTION_CACHE_FLUSH = ResourcesPlugin.PI_RESOURCES + ".contentDescriptionCacheFamily"; //$NON-NLS-1$

	//possible values for the CACHE_STATE property
	public static final byte EMPTY_CACHE = 1;
	public static final byte USED_CACHE = 2;
	public static final byte INVALID_CACHE = 3;
	public static final byte FLUSHING_CACHE = 4;

	// This state indicates that FlushJob is scheduled and full flush is going to be performed.
	// In the meantime the cache was discarded. It is used as a temporary cache till the FlushJob start.
	public static final byte ABOUT_TO_FLUSH = 5;

	private static final String PT_CONTENTTYPES = "contentTypes"; //$NON-NLS-1$

	private final Cache<IPath, IContentDescription> cache = new Cache<>();

	private volatile byte cacheState;

	private FlushJob flushJob;
	private ProjectContentTypes projectContentTypes;

	private final Workspace workspace;
	protected final Bundle systemBundle = Platform.getBundle("org.eclipse.osgi"); //$NON-NLS-1$

	public ContentDescriptionManager(Workspace workspace) {
		this.workspace = workspace;
	}

	/**
	 * @see org.eclipse.core.runtime.content.IContentTypeManager.IContentTypeChangeListener#contentTypeChanged(IContentTypeManager.ContentTypeChangeEvent)
	 */
	@Override
	public void contentTypeChanged(ContentTypeChangeEvent event) {
		if (Policy.DEBUG_CONTENT_TYPE) {
			Policy.debug("Content type settings changed for " + event.getContentType()); //$NON-NLS-1$
		}
		invalidateCache(true, null);
	}

	synchronized void doFlushCache(final IProgressMonitor monitor, Set<IPath> toClean) throws CoreException {
		// nothing to be done if no information cached
		if (getCacheState() != INVALID_CACHE && getCacheState() != ABOUT_TO_FLUSH) {
			if (Policy.DEBUG_CONTENT_TYPE_CACHE) {
				Policy.debug("Content type cache flush not performed"); //$NON-NLS-1$
			}
			return;
		}
		try {
			setCacheState(FLUSHING_CACHE);
			// flush the MRU cache
			cache.clear();
			SubMonitor subMonitor = SubMonitor.convert(monitor);
			if (toClean.isEmpty()) {
				// no project was added, must be a global flush
				clearContentFlags(IPath.ROOT, subMonitor.split(1));
			} else {
				subMonitor.setWorkRemaining(toClean.size());
				// flush a project at a time
				for (IPath element : toClean) {
					subMonitor.subTask("Clear content flags for project '" + element.lastSegment() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
					clearContentFlags(element, subMonitor.split(1));
				}
			}
		} catch (CoreException ce) {
			setCacheState(INVALID_CACHE);
			throw ce;
		}
		// done cleaning (only if we didn't fail)
		setCacheState(EMPTY_CACHE);
	}

	/**
	 * Clears the content related flags for every file under the given root.
	 */
	private void clearContentFlags(IPath root, final IProgressMonitor monitor) {
		long flushStart = System.currentTimeMillis();
		if (Policy.DEBUG_CONTENT_TYPE_CACHE) {
			Policy.debug("Flushing content type cache for " + root); //$NON-NLS-1$
		}
		// discard content type related flags for all files in the tree
		IElementContentVisitor visitor = (tree, requestor, elementContents) -> {
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			if (elementContents == null) {
				return false;
			}
			ResourceInfo info = (ResourceInfo) elementContents;
			if (info.getType() != IResource.FILE) {
				return true;
			}
			info = workspace.getResourceInfo(requestor.requestPath(), false, true);
			if (info == null) {
				return false;
			}
			info.clear(ICoreConstants.M_CONTENT_CACHE);
			return true;
		};
		new ElementTreeIterator(workspace.getElementTree(), root).iterate(visitor);
		if (Policy.DEBUG_CONTENT_TYPE_CACHE) {
			Policy.debug("Content type cache for " + root + " flushed in " + (System.currentTimeMillis() - flushStart) + " ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	/** Public so tests can examine it. */
	public byte getCacheState() {
		if (cacheState != 0) {
			// we have read/set it before, no need to read property
			return cacheState;
		}
		synchronized (this) {
			if (cacheState != 0) {
				// we have read/set it before, no need to read property
				return cacheState;
			}
			String persisted;
			try {
				persisted = workspace.getRoot().getPersistentProperty(CACHE_STATE);
				cacheState = persisted != null ? Byte.parseByte(persisted) : INVALID_CACHE;
			} catch (NumberFormatException e) {
				cacheState = INVALID_CACHE;
			} catch (CoreException e) {
				Policy.log(e.getStatus());
				cacheState = INVALID_CACHE;
			}
		}
		return cacheState;
	}

	public IContentTypeMatcher getContentTypeMatcher(Project project) throws CoreException {
		return projectContentTypes.getMatcherFor(project);
	}

	/**
	 * Discovers, and caches, the content description of the requested File.
	 * @param file to discover the content description for; result cached
	 * @param info ResourceInfo for the passed in file
	 * @param inSync boolean flag which indicates if cache can be trusted. If false false don't trust the cache
	 * @return IContentDescription for the file
	 */
	public IContentDescription getDescriptionFor(File file, ResourceInfo info, boolean inSync) throws CoreException {
		if (ProjectContentTypes.usesContentTypePreferences(file.getFullPath().segment(0))) {
			// caching for project containing project specific settings is not supported
			return readDescription(file);
		}
		if (getCacheState() == INVALID_CACHE) {
			// discard the cache, so it can be used before the flush job starts
			setCacheState(ABOUT_TO_FLUSH);
			cache.clear();
			// the cache is not good, flush it
			flushJob.schedule(1000);
		}
		if (inSync && getCacheState() != ABOUT_TO_FLUSH) {
			// first look for the flags in the resource info to avoid looking in the cache
			// don't need to copy the info because the modified bits are not in the deltas
			if ((info == null) || info.isSet(ICoreConstants.M_NO_CONTENT_DESCRIPTION)) {
				// presumably, this file has no known content type
				return null;
			}
			if (info.isSet(ICoreConstants.M_DEFAULT_CONTENT_DESCRIPTION)) {
				// this file supposedly has a default content description for an "obvious" content type
				IContentTypeManager contentTypeManager = Platform.getContentTypeManager();
				// try to find the obvious content type matching its name
				IContentType type = contentTypeManager.findContentTypeFor(file.getName());
				if (type != null) {
					// we found it, we are done
					return type.getDefaultDescription();
				}
				// for some reason, there was no content type for this file name
				// fix this and keep going
				info.clear(ICoreConstants.M_CONTENT_CACHE);
			}
		}
		if (inSync) {
			// tries to get a description from the cache
			synchronized (this) {
				Entry<IContentDescription> entry = cache.getEntry(file.getFullPath());
				if (entry != null && entry.getTimestamp() == getTimestamp(info)) {
					// there was a description in the cache, and it was up to date
					return entry.getCached();
				}
			}
		}

		// either we didn't find a description in the cache, or it was not up-to-date - has to be read again
		// reading description can call 3rd party code, so don't synchronize it
		IContentDescription newDescription = readDescription(file);

		synchronized (this) {
			// tries to get a description from the cache
			Entry<IContentDescription> entry = cache.getEntry(file.getFullPath());
			if (entry != null && inSync && entry.getTimestamp() == getTimestamp(info)) {
				// there was a description in the cache, and it was up to date
				return entry.getCached();
			}

			if (getCacheState() != ABOUT_TO_FLUSH) {
				// we are going to add an entry to the cache or update the resource info - remember that
				setCacheState(USED_CACHE);
				if (newDescription == null) {
					// no content type exists for this file name/contents - remember this
					info.set(ICoreConstants.M_NO_CONTENT_DESCRIPTION);
					return null;
				}
				if (newDescription.getContentType().getDefaultDescription().equals(newDescription)) {
					// we got a default description
					IContentType defaultForName = Platform.getContentTypeManager().findContentTypeFor(file.getName());
					if (newDescription.getContentType().equals(defaultForName)) {
						// it is a default description for the obvious content type given its file name, we don't have to cache
						info.set(ICoreConstants.M_DEFAULT_CONTENT_DESCRIPTION);
						return newDescription;
					}
				}
			}
			// we actually got a description filled by a describer (or a default description for a non-obvious type)
			entry = cache.addEntry(file.getFullPath(), newDescription, getTimestamp(info));
			return newDescription;
		}
	}

	/**
	 * Returns a timestamp that uniquely identifies a particular content state
	 * of a particular resource. For use as a key in a content type cache.
	 */
	private long getTimestamp(ResourceInfo info) {
		return info.getContentId() + info.getNodeId();
	}

	/**
	 * Marks the cache as invalid. Does not do anything if the cache is new.
	 * Optionally causes the cached information to be actually flushed.
	 *
	 * @param flush whether the cached information should be flushed
	 * @see #doFlushCache(IProgressMonitor, Set)
	 */
	public synchronized void invalidateCache(boolean flush, IProject project) {
		if (getCacheState() == EMPTY_CACHE) {
			// cache has not been touched, nothing to do
			return;
		}
		// mark the cache as invalid
		try {
			setCacheState(INVALID_CACHE);
		} catch (CoreException e) {
			Policy.log(e.getStatus());
		}
		if (Policy.DEBUG_CONTENT_TYPE_CACHE) {
			Policy.debug("Invalidated cache for " + (project == null ? IPath.ROOT : project.getFullPath())); //$NON-NLS-1$
		}
		if (flush) {
			try {
				// discard the cache, so it can be used before the flush job starts
				setCacheState(ABOUT_TO_FLUSH);
				cache.clear();
			} catch (CoreException e) {
				Policy.log(e.getStatus());
			}
			// the cache is not good, flush it
			flushJob.flush(project);
		}
	}

	private static String getCurrentPlatformState() {
		ResourcesPlugin plugin = ResourcesPlugin.getPlugin();
		if (plugin == null) {
			return ""; //$NON-NLS-1$
		}
		BundleContext bundleContext = plugin.getBundle().getBundleContext();
		if (bundleContext == null) {
			return ""; //$NON-NLS-1$
		}
		String ID = Arrays.stream(bundleContext.getBundles())
				.map(bundle -> String.format("%d %s %s", bundle.getBundleId(), bundle.getSymbolicName(), //$NON-NLS-1$
						bundle.getVersion()))
				.sorted().collect(Collectors.joining(System.lineSeparator()));
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
			return HexFormat.of().formatHex(digest.digest(ID.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(ID.hashCode());
		}
	}

	/**
	 * @return the cached platform state, only public for testing purpose!
	 */
	private String getCachedPlatformState() {
		try {
			return Objects.requireNonNullElse(workspace.getRoot().getPersistentProperty(CACHE_TIMESTAMP), ""); //$NON-NLS-1$
		} catch (CoreException e) {
			return e.toString();
		}
	}

	/**
	 * Tries to obtain a content description for the given file.
	 */
	private IContentDescription readDescription(File file) throws CoreException {
		if (Policy.DEBUG_CONTENT_TYPE) {
			Policy.debug("reading contents of " + file); //$NON-NLS-1$
		}
		// tries to obtain a description for this file contents
		try (
			InputStream contents = new LazyFileInputStream(file.getStore());
		) {
			IContentTypeMatcher matcher = getContentTypeMatcher((Project) file.getProject());
			return matcher.getDescriptionFor(contents, file.getName(), IContentDescription.ALL);
		} catch (FileNotFoundException e) {
			String message = NLS.bind(Messages.localstore_fileNotFound, file.getFullPath());
			throw new ResourceException(IResourceStatus.RESOURCE_NOT_FOUND, file.getFullPath(), message, e);
		} catch (IOException e) {
			String message = NLS.bind(Messages.resources_errorContentDescription, file.getFullPath());
			throw new ResourceException(IResourceStatus.FAILED_DESCRIBING_CONTENTS, file.getFullPath(), message, e);
		}
	}

	/**
	 * @see IRegistryChangeListener#registryChanged(IRegistryChangeEvent)
	 */
	@Override
	public void registryChanged(IRegistryChangeEvent event) {
		// no changes related to the content type registry
		if (event.getExtensionDeltas(Platform.PI_RUNTIME, PT_CONTENTTYPES).length == 0) {
			return;
		}
		invalidateCache(true, null);
	}

	/**
	 * @see ILifecycleListener#handleEvent(LifecycleEvent)
	 */
	@Override
	public void handleEvent(LifecycleEvent event) {
		//TODO are these the only events we care about?
		switch (event.kind) {
			case LifecycleEvent.POST_PROJECT_CHANGE :
				// if the project changes, its natures may have changed as well (content types may be associated to natures)
			case LifecycleEvent.PRE_PROJECT_DELETE :
				// if the project gets deleted, we may get confused if it is recreated again (content ids might match)
			case LifecycleEvent.PRE_PROJECT_MOVE :
				// if the project moves, resource paths (used as keys in the in-memory cache) will have changed
				invalidateCache(true, (IProject) event.resource);
		}
	}

	synchronized void setCacheState(byte newCacheState) throws CoreException {
		if (cacheState == newCacheState) {
			return;
		}
		workspace.getRoot().setPersistentProperty(CACHE_STATE, Byte.toString(newCacheState));
		cacheState = newCacheState;
	}

	@Override
	public void shutdown(IProgressMonitor monitor) throws CoreException {
		if (getCacheState() != INVALID_CACHE) {
			workspace.getRoot().setPersistentProperty(CACHE_TIMESTAMP, getCurrentPlatformState());
		}
		IContentTypeManager contentTypeManager = Platform.getContentTypeManager();
		//tolerate missing services during shutdown because they might be already gone
		if (contentTypeManager != null) {
			contentTypeManager.removeContentTypeChangeListener(this);
		}
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		if (registry != null) {
			registry.removeRegistryChangeListener(this);
		}
		cache.clear();
		flushJob.cancel();
		flushJob = null;
		projectContentTypes = null;
	}

	@Override
	public void startup(IProgressMonitor monitor) throws CoreException {
		projectContentTypes = new ProjectContentTypes(workspace);
		getCacheState();
		if (cacheState == FLUSHING_CACHE || cacheState == ABOUT_TO_FLUSH) {
			// in case we died before completing the last flushing
			setCacheState(INVALID_CACHE);
		}
		flushJob = new FlushJob(workspace);
		// the cache is stale (plug-ins that might be contributing content types were added/removed)
		if (!Objects.equals(getCachedPlatformState(), getCurrentPlatformState())) {
			invalidateCache(false, null);
		}
		// register a lifecycle listener
		workspace.addLifecycleListener(this);
		// register a content type change listener
		Platform.getContentTypeManager().addContentTypeChangeListener(this);
		// register a registry change listener
		Platform.getExtensionRegistry().addRegistryChangeListener(this, Platform.PI_RUNTIME);
	}

	public void projectPreferencesChanged(IProject project) {
		if (Policy.DEBUG_CONTENT_TYPE) {
			Policy.debug("Project preferences changed for " + project); //$NON-NLS-1$
		}
		projectContentTypes.contentTypePreferencesChanged(project);
	}
}
